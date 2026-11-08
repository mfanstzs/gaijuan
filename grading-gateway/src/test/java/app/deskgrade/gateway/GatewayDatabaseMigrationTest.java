package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDatabaseMigrationTest {
  @TempDir Path temp;

  record Original(UUID admin, UUID user, String token) {}

  @Test
  void copiesEveryBusinessColumnIncludingCredentialsSessionsUnicodeJsonAndNulls() throws Exception {
    Path sourceBase = temp.resolve("source"), targetBase = temp.resolve("target");
    Original original = populate(sourceBase);
    initializeEmpty(targetBase);
    byte[] before = Files.readAllBytes(sourceBase.resolveSibling("source.mv.db"));
    try (Connection source = GatewayDatabaseMigration.openSource(sourceBase);
        Connection target = writable(targetBase)) {
      target.setAutoCommit(false);
      var copied = GatewayDatabaseMigration.copyAndVerify(source, target);
      assertEquals(14, copied.rows().size());
      assertEquals(2L, copied.rows().get("accounts"));
      assertEquals(1L, copied.rows().get("sessions"));
      assertEquals(3L, copied.rows().get("credit_packages"));
      assertEquals(24, copied.ledger().availableCredits().intValueExact());
      assertEquals(24, copied.ledger().quotaEventDelta().intValueExact());
      assertEquals(1, copied.ledger().chargedRequests());
      GatewayDatabaseMigration.verifyCopiedContents(source, target);
      target.commit();
    }
    assertArrayEquals(before, Files.readAllBytes(sourceBase.resolveSibling("source.mv.db")));
    try (var reopened = new GatewayStore(targetBase, Clock.systemUTC())) {
      assertEquals(original.user(), reopened.authenticate(original.token()).id());
      assertEquals(24, reopened.account(original.user()).balance());
      assertEquals(
          original.user(), reopened.login("student", GatewayFixtures.password()).account().id());
      assertEquals(2, reopened.ledger(original.admin(), original.user(), 100).size());
    }
  }

  @Test
  void nonexistentSourceNeverCreatesAnEmptyReplacementDatabase() {
    Path absent = temp.resolve("absent");
    assertThrows(GatewayException.class, () -> GatewayDatabaseMigration.migrate(absent, null));
    assertFalse(Files.exists(temp.resolve("absent.mv.db")));
  }

  @Test
  void refusesAnActiveSourceGateway() {
    Path source = temp.resolve("active");
    try (var gateway = new GatewayStore(source, Clock.systemUTC())) {
      gateway.bootstrapAdmin("operator", GatewayFixtures.password());
      assertThrows(GatewayException.class, () -> GatewayDatabaseMigration.migrate(source, null));
    }
  }

  @Test
  void sourceConnectionCannotModifyOriginalData() throws Exception {
    Path source = temp.resolve("readonly");
    populate(source);
    try (Connection connection = GatewayDatabaseMigration.openSource(source);
        var update = connection.prepareStatement("UPDATE accounts SET balance=999")) {
      assertThrows(SQLException.class, update::executeUpdate);
    }
  }

  @Test
  void versionTwoSourceIsUpgradedOnlyInsideAControlledTemporaryCopy() throws Exception {
    Path source = temp.resolve("version-two-source");
    populate(source);
    downgradeToVersionTwo(source);
    Path sourceFile = source.resolveSibling("version-two-source.mv.db");
    byte[] before = Files.readAllBytes(sourceFile);
    var modifiedAt = Files.getLastModifiedTime(sourceFile);
    Path stagingRoot = Files.createDirectory(temp.resolve("migration-staging"));

    try (var prepared = GatewayDatabaseMigration.prepareSource(source, stagingRoot)) {
      assertTrue(prepared.temporary());
      assertNotEquals(source.toAbsolutePath().normalize(), prepared.base());
      try (var upgraded = GatewayDatabaseMigration.openSource(prepared.base());
          var query = upgraded.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
          var rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(6, rows.getInt(1));
      }
      try (var original = GatewayDatabaseMigration.openSource(source);
          var query = original.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
          var rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
      }
    }

    assertArrayEquals(before, Files.readAllBytes(sourceFile));
    assertEquals(modifiedAt, Files.getLastModifiedTime(sourceFile));
    try (var children = Files.list(stagingRoot)) {
      assertEquals(0, children.count(), "The private upgraded copy must be removed");
    }
  }

  @Test
  void nonemptyDestinationIsRejectedWithoutMergingUsersOrCredits() throws Exception {
    Path source = temp.resolve("source"), target = temp.resolve("occupied");
    populate(source);
    Original destination = populate(target);
    try (Connection reading = GatewayDatabaseMigration.openSource(source);
        Connection writing = writable(target)) {
      writing.setAutoCommit(false);
      assertThrows(
          GatewayException.class, () -> GatewayDatabaseMigration.copyAndVerify(reading, writing));
      writing.rollback();
    }
    try (var unchanged = new GatewayStore(target, Clock.systemUTC())) {
      assertEquals(24, unchanged.account(destination.user()).balance());
      assertEquals(destination.user(), unchanged.authenticate(destination.token()).id());
    }
  }

  @Test
  void lateInsertFailureCanRollBackEarlierAccountsLedgerAndPolicyRows() throws Exception {
    Path source = temp.resolve("source"), target = temp.resolve("failed");
    populate(source);
    initializeEmpty(target);
    try (Connection reading = GatewayDatabaseMigration.openSource(source);
        Connection writing = writable(target)) {
      try (var alter = writing.createStatement()) {
        alter.execute(
            "ALTER TABLE grade_requests ADD CONSTRAINT import_rejection CHECK(model_id<>'synthetic-model')");
      }
      writing.setAutoCommit(false);
      assertThrows(
          SQLException.class, () -> GatewayDatabaseMigration.copyAndVerify(reading, writing));
      writing.rollback();
      assertEquals(0, count(writing, "accounts"));
      assertEquals(0, count(writing, "adjustments"));
      assertEquals(0, count(writing, "quota_events"));
      assertEquals(1, count(writing, "gateway_model_policy"));
      assertEquals(1, count(writing, "gateway_rubric_model_policy"));
    }
  }

  @Test
  void valueVerificationDetectsChangesEvenWhenRowCountsAndBalancesMatch() throws Exception {
    Path source = temp.resolve("source"), target = temp.resolve("changed");
    populate(source);
    initializeEmpty(target);
    try (Connection reading = GatewayDatabaseMigration.openSource(source);
        Connection writing = writable(target)) {
      writing.setAutoCommit(false);
      GatewayDatabaseMigration.copyAndVerify(reading, writing);
      try (var change = writing.prepareStatement("UPDATE orders SET price_cents=0")) {
        change.executeUpdate();
      }
      assertThrows(
          GatewayException.class,
          () -> GatewayDatabaseMigration.verifyCopiedContents(reading, writing));
      writing.rollback();
    }
  }

  static Original populate(Path database) {
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("student", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 25, "合成迁移测试：保留额度");
      String token = store.login("student", GatewayFixtures.password()).token();
      store.createOrder(user.id(), UUID.randomUUID(), "calls-100", 100, null);
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      UUID request = UUID.randomUUID();
      store.prepare(user.id(), request, "a".repeat(64), profile.modelId(), profile.id(), 1L);
      store.beforeDispatch(user.id(), request, token);
      store.finish(
          user.id(), request, "{\"评分\":5,\"依据内容\":\"合成示例😀\",\"学生答题内容\":\"2\"}", null, false);
      return new Original(admin.id(), user.id(), token);
    }
  }

  static void downgradeToVersionTwo(Path database) throws Exception {
    try (var connection = writable(database);
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE package_audit");
      statement.execute("DROP TABLE rubric_model_policy_audit");
      statement.execute("DROP TABLE gateway_rubric_model_policy");
      statement.execute("DROP TABLE rubric_generation_requests");
      statement.execute("DROP TABLE credit_packages");
      statement.execute("ALTER TABLE orders DROP COLUMN package_name");
      statement.execute("ALTER TABLE orders DROP COLUMN currency");
      statement.execute("ALTER TABLE grade_requests DROP COLUMN request_json");
      statement.execute("ALTER TABLE grade_requests DROP COLUMN raw_model_output");
      statement.execute("ALTER TABLE grade_requests DROP COLUMN completed_at");
      statement.execute("DROP INDEX IF EXISTS accounts_registration_dashboard");
      statement.execute("DROP INDEX IF EXISTS quota_events_dashboard");
      statement.execute("DROP INDEX IF EXISTS orders_dashboard");
      statement.execute("DROP INDEX IF EXISTS grade_calls_global_page");
      statement.execute("DROP INDEX IF EXISTS grade_calls_user_page");
      if (statement.executeUpdate("UPDATE gateway_schema SET version=2 WHERE id=1") != 1)
        throw new IllegalStateException("Synthetic v2 downgrade failed");
    }
  }

  static void initializeEmpty(Path database) {
    try (var ignored = new GatewayStore(database, Clock.systemUTC())) {}
  }

  static Connection writable(Path database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:h2:file:"
            + database.toAbsolutePath().toString().replace('\\', '/')
            + ";IFEXISTS=TRUE",
        "sa",
        "");
  }

  private static long count(Connection connection, String table) throws SQLException {
    try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
        var rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
