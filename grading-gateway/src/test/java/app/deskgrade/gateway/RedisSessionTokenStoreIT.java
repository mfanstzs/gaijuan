package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/** Opt-in test against Redis. Every touched key belongs to a fresh DeskGrade test namespace. */
@EnabledIfSystemProperty(named = "deskgrade.redis.integration", matches = "true")
class RedisSessionTokenStoreIT {
  private final String namespace = "deskgrade:test:" + UUID.randomUUID() + ":";
  private final Set<String> ownedKeys = new LinkedHashSet<>();
  private final String host = System.getProperty("deskgrade.redis.host", "127.0.0.1");
  private final int port = Integer.getInteger("deskgrade.redis.port", 6379);
  private final String password = System.getenv("DESKGRADE_REDIS_TEST_PASSWORD");
  private JedisPooled inspector;
  private RedisSessionTokenStore store;

  @BeforeEach
  void connect() {
    var configuration =
        DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(3000);
    if (password != null && !password.isEmpty()) configuration.password(password);
    inspector = new JedisPooled(new HostAndPort(host, port), configuration.build());
    store = newStore();
    store.verifyAvailable();
  }

  @AfterEach
  void closeAndRemoveOnlyKeysCreatedByThisTest() {
    try {
      if (inspector != null && !ownedKeys.isEmpty())
        inspector.del(ownedKeys.toArray(String[]::new));
    } finally {
      if (store != null) store.close();
      if (inspector != null) inspector.close();
    }
  }

  @Test
  void sharedSessionSurvivesGatewayClientRestartAndStoresOnlyTheTokenHash() {
    UUID user = UUID.randomUUID();
    String bearer = "synthetic-bearer-" + UUID.randomUUID();
    String hash = GatewayStore.hash(bearer);
    String key = tokenKey(hash);
    Instant expiry = Instant.ofEpochMilli(Clock.systemUTC().millis()).plusSeconds(12 * 3600);
    store.put(hash, user, expiry);
    String stored = inspector.get(key);
    assertNotNull(stored);
    assertFalse(stored.contains(bearer));
    assertFalse(key.contains(bearer));
    assertTrue(inspector.pttl(key) > 0);
    assertTrue(inspector.pttl(key) <= Duration.ofHours(12).toMillis());
    store.close();
    store = newStore();
    assertEquals(new SessionTokenStore.Session(user, expiry), store.find(hash).orElseThrow());
  }

  @Test
  void logoutInvalidatesOtherGatewayClientsImmediately() {
    String hash = newToken();
    store.put(hash, UUID.randomUUID(), Instant.now().plusSeconds(60));
    try (var otherGateway = newStore()) {
      assertTrue(otherGateway.find(hash).isPresent());
      store.delete(hash);
      assertTrue(otherGateway.find(hash).isEmpty());
      assertFalse(inspector.exists(tokenKey(hash)));
    }
  }

  @Test
  void redisExpiresSessionWithoutAnApplicationCleanupJob() throws Exception {
    String hash = newToken();
    store.put(hash, UUID.randomUUID(), Instant.now().plusMillis(200));
    long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    while (inspector.exists(tokenKey(hash)) && System.nanoTime() < until) Thread.sleep(50);
    assertFalse(inspector.exists(tokenKey(hash)));
    assertTrue(store.find(hash).isEmpty());
  }

  @Test
  void accountWideRevocationKeepsOtherUsersAndAllowsFreshSessions() {
    UUID revokedUser = UUID.randomUUID();
    UUID otherUser = UUID.randomUUID();
    ownedKeys.add(namespace + "user-generation:" + revokedUser);
    String first = newToken(), second = newToken(), unaffected = newToken(), later = newToken();
    Instant expires = Instant.now().plusSeconds(60);
    store.put(first, revokedUser, expires);
    store.put(second, revokedUser, expires);
    store.put(unaffected, otherUser, expires);
    store.revokeUser(revokedUser);
    assertTrue(store.find(first).isEmpty());
    assertTrue(store.find(second).isEmpty());
    assertEquals(otherUser, store.find(unaffected).orElseThrow().userId());
    store.put(later, revokedUser, expires);
    assertEquals(revokedUser, store.find(later).orElseThrow().userId());
  }

  @Test
  void tokenCollisionCannotReplaceAnotherUsersSession() {
    String hash = newToken();
    UUID first = UUID.randomUUID();
    Instant expiry = Instant.now().plusSeconds(60);
    store.put(hash, first, expiry);
    assertDoesNotThrow(() -> store.put(hash, first, expiry));
    assertEquals(
        GatewayException.Code.SERVICE_UNAVAILABLE,
        assertThrows(GatewayException.class, () -> store.put(hash, UUID.randomUUID(), expiry))
            .code());
    assertEquals(first, store.find(hash).orElseThrow().userId());
  }

  @Test
  void corruptAndWrongTypeRedisEntriesFailClosedWithoutExposingValues() {
    String hash = newToken();
    inspector.setex(tokenKey(hash), 60, "synthetic-sensitive-value");
    GatewayException failure = assertThrows(GatewayException.class, () -> store.find(hash));
    assertEquals(GatewayException.Code.SERVICE_UNAVAILABLE, failure.code());
    assertFalse(failure.toString().contains("synthetic-sensitive-value"));
    inspector.del(tokenKey(hash));
    inspector.sadd(tokenKey(hash), "wrong type");
    assertEquals(
        GatewayException.Code.SERVICE_UNAVAILABLE,
        assertThrows(GatewayException.class, () -> store.find(hash)).code());
  }

  private RedisSessionTokenStore newStore() {
    return new RedisSessionTokenStore(host, port, password, namespace, Clock.systemUTC());
  }

  private String newToken() {
    String hash = GatewayStore.hash("synthetic-session-" + UUID.randomUUID());
    tokenKey(hash);
    return hash;
  }

  private String tokenKey(String hash) {
    String key = namespace + "session:" + hash;
    ownedKeys.add(key);
    return key;
  }
}
