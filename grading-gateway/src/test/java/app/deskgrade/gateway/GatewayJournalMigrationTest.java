package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.persistence.MySqlSessionJournal;
import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filesystem and migration-gate contract tests; H2's compatibility mode is not a real MySQL test.
 */
class GatewayJournalMigrationTest {
  @TempDir Path directory;
  private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

  @Test
  void readonlySourceRejectsWritesAndMissingSourceNeverCreatesAnEmptyDatabase() throws Exception {
    Path source = directory.resolve("original");
    populate(source, UUID.randomUUID().toString());
    byte[] before = Files.readAllBytes(directory.resolve("original.mv.db"));
    try (var connection = GatewayJournalMigration.openSource(source);
        var statement = connection.prepareStatement("DELETE FROM grading_run")) {
      assertThrows(SQLException.class, statement::executeUpdate);
    }
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("original.mv.db")));
    assertThrows(
        GatewayException.class,
        () -> GatewayJournalMigration.migrate(directory.resolve("absent"), null));
    assertFalse(Files.exists(directory.resolve("absent.mv.db")));
  }

  @Test
  void anActiveWritableSourceIsRejectedBeforeOpeningDestination() throws Exception {
    Path source = directory.resolve("active");
    try (var journal = new H2SessionJournal(source)) {
      journal.assertWritable();
      var destination = new TestDataSource(true, UUID.randomUUID().toString());
      assertThrows(
          GatewayException.class, () -> GatewayJournalMigration.migrate(source, destination));
    }
  }

  @Test
  void pendingTargetIsRejectedBeforeJournalTablesAreInitialized() throws Exception {
    String account = UUID.randomUUID().toString();
    Path source = directory.resolve("pending-source");
    populate(source, account);
    var destination = new TestDataSource(false, account);
    assertThrows(
        GatewayException.class, () -> GatewayJournalMigration.migrate(source, destination));
    try (var connection = destination.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'journal_%'");
        var rows = query.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(0, rows.getLong(1));
    }
  }

  @Test
  void readyTargetImportsOnlyExistingAccountsAndReportsNoGradingContent() throws Exception {
    String account = UUID.randomUUID().toString();
    Path source = directory.resolve("records");
    var original = populate(source, account);
    byte[] before = Files.readAllBytes(directory.resolve("records.mv.db"));
    var destination = new TestDataSource(true, account);
    var report = GatewayJournalMigration.migrate(source, destination);
    assertEquals("COMMITTED_WITH_QUARANTINE", report.status());
    assertEquals(1, report.importedRuns());
    assertEquals(1, report.importedAttempts());
    assertEquals(List.of(original.quarantined().toString()), report.quarantinedRunIds());
    assertTrue(report.originalDatabaseUnchanged());
    assertFalse(report.imageFilesRead());
    assertEquals(report.sourceSha256Before(), report.sourceSha256After());
    String json = GatewayJson.MAPPER.writeValueAsString(report);
    assertFalse(json.contains("synthetic-standard-answer"));
    assertFalse(json.contains("synthetic-student-paper"));
    assertFalse(json.contains("password"));
    assertFalse(json.contains("token"));
    assertFalse(json.contains(account));
    try (var imported =
        new MySqlSessionJournal(
            destination.getConnection(),
            new GradingRecords.Owner("http://127.0.0.1:8787", account),
            "a".repeat(64))) {
      assertTrue(imported.hasUnknownSubmission("synthetic-student-paper"));
      assertEquals(
          SessionState.SUBMIT_PREPARED, imported.attempts(original.run()).getFirst().state());
      assertEquals(original.attempt(), imported.attempts(original.run()).getFirst().attemptId());
    }
    var repeated = GatewayJournalMigration.migrate(source, destination);
    assertEquals(0, repeated.importedRuns());
    assertEquals(1, repeated.skippedRuns());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("records.mv.db")));
  }

  @Test
  void reportCannotReplaceTheSourceConfigurationExistingFileOrH2Base() throws Exception {
    Path source = directory.resolve("alias");
    populate(source, UUID.randomUUID().toString());
    Path config = directory.resolve("storage.json");
    Files.writeString(config, "{}");
    Path existing = directory.resolve("previous-report.json");
    Files.writeString(existing, "preserve");
    for (Path forbidden : List.of(source, directory.resolve("alias.mv.db"), config, existing)) {
      assertThrows(
          GatewayException.class,
          () -> GatewayJournalMigration.validatePaths(source, config, forbidden));
    }
    assertThrows(
        GatewayException.class,
        () ->
            GatewayJournalMigration.validatePaths(
                source, directory.resolve("alias.mv.db"), directory.resolve("new.json")));
    var valid =
        GatewayJournalMigration.validatePaths(source, config, directory.resolve("new.json"));
    assertEquals(directory.toRealPath().resolve("new.json"), valid.report());
    assertEquals("preserve", Files.readString(existing));
    assertFalse(Files.exists(directory.resolve("new.json")));
  }

  private record Original(UUID run, UUID attempt, UUID quarantined) {}

  private static Original populate(Path source, String account) throws Exception {
    UUID run = UUID.randomUUID(), attempt = UUID.randomUUID(), quarantined = UUID.randomUUID();
    try (var journal = new H2SessionJournal(source)) {
      var settings = settings(account);
      journal.beginRun(run, "synthetic-student-paper", settings, NOW);
      journal.beginAttempt(
          run,
          attempt,
          1,
          new SessionPlatform.View(
              "synthetic-answer", "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
          1,
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
          new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), NOW),
          settings);
      journal.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      journal.beginRun(
          quarantined, "unmatched-account", settings(UUID.randomUUID().toString()), NOW);
    }
    return new Original(run, attempt, quarantined);
  }

  private static SessionSettings settings(String account) throws Exception {
    var configuration = GatewayJson.MAPPER.createObjectNode();
    configuration
        .putObject("access")
        .put("mode", "COMMERCIAL")
        .put("gateway", "http://127.0.0.1:8787")
        .put("accountId", account);
    var question =
        new QuestionDefinition(
            "q",
            1,
            "synthetic-question",
            "synthetic-standard-answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion("a", "criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    return new SessionSettings(
        question,
        "synthetic-profile",
        1,
        1,
        configuration.toString(),
        0,
        0,
        0,
        null,
        Duration.ofMillis(20),
        Duration.ofSeconds(5));
  }

  private static final class TestDataSource implements DataSource {
    final String url =
        "jdbc:h2:mem:journal_cli_"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    TestDataSource(boolean ready, String account) throws Exception {
      try (var connection = DriverManager.getConnection(url, "sa", "");
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE gateway_schema(id INT PRIMARY KEY, version INT NOT NULL)");
        statement.execute("INSERT INTO gateway_schema VALUES(1,6)");
        statement.execute(
            "CREATE TABLE gateway_storage_state(id INT PRIMARY KEY, migration_state VARCHAR(20))");
        statement.execute(
            "INSERT INTO gateway_storage_state VALUES(1,'" + (ready ? "READY" : "PENDING") + "')");
        statement.execute("CREATE TABLE accounts(id VARCHAR(36) PRIMARY KEY)");
        try (var insert = connection.prepareStatement("INSERT INTO accounts VALUES(?)")) {
          insert.setString(1, account);
          insert.executeUpdate();
        }
      }
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = DriverManager.getConnection(url, "sa", "");
      DatabaseMetaData metadata = delegate.getMetaData();
      var dialect =
          Proxy.newProxyInstance(
              DatabaseMetaData.class.getClassLoader(),
              new Class<?>[] {DatabaseMetaData.class},
              (proxy, method, args) ->
                  method.getName().equals("getDatabaseProductName")
                      ? "MySQL"
                      : invoke(method, metadata, args));
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  method.getName().equals("getMetaData")
                      ? dialect
                      : invoke(method, delegate, args));
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
        throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter writer) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getAnonymousLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException();
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
