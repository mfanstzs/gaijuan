package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Explicit MySQL-only integration test; never accepts a production schema or a nonempty database.
 */
class GatewayMySqlStoreIT {
  private static final List<String> TABLES =
      List.of(
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

  @Test
  void durableBalancesReceiptsSessionsAndLocksSurviveRestartAndConnectionLoss() throws Exception {
    String url = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    assumeTrue(
        url != null && !url.isBlank(), "An explicitly isolated MySQL test database is required");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(System.getenv("DESKGRADE_TEST_MYSQL_USER"));
    config.setPassword(System.getenv("DESKGRADE_TEST_MYSQL_PASSWORD"));
    config.setMaximumPoolSize(4);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setPoolName("DeskGradeSyntheticStoreTest");
    boolean ownsTestTables = false;
    try (var source = new HikariDataSource(config)) {
      try (var check = source.getConnection()) {
        assertEquals("MySQL", check.getMetaData().getDatabaseProductName());
        assertTrue(
            check.getCatalog().matches("deskgrade_test_[a-zA-Z0-9_]+"),
            "Refusing a production schema");
        try (var query =
                check.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
            var rs = query.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(0, rs.getLong(1), "Refusing a database that was not initially empty");
        }
      }
      ownsTestTables = true;
      try {
        var cache = new GatewaySessionAuthorityTest.FakeCache();
        assertEquals(
            GatewayException.Code.STORAGE_FAILURE,
            assertThrows(
                    GatewayException.class,
                    () -> new GatewayStore(source, Clock.systemUTC(), cache))
                .code());
        GatewayStore.initializeMySql(source);
        GatewayStore.initializeMySql(source);
        assertThrows(
            GatewayException.class, () -> new GatewayStore(source, Clock.systemUTC(), cache));
        assertThrows(
            GatewayException.class, () -> GatewayStore.markMySqlReady(source, "SYNTHETIC_EMPTY"));

        UUID adminId = UUID.randomUUID();
        GatewayStore.withMySqlMigration(
            source,
            connection -> {
              insertSyntheticAdmin(connection, adminId);
              GatewayStore.markMySqlReady(connection, "SYNTHETIC_MYSQL_STORE_IT");
              return null;
            });
        assertTrue(GatewayStore.adminPresent(source));
        UUID userId, prepared = UUID.randomUUID(), uncertain = UUID.randomUUID();
        UUID orderId;
        String userToken;
        try (var store = new GatewayStore(source, Clock.systemUTC(), cache)) {
          assertEquals(adminId, store.login("operator", GatewayFixtures.password()).account().id());
          var user = store.register("alice", GatewayFixtures.password());
          userId = user.id();
          userToken = store.login("alice", GatewayFixtures.password()).token();
          assertThrows(
              GatewayException.class, () -> new GatewayStore(source, Clock.systemUTC(), cache));
          UUID adjustment = UUID.randomUUID();
          var recorded = store.adjust(adminId, adjustment, userId, 10, "synthetic credits");
          assertEquals(
              recorded, store.adjust(adminId, adjustment, userId, 10, "synthetic credits"));
          assertThrows(
              GatewayException.class,
              () -> store.adjust(adminId, adjustment, userId, 11, "synthetic credits"));
          assertThrows(
              GatewayException.class,
              () -> store.adjust(adminId, UUID.randomUUID(), userId, -11, "synthetic overdraw"));
          store.bindProfile("synthetic", "a".repeat(64));
          store.selectModelPolicy(
              adminId,
              UUID.randomUUID(),
              "synthetic",
              0,
              "synthetic model selection",
              ignored -> "synthetic-model");
          var order = store.createOrder(userId, UUID.randomUUID(), "calls_100", 100, null);
          orderId = order.id();
          assertEquals(1, store.adminOrders(adminId, userId, 1, 1, "ali").total());
          assertEquals(2, store.adminAudit(adminId, 1, 1, "ALL").total());
          assertEquals(1, store.userPage(adminId, 1, 1, "ali").total());

          // Multiple HTTP workers cannot spend more than the single available balance.
          var work = new ArrayList<Callable<Boolean>>();
          for (int i = 0; i < 8; i++) {
            UUID request = UUID.randomUUID();
            store.prepare(userId, request, "b".repeat(64), "synthetic-model");
            work.add(
                () -> {
                  store.beforeDispatch(userId, request, userToken);
                  store.finish(userId, request, "{\"synthetic\":true}", null, false);
                  return true;
                });
          }
          try (var workers = Executors.newFixedThreadPool(6)) {
            for (var result : workers.invokeAll(work)) assertTrue(result.get());
          }
          assertEquals(2, store.account(userId).balance());
          store.prepare(userId, prepared, "c".repeat(64), "synthetic-model");
          store.prepare(userId, uncertain, "d".repeat(64), "synthetic-model");
          store.beforeDispatch(userId, uncertain, userToken);
          assertEquals(1, store.account(userId).balance());

          // Kill only the connection identified by this isolated database's gateway lock.
          try (var killer = source.getConnection()) {
            long ownConnection;
            try (var query = killer.prepareStatement("SELECT IS_USED_LOCK(?)")) {
              query.setString(
                  1,
                  "deskgrade.gateway." + GatewayStore.hash(killer.getCatalog()).substring(0, 40));
              try (var rs = query.executeQuery()) {
                assertTrue(rs.next());
                ownConnection = rs.getLong(1);
                assertTrue(ownConnection > 0);
              }
            }
            try (var statement = killer.createStatement()) {
              statement.execute("KILL CONNECTION " + ownConnection);
            }
          }
          assertEquals(1, store.account(userId).balance());
          assertEquals(
              "DISPATCHING",
              store.request(userId, uncertain).state(),
              "Reconnect must not run process-restart recovery");
        }

        // Initialization cannot reset an already migrated schema, balances, policy or audit
        // history.
        GatewayStore.initializeMySql(source);
        try (var restarted = new GatewayStore(source, Clock.systemUTC(), cache)) {
          assertEquals(1, restarted.account(userId).balance());
          assertEquals(userId, restarted.authenticate(userToken).id());
          assertEquals("NOT_DISPATCHED", restarted.request(userId, prepared).state());
          assertEquals("UNKNOWN", restarted.request(userId, uncertain).state());
          assertTrue(restarted.request(userId, uncertain).charged());
          assertEquals(orderId, restarted.orders(userId, 0, 10).getFirst().id());
          assertEquals(1, restarted.modelPolicy().revision());
          assertEquals(10, restarted.ledgerPage(adminId, userId, 1, 100, "ALL").total());
          cache.values.clear();
          assertEquals(userId, restarted.authenticate(userToken).id());
          var finalCalls = new ArrayList<Callable<Boolean>>();
          for (int i = 0; i < 4; i++) {
            UUID request = UUID.randomUUID();
            restarted.prepare(userId, request, "e".repeat(64), "synthetic-model");
            finalCalls.add(
                () -> {
                  try {
                    restarted.beforeDispatch(userId, request, userToken);
                    restarted.finish(userId, request, null, "SYNTHETIC_FAILURE", false);
                    return true;
                  } catch (GatewayException failure) {
                    assertEquals(GatewayException.Code.INSUFFICIENT_QUOTA, failure.code());
                    return false;
                  }
                });
          }
          int charged = 0;
          try (var workers = Executors.newFixedThreadPool(4)) {
            for (var result : workers.invokeAll(finalCalls)) if (result.get()) charged++;
          }
          assertEquals(1, charged);
          assertEquals(0, restarted.account(userId).balance());
          assertEquals(11, restarted.ledgerPage(adminId, userId, 1, 100, "ALL").total());
          restarted.logout(userToken);
          cache.values.put(
              GatewayStore.hash(userToken),
              new SessionTokenStore.Session(userId, Instant.now().plusSeconds(3600)));
          assertEquals(
              GatewayException.Code.UNAUTHENTICATED,
              assertThrows(GatewayException.class, () -> restarted.authenticate(userToken)).code());
        }
        try (var finalRestart = new GatewayStore(source, Clock.systemUTC(), cache)) {
          assertEquals(0, finalRestart.account(userId).balance());
          assertEquals(11, finalRestart.ledgerPage(adminId, userId, 1, 100, "ALL").total());
        }
      } finally {
        if (ownsTestTables) {
          try (var cleanup = source.getConnection();
              var statement = cleanup.createStatement()) {
            assertTrue(cleanup.getCatalog().matches("deskgrade_test_[a-zA-Z0-9_]+"));
            for (String table : TABLES) statement.execute("DROP TABLE IF EXISTS " + table);
          }
        }
      }
    }
  }

  private static void insertSyntheticAdmin(Connection connection, UUID id) throws Exception {
    var encoded = PasswordHasher.create(GatewayFixtures.password());
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO accounts(id,username,role,balance,salt,password_hash,iterations,created_at) VALUES(?,'operator','ADMIN',0,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setBytes(2, encoded.salt());
      insert.setBytes(3, encoded.hash());
      insert.setInt(4, encoded.iterations());
      insert.setLong(5, System.currentTimeMillis());
      assertEquals(1, insert.executeUpdate());
    } finally {
      Arrays.fill(encoded.salt(), (byte) 0);
      Arrays.fill(encoded.hash(), (byte) 0);
    }
  }
}
