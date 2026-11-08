package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSubmissionRecordTest {
  @TempDir Path directory;

  @Test
  void h2ManualProgressPreservesReadbackAndRemainsExplicitlyNonAuthoritative() throws Exception {
    manualProgress(false);
  }

  @Test
  void mysqlManualProgressPreservesReadbackAndRemainsExplicitlyNonAuthoritative() throws Exception {
    manualProgress(true);
  }

  private void manualProgress(boolean mysql) throws Exception {
    try (var fixture = new Fixture(mysql, true)) {
      var before = fixture.record();
      assertEquals("NOT_PREPARED", before.at("/submission/status").asText());
      assertTrue(before.at("/submission/confirmationKind").isNull());
      assertFalse(before.at("/submission/platformConfirmed").asBoolean());
      fixture.prepare();
      assertEquals("UNKNOWN", fixture.record().at("/submission/status").asText());
      assertTrue(fixture.journal.hasUnknownSubmission("synthetic-window"));
      fixture.journal.confirmSubmission(
          fixture.run,
          fixture.attempt,
          H2TestFixtures.NOW,
          SessionPlatform.ConfirmationKind.SCREEN_TRANSITION);
      fixture.reopen();
      var record = fixture.record();
      assertEquals("VISUALLY_ADVANCED", record.at("/submission/status").asText());
      assertEquals("SCREEN_TRANSITION", record.at("/submission/confirmationKind").asText());
      assertFalse(record.at("/submission/platformConfirmed").asBoolean());
      assertEquals(H2TestFixtures.NOW.toString(), record.at("/submission/confirmedAt").asText());
      assertTrue(
          record
              .at("/submission/note")
              .asText()
              .contains("does not establish platform acceptance"));
      assertEquals("READBACK_MATCHED", record.at("/input/status").asText());
      assertEquals(0, BigDecimal.ONE.compareTo(record.at("/input/readbackScore").decimalValue()));
      assertEquals("合成答案", record.at("/modelCalls/0/modelResult/studentText").asText());
      assertEquals("NOT_STORED", record.path("imagePersistence").asText());
      assertTrue(
          record.path("events").findValuesAsText("state").contains("SCREEN_TRANSITION_OBSERVED"));
      assertFalse(record.path("events").findValuesAsText("state").contains("SUBMISSION_CONFIRMED"));
      assertEquals(
          "VISUALLY_ADVANCED",
          fixture
              .journal
              .queryRecords(fixture.owner, GradingRecords.Query.firstPage())
              .rows()
              .getFirst()
              .submissionStatus());
      assertFalse(fixture.journal.hasUnknownSubmission("synthetic-window"));
      Path exported = directory.resolve("manual-export.json");
      fixture.journal.exportJson(exported, fixture.owner, GradingRecords.Query.firstPage());
      var export = JournalJson.MAPPER.readTree(Files.readString(exported));
      assertEquals(record, export.path("records").get(0));
    }
  }

  @Test
  void absentManualMarkerPreservesLegacyPlatformAckMeaningOnBothReaders() throws Exception {
    for (boolean mysql : new boolean[] {false, true}) {
      try (var fixture = new Fixture(mysql, false)) {
        fixture.prepare();
        fixture.journal.confirmSubmission(fixture.run, fixture.attempt, H2TestFixtures.NOW);
        var record = fixture.record();
        assertEquals("CONFIRMED", record.at("/submission/status").asText());
        assertEquals("PLATFORM_ACK", record.at("/submission/confirmationKind").asText());
        assertTrue(record.at("/submission/platformConfirmed").asBoolean());
        assertTrue(
            record.path("events").findValuesAsText("state").contains("SUBMISSION_CONFIRMED"));
        assertEquals(
            "CONFIRMED",
            fixture
                .journal
                .queryRecords(fixture.owner, GradingRecords.Query.firstPage())
                .rows()
                .getFirst()
                .submissionStatus());
      }
    }
  }

  @Test
  void separateSubmitClickIsPersistedAsDispatchOnlyAcrossBothReadersAndRpc() throws Exception {
    for (boolean mysql : new boolean[] {false, true}) {
      try (var fixture = new Fixture(mysql, true)) {
        fixture.prepare();
        var command =
            JournalJson.MAPPER
                .createObjectNode()
                .put("schemaVersion", 1)
                .put("operation", "confirmSubmission");
        command
            .putObject("arguments")
            .put("runId", fixture.run.toString())
            .put("attemptId", fixture.attempt.toString())
            .put("now", H2TestFixtures.NOW.toString())
            .put("confirmationKind", "INPUT_DISPATCHED");
        JournalCommandDispatcher.execute(fixture.journal, fixture.owner, command);
        fixture.reopen();
        var record = fixture.record();
        assertEquals("SUBMIT_CLICKED", record.at("/submission/status").asText());
        assertEquals("INPUT_DISPATCHED", record.at("/submission/confirmationKind").asText());
        assertFalse(record.at("/submission/platformConfirmed").asBoolean());
        assertTrue(record.path("events").findValuesAsText("state").contains("SUBMIT_CLICKED"));
        assertFalse(
            record.path("events").findValuesAsText("state").contains("SCREEN_TRANSITION_OBSERVED"));
        assertEquals(
            "SUBMIT_CLICKED",
            fixture
                .journal
                .queryRecords(fixture.owner, GradingRecords.Query.firstPage())
                .rows()
                .getFirst()
                .submissionStatus());
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.journal.confirmSubmission(
                    fixture.run,
                    fixture.attempt,
                    H2TestFixtures.NOW,
                    SessionPlatform.ConfirmationKind.PLATFORM_ACK));
        assertEquals(
            "INPUT_DISPATCHED", fixture.record().at("/submission/confirmationKind").asText());
      }
    }
  }

  @Test
  void legacyManualConfirmationEventNeverBecomesAPlatformAcknowledgement() throws Exception {
    for (boolean mysql : new boolean[] {false, true}) {
      try (var fixture = new Fixture(mysql, true)) {
        fixture.prepare();
        fixture.journal.confirmSubmission(fixture.run, fixture.attempt, H2TestFixtures.NOW);
        assertEquals(
            "SCREEN_TRANSITION", fixture.record().at("/submission/confirmationKind").asText());
        assertFalse(fixture.record().at("/submission/platformConfirmed").asBoolean());
      }
    }
  }

  @Test
  void interruptionDoesNotTurnAnUnobservedManualSubmissionIntoVisualProgress() throws Exception {
    for (boolean mysql : new boolean[] {false, true}) {
      try (var fixture = new Fixture(mysql, true)) {
        fixture.prepare();
        fixture.reopen();
        fixture.journal.recoverInterrupted(H2TestFixtures.NOW.plusSeconds(1));
        var record = fixture.record();
        assertEquals("UNKNOWN", record.at("/submission/status").asText());
        assertTrue(record.at("/submission/confirmedAt").isNull());
        assertTrue(record.at("/submission/confirmationKind").isNull());
        assertFalse(record.at("/submission/platformConfirmed").asBoolean());
        assertTrue(fixture.journal.hasUnknownSubmission("synthetic-window"));
        assertEquals(SessionState.SUBMISSION_UNKNOWN.name(), record.path("state").asText());
      }
    }
  }

  @Test
  void legacyViewWireFormatIsAcceptedButExplicitNullKindIsRejected() throws Exception {
    var owner = new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
    var settings = MySqlSessionJournalTest.settings(owner);
    UUID run = UUID.randomUUID(), attempt = UUID.randomUUID();
    try (var journal = new H2SessionJournal(directory.resolve("legacy-view"))) {
      journal.beginRun(run, "synthetic-window", settings, H2TestFixtures.NOW);
      var command =
          JournalJson.MAPPER
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("operation", "beginAttempt");
      var arguments =
          command
              .putObject("arguments")
              .put("runId", run.toString())
              .put("attemptId", attempt.toString())
              .put("ordinal", 1)
              .put("epoch", 1)
              .put("evidencePath", MetadataOnlyEvidenceStore.NO_IMAGE_STORED)
              .put("sha256", "0".repeat(64))
              .put("capturedAt", H2TestFixtures.NOW.toString());
      arguments.set("settings", JournalJson.MAPPER.valueToTree(settings));
      ObjectNode view =
          JournalJson.MAPPER.valueToTree(
              new SessionPlatform.View(
                  "legacy-answer", "q", 1, SessionPlatform.Page.READY, null, 0, null, false));
      arguments.set("view", view);
      view.putNull("confirmationKind");
      assertThrows(
          IllegalArgumentException.class,
          () -> JournalCommandDispatcher.execute(journal, owner, command));
      assertTrue(journal.attempts(run).isEmpty());
      view.remove("confirmationKind");
      JournalCommandDispatcher.execute(journal, owner, command);
      assertEquals(attempt, journal.attempts(run).getFirst().attemptId());
    }
  }

  private final class Fixture implements AutoCloseable {
    private final MySqlSessionJournalTest.Database mysqlDatabase;
    private final Path databasePath;
    private final GradingRecords.Owner owner;
    private PersistentSessionJournal journal;
    private final UUID run = UUID.randomUUID(), attempt = UUID.randomUUID();

    Fixture(boolean mysql, boolean manual) throws Exception {
      mysqlDatabase = mysql ? new MySqlSessionJournalTest.Database() : null;
      owner =
          mysql
              ? mysqlDatabase.alice
              : new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
      databasePath = directory.resolve("record-" + UUID.randomUUID());
      journal = open();
      ObjectNode configuration =
          (ObjectNode)
              JournalJson.MAPPER.readTree(
                  MySqlSessionJournalTest.settings(owner).configurationJson());
      if (manual) configuration.putObject("platform").put("mode", "MANUAL_COORDINATES");
      var settings = H2TestFixtures.settings(configuration.toString(), 1);
      MySqlSessionJournalTest.begin(journal, run, attempt, settings);
      GradingResponse response = H2TestFixtures.response(null);
      journal.beginModelCall(
          response.requestId(), run, attempt, "synthetic-profile", H2TestFixtures.NOW);
      journal.completeModelCall(response.requestId(), response, null, H2TestFixtures.NOW);
      journal.saveGrade(attempt, response, BigDecimal.ONE);
      journal.recordScoreReadback(attempt, BigDecimal.ONE, H2TestFixtures.NOW);
    }

    private PersistentSessionJournal open() throws Exception {
      return mysqlDatabase == null
          ? new H2SessionJournal(databasePath)
          : mysqlDatabase.journal(owner);
    }

    void reopen() throws Exception {
      journal.close();
      journal = open();
    }

    JsonNode record() throws Exception {
      return JournalJson.MAPPER.readTree(journal.recordJson(owner, attempt));
    }

    void prepare() {
      journal.transition(
          run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, H2TestFixtures.NOW);
      journal.transition(
          run, attempt, SessionState.SUBMIT_SENT, PauseReason.NONE, H2TestFixtures.NOW);
    }

    @Override
    public void close() {
      journal.close();
    }
  }
}
