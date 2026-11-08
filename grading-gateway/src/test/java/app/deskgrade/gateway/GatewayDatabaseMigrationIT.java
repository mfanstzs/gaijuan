package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Requires an explicitly empty deskgrade_test_* MySQL schema, shared only with serial tests. */
class GatewayDatabaseMigrationIT {
  @TempDir Path temp;
  private static final List<String> OWNED_TABLES =
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
  void importsRealH2IntoMySqlPreservesTokensAndCreditsAndRefusesRepeatedImport() throws Exception {
    Path sourceBase = temp.resolve("original");
    var original = GatewayDatabaseMigrationTest.populate(sourceBase);
    byte[] originalFile = Files.readAllBytes(temp.resolve("original.mv.db"));
    try (var target = isolatedDatabase()) {
      requireEmptyTestDatabase(target);
      try {
        var report = GatewayDatabaseMigration.migrate(sourceBase, target);
        assertEquals("COMMITTED_AND_VERIFIED", report.status());
        assertEquals(24, report.ledger().availableCredits().intValueExact());
        assertTrue(report.originalDatabaseUnchanged());
        String json = GatewayJson.MAPPER.writeValueAsString(report);
        assertFalse(json.contains(original.token()));
        assertFalse(json.contains(GatewayStore.hash(original.token())));
        assertFalse(json.contains("password_hash"));
        assertFalse(json.contains("salt"));
        try (Connection source = GatewayDatabaseMigration.openSource(sourceBase);
            Connection imported = target.getConnection()) {
          GatewayDatabaseMigration.verifyCopiedContents(source, imported);
          assertEquals("READY", state(imported));
        }
        assertThrows(
            GatewayException.class, () -> GatewayDatabaseMigration.migrate(sourceBase, target));
        try (var store =
            new GatewayStore(
                target, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
          assertEquals(original.user(), store.authenticate(original.token()).id());
          assertEquals(
              original.user(), store.login("student", GatewayFixtures.password()).account().id());
          assertEquals(24, store.account(original.user()).balance());
          store.adjust(original.admin(), UUID.randomUUID(), original.user(), 7, "合成重启持久性验证");
        }
        try (var restarted =
            new GatewayStore(
                target, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
          assertEquals(31, restarted.account(original.user()).balance());
          assertEquals(31, restarted.authenticate(original.token()).balance());
        }
        assertArrayEquals(originalFile, Files.readAllBytes(temp.resolve("original.mv.db")));
      } finally {
        dropOnlyOwnedTables(target);
      }
    }
  }

  @Test
  void importsVersionTwoH2ThroughPrivateUpgradeWithoutTouchingTheOriginal() throws Exception {
    Path sourceBase = temp.resolve("original-v2");
    var original = GatewayDatabaseMigrationTest.populate(sourceBase);
    GatewayDatabaseMigrationTest.downgradeToVersionTwo(sourceBase);
    Path sourceFile = temp.resolve("original-v2.mv.db");
    byte[] originalFile = Files.readAllBytes(sourceFile);
    var originalModifiedAt = Files.getLastModifiedTime(sourceFile);
    Path stagingRoot = Files.createDirectory(temp.resolve("v2-upgrade-staging"));

    try (var target = isolatedDatabase()) {
      requireEmptyTestDatabase(target);
      try {
        var report = GatewayDatabaseMigration.migrate(sourceBase, target, stagingRoot);
        assertEquals("COMMITTED_AND_VERIFIED", report.status());
        assertTrue(report.originalDatabaseUnchanged());
        assertEquals(24, report.ledger().availableCredits().intValueExact());
        assertEquals(0L, report.copiedRows().get("rubric_generation_requests"));
        assertEquals(1L, report.copiedRows().get("gateway_rubric_model_policy"));

        assertArrayEquals(originalFile, Files.readAllBytes(sourceFile));
        assertEquals(originalModifiedAt, Files.getLastModifiedTime(sourceFile));
        try (var children = Files.list(stagingRoot)) {
          assertEquals(0, children.count(), "No upgraded H2 copy may remain after commit");
        }
        try (Connection source = GatewayDatabaseMigration.openSource(sourceBase);
            var query = source.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
            var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(2, rows.getInt(1), "The original schema must remain v2");
        }

        try (var imported =
            new GatewayStore(
                target, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
          assertEquals(original.user(), imported.authenticate(original.token()).id());
          assertEquals(24, imported.account(original.user()).balance());
          assertEquals(
              original.user(),
              imported.login("student", GatewayFixtures.password()).account().id());
          assertEquals(1, imported.modelPolicy().revision());
          assertEquals(GatewayFixtures.profile().id(), imported.modelPolicy().profileId());
          assertEquals(1, imported.modelPolicyAudit(original.admin()).size());
          assertEquals(new GatewayStore.ModelPolicy(0, null, null), imported.rubricModelPolicy());
          assertTrue(imported.rubricModelPolicyAudit(original.admin()).isEmpty());
        }
      } finally {
        dropOnlyOwnedTables(target);
      }
    }
  }

  @Test
  void resumesPendingVersionThreeAndFourTargetsBeforeImporting() throws Exception {
    Path sourceBase = temp.resolve("resume-source");
    var original = GatewayDatabaseMigrationTest.populate(sourceBase);
    try (var target = isolatedDatabase()) {
      requireEmptyTestDatabase(target);
      for (int version : new int[] {3, 4}) {
        try {
          GatewayStore.initializeMySql(target);
          downgradePendingTarget(target, version);

          var report = GatewayDatabaseMigration.migrate(sourceBase, target);
          assertEquals("COMMITTED_AND_VERIFIED", report.status());
          try (var connection = target.getConnection();
              var versionQuery =
                  connection.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
              var versions = versionQuery.executeQuery()) {
            assertTrue(versions.next());
            assertEquals(6, versions.getInt(1));
            assertEquals("READY", state(connection));
          }
          try (var imported =
              new GatewayStore(
                  target, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
            assertEquals(original.user(), imported.authenticate(original.token()).id());
            var order = imported.orders(original.user(), 0, 10).getFirst();
            assertEquals("100 次", order.packageName());
            assertEquals("CNY", order.currency());
          }
        } finally {
          dropOnlyOwnedTables(target);
        }
      }
    }
  }

  @Test
  void explicitNewInstallationRequiresAnEntirelyEmptyMysqlSchema() throws Exception {
    try (var target = isolatedDatabase()) {
      requireEmptyTestDatabase(target);
      var encoded = PasswordHasher.create(GatewayFixtures.password());
      try {
        GatewayStore.assertNewMySqlDatabase(target);
        GatewayStore.bootstrapNewMySql(
            target, "operator", encoded.salt(), encoded.hash(), encoded.iterations());
        GatewayStore.verifyMySqlInstallation(target);
        assertThrows(GatewayException.class, () -> GatewayStore.assertNewMySqlDatabase(target));
        assertThrows(
            GatewayException.class,
            () ->
                GatewayStore.bootstrapNewMySql(
                    target, "replacement", encoded.salt(), encoded.hash(), encoded.iterations()));
        try (var store =
            new GatewayStore(
                target, Clock.systemUTC(), new GatewaySessionAuthorityTest.FakeCache())) {
          assertEquals(
              "operator", store.login("operator", GatewayFixtures.password()).account().username());
          assertEquals(
              1,
              store
                  .userPage(
                      store.login("operator", GatewayFixtures.password()).account().id(), 1, 10, "")
                  .total());
        }
      } finally {
        java.util.Arrays.fill(encoded.salt(), (byte) 0);
        java.util.Arrays.fill(encoded.hash(), (byte) 0);
        dropOnlyOwnedTables(target);
      }
    }
  }

  @Test
  void realMySqlLateImportFailureRollsBackRowsAndNeverMarksReady() throws Exception {
    Path sourceBase = temp.resolve("original");
    GatewayDatabaseMigrationTest.populate(sourceBase);
    try (var target = isolatedDatabase()) {
      requireEmptyTestDatabase(target);
      try {
        GatewayStore.initializeMySql(target);
        assertThrows(GatewayException.class, () -> GatewayStore.assertNewMySqlDatabase(target));
        assertThrows(GatewayException.class, () -> GatewayStore.verifyMySqlInstallation(target));
        try (var connection = target.getConnection();
            var statement = connection.createStatement()) {
          statement.execute(
              "ALTER TABLE grade_requests ADD CONSTRAINT reject_synthetic_import CHECK(model_id<>'synthetic-model')");
        }
        assertThrows(
            GatewayException.class, () -> GatewayDatabaseMigration.migrate(sourceBase, target));
        try (var connection = target.getConnection()) {
          assertEquals("PENDING", state(connection));
          for (String table :
              List.of(
                  "accounts",
                  "sessions",
                  "adjustments",
                  "quota_events",
                  "orders",
                  "grade_requests",
                  "package_audit",
                  "credit_packages",
                  "model_policy_audit",
                  "rubric_generation_requests",
                  "rubric_model_policy_audit")) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                var rows = query.executeQuery()) {
              assertTrue(rows.next());
              assertEquals(0, rows.getLong(1), table);
            }
          }
        }
      } finally {
        dropOnlyOwnedTables(target);
      }
    }
  }

  private static HikariDataSource isolatedDatabase() {
    String url = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    assumeTrue(
        url != null && !url.isBlank(), "An explicitly isolated MySQL test database is required");
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(System.getenv("DESKGRADE_TEST_MYSQL_USER"));
    config.setPassword(System.getenv("DESKGRADE_TEST_MYSQL_PASSWORD"));
    config.setMaximumPoolSize(4);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setPoolName("DeskGradeSyntheticMigrationTest");
    return new HikariDataSource(config);
  }

  private static void requireEmptyTestDatabase(HikariDataSource source) throws Exception {
    try (var connection = source.getConnection()) {
      assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
      assertTrue(
          connection.getCatalog().matches("deskgrade_test_[A-Za-z0-9_]+"),
          "Refusing a production schema");
      try (var query =
              connection.prepareStatement(
                  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
          var rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(0, rows.getLong(1), "Refusing a database that was not initially empty");
      }
    }
  }

  private static void downgradePendingTarget(HikariDataSource source, int version)
      throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      assertEquals("PENDING", state(connection));
      statement.execute("DROP TABLE package_audit");
      statement.execute("DROP TABLE credit_packages");
      statement.execute("ALTER TABLE orders DROP COLUMN package_name");
      statement.execute("ALTER TABLE orders DROP COLUMN currency");
      statement.execute("DROP INDEX accounts_registration_dashboard ON accounts");
      statement.execute("DROP INDEX quota_events_dashboard ON quota_events");
      statement.execute("DROP INDEX orders_dashboard ON orders");
      if (version == 3) {
        for (String table : List.of("grade_requests", "rubric_generation_requests")) {
          for (String column : List.of("request_json", "raw_model_output", "completed_at"))
            statement.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
          String prefix = table.equals("grade_requests") ? "grade_calls" : "rubric_calls";
          statement.execute("DROP INDEX " + prefix + "_global_page ON " + table);
          statement.execute("DROP INDEX " + prefix + "_user_page ON " + table);
        }
      }
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE gateway_schema SET version=" + version + " WHERE id=1 AND version=6"));
    }
  }

  private static void dropOnlyOwnedTables(HikariDataSource source) throws Exception {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      assertTrue(connection.getCatalog().matches("deskgrade_test_[A-Za-z0-9_]+"));
      for (String table : OWNED_TABLES) statement.execute("DROP TABLE IF EXISTS " + table);
    }
  }

  private static String state(Connection connection) throws Exception {
    try (var query =
            connection.prepareStatement(
                "SELECT migration_state FROM gateway_storage_state WHERE id=1");
        var rows = query.executeQuery()) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }
}
