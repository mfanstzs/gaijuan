package app.deskgrade.gateway;

import static app.deskgrade.gateway.GatewayException.Code.SERVICE_UNAVAILABLE;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * Shared Redis sessions with server-enforced expiry and revocation. Account data and balances stay
 * in SQL. The gateway also checks the durable SQL session authority so restoring an older Redis
 * snapshot cannot resurrect a logged-out session. Redis outages fail closed.
 */
public final class RedisSessionTokenStore implements SessionTokenStore {
  private static final long MAX_TTL_MILLIS = Duration.ofDays(30).toMillis();

  // Issuance and revocation are ordered by Redis, so a concurrent revoke cannot miss an older
  // session. Generation keys deliberately have no TTL: old revoked tokens must never become valid.
  private static final String PUT =
      """
      local generation = redis.call('GET', KEYS[2]) or '0'
      local value = ARGV[1] .. '|' .. ARGV[2] .. '|' .. generation
      local existing = redis.call('GET', KEYS[1])
      if existing then
        if existing == value then return 1 end
        return 0
      end
      local written = redis.call('SET', KEYS[1], value, 'PX', ARGV[3], 'NX')
      if written then return 1 end
      return 0
      """;

  private static final String FIND =
      """
      local value = redis.call('GET', KEYS[1])
      if not value then return false end
      local user, expiry, generation = string.match(value, '^([0-9a-f%-]+)|(%d+)|(%d+)$')
      if not user then return redis.error_reply('INVALID_SESSION_RECORD') end
      local current = redis.call('GET', ARGV[1] .. user) or '0'
      if generation ~= current then
        redis.call('DEL', KEYS[1])
        return false
      end
      return value
      """;

  private final JedisPooled client;
  private final String namespace;
  private final Clock clock;
  private volatile boolean closed;

  public RedisSessionTokenStore(
      String host, int port, String password, String namespace, Clock clock) {
    this(host, port, null, password, 0, false, namespace, clock);
  }

  public RedisSessionTokenStore(
      String host,
      int port,
      String username,
      String password,
      int database,
      boolean ssl,
      String namespace,
      Clock clock) {
    this.namespace = validateNamespace(namespace);
    this.clock = Objects.requireNonNull(clock);
    if (host == null
        || host.isBlank()
        || host.length() > 253
        || host.chars().anyMatch(Character::isISOControl)
        || port < 1
        || port > 65535
        || database < 0
        || database > 15) throw new IllegalArgumentException("Invalid Redis endpoint");
    var configuration =
        DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(3000)
            .socketTimeoutMillis(3000)
            .database(database)
            .ssl(ssl)
            .clientName("DeskGrade");
    if (username != null && !username.isBlank()) configuration.user(username);
    if (password != null && !password.isEmpty()) configuration.password(password);
    var pool = new GenericObjectPoolConfig<Connection>();
    pool.setMaxTotal(16);
    pool.setMaxIdle(8);
    pool.setMinIdle(0);
    pool.setMaxWait(Duration.ofSeconds(2));
    pool.setTestWhileIdle(true);
    pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
    try {
      client = new JedisPooled(new HostAndPort(host, port), configuration.build(), pool);
    } catch (RuntimeException failure) {
      // Supplier exceptions can contain the endpoint, command arguments, or credentials.
      throw unavailable();
    }
  }

  @Override
  public void put(String tokenHash, UUID userId, Instant expiresAt) {
    String key = tokenKey(tokenHash);
    Objects.requireNonNull(userId);
    Objects.requireNonNull(expiresAt);
    long ttl;
    try {
      ttl = Math.subtractExact(expiresAt.toEpochMilli(), clock.millis());
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException("Invalid session lifetime");
    }
    if (ttl <= 0 || ttl > MAX_TTL_MILLIS)
      throw new IllegalArgumentException("Invalid session lifetime");
    Object result =
        operation(
            () ->
                client.eval(
                    PUT,
                    List.of(key, generationKey(userId)),
                    List.of(
                        userId.toString(),
                        Long.toString(expiresAt.toEpochMilli()),
                        Long.toString(ttl))));
    if (!(result instanceof Long written) || written != 1) throw unavailable();
  }

  @Override
  public Optional<Session> find(String tokenHash) {
    String key = tokenKey(tokenHash);
    Object result =
        operation(() -> client.eval(FIND, List.of(key), List.of(namespace + "user-generation:")));
    if (result == null) return Optional.empty();
    if (!(result instanceof String value) || value.length() > 128) throw unavailable();
    try {
      String[] parts = value.split("\\|", -1);
      if (parts.length != 3) throw unavailable();
      UUID userId = UUID.fromString(parts[0]);
      if (!userId.toString().equals(parts[0]) || !parts[2].matches("[0-9]{1,19}"))
        throw unavailable();
      Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(parts[1]));
      if (!expiresAt.isAfter(clock.instant())) {
        delete(tokenHash);
        return Optional.empty();
      }
      return Optional.of(new Session(userId, expiresAt));
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  @Override
  public void delete(String tokenHash) {
    String key = tokenKey(tokenHash);
    operation(() -> client.del(key));
  }

  @Override
  public void revokeUser(UUID userId) {
    String key = generationKey(Objects.requireNonNull(userId));
    operation(() -> client.incr(key));
  }

  @Override
  public void verifyAvailable() {
    if (!"PONG".equals(operation(client::ping))) throw unavailable();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      client.close();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private <T> T operation(Supplier<T> operation) {
    if (closed) throw unavailable();
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private String tokenKey(String tokenHash) {
    if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Expected a SHA-256 token hash");
    return namespace + "session:" + tokenHash;
  }

  private String generationKey(UUID userId) {
    return namespace + "user-generation:" + userId;
  }

  private static String validateNamespace(String value) {
    if (value == null || value.length() > 160 || !value.matches("deskgrade:(?:[A-Za-z0-9_-]+:)*"))
      throw new IllegalArgumentException("Expected a DeskGrade Redis namespace");
    return value;
  }

  private static GatewayException unavailable() {
    return new GatewayException(SERVICE_UNAVAILABLE);
  }
}
