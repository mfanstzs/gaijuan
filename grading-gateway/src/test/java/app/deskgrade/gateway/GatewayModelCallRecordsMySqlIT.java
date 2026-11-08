package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Runs only in an explicitly empty deskgrade_test_* schema supplied through environment variables.
 */
class GatewayModelCallRecordsMySqlIT {
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
  void v2AndV3UpgradePreservesReceiptsAndMakesUserAndGlobalPagingIndexesAvailable()
      throws Exception {
    String url = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    assumeTrue(url != null && !url.isBlank(), "An isolated MySQL test schema is required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(System.getenv("DESKGRADE_TEST_MYSQL_USER"));
    config.setPassword(System.getenv("DESKGRADE_TEST_MYSQL_PASSWORD"));
    config.setMaximumPoolSize(3);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setPoolName("DeskGradeCallRecordMigrationTest");
    try (var source = new HikariDataSource(config)) {
      try (var check = source.getConnection()) {
        assertEquals("MySQL", check.getMetaData().getDatabaseProductName());
        assertTrue(
            check.getCatalog().matches("deskgrade_test_[a-zA-Z0-9_]+"),
            "Refusing production schema");
        try (var query =
                check.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
            var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(0, rows.getLong(1), "Refusing nonempty test schema");
        }
      }
      for (int version : new int[] {2, 3}) {
        try {
          var encoded = PasswordHasher.create(GatewayFixtures.password());
          try {
            GatewayStore.bootstrapNewMySql(
                source, "operator", encoded.salt(), encoded.hash(), encoded.iterations());
          } finally {
            Arrays.fill(encoded.salt(), (byte) 0);
            Arrays.fill(encoded.hash(), (byte) 0);
          }
          UUID user, admin, request = UUID.randomUUID();
          try (var store =
              new GatewayStore(
                  source, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
            admin = store.login("operator", GatewayFixtures.password()).account().id();
            user = store.register("alice", GatewayFixtures.password()).id();
            store.adjust(admin, UUID.randomUUID(), user, 10, "synthetic call history");
            GatewayFixtures.activate(store, admin, GatewayFixtures.profile());
            GatewayModelCallRecordsTest.prepare(store, user, request, "GRADING");
            GatewayModelCallRecordsTest.dispatch(store, user, request, "GRADING");
            GatewayModelCallRecordsTest.finish(
                store, user, request, "GRADING", GatewayModelCallRecordsTest.RESULT, null);
          }
          try (var connection = source.getConnection();
              var statement = connection.createStatement()) {
            for (String table : List.of("grade_requests", "rubric_generation_requests")) {
              for (String column : List.of("request_json", "raw_model_output", "completed_at"))
                statement.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
              String prefix = table.equals("grade_requests") ? "grade_calls" : "rubric_calls";
              statement.execute("DROP INDEX " + prefix + "_global_page ON " + table);
              statement.execute("DROP INDEX " + prefix + "_user_page ON " + table);
            }
            statement.execute("DROP TABLE package_audit");
            statement.execute("DROP TABLE credit_packages");
            statement.execute("ALTER TABLE orders DROP COLUMN package_name");
            statement.execute("ALTER TABLE orders DROP COLUMN currency");
            statement.execute("DROP INDEX accounts_registration_dashboard ON accounts");
            statement.execute("DROP INDEX quota_events_dashboard ON quota_events");
            statement.execute("DROP INDEX orders_dashboard ON orders");
            if (version == 2) {
              statement.execute("DROP TABLE rubric_model_policy_audit");
              statement.execute("DROP TABLE gateway_rubric_model_policy");
              statement.execute("DROP TABLE rubric_generation_requests");
            }
            statement.executeUpdate("UPDATE gateway_schema SET version=" + version + " WHERE id=1");
          }
          try (var upgraded =
              new GatewayStore(
                  source, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
            var detail = upgraded.modelCallDetail(user, request, "GRADING");
            assertEquals(GatewayModelCallRecordsTest.RESULT, detail.responseJson());
            assertNull(detail.requestJson());
            assertNull(detail.modelOutput());
            assertNull(detail.call().completedAt());
            assertEquals(9, upgraded.account(user).balance());
            assertEquals(1, upgraded.adminModelCalls(admin, null, "alice", 1, 20).total());
            assertEquals(1, upgraded.adminModelCalls(admin, null, null, 1, 20).total());
          }
          try (var connection = source.getConnection()) {
            for (String table : List.of("grade_requests", "rubric_generation_requests")) {
              var names = new HashSet<String>();
              try (var indexes =
                  connection
                      .getMetaData()
                      .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                while (indexes.next())
                  if (indexes.getString("INDEX_NAME") != null)
                    names.add(indexes.getString("INDEX_NAME"));
              }
              String prefix = table.equals("grade_requests") ? "grade_calls" : "rubric_calls";
              assertTrue(names.contains(prefix + "_global_page"));
              assertTrue(names.contains(prefix + "_user_page"));
              // FORCE INDEX proves compatibility without assuming an optimizer must use it on a
              // tiny fixture.
              assertIndexPlan(connection, table, prefix + "_user_page", user.toString(), true);
              assertIndexPlan(connection, table, prefix + "_global_page", null, false);
            }
          }
        } finally {
          try (var cleanup = source.getConnection();
              var statement = cleanup.createStatement()) {
            assertTrue(cleanup.getCatalog().matches("deskgrade_test_[a-zA-Z0-9_]+"));
            for (String table : TABLES) statement.execute("DROP TABLE IF EXISTS " + table);
          }
        }
      }
    }
  }

  private static void assertIndexPlan(
      Connection connection, String table, String index, String owner, boolean byUser)
      throws Exception {
    try (var explain =
        connection.prepareStatement(
            "EXPLAIN SELECT user_id,request_id,created_at FROM "
                + table
                + " FORCE INDEX ("
                + index
                + ")"
                + (byUser ? " WHERE user_id=?" : "")
                + " ORDER BY created_at DESC,request_id DESC"
                + (byUser ? "" : ",user_id DESC")
                + " LIMIT 20")) {
      if (byUser) explain.setString(1, owner);
      try (var row = explain.executeQuery()) {
        assertTrue(row.next());
        // Empty rubric history can be optimized away entirely; metadata above still verifies both
        // indexes.
        if (row.getString("key") != null) assertEquals(index, row.getString("key"));
        assertFalse(String.valueOf(row.getString("Extra")).toLowerCase().contains("filesort"));
      }
    }
  }
}
