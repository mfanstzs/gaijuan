package app.deskgrade.gateway;

import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * Server-only endpoints and secret references. Client artifacts never receive database passwords.
 */
public final class StorageConfiguration {
  private final JsonNode config;

  private StorageConfiguration(JsonNode config) {
    this.config = config;
  }

  public static StorageConfiguration load(Path path) {
    try {
      if (!Files.isRegularFile(path) || Files.size(path) > 32768)
        throw new IllegalArgumentException();
      JsonNode root = GatewayJson.parse(Files.readAllBytes(path));
      GatewayJson.fields(root, "schemaVersion", "credentialsDirectory", "mysql", "redis");
      integer(root, "schemaVersion", 1, 1);
      if (!Path.of(GatewayJson.string(root, "credentialsDirectory", 1, 2048)).isAbsolute())
        throw new IllegalArgumentException();
      GatewayJson.fields(root.path("mysql"), "jdbcUrl", "username", "passwordRef");
      GatewayJson.fields(
          root.path("redis"),
          "host",
          "port",
          "database",
          "ssl",
          "namespace",
          "username",
          "passwordRef");
      String url = GatewayJson.string(root.path("mysql"), "jdbcUrl", 1, 2048);
      validateJdbcUrl(url);
      GatewayJson.string(root.path("mysql"), "username", 1, 100);
      validateReference(root.path("mysql").path("passwordRef"));
      JsonNode redis = root.path("redis");
      String host = GatewayJson.string(redis, "host", 1, 253);
      if (!host.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException();
      integer(redis, "port", 1, 65535);
      integer(redis, "database", 0, 15);
      if (!redis.path("ssl").isBoolean()) throw new IllegalArgumentException();
      if (!GatewayJson.string(redis, "namespace", 1, 100).matches("deskgrade:(?:[A-Za-z0-9_-]+:)*"))
        throw new IllegalArgumentException();
      if (!redis.path("username").isNull()) GatewayJson.string(redis, "username", 1, 100);
      validateReference(redis.path("passwordRef"));
      return new StorageConfiguration(root);
    } catch (Exception failure) {
      throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    }
  }

  private static void validateReference(JsonNode value) {
    if (value.isNull()) return; // An environment-provided secret may be used instead.
    if (!value.isTextual() || !value.textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw new IllegalArgumentException();
  }

  private static void validateJdbcUrl(String url) {
    if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
    URI endpoint = URI.create(url.substring(5));
    if (endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null
        || endpoint.getPort() < -1
        || endpoint.getPort() == 0
        || endpoint.getPort() > 65535
        || !endpoint.getRawPath().matches("/[A-Za-z0-9_][A-Za-z0-9_$-]{0,63}"))
      throw new IllegalArgumentException();
    if (endpoint.getRawQuery() == null) return;
    var keys = new HashSet<String>();
    for (String pair : endpoint.getRawQuery().split("&", -1)) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) throw new IllegalArgumentException();
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      if (!key.matches("[a-z][a-z0-9]*")
          || key.contains("password")
          || key.contains("secret")
          || key.contains("token")
          || key.equals("user")
          || key.equals("username")
          || !keys.add(key)) throw new IllegalArgumentException();
    }
  }

  public HikariDataSource openDataSource() {
    var mysql = config.path("mysql");
    var pool = new HikariConfig();
    pool.setPoolName("DeskGrade-MySQL");
    pool.setJdbcUrl(GatewayJson.string(mysql, "jdbcUrl", 1, 2048));
    pool.setUsername(GatewayJson.string(mysql, "username", 1, 100));
    pool.setPassword(secret(mysql.path("passwordRef"), "DESKGRADE_MYSQL_PASSWORD", true));
    pool.setMaximumPoolSize(16);
    pool.setMinimumIdle(1);
    pool.setConnectionTimeout(5000);
    pool.setValidationTimeout(3000);
    pool.setIdleTimeout(300000);
    pool.setMaxLifetime(1800000);
    pool.setKeepaliveTime(120000);
    pool.setInitializationFailTimeout(5000);
    pool.setAutoCommit(true);
    return new HikariDataSource(pool);
  }

  public RedisSessionTokenStore openSessions(Clock clock) {
    var redis = config.path("redis");
    return new RedisSessionTokenStore(
        GatewayJson.string(redis, "host", 1, 253),
        integer(redis, "port", 1, 65535),
        nullableString(redis.path("username")),
        secret(redis.path("passwordRef"), "DESKGRADE_REDIS_PASSWORD", false),
        integer(redis, "database", 0, 15),
        redis.path("ssl").asBoolean(false),
        GatewayJson.string(redis, "namespace", 1, 100),
        clock);
  }

  private String secret(JsonNode reference, String environmentName, boolean required) {
    String environment = System.getenv(environmentName);
    if (environment != null && !environment.isBlank()) return environment;
    if (reference == null || reference.isNull()) {
      if (required) throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
      return null;
    }
    String ref = nullableString(reference);
    if (ref == null || !ref.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    Path directory = Path.of(GatewayJson.string(config, "credentialsDirectory", 1, 2048));
    if (!directory.isAbsolute()) throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    char[] value =
        new DpapiSecretStore(directory)
            .read(ref)
            .orElseThrow(() -> new GatewayException(GatewayException.Code.STORAGE_FAILURE));
    try {
      return new String(value);
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  private static int integer(JsonNode object, String field, int min, int max) {
    var value = object.path(field);
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < min
        || value.intValue() > max)
      throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    return value.intValue();
  }

  private static String nullableString(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual() || value.textValue().length() > 200)
      throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    return value.textValue();
  }
}
