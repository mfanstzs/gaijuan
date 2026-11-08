package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSubmissionDeduplicationTest {
  @TempDir Path directory;
  static final String TARGET = "manual-calibration:manual-platform";
  static final String HASH = "1".repeat(64);

  @Test
  void h2ReopenRejectsPreparedAndClickedImagesBeforeAnotherModelCall() throws Exception {
    reopenRejects(false);
  }

  @Test
  void mysqlReopenRejectsPreparedAndClickedImagesBeforeAnotherModelCall() throws Exception {
    reopenRejects(true);
  }

  private void reopenRejects(boolean mysql) throws Exception {
    for (boolean confirmed : new boolean[] {false, true}) {
      try (var fixture = new Fixture(mysql)) {
        var original = create(fixture.journal, fixture.owner, TARGET, "q", HASH);
        modelCall(fixture.journal, original);
        prepare(fixture.journal, original);
        if (confirmed)
          fixture.journal.confirmSubmission(
              original.run(),
              original.attempt(),
              H2TestFixtures.NOW,
              SessionPlatform.ConfirmationKind.INPUT_DISPATCHED);
        fixture.reopen();
        var repeated = create(fixture.journal, fixture.owner, TARGET, "q", HASH);
        assertDuplicate(() -> modelCall(fixture.journal, repeated));
        assertDuplicate(() -> prepare(fixture.journal, repeated));
        var json =
            JournalJson.MAPPER.readTree(
                fixture.journal.recordJson(fixture.owner, repeated.attempt()));
        assertEquals(0, json.path("modelCalls").size());
        assertEquals(0, json.path("events").size());
        assertFalse(json.path("submissionPrepared").asBoolean());
        assertEquals(SessionState.CAPTURING.name(), json.path("state").asText());
        assertEquals(
            SessionState.IDLE,
            fixture.journal.runs().stream()
                .filter(run -> run.runId().equals(repeated.run()))
                .findFirst()
                .orElseThrow()
                .state());
        fixture.journal.assertWritable();
      }
    }
  }

  @Test
  void h2OtherOwnersQuestionsTargetsAndImagesRemainIndependent() throws Exception {
    independentScopes(false);
  }

  @Test
  void mysqlOtherOwnersQuestionsTargetsAndImagesRemainIndependent() throws Exception {
    independentScopes(true);
  }

  @Test
  void h2IdenticalAnswerPixelsForDifferentStudentIdentitiesRemainIndependent() throws Exception {
    identicalAnswersForDifferentStudents(false);
  }

  @Test
  void mysqlIdenticalAnswerPixelsForDifferentStudentIdentitiesRemainIndependent() throws Exception {
    identicalAnswersForDifferentStudents(true);
  }

  private void identicalAnswersForDifferentStudents(boolean mysql) throws Exception {
    try (var fixture = new Fixture(mysql)) {
      var first = create(fixture.journal, fixture.owner, TARGET, "q", HASH, "a".repeat(64));
      modelCall(fixture.journal, first);
      prepare(fixture.journal, first);
      var second = create(fixture.journal, fixture.owner, TARGET, "q", HASH, "b".repeat(64));
      assertDoesNotThrow(() -> modelCall(fixture.journal, second));
      assertDoesNotThrow(() -> prepare(fixture.journal, second));
    }
  }

  private void independentScopes(boolean mysql) throws Exception {
    try (var fixture = new Fixture(mysql)) {
      prepare(fixture.journal, create(fixture.journal, fixture.owner, TARGET, "q", HASH));
      for (String[] scope :
          List.of(
              new String[] {TARGET, "different-question", HASH},
              new String[] {TARGET + "-other", "q", HASH},
              new String[] {TARGET, "q", "2".repeat(64)})) {
        var independent = create(fixture.journal, fixture.owner, scope[0], scope[1], scope[2]);
        modelCall(fixture.journal, independent);
        prepare(fixture.journal, independent);
      }
      // Protocol adapters have their own authoritative answer IDs and are outside this guard.
      for (int count = 0; count < 2; count++) {
        var protocol = create(fixture.journal, fixture.owner, "protocol-target", "q", HASH);
        modelCall(fixture.journal, protocol);
        prepare(fixture.journal, protocol);
      }
      var otherOwner =
          new GradingRecords.Owner(fixture.owner.gateway(), UUID.randomUUID().toString());
      if (mysql) {
        try (var other = fixture.mysqlDatabase.journal(otherOwner)) {
          var independent = create(other, otherOwner, TARGET, "q", HASH);
          modelCall(other, independent);
          prepare(other, independent);
        }
      } else {
        var independent = create(fixture.journal, otherOwner, TARGET, "q", HASH);
        modelCall(fixture.journal, independent);
        prepare(fixture.journal, independent);
      }
    }
  }

  @Test
  void h2TwoConnectionsThatAlreadyGradedAnImageCanPrepareOnlyOneSubmission() throws Exception {
    Path path = directory.resolve("concurrent");
    var owner = new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
    try (var first = new H2SessionJournal(path);
        var second = new H2SessionJournal(path)) {
      var left = create(first, owner, TARGET, "q", HASH);
      var right = create(second, owner, TARGET, "q", HASH);
      modelCall(first, left);
      modelCall(second, right);
      compete(first, left, second, right);
      assertEquals(
          1,
          (first.attempts(left.run()).getFirst().submissionPrepared() ? 1 : 0)
              + (second.attempts(right.run()).getFirst().submissionPrepared() ? 1 : 0));
    }
  }

  static RunAttempt create(
      PersistentSessionJournal journal,
      GradingRecords.Owner owner,
      String target,
      String questionId,
      String hash) {
    return create(journal, owner, target, questionId, hash, "0".repeat(64));
  }

  static RunAttempt create(
      PersistentSessionJournal journal,
      GradingRecords.Owner owner,
      String target,
      String questionId,
      String hash,
      String identityHash) {
    var template = MySqlSessionJournalTest.settings(owner);
    var old = template.question();
    var question =
        new QuestionDefinition(
            questionId,
            old.revision(),
            old.prompt(),
            old.standardAnswer(),
            old.maximumScore(),
            old.criteria());
    var configuration = JournalJson.MAPPER.createObjectNode();
    configuration
        .putObject("access")
        .put("mode", "COMMERCIAL")
        .put("gateway", owner.gateway())
        .put("accountId", owner.accountId());
    configuration
        .putObject("platform")
        .put(
            "mode",
            target.startsWith("manual-calibration:") ? "MANUAL_COORDINATES" : "PLATFORM_PROTOCOL");
    var settings =
        new SessionSettings(
            question,
            template.modelProfileId(),
            1,
            1,
            configuration.toString(),
            0,
            0,
            0,
            null,
            template.pollInterval(),
            template.pageTimeout());
    var ids = new RunAttempt(UUID.randomUUID(), UUID.randomUUID());
    journal.beginRun(ids.run(), target, settings, H2TestFixtures.NOW);
    journal.beginAttempt(
        ids.run(),
        ids.attempt(),
        1,
        new SessionPlatform.View(
            "screen:" + hash + ":" + identityHash,
            questionId,
            1,
            SessionPlatform.Page.READY,
            null,
            0,
            null,
            false),
        1,
        MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
        new SessionPlatform.Capture(new byte[] {1}, hash, H2TestFixtures.NOW),
        settings);
    return ids;
  }

  static void modelCall(PersistentSessionJournal journal, RunAttempt ids) {
    journal.beginModelCall(
        UUID.randomUUID(), ids.run(), ids.attempt(), "synthetic", H2TestFixtures.NOW);
  }

  static void prepare(PersistentSessionJournal journal, RunAttempt ids) {
    journal.transition(
        ids.run(),
        ids.attempt(),
        SessionState.SUBMIT_PREPARED,
        PauseReason.NONE,
        H2TestFixtures.NOW);
  }

  static void compete(
      PersistentSessionJournal first,
      RunAttempt left,
      PersistentSessionJournal second,
      RunAttempt right)
      throws Exception {
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(competing(first, left, ready, go));
      var b = executor.submit(competing(second, right, ready, go));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      go.countDown();
      var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter("COMMITTED"::equals).count());
      assertEquals(
          1, results.stream().filter(ErrorCode.DUPLICATE_SUBMISSION.name()::equals).count());
    } finally {
      go.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static Callable<String> competing(
      PersistentSessionJournal journal, RunAttempt ids, CountDownLatch ready, CountDownLatch go) {
    return () -> {
      ready.countDown();
      if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
      try {
        prepare(journal, ids);
        return "COMMITTED";
      } catch (DeskGradeException failure) {
        return failure.code().name();
      }
    };
  }

  private static void assertDuplicate(Runnable action) {
    assertEquals(
        ErrorCode.DUPLICATE_SUBMISSION, assertThrows(DeskGradeException.class, action::run).code());
  }

  record RunAttempt(UUID run, UUID attempt) {}

  private final class Fixture implements AutoCloseable {
    final MySqlSessionJournalTest.Database mysqlDatabase;
    final GradingRecords.Owner owner;
    final Path path = directory.resolve("dedup-" + UUID.randomUUID());
    PersistentSessionJournal journal;

    Fixture(boolean mysql) throws Exception {
      mysqlDatabase = mysql ? new MySqlSessionJournalTest.Database() : null;
      owner =
          mysql
              ? mysqlDatabase.alice
              : new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
      journal = open();
    }

    PersistentSessionJournal open() throws Exception {
      return mysqlDatabase == null ? new H2SessionJournal(path) : mysqlDatabase.journal(owner);
    }

    void reopen() throws Exception {
      journal.close();
      journal = open();
    }

    @Override
    public void close() {
      journal.close();
    }
  }
}
