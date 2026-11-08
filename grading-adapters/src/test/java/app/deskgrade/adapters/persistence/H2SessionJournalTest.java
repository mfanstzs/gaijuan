package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2SessionJournalTest {
  @TempDir Path directory;

  @Test
  void capabilityMetricsDoNotRequireInventingGradeResultOrScore() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      UUID call = UUID.randomUUID();
      journal.beginModelCall(call, RUN, ATTEMPT, "synthetic-capability", NOW);
      journal.completeModelCallMetrics(
          call,
          GradingResponse.Source.LIVE,
          new GradingResponse.Usage(12L, 6L, null),
          java.time.Duration.ofMillis(123),
          null,
          NOW);
      var attempt = journal.attempts(RUN).getFirst();
      assertNull(attempt.score());
      assertNull(attempt.resultJson());
      assertFalse(attempt.submissionPrepared());
      try (var c = connection();
          var rows =
              c.createStatement()
                  .executeQuery(
                      "SELECT source,input_tokens,output_tokens,estimated_cost,elapsed_ms FROM model_call")) {
        assertTrue(rows.next());
        assertEquals("LIVE", rows.getString(1));
        assertEquals(12L, rows.getLong(2));
        assertEquals(6L, rows.getLong(3));
        assertNull(rows.getObject(4));
        assertEquals(123L, rows.getLong(5));
      }
      assertEquals(0, journal.runs().getFirst().confirmedSubmissions());
    }
  }

  private Path database() {
    return directory.resolve("ledger");
  }

  @Test
  void attemptAuditKeepsAllCallsUnknownUsageAndOriginalConfiguration() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      UUID first = UUID.randomUUID(), second = UUID.randomUUID();
      journal.beginModelCall(first, RUN, ATTEMPT, "profile_A", NOW);
      journal.completeModelCallMetrics(
          first,
          GradingResponse.Source.LIVE,
          null,
          java.time.Duration.ofMillis(25),
          "TIMEOUT",
          NOW);
      journal.beginModelCall(second, RUN, ATTEMPT, "profile_A", NOW.plusSeconds(1));
      journal.completeModelCall(
          second,
          response(new GradingResponse.Usage(3L, 2L, new BigDecimal("0.0005"))),
          null,
          NOW.plusSeconds(2));
      var audit =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(journal.attemptAuditJson(ATTEMPT));
      assertEquals(ATTEMPT.toString(), audit.path("attemptId").asText());
      assertEquals(2, audit.path("modelCalls").size());
      var failed = audit.path("modelCalls").get(0);
      assertEquals("TIMEOUT", failed.path("errorCode").asText());
      assertTrue(failed.path("inputTokens").isNull());
      assertTrue(failed.path("estimatedCost").isNull());
      assertEquals(25, failed.path("elapsedMs").asInt());
      assertEquals("profile_A", audit.path("modelCalls").get(1).path("profileId").asText());
      assertEquals(
          0,
          new BigDecimal("0.0005")
              .compareTo(audit.path("modelCalls").get(1).path("estimatedCost").decimalValue()));
      assertTrue(audit.path("gradingResult").isNull());
      assertEquals(
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(settings().configurationJson()),
          audit.path("configuration"));
      assertFalse(audit.path("submissionPrepared").asBoolean());
      assertThrows(DeskGradeException.class, () -> journal.attemptAuditJson(UUID.randomUUID()));
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:file:" + database().toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",
        "sa",
        "");
  }

  @Test
  void freshMigrationHasExactlyFiveTablesAndReopenPreservesVersionAndRuns() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
    }
    try (var journal = new H2SessionJournal(database());
        var c = connection()) {
      assertEquals(1, journal.runs().size());
      Set<String> tables = new HashSet<>();
      try (var rows = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
        while (rows.next()) tables.add(rows.getString("TABLE_NAME"));
      }
      assertEquals(
          Set.of("CONFIG_DOCUMENT", "GRADING_RUN", "GRADING_ATTEMPT", "MODEL_CALL", "RUN_EVENT"),
          tables);
      try (var rows =
          c.createStatement()
              .executeQuery(
                  "SELECT revision,json FROM config_document WHERE id='schema-version'")) {
        assertTrue(rows.next());
        assertEquals(2L, rows.getLong(1));
        assertEquals("2", rows.getString(2));
      }
    }
  }

  @Test
  void unknownFutureVersionIsRejectedWithoutOverwritingItsMetadata() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      journal.assertWritable();
    }
    try (var c = connection()) {
      c.createStatement()
          .executeUpdate(
              "UPDATE config_document SET revision=9,json='9' WHERE id='schema-version'");
    }
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(DeskGradeException.class, () -> new H2SessionJournal(database())).code());
    try (var c = connection();
        var rows =
            c.createStatement()
                .executeQuery(
                    "SELECT revision,json FROM config_document WHERE id='schema-version'")) {
      assertTrue(rows.next());
      assertEquals(9, rows.getLong(1));
      assertEquals("9", rows.getString(2));
    }
  }

  @Test
  void partialOrMismatchedMigrationMarkerFailsClosed() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      journal.assertWritable();
    }
    try (var c = connection()) {
      c.createStatement()
          .executeUpdate("UPDATE config_document SET json='999' WHERE id='schema-version'");
    }
    assertThrows(DeskGradeException.class, () -> new H2SessionJournal(database()));
    try (var c = connection()) {
      c.createStatement().executeUpdate("DELETE FROM config_document WHERE id='schema-version'");
    }
    assertThrows(DeskGradeException.class, () -> new H2SessionJournal(database()));
  }

  @Test
  void sameConfigurationRevisionCannotOverwriteSnapshotAndNewRevisionPreservesBoth()
      throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      journal.recordConfiguration(RUN, settings(), NOW);
      assertThrows(
          DeskGradeException.class,
          () -> journal.recordConfiguration(RUN, settings("{\"changed\":true}", 1), NOW));
      journal.recordConfiguration(RUN, settings("{\"changed\":true}", 2), NOW);
      try (var c = connection();
          var rows =
              c.createStatement()
                  .executeQuery(
                      "SELECT json FROM config_document WHERE kind='RUN_CONFIGURATION' ORDER BY revision")) {
        assertTrue(rows.next());
        assertEquals(settings().configurationJson(), rows.getString(1));
        assertTrue(rows.next());
        assertEquals("{\"changed\":true}", rows.getString(1));
        assertFalse(rows.next());
      }
      assertEquals(1, journal.attempts(RUN).size());
    }
  }

  @Test
  void preparationAndSendAreOneTimeTransactionsAndWrongRunCannotMutateAttempt() {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      var other = UUID.randomUUID();
      journal.beginRun(other, "other-window", settings(), NOW);
      assertThrows(
          DeskGradeException.class,
          () ->
              journal.transition(
                  other, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW));
      assertFalse(journal.attempts(RUN).getFirst().submissionPrepared());
      journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      assertThrows(
          DeskGradeException.class,
          () ->
              journal.transition(
                  RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW));
      journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW);
      assertThrows(
          DeskGradeException.class,
          () -> journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW));
      journal.confirmSubmission(RUN, ATTEMPT, NOW);
      assertThrows(DeskGradeException.class, () -> journal.confirmSubmission(RUN, ATTEMPT, NOW));
      assertEquals(
          1,
          journal.runs().stream()
              .filter(r -> r.runId().equals(RUN))
              .findFirst()
              .orElseThrow()
              .confirmedSubmissions());
    }
  }

  @Test
  void callUsageUnknownRemainsSqlNullAndCannotBeOverwrittenByLateCompletion() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      UUID first = UUID.randomUUID(), second = UUID.randomUUID();
      journal.beginModelCall(first, RUN, ATTEMPT, "synthetic-profile", NOW);
      journal.completeModelCall(first, response(null), null, NOW);
      journal.beginModelCall(second, RUN, ATTEMPT, "synthetic-profile", NOW);
      journal.completeModelCall(second, null, "TIMEOUT", NOW);
      assertThrows(
          DeskGradeException.class,
          () ->
              journal.completeModelCall(
                  second,
                  response(new GradingResponse.Usage(2L, 3L, new BigDecimal("0.1"))),
                  null,
                  NOW));
      try (var c = connection();
          var rows =
              c.createStatement()
                  .executeQuery(
                      "SELECT input_tokens,output_tokens,estimated_cost FROM model_call")) {
        int count = 0;
        while (rows.next()) {
          count++;
          assertNull(rows.getObject(1));
          assertNull(rows.getObject(2));
          assertNull(rows.getObject(3));
        }
        assertEquals(2, count);
      }
      assertEquals(2, journal.runs().getFirst().modelCalls());
      assertEquals(0, journal.runs().getFirst().confirmedSubmissions());
    }
  }

  @Test
  void missingProbeOrClosedDatabaseNeverPassesWritableCheck() throws Exception {
    try (var journal = new H2SessionJournal(database())) {
      try (var c = connection()) {
        c.createStatement().executeUpdate("DELETE FROM config_document WHERE id='storage-probe'");
      }
      assertThrows(DeskGradeException.class, journal::assertWritable);
    }
    assertThrows(DeskGradeException.class, () -> new H2SessionJournal(database()));
    var second = new H2SessionJournal(directory.resolve("closed"));
    second.close();
    assertThrows(DeskGradeException.class, second::assertWritable);
  }

  @Test
  void gradeAndCostCannotBeRoundedOrRewritePreparedEvidence() {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      var returned = response(null);
      journal.beginModelCall(returned.requestId(), RUN, ATTEMPT, "synthetic-profile", NOW);
      journal.completeModelCall(returned.requestId(), returned, null, NOW);
      assertThrows(
          DeskGradeException.class,
          () -> journal.saveGrade(ATTEMPT, returned, new BigDecimal("0.000000001")));
      journal.saveGrade(ATTEMPT, returned, BigDecimal.ONE);
      journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      assertThrows(
          DeskGradeException.class, () -> journal.saveGrade(ATTEMPT, returned, BigDecimal.ZERO));
      assertEquals(0, BigDecimal.ONE.compareTo(journal.attempts(RUN).getFirst().score()));
      UUID call = UUID.randomUUID();
      journal.beginModelCall(call, RUN, ATTEMPT, "synthetic-profile", NOW);
      assertThrows(
          DeskGradeException.class,
          () ->
              journal.completeModelCall(
                  call,
                  response(
                      new GradingResponse.Usage(1L, 1L, new BigDecimal("0.0000000000000000001"))),
                  null,
                  NOW));
    }
  }

  @Test
  void onlyOldTerminalRunsWithoutUnknownSubmissionAreEligibleForPruning() {
    try (var journal = new H2SessionJournal(database())) {
      begin(journal);
      journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      journal.transition(RUN, ATTEMPT, SessionState.STOPPED, PauseReason.NONE, NOW);
      var safe = UUID.randomUUID();
      journal.beginRun(safe, "safe", settings(), NOW);
      journal.transition(safe, null, SessionState.COMPLETED, PauseReason.NONE, NOW);
      var paused = UUID.randomUUID();
      journal.beginRun(paused, "paused", settings(), NOW);
      journal.transition(paused, null, SessionState.PAUSED, PauseReason.NONE, NOW);
      assertEquals(Set.of(safe), journal.resolvedEvidenceRunsBefore(NOW.plusSeconds(1)));
      assertTrue(journal.hasUnknownSubmission(TARGET));
    }
  }
}
