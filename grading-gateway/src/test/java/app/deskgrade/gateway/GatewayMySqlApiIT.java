package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.deskgrade.adapters.persistence.JournalJson;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.persistence.MySqlSessionJournal;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * Opt-in HTTP integration. Only an empty deskgrade_test_* schema and fresh Redis keys are touched.
 */
class GatewayMySqlApiIT {
  private static final List<String> TABLES =
      List.of(
          "journal_model_call",
          "journal_run_event",
          "journal_legacy_import",
          "journal_grading_attempt",
          "journal_grading_run",
          "journal_config_document",
          "rubric_model_policy_audit",
          "model_policy_audit",
          "rubric_generation_requests",
          "grade_requests",
          "package_audit",
          "credit_packages",
          "orders",
          "quota_events",
          "adjustments",
          "sessions",
          "gateway_rubric_model_policy",
          "gateway_model_policy",
          "accounts",
          "gateway_profiles",
          "gateway_storage_state",
          "gateway_schema");
  private final String namespace = "deskgrade:test:api:" + UUID.randomUUID() + ":";
  private final Set<String> ownedRedisKeys = new LinkedHashSet<>();
  private final AtomicInteger modelCalls = new AtomicInteger();
  private final AtomicInteger httpCalls = new AtomicInteger();
  private final String redisHost = System.getProperty("deskgrade.redis.host", "127.0.0.1");
  private final int redisPort = Integer.getInteger("deskgrade.redis.port", 6379);
  private final String redisPassword = System.getenv("DESKGRADE_REDIS_TEST_PASSWORD");
  private String mysqlUrl;

