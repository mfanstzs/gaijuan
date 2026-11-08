package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.error.DeskGradeException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MySqlSessionJournalTest {
  @TempDir Path directory;
  private static final Instant NOW = H2TestFixtures.NOW;

  @Test
  void committedGradeAndSubmissionSurviveReopenAndRetainFixedJson() throws Exception {
    var db = new Database();
    var run = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    var response = H2TestFixtures.response(null);
    try (var journal = db.journal(db.alice)) {
      begin(journal, run, attempt, settings(db.alice));
      journal.beginModelCall(response.requestId(), run, attempt, "synthetic", NOW);
      journal.completeModelCall(response.requestId(), response, null, NOW);
      journal.saveGrade(attempt, response, BigDecimal.ONE);
      journal.recordScoreReadback(attempt, BigDecimal.ONE, NOW);
      journal.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      journal.transition(run, attempt, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW);
      journal.confirmSubmission(run, attempt, NOW);
    }
    try (var journal = db.journal(db.alice)) {
      assertEquals(1, journal.runs().getFirst().confirmedSubmissions());
      var record = JournalJson.MAPPER.readTree(journal.recordJson(db.alice, attempt));
      assertEquals(0, record.path("javaValidatedTotal").decimalValue().compareTo(BigDecimal.ONE));
      assertEquals("CONFIRMED", record.path("submission").path("status").asText());
      assertEquals("READBACK_MATCHED", record.path("input").path("status").asText());
      assertEquals("NOT_STORED", record.path("imagePersistence").asText());
      assertEquals(response.requestId().toString(), record.path("acceptedRequestId").asText());
      assertEquals(
          "合成答案",
          record.path("modelCalls").get(0).path("modelResult").path("studentText").asText());
      assertEquals(1, journal.queryRecords(db.alice, GradingRecords.Query.firstPage()).total());
    }
  }

  @Test
  void allOwnerSensitivePathsDenyAnotherAccountAndRecoveryIsScoped() throws Exception {
    var db = new Database();
    var run = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    var call = UUID.randomUUID();
    try (var alice = db.journal(db.alice)) {
      begin(alice, run, attempt, settings(db.alice));
      alice.beginModelCall(call, run, attempt, "synthetic", NOW);
      alice.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
    }
    try (var bob = db.journal(db.bob)) {
      assertTrue(bob.runs().isEmpty());
      assertFalse(bob.hasUnknownSubmission("synthetic-window"));
      assertTrue(bob.recoverInterrupted(NOW.plusSeconds(1)).isEmpty());
      assertThrows(DeskGradeException.class, () -> bob.attempts(run));
      assertThrows(DeskGradeException.class, () -> bob.recordJson(db.bob, attempt));
      assertThrows(
          DeskGradeException.class,
          () -> bob.queryRecords(db.alice, GradingRecords.Query.firstPage()));
      assertThrows(
          DeskGradeException.class, () -> bob.recordConfiguration(run, settings(db.bob), NOW));
      assertThrows(
          DeskGradeException.class,
          () -> bob.transition(run, attempt, SessionState.STOPPED, PauseReason.NONE, NOW));
      assertThrows(
          DeskGradeException.class, () -> bob.completeModelCall(call, null, "TIMEOUT", NOW));
      assertThrows(
          DeskGradeException.class,
          () -> bob.saveGrade(attempt, H2TestFixtures.response(null), BigDecimal.ONE));
      assertThrows(
          DeskGradeException.class, () -> bob.recordScoreReadback(attempt, BigDecimal.ONE, NOW));
      assertThrows(DeskGradeException.class, () -> bob.confirmSubmission(run, attempt, NOW));
      assertThrows(
          DeskGradeException.class,
          () -> bob.beginRun(UUID.randomUUID(), "synthetic", settings(db.alice), NOW));
    }
    try (var alice = db.journal(db.alice)) {
      assertEquals(SessionState.SUBMIT_PREPARED, alice.runs().getFirst().state());
      alice.recoverInterrupted(NOW.plusSeconds(2));
      assertEquals(SessionState.SUBMISSION_UNKNOWN, alice.runs().getFirst().state());
      assertTrue(alice.hasUnknownSubmission("synthetic-window"));
    }
  }

  @Test
  void duplicateSubmissionIntentAndMismatchedGradeRollBack() throws Exception {
    var db = new Database();
    var run = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    try (var journal = db.journal(db.alice)) {
      begin(journal, run, attempt, settings(db.alice));
      var response = H2TestFixtures.response(null);
      journal.beginModelCall(response.requestId(), run, attempt, "synthetic", NOW);
      journal.completeModelCall(response.requestId(), response, null, NOW);
      assertThrows(
          DeskGradeException.class, () -> journal.saveGrade(attempt, response, BigDecimal.TEN));
      assertNull(journal.attempts(run).getFirst().score());
      journal.saveGrade(attempt, response, BigDecimal.ONE);
      journal.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      assertThrows(
          DeskGradeException.class,
          () ->
              journal.transition(
                  run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW));
      assertEquals(
          1,
          JournalJson.MAPPER.readTree(journal.recordJson(db.alice, attempt)).path("events").size());
    }
  }

  @Test
  void anotherLoginForTheSameAccountCannotRecoverOrMutateAnActiveClientRun() throws Exception {
    var db = new Database();
    var run = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    try (var first = db.journal(db.alice)) {
      begin(first, run, attempt, settings(db.alice));
      first.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
    }
    try (var other = new MySqlSessionJournal(db.connect(), db.alice, "f".repeat(64))) {
      other.recoverInterrupted(NOW.plusSeconds(1));
      assertEquals(SessionState.SUBMIT_PREPARED, other.runs().getFirst().state());
      assertTrue(other.hasUnknownSubmission("synthetic-window"));
      assertEquals(1, other.queryRecords(db.alice, GradingRecords.Query.firstPage()).total());
      assertThrows(
          DeskGradeException.class,
          () -> other.transition(run, attempt, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW));
      assertThrows(DeskGradeException.class, () -> other.confirmSubmission(run, attempt, NOW));
    }
  }

  @Test
  void legacyImportPreservesUnknownIntentAndCanResumeWithoutOverwriting() throws Exception {
    var db = new Database();
    var run = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    var unknown = UUID.randomUUID();
    Path legacyPath = directory.resolve("legacy");
    try (var legacy = new H2SessionJournal(legacyPath)) {
      begin(legacy, run, attempt, settings(db.alice));
      legacy.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      legacy.beginRun(unknown, "unowned", H2TestFixtures.settings(), NOW);
    }
    try (var legacy = DriverManager.getConnection("jdbc:h2:file:" + legacyPath, "sa", "");
        var target = db.connect()) {
      var report = LegacyJournalImporter.importV2(legacy, target, Set.of(db.alice.accountId()));
      assertEquals(1, report.importedRuns());
      assertEquals(1, report.importedAttempts());
      assertEquals(java.util.List.of(unknown.toString()), report.quarantinedRunIds());
      assertEquals(
          1,
          LegacyJournalImporter.importV2(legacy, target, Set.of(db.alice.accountId()))
              .skippedRuns());
    }
    try (var journal = db.journal(db.alice)) {
      assertTrue(journal.hasUnknownSubmission("synthetic-window"));
      assertEquals(SessionState.SUBMIT_PREPARED, journal.attempts(run).getFirst().state());
      assertEquals(attempt, journal.attempts(run).getFirst().attemptId());
    }
  }

  @Test
  void legacyImportPreservesHumanDispositionAndItsOldImageFence() throws Exception {
    var db = new Database();
    Path legacyPath = directory.resolve("legacy-manual-disposition");
    String targetIdentity = "manual-calibration:legacy-disposition";
    var decision = UUID.randomUUID();
    ManualSubmissionDeduplicationTest.RunAttempt old;
    try (var legacy = new H2SessionJournal(legacyPath)) {
      old =
          ManualSubmissionDeduplicationTest.create(
              legacy, db.alice, targetIdentity, "q", ManualSubmissionDeduplicationTest.HASH);
      ManualSubmissionDeduplicationTest.prepare(legacy, old);
      legacy.transition(
          old.run(),
          old.attempt(),
          SessionState.SUBMISSION_UNKNOWN,
          PauseReason.SUBMISSION_UNCERTAIN,
          NOW);
      legacy.abandonUnresolvedRun(
          db.alice, old.run(), targetIdentity, decision, NOW.plusSeconds(1));
    }
    String disposition;
    try (var legacy =
            DriverManager.getConnection(
                "jdbc:h2:file:" + legacyPath + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
        var target = db.connect()) {
      try (var statement = legacy.prepareStatement("SELECT json FROM config_document WHERE id=?")) {
        statement.setString(1, "manual-abandon:" + old.run());
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          disposition = row.getString(1);
        }
      }
      var report = LegacyJournalImporter.importV2(legacy, target, Set.of(db.alice.accountId()));
      assertEquals(1, report.importedRuns());
      assertEquals(1, report.importedAttempts());
      assertEquals(
          1,
          LegacyJournalImporter.importV2(legacy, target, Set.of(db.alice.accountId()))
              .skippedRuns());
      try (var statement =
          target.prepareStatement("SELECT kind,json FROM journal_config_document WHERE id=?")) {
        statement.setString(1, "manual-abandon:" + old.run());
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals(ManualBatchDisposition.KIND, row.getString("kind"));
          assertEquals(disposition, row.getString("json"));
        }
      }
    }
    try (var journal = db.journal(db.alice)) {
      assertFalse(journal.hasUnknownSubmission(targetIdentity));
      assertTrue(journal.unresolvedSubmissions(db.alice, targetIdentity).isEmpty());
      var attempt = journal.attempts(old.run()).getFirst();
      assertEquals(SessionState.SUBMISSION_UNKNOWN, attempt.state());
      assertTrue(attempt.submissionPrepared());
      assertFalse(attempt.confirmed());
      var record = JournalJson.MAPPER.readTree(journal.recordJson(db.alice, old.attempt()));
      assertEquals("UNKNOWN", record.at("/submission/status").asText());
      assertEquals(
          1,
          java.util.stream.StreamSupport.stream(record.path("events").spliterator(), false)
              .filter(event -> ManualBatchDisposition.EVENT.equals(event.path("state").asText()))
              .count());
      // Imported runs belong to their historical client; owner-level review still replays safely.
      journal.abandonUnresolvedRun(
          db.alice, old.run(), targetIdentity, decision, NOW.plusSeconds(5));
      var duplicate =
          ManualSubmissionDeduplicationTest.create(
              journal,
              db.alice,
              "manual-calibration:rebound",
              "q",
              ManualSubmissionDeduplicationTest.HASH,
              "b".repeat(64));
      assertEquals(
          app.deskgrade.core.error.ErrorCode.DUPLICATE_SUBMISSION,
          assertThrows(
                  DeskGradeException.class,
                  () -> ManualSubmissionDeduplicationTest.modelCall(journal, duplicate))
              .code());
    }
  }

  @Test
  void dispatcherRejectsClientOwnerImageAndUnexpectedFieldsBeforeStorage() throws Exception {
    var db = new Database();
    try (var journal = db.journal(db.alice)) {
      var command =
          JournalJson.MAPPER.createObjectNode().put("schemaVersion", 1).put("operation", "runs");
      command.putObject("arguments").put("owner", db.bob.accountId());
      assertThrows(
          IllegalArgumentException.class,
          () -> JournalCommandDispatcher.execute(journal, db.alice, command));
      command.set("arguments", JournalJson.MAPPER.createObjectNode());
      assertTrue(
          JournalCommandDispatcher.execute(journal, db.alice, command).path("result").isArray());
      command.put("pngBase64", "synthetic");
      assertThrows(
          IllegalArgumentException.class,
          () -> JournalCommandDispatcher.execute(journal, db.alice, command));
    }
  }

  static SessionSettings settings(GradingRecords.Owner owner) {
    var config = JournalJson.MAPPER.createObjectNode();
    var access = config.putObject("access");
    access
        .put("mode", "COMMERCIAL")
        .put("gateway", owner.gateway())
        .put("accountId", owner.accountId());
    return H2TestFixtures.settings(config.toString(), 1);
  }

  static void begin(
      PersistentSessionJournal journal, UUID run, UUID attempt, SessionSettings settings) {
    journal.beginRun(run, "synthetic-window", settings, NOW);
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
  }

  static final class Database {
    final String configuredUrl = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    final boolean live = configuredUrl != null && !configuredUrl.isBlank();
    final String url =
        live
            ? configuredUrl
            : "jdbc:h2:mem:journal_"
                + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    final GradingRecords.Owner alice =
        new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
    final GradingRecords.Owner bob =
        new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());

    Database() throws Exception {
      try (var connection = connect()) {
        MySqlSessionJournal.initializeSchema(connection);
      }
    }

    Connection connect() throws SQLException {
      return DriverManager.getConnection(
          url,
          live ? System.getenv("DESKGRADE_TEST_MYSQL_USER") : "sa",
          live ? System.getenv("DESKGRADE_TEST_MYSQL_PASSWORD") : "");
    }

    MySqlSessionJournal journal(GradingRecords.Owner owner) throws SQLException {
      return new MySqlSessionJournal(
          connect(), owner, owner.accountId().replace("-", "").repeat(2));
    }
  }
}
