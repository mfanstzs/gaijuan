package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.port.GradingProvider;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControllerHistoricalRecoveryTest {
  @TempDir Path directory;

  private SessionController controller(
      SessionControllerTest.FakePlatform platform,
      SessionJournal journal,
      GradingProvider provider) {
    return new SessionController(
        platform,
        journal,
        (run, attempt, capture) -> MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
        provider,
        result -> new BigDecimal("4"),
        SessionControllerTest.settings(1),
        Clock.systemUTC(),
        () -> true,
        status -> {});
  }

  private SessionControllerTest.FakePlatform manual(String calibration) {
    return new SessionControllerTest.FakePlatform(20) {
      @Override
      public String targetIdentity() {
        return "manual-calibration:" + calibration;
      }
    };
  }

  private UUID leaveUnknown(H2SessionJournal journal, SessionControllerTest.FakePlatform platform)
      throws Exception {
    platform.loseAck = true;
    try (var task = controller(platform, journal, SessionControllerTest.immediate())) {
      task.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      SessionControllerTest.await(task, SessionState.SUBMISSION_UNKNOWN);
      assertEquals(1, platform.ledger.get(1).size());
      return task.status().runId();
    }
  }

  @Test
  void historicalBlockHasNoPhantomRunAndStoppingItDoesNotCorruptHistory() throws Exception {
    try (var journal = new H2SessionJournal(directory.resolve("phantom"))) {
      var oldPlatform = manual("same-target");
      UUID oldRun = leaveUnknown(journal, oldPlatform);
      var history = journal.attempts(oldRun).getFirst();
      AtomicInteger modelCalls = new AtomicInteger();
      try (var task =
          controller(
              oldPlatform,
              journal,
              (request, token) -> {
                modelCalls.incrementAndGet();
                return SessionControllerTest.immediate().grade(request, token);
              })) {
        task.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(SessionState.SUBMISSION_UNKNOWN, task.status().state());
        assertNull(task.status().runId(), "A historical block must not pretend to be a new run");
        task.reconcileUnknown().toCompletableFuture().get(2, TimeUnit.SECONDS);
        task.pause(PauseReason.USER_PAUSED);
        task.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(SessionState.PAUSED, task.status().state());
        assertNull(task.status().runId());
        task.stop();
        task.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(SessionState.STOPPED, task.status().state());
        task.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(SessionState.SUBMISSION_UNKNOWN, task.status().state());
        assertEquals(0, modelCalls.get());
        assertEquals(1, journal.runs().size());
        assertEquals(history, journal.attempts(oldRun).getFirst());
      }
    }
  }

  @Test
  void explicitEndSurvivesRestartAndAllowsANewAnswerWithoutFalselyConfirmingTheOldOne()
      throws Exception {
    Path database = directory.resolve("recovery");
    UUID oldRun;
    try (var journal = new H2SessionJournal(database)) {
      oldRun = leaveUnknown(journal, manual("saved-layout"));
      var unresolved =
          journal.unresolvedSubmissions(
              GradingRecords.Owner.localDeveloper(), "manual-calibration:saved-layout");
      assertEquals(1, unresolved.size());
      assertEquals(oldRun, unresolved.getFirst().run().runId());
      journal.abandonUnresolvedRun(
          GradingRecords.Owner.localDeveloper(),
          oldRun,
          "manual-calibration:saved-layout",
          UUID.randomUUID(),
          Instant.now());
    }
    try (var journal = new H2SessionJournal(database)) {
      journal.recoverInterrupted(Instant.now());
      assertFalse(journal.hasUnknownSubmission("manual-calibration:saved-layout"));
      var newPlatform = manual("saved-layout");
      newPlatform.answer = 2;
      newPlatform.answerPixels = new byte[] {7, 8, 9};
      try (var task = controller(newPlatform, journal, SessionControllerTest.immediate())) {
        task.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        SessionControllerTest.await(task, SessionState.STOPPED);
        assertEquals(PauseReason.LIMIT_REACHED, task.status().reason());
        assertEquals(1, task.status().modelCalls());
        assertEquals(1, task.status().confirmedSubmissions());
        assertEquals(1, newPlatform.ledger.size());
        assertNotEquals(oldRun, task.status().runId());
      }
      var history = journal.attempts(oldRun).getFirst();
      assertEquals(SessionState.SUBMISSION_UNKNOWN, history.state());
      assertTrue(history.submissionPrepared());
      assertFalse(history.confirmed());
      assertEquals(0, new BigDecimal("4").compareTo(history.score()));
      assertTrue(journal.attemptAuditJson(history.attemptId()).contains("MANUAL_BATCH_ABANDONED"));
    }
  }

  @Test
  void endedUnknownAnswerCannotBeRegradedOrResubmittedAfterRebinding() throws Exception {
    try (var journal = new H2SessionJournal(directory.resolve("duplicate"))) {
      UUID oldRun = leaveUnknown(journal, manual("original-layout"));
      journal.abandonUnresolvedRun(
          GradingRecords.Owner.localDeveloper(),
          oldRun,
          "manual-calibration:original-layout",
          UUID.randomUUID(),
          Instant.now());
      var samePicture = manual("new-layout");
      samePicture.answer = 9;
      AtomicInteger modelCalls = new AtomicInteger();
      try (var task =
          controller(
              samePicture,
              journal,
              (request, token) -> {
                modelCalls.incrementAndGet();
                return SessionControllerTest.immediate().grade(request, token);
              })) {
        task.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        SessionControllerTest.await(task, SessionState.STOPPED);
        assertEquals(PauseReason.DUPLICATE_SCREEN, task.status().reason());
        assertEquals(0, modelCalls.get(), "The duplicate fence must precede a paid request");
        assertEquals(0, samePicture.inputs.get());
        assertTrue(samePicture.ledger.isEmpty());
        assertFalse(journal.attempts(oldRun).getFirst().confirmed());
      }
    }
  }
}