  @Test
  void httpCreditsSessionsAndMetadataSurviveACompleteServerAndConnectionPoolRestart()
      throws Exception {
    mysqlUrl = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    assumeTrue(
        mysqlUrl != null && !mysqlUrl.isBlank(),
        "An explicitly isolated MySQL database is required");
    assumeTrue(
        Boolean.getBoolean("deskgrade.redis.integration"),
        "Real Redis integration must be explicitly enabled");
    var redisConfig =
        DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(3000);
    if (redisPassword != null && !redisPassword.isEmpty()) redisConfig.password(redisPassword);
    boolean ownsTables = false;
    try (var inspector =
            new JedisPooled(new HostAndPort(redisHost, redisPort), redisConfig.build());
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      try {
        assertEquals("PONG", inspector.ping());
        try (var setup = dataSource();
            var connection = setup.getConnection()) {
          assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
          assertTestCatalog(connection.getCatalog());
          try (var query =
                  connection.prepareStatement(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
              var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1), "Refusing a database that was not initially empty");
          }
          ownsTables = true;
          GatewayStore.initializeMySql(setup);
          GatewayStore.withMySqlMigration(
              setup,
              target -> {
                var encoded = PasswordHasher.create(GatewayFixtures.password());
                try (var insert =
                    target.prepareStatement(
                        "INSERT INTO accounts(id,username,role,balance,salt,password_hash,iterations,created_at) VALUES(?,'operator','ADMIN',0,?,?,?,?)")) {
                  insert.setString(1, UUID.randomUUID().toString());
                  insert.setBytes(2, encoded.salt());
                  insert.setBytes(3, encoded.hash());
                  insert.setInt(4, encoded.iterations());
                  insert.setLong(5, System.currentTimeMillis());
                  assertEquals(1, insert.executeUpdate());
                } finally {
                  Arrays.fill(encoded.salt(), (byte) 0);
                  Arrays.fill(encoded.hash(), (byte) 0);
                }
                GatewayStore.markMySqlReady(target, "SYNTHETIC_MYSQL_REDIS_HTTP_IT");
                return null;
              });
          MySqlSessionJournal.initializeSchema(connection);
        }

        String aliceToken, bobToken, adminToken, aliceId, adjustmentBody;
        UUID runId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        int port;
        try (var first = new RunningServer(0)) {
          port = first.server.port();
          Wire health = call(client, first.server, "GET", "/health", null, null);
          assertEquals(200, health.status());
          assertEquals("deskgrade-gateway", health.body().path("service").asText());
          assertEquals(2, health.body().path("apiVersion").asInt());
          Wire alice = register(client, first.server, "alice");
          aliceToken = remember(alice.body().path("token").asText());
          aliceId = alice.body().path("account").path("id").asText();
          assertEquals(0, alice.body().path("account").path("credits").asLong());
          bobToken = remember(register(client, first.server, "bob").body().path("token").asText());
          Wire adminLogin =
              call(client, first.server, "POST", "/v1/auth/login", null, credentials("operator"));
          assertEquals(200, adminLogin.status());
          adminToken = remember(adminLogin.body().path("token").asText());
          assertEquals(
              403, call(client, first.server, "GET", "/v1/admin/users", aliceToken, null).status());
          adjustmentBody =
              GatewayJson.MAPPER
                  .createObjectNode()
                  .put("requestId", UUID.randomUUID().toString())
                  .put("targetUserId", aliceId)
                  .put("delta", 4000)
                  .put("reason", "synthetic restart allocation")
                  .toString();
          assertEquals(
              403,
              call(client, first.server, "POST", "/v1/admin/credits", aliceToken, adjustmentBody)
                  .status());
          Wire adjusted =
              call(client, first.server, "POST", "/v1/admin/credits", adminToken, adjustmentBody);
          assertEquals(200, adjusted.status());
          assertFalse(adjusted.body().path("replayed").asBoolean());
          Wire replay =
              call(client, first.server, "POST", "/v1/admin/credits", adminToken, adjustmentBody);
          assertEquals(200, replay.status());
          assertTrue(replay.body().path("replayed").asBoolean());
          assertCredits(client, first.server, aliceToken, aliceId, 4000);
          assertEquals(
              1,
              call(
                      client,
                      first.server,
                      "GET",
                      "/v1/admin/ledger?userId=" + aliceId,
                      adminToken,
                      null)
                  .body()
                  .path("entries")
                  .size());

          var snapshot = JournalJson.MAPPER.createObjectNode();
          snapshot
              .putObject("access")
              .put("mode", "COMMERCIAL")
              .put("gateway", "http://127.0.0.1:" + port)
              .put("accountId", aliceId);
          var settings =
              new SessionSettings(
                  GatewayFixtures.rubric().question(),
                  "synthetic",
                  1,
                  1,
                  snapshot.toString(),
                  0,
                  0,
                  0,
                  null,
                  Duration.ofMillis(50),
                  Duration.ofSeconds(3));
          Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
          ObjectNode beginRun = JournalJson.MAPPER.createObjectNode();
          beginRun
              .put("runId", runId.toString())
              .put("targetIdentity", "synthetic-window")
              .put("now", now.toString())
              .set("settings", JournalJson.MAPPER.valueToTree(settings));
          assertEquals(
              200, journal(client, first.server, aliceToken, "beginRun", beginRun).status());
          var view =
              new SessionPlatform.View(
                  "synthetic-answer", "q1", 1, SessionPlatform.Page.READY, null, 0, null, false);
          ObjectNode beginAttempt = JournalJson.MAPPER.createObjectNode();
          beginAttempt
              .put("runId", runId.toString())
              .put("attemptId", attemptId.toString())
              .put("ordinal", 1)
              .put("epoch", 1)
              .put("evidencePath", MetadataOnlyEvidenceStore.NO_IMAGE_STORED)
              .put("sha256", "a".repeat(64))
              .put("capturedAt", now.toString());
          beginAttempt.set("view", JournalJson.MAPPER.valueToTree(view));
          beginAttempt.set("settings", JournalJson.MAPPER.valueToTree(settings));
          assertFalse(beginAttempt.has("png"));
          assertFalse(beginAttempt.has("imageBase64"));
          assertEquals(
              200,
              journal(client, first.server, aliceToken, "beginAttempt", beginAttempt).status());
          assertEquals(
              1,
              journal(client, first.server, aliceToken, "runs", empty())
                  .body()
                  .path("result")
                  .size());
          assertEquals(
              0,
              journal(client, first.server, bobToken, "runs", empty())
                  .body()
                  .path("result")
                  .size());
          Wire forbiddenRecord =
              journal(
                  client,
                  first.server,
                  bobToken,
                  "recordJson",
                  empty().put("attemptId", attemptId.toString()));
          assertEquals(503, forbiddenRecord.status());
          assertFalse(forbiddenRecord.body().toString().contains("synthetic-answer"));
          assertMetadata(client, first.server, aliceToken, attemptId);
          assertEquals(0, modelCalls.get());
        }

        // Every server-side client and MySQL pool above is closed. Keep the same port as
        // production.
        try (var reopened = new RunningServer(port)) {
          assertEquals(200, call(client, reopened.server, "GET", "/health", null, null).status());
          assertCredits(client, reopened.server, aliceToken, aliceId, 4000);
          Wire replay =
              call(
                  client, reopened.server, "POST", "/v1/admin/credits", adminToken, adjustmentBody);
          assertEquals(200, replay.status());
          assertTrue(replay.body().path("replayed").asBoolean());
          assertCredits(client, reopened.server, aliceToken, aliceId, 4000);
          assertEquals(
              1,
              journal(client, reopened.server, aliceToken, "runs", empty())
                  .body()
                  .path("result")
                  .size());
          assertEquals(
              0,
              journal(client, reopened.server, bobToken, "runs", empty())
                  .body()
                  .path("result")
                  .size());
          assertMetadata(client, reopened.server, aliceToken, attemptId);
          String key = redisKey(aliceToken);
          assertEquals(1, inspector.del(key));
          assertFalse(inspector.exists(key));
          assertCredits(client, reopened.server, aliceToken, aliceId, 4000);
          assertTrue(
              inspector.exists(key), "Healthy Redis miss must rebuild from a valid SQL session");
          String oldSnapshot = inspector.get(key);
          assertNotNull(oldSnapshot);
          assertFalse(oldSnapshot.contains(aliceToken));
          assertEquals(
              204,
              call(client, reopened.server, "POST", "/v1/auth/logout", aliceToken, null).status());
          inspector.setex(key, 60, oldSnapshot);
          assertEquals(
              401, call(client, reopened.server, "GET", "/v1/account", aliceToken, null).status());
          assertEquals(401, journal(client, reopened.server, aliceToken, "runs", empty()).status());
          Wire relogin =
              call(client, reopened.server, "POST", "/v1/auth/login", null, credentials("alice"));
          assertEquals(200, relogin.status());
          String freshToken = remember(relogin.body().path("token").asText());
          assertNotEquals(aliceToken, freshToken);
          assertCredits(client, reopened.server, freshToken, aliceId, 4000);
          assertMetadata(client, reopened.server, freshToken, attemptId);
          // Unavailable dependency client must not report a healthy server or authorize an API.
          reopened.tokens.close();
          assertEquals(503, call(client, reopened.server, "GET", "/health", null, null).status());
          assertEquals(
              503, call(client, reopened.server, "GET", "/v1/account", freshToken, null).status());
          assertEquals(0, modelCalls.get());
        }
      } finally {
        if (!ownedRedisKeys.isEmpty()) inspector.del(ownedRedisKeys.toArray(String[]::new));
        if (ownsTables) {
          try (var cleanup = dataSource();
              var connection = cleanup.getConnection();
              var statement = connection.createStatement()) {
            assertTestCatalog(connection.getCatalog());
            for (String table : TABLES) statement.execute("DROP TABLE IF EXISTS " + table);
          }
        }
      }
    }
  }

  private final class RunningServer implements AutoCloseable {
    final HikariDataSource source;
    final RedisSessionTokenStore tokens;
    final GatewayStore store;
    final GatewayServer server;

    RunningServer(int port) throws Exception {
      source = dataSource();
      RedisSessionTokenStore openedTokens = null;
      GatewayStore openedStore = null;
      GatewayServer openedServer = null;
      try {
        openedTokens =
            new RedisSessionTokenStore(
                redisHost, redisPort, redisPassword, namespace, Clock.systemUTC());
        openedStore = new GatewayStore(source, Clock.systemUTC(), openedTokens);
        var models =
            new GatewayModelService(
                openedStore,
                List.of(),
                (profile, request, question, hook) -> {
                  modelCalls.incrementAndGet();
                  throw new AssertionError("API persistence test must not dispatch a model");
                });
        openedServer = new GatewayServer(port, openedStore, models, Clock.systemUTC(), source);
        server = openedServer;
        tokens = openedTokens;
        store = openedStore;
        server.start();
      } catch (Exception | AssertionError failure) {
        if (openedServer != null) openedServer.close();
        if (openedStore != null) openedStore.close();
        else if (openedTokens != null) openedTokens.close();
        source.close();
        throw failure;
      }
    }

    public void close() {
      try {
        server.close();
      } finally {
        try {
          store.close();
        } finally {
          source.close();
        }
      }
    }
  }

  private HikariDataSource dataSource() {
    var config = new HikariConfig();
    config.setJdbcUrl(mysqlUrl);
    config.setUsername(System.getenv("DESKGRADE_TEST_MYSQL_USER"));
    config.setPassword(System.getenv("DESKGRADE_TEST_MYSQL_PASSWORD"));
    config.setMaximumPoolSize(6);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setPoolName("DeskGradeSyntheticApiTest");
    return new HikariDataSource(config);
  }

  private static void assertTestCatalog(String catalog) {
    assertNotNull(catalog);
    assertTrue(catalog.matches("deskgrade_test_[a-zA-Z0-9_]+"), "Refusing a production schema");
  }

  private static String credentials(String username) {
    return GatewayJson.MAPPER
        .createObjectNode()
        .put("username", username)
        .put("password", new String(GatewayFixtures.password()))
        .toString();
  }

  private String redisKey(String token) {
    return namespace + "session:" + GatewayStore.hash(token);
  }

  private String remember(String token) {
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    ownedRedisKeys.add(redisKey(token));
    return token;
  }

  private static ObjectNode empty() {
    return JournalJson.MAPPER.createObjectNode();
  }

  private record Wire(int status, JsonNode body) {}

  private Wire call(
      HttpClient client,
      GatewayServer server,
      String method,
      String path,
      String token,
      String body)
      throws Exception {
    assertTrue(httpCalls.incrementAndGet() <= 80, "Bounded HTTP integration request guard");
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (method.equals("POST")) {
      if (body != null) request.header("Content-Type", "application/json");
      request.POST(
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
    } else request.GET();
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.body().length() <= JournalJson.MAX_BYTES);
    return new Wire(
        response.statusCode(),
        response.body().isEmpty() ? null : GatewayJson.MAPPER.readTree(response.body()));
  }

  private Wire register(HttpClient client, GatewayServer server, String username) throws Exception {
    Wire result = call(client, server, "POST", "/v1/auth/register", null, credentials(username));
    assertEquals(201, result.status());
    return result;
  }

  private Wire journal(
      HttpClient client, GatewayServer server, String token, String operation, ObjectNode arguments)
      throws Exception {
    var command =
        JournalJson.MAPPER.createObjectNode().put("schemaVersion", 1).put("operation", operation);
    command.set("arguments", arguments);
    return call(client, server, "POST", "/v1/journal", token, command.toString());
  }

  private void assertCredits(
      HttpClient client, GatewayServer server, String token, String id, long expected)
      throws Exception {
    Wire account = call(client, server, "GET", "/v1/account", token, null);
    assertEquals(200, account.status());
    assertEquals(id, account.body().path("id").asText());
    assertEquals(expected, account.body().path("credits").asLong());
  }

  private void assertMetadata(HttpClient client, GatewayServer server, String token, UUID attempt)
      throws Exception {
    Wire record =
        journal(client, server, token, "recordJson", empty().put("attemptId", attempt.toString()));
    assertEquals(200, record.status());
    JsonNode json = JournalJson.MAPPER.readTree(record.body().path("result").asText());
    assertEquals(attempt.toString(), json.path("attemptId").asText());
    assertEquals("synthetic-answer", json.path("answerId").asText());
    assertEquals("NOT_STORED", json.path("imagePersistence").asText());
    assertEquals("2", json.path("standardAnswer").asText());
    assertTrue(json.path("modelCalls").isEmpty());
    assertTrue(json.path("javaValidatedTotal").isNull());
  }
}
