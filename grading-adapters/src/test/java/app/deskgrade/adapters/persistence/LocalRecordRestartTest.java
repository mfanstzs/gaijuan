package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRecordRestartTest {
  @TempDir Path directory;

  @Test
  void coldRestartReadsAllDatesAndKeepsUnknownSubmissionWithoutReplayingIt() throws Exception {
    Path database = directory.resolve("database/local-deskgrade");
    Path logs = directory.resolve("records");
    UUID completedRun = UUID.randomUUID(), completedAttempt = UUID.randomUUID();
    UUID unknownRun = UUID.randomUUID(), unknownAttempt = UUID.randomUUID();
    Instant old = Instant.parse("2025-01-01T01:00:00Z"), recent = NOW;
    try (var journal = new LocalLoggingSessionJournal(new H2SessionJournal(database), logs)) {
      begin(journal, completedRun, completedAttempt, old);
      score(journal, completedRun, completedAttempt, old);
      journal.confirmSubmission(
          completedRun,
          completedAttempt,
          old.plusSeconds(2),
          SessionPlatform.ConfirmationKind.SCREEN_TRANSITION);
      journal.transition(
          completedRun,
          completedAttempt,
          SessionState.COMPLETED,
          PauseReason.NONE,
          old.plusSeconds(3));
      begin(journal, unknownRun, unknownAttempt, recent);
      score(journal, unknownRun, unknownAttempt, recent);
    }
    byte[] originalTimeline = timeline(logs);
    assertTrue(originalTimeline.length > 0);

    try (var restarted = new LocalLoggingSessionJournal(new H2SessionJournal(database), logs)) {
      restarted.recoverInterrupted(NOW.plusSeconds(10));
      var page =
          restarted.queryRecords(GradingRecords.Owner.local(), GradingRecords.Query.firstPage());
      assertEquals(2, page.total());
      var completed =
          page.rows().stream()
              .filter(r -> r.attemptId().equals(completedAttempt))
              .findFirst()
              .orElseThrow();
      var unresolved =
          page.rows().stream()
              .filter(r -> r.attemptId().equals(unknownAttempt))
              .findFirst()
              .orElseThrow();
      assertEquals(old, completed.capturedAt());
      assertEquals(SessionState.COMPLETED, completed.state());
      assertEquals("VISUALLY_ADVANCED", completed.submissionStatus());
      assertEquals(SessionState.SUBMISSION_UNKNOWN, unresolved.state());
      assertEquals("UNKNOWN", unresolved.submissionStatus());
      assertTrue(restarted.hasUnknownSubmission("synthetic-local-restart"));
      assertFalse(restarted.attempts(unknownRun).getFirst().confirmed());
      assertEquals(
          1,
          restarted.runs().stream()
              .filter(r -> r.runId().equals(unknownRun))
              .findFirst()
              .orElseThrow()
              .modelCalls());
      assertTrue(
          restarted
              .recordJson(GradingRecords.Owner.local(), completedAttempt)
              .contains("inputTokens"));
      assertEquals(
          0,
          restarted
              .queryRecords(GradingRecords.Owner.localDeveloper(), GradingRecords.Query.firstPage())
              .total());
    }
    byte[] appendedTimeline = timeline(logs);
    assertTrue(appendedTimeline.length > originalTimeline.length);
    assertArrayEquals(
        originalTimeline, java.util.Arrays.copyOf(appendedTimeline, originalTimeline.length));
  }

  @Test
  void missingMetadataLogDoesNotEraseAuthoritativeLocalHistory() throws Exception {
    Path database = directory.resolve("database/local-deskgrade");
    Path originalLogs = directory.resolve("records-original");
    UUID run = UUID.randomUUID(), attempt = UUID.randomUUID();
    try (var journal =
        new LocalLoggingSessionJournal(new H2SessionJournal(database), originalLogs)) {
      begin(journal, run, attempt, NOW);
      score(journal, run, attempt, NOW);
      journal.confirmSubmission(
          run, attempt, NOW.plusSeconds(2), SessionPlatform.ConfirmationKind.SCREEN_TRANSITION);
    }
    Path newLogs = directory.resolve("records-new");
    try (var restarted = new LocalLoggingSessionJournal(new H2SessionJournal(database), newLogs)) {
      restarted.recoverInterrupted(NOW.plusSeconds(5));
      var history =
          restarted.queryRecords(GradingRecords.Owner.local(), GradingRecords.Query.firstPage());
      assertEquals(1, history.total());
      assertEquals("VISUALLY_ADVANCED", history.rows().getFirst().submissionStatus());
      assertTrue(restarted.attempts(run).getFirst().confirmed());
    }
    assertTrue(timeline(originalLogs).length > 0);
    assertTrue(timeline(newLogs).length > 0);
  }

  private static void begin(
      LocalLoggingSessionJournal journal, UUID run, UUID attempt, Instant at) {
    var config = settings("{\"access\":{\"mode\":\"LOCAL\"}}", 1);
    journal.beginRun(run, "synthetic-local-restart", config, at);
    journal.beginAttempt(
        run,
        attempt,
        1,
        new SessionPlatform.View(
            "synthetic-answer-" + attempt,
            "q",
            1,
            SessionPlatform.Page.READY,
            null,
            0,
            null,
            false),
        1,
        MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
        new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), at),
        config);
  }

  private static void score(
      LocalLoggingSessionJournal journal, UUID run, UUID attempt, Instant at) {
    var response = response(new GradingResponse.Usage(12L, 4L, null));
    journal.beginModelCall(response.requestId(), run, attempt, "synthetic-profile", at);
    journal.completeModelCall(response.requestId(), response, null, at.plusSeconds(1));
    journal.saveGrade(attempt, response, BigDecimal.ONE);
    journal.recordScoreReadback(attempt, BigDecimal.ONE, at.plusSeconds(1));
    journal.transition(
        run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, at.plusSeconds(1));
    journal.transition(run, attempt, SessionState.SUBMIT_SENT, PauseReason.NONE, at.plusSeconds(1));
  }

  private static byte[] timeline(Path logs) throws Exception {
    var result = new java.io.ByteArrayOutputStream();
    try (var files = Files.list(logs)) {
      for (Path file : files.sorted().toList()) result.write(Files.readAllBytes(file));
    }
    return result.toByteArray();
  }
}
