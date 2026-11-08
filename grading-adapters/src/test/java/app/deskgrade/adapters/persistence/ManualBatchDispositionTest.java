package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.NOW;
import static app.deskgrade.adapters.persistence.ManualSubmissionDeduplicationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualBatchDispositionTest {
  @TempDir Path directory;

  @Test
  void h2HumanDispositionSurvivesReopenWithoutChangingTheUnknownSubmission() throws Exception {
    try (var fixture = new Fixture(false)) {
      durableReview(fixture);
    }
  }

  @Test
  void mysqlHumanDispositionSurvivesNewSessionWithoutChangingTheUnknownSubmission()
      throws Exception {
    try (var fixture = new Fixture(true)) {
      durableReview(fixture);
    }
  }

  private void durableReview(Fixture fixture) throws Exception {
    var journal = fixture.journal;
    String target = TARGET + UUID.randomUUID();
    var old = create(journal, fixture.owner, target, "q", HASH, "a".repeat(64));
    var response = H2TestFixtures.response(null);
    journal.beginModelCall(response.requestId(), old.run(), old.attempt(), "synthetic", NOW);
    journal.completeModelCall(response.requestId(), response, null, NOW);
    journal.saveGrade(old.attempt(), response, BigDecimal.ONE);
    journal.recordScoreReadback(old.attempt(), BigDecimal.ONE, NOW);
    prepare(journal, old);
    // A prepared intent may still belong to a live worker. Explicitly stop it before review.
    assertThrows(
        DeskGradeException.class,
        () ->
            journal.abandonUnresolvedRun(fixture.owner, old.run(), target, UUID.randomUUID(), NOW));
    journal.transition(
        old.run(),
        old.attempt(),
        SessionState.SUBMISSION_UNKNOWN,
        PauseReason.SUBMISSION_UNCERTAIN,
        NOW);
    var unknown = journal.attempts(old.run()).getFirst();
    var review = journal.unresolvedSubmissions(fixture.owner, target);
    assertEquals(1, review.size());
    assertEquals(old.run(), review.getFirst().run().runId());
    assertEquals(old.attempt(), review.getFirst().attemptId());
    assertEquals(0, BigDecimal.ONE.compareTo(review.getFirst().score()));
    assertTrue(journal.hasUnknownSubmission(target));
    var wrongOwner =
        new GradingRecords.Owner(fixture.owner.gateway(), UUID.randomUUID().toString());
    if (fixture.mysql) {
      assertThrows(
          DeskGradeException.class, () -> journal.unresolvedSubmissions(wrongOwner, target));
    } else assertTrue(journal.unresolvedSubmissions(wrongOwner, target).isEmpty());
    assertThrows(
        DeskGradeException.class,
        () -> journal.abandonUnresolvedRun(wrongOwner, old.run(), target, UUID.randomUUID(), NOW));
    assertThrows(
        DeskGradeException.class,
        () ->
            journal.abandonUnresolvedRun(
                fixture.owner, old.run(), target + "-wrong", UUID.randomUUID(), NOW));

    UUID decision = UUID.randomUUID();
    try (var reviewer = fixture.anotherSession()) {
      reviewer.abandonUnresolvedRun(
          fixture.owner, old.run(), target, decision, NOW.plusSeconds(10));
      reviewer.abandonUnresolvedRun(
          fixture.owner, old.run(), target, decision, NOW.plusSeconds(20));
      assertThrows(
          DeskGradeException.class,
          () ->
              reviewer.abandonUnresolvedRun(
                  fixture.owner, old.run(), target, UUID.randomUUID(), NOW.plusSeconds(20)));
      reviewer.recoverInterrupted(NOW.plusSeconds(30));
      assertFalse(reviewer.hasUnknownSubmission(target));
      assertTrue(reviewer.unresolvedSubmissions(fixture.owner, target).isEmpty());
      assertEquals(unknown, reviewer.attempts(old.run()).getFirst());
      assertEquals(
          SessionState.STOPPED,
          reviewer.runs().stream()
              .filter(run -> run.runId().equals(old.run()))
              .findFirst()
              .orElseThrow()
              .state());
      var record = JournalJson.MAPPER.readTree(reviewer.recordJson(fixture.owner, old.attempt()));
      assertEquals("UNKNOWN", record.at("/submission/status").asText());
      assertFalse(record.at("/submission/platformConfirmed").asBoolean());
      assertTrue(record.path("confirmedAt").isNull());
      assertEquals(
          1,
          java.util.stream.StreamSupport.stream(record.path("events").spliterator(), false)
              .filter(event -> ManualBatchDisposition.EVENT.equals(event.path("state").asText()))
              .count());
    }
    // A worker on the original token must not be able to revive the manually ended run.
    assertThrows(DeskGradeException.class, () -> modelCall(journal, old));
    assertThrows(DeskGradeException.class, () -> prepare(journal, old));
    assertThrows(
        DeskGradeException.class, () -> journal.confirmSubmission(old.run(), old.attempt(), NOW));
    assertThrows(
        DeskGradeException.class,
        () ->
            journal.recordConfiguration(
                old.run(), MySqlSessionJournalTest.settings(fixture.owner), NOW));
    assertThrows(
        DeskGradeException.class,
        () -> journal.recordScoreReadback(old.attempt(), BigDecimal.ONE, NOW));
    assertThrows(
        DeskGradeException.class, () -> journal.saveGrade(old.attempt(), response, BigDecimal.ONE));
    assertThrows(
        DeskGradeException.class,
        () ->
            journal.transition(
                old.run(), old.attempt(), SessionState.STOPPED, PauseReason.USER_STOPPED, NOW));

    fixture.reopen();
    assertFalse(fixture.journal.hasUnknownSubmission(target));
    fixture.journal.recoverInterrupted(NOW.plusSeconds(40));
    assertFalse(fixture.journal.hasUnknownSubmission(target));
    // Even a new calibration and a changed legacy student-identity region cannot replay this image.
    var duplicate =
        create(fixture.journal, fixture.owner, target + "-rebound", "q", HASH, "b".repeat(64));
    assertDuplicate(() -> modelCall(fixture.journal, duplicate));
    assertDuplicate(() -> prepare(fixture.journal, duplicate));
    var next = create(fixture.journal, fixture.owner, target, "q", "2".repeat(64));
    modelCall(fixture.journal, next);
    prepare(fixture.journal, next);
    var otherQuestion = create(fixture.journal, fixture.owner, target, "q2", HASH);
    modelCall(fixture.journal, otherQuestion);
    prepare(fixture.journal, otherQuestion);
    if (fixture.mysql) {
      try (var other = fixture.database.journal(wrongOwner)) {
        var independent = create(other, wrongOwner, target, "q", HASH);
        modelCall(other, independent);
        prepare(other, independent);
      }
    } else {
      var independent = create(fixture.journal, wrongOwner, target, "q", HASH);
      modelCall(fixture.journal, independent);
      prepare(fixture.journal, independent);
    }
  }

  @Test
  void lateModelCompletionOnlyAddsItsAuditAfterHumanDisposition() throws Exception {
    try (var fixture = new Fixture(true)) {
      var old = create(fixture.journal, fixture.owner, TARGET, "q", HASH);
      var response = H2TestFixtures.response(null);
      fixture.journal.beginModelCall(
          response.requestId(), old.run(), old.attempt(), "synthetic", NOW);
      prepare(fixture.journal, old);
      fixture.journal.transition(
          old.run(),
          old.attempt(),
          SessionState.SUBMISSION_UNKNOWN,
          PauseReason.SUBMISSION_UNCERTAIN,
          NOW);
      fixture.journal.abandonUnresolvedRun(
          fixture.owner, old.run(), TARGET, UUID.randomUUID(), NOW);
      fixture.journal.completeModelCall(response.requestId(), response, null, NOW.plusSeconds(5));
      var record =
          JournalJson.MAPPER.readTree(fixture.journal.recordJson(fixture.owner, old.attempt()));
      assertEquals(1, record.path("modelCalls").size());
      assertEquals("VALIDATED", record.at("/modelCalls/0/validationStatus").asText());
      assertTrue(record.path("javaValidatedTotal").isNull());
      assertTrue(record.path("confirmedAt").isNull());
      assertThrows(
          DeskGradeException.class,
          () -> fixture.journal.saveGrade(old.attempt(), response, BigDecimal.ONE));
      assertFalse(fixture.journal.hasUnknownSubmission(TARGET));
    }
  }

  @Test
  void boundedReviewReturnsOldestFirstAndRefreshesAfterEntireRunDisposition() throws Exception {
    try (var fixture = new Fixture(true)) {
      var ids = new java.util.ArrayList<ManualSubmissionDeduplicationTest.RunAttempt>();
      for (int i = 0; i < 101; i++) {
        var value = create(fixture.journal, fixture.owner, TARGET, "q", String.format("%064x", i));
        prepare(fixture.journal, value);
        fixture.journal.transition(
            value.run(),
            value.attempt(),
            SessionState.SUBMISSION_UNKNOWN,
            PauseReason.SUBMISSION_UNCERTAIN,
            NOW);
        ids.add(value);
      }
      var first = fixture.journal.unresolvedSubmissions(fixture.owner, TARGET);
      assertEquals(100, first.size());
      var orderedIds =
          first.stream().map(PersistentSessionJournal.UnresolvedSubmission::attemptId).toList();
      assertEquals(
          orderedIds.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList(),
          orderedIds);
      var reviewed = first.getFirst();
      fixture.journal.abandonUnresolvedRun(
          fixture.owner, reviewed.run().runId(), TARGET, UUID.randomUUID(), NOW);
      var refreshed = fixture.journal.unresolvedSubmissions(fixture.owner, TARGET);
      assertEquals(100, refreshed.size());
      assertTrue(
          refreshed.stream().noneMatch(row -> row.run().runId().equals(reviewed.run().runId())));
    }
  }

  @Test
  void authenticatedDispatcherRejectsCallerOwnerAndUsesServerDecisionTime() throws Exception {
    try (var fixture = new Fixture(true)) {
      var old = create(fixture.journal, fixture.owner, TARGET, "q", HASH);
      prepare(fixture.journal, old);
      fixture.journal.transition(
          old.run(),
          old.attempt(),
          SessionState.SUBMISSION_UNKNOWN,
          PauseReason.SUBMISSION_UNCERTAIN,
          NOW);
      var query =
          JournalJson.MAPPER
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("operation", "unresolvedSubmissions");
      query.putObject("arguments").put("targetIdentity", TARGET);
      assertEquals(
          1,
          JournalCommandDispatcher.execute(fixture.journal, fixture.owner, query)
              .path("result")
              .size());
      var command =
          JournalJson.MAPPER
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("operation", "abandonUnresolvedRun");
      var args =
          command
              .putObject("arguments")
              .put("runId", old.run().toString())
              .put("targetIdentity", TARGET)
              .put("decisionId", UUID.randomUUID().toString());
      args.put("owner", "someone-else");
      assertThrows(
          IllegalArgumentException.class,
          () -> JournalCommandDispatcher.execute(fixture.journal, fixture.owner, command));
      args.remove("owner");
      args.put("now", NOW.toString());
      assertThrows(
          IllegalArgumentException.class,
          () -> JournalCommandDispatcher.execute(fixture.journal, fixture.owner, command));
      args.remove("now");
      Instant before = Instant.now();
      JournalCommandDispatcher.execute(fixture.journal, fixture.owner, command);
      var events =
          JournalJson.MAPPER
              .readTree(fixture.journal.recordJson(fixture.owner, old.attempt()))
              .path("events");
      var event =
          java.util.stream.StreamSupport.stream(events.spliterator(), false)
              .filter(item -> ManualBatchDisposition.EVENT.equals(item.path("state").asText()))
              .findFirst()
              .orElseThrow();
      assertFalse(Instant.parse(event.path("at").asText()).isBefore(before.minusMillis(1)));
    }
  }

  @Test
  void reviewingOneRunResolvesEveryOutstandingAttemptWithoutAlteringEitherIntent()
      throws Exception {
    var database = new MySqlSessionJournalTest.Database();
    try (var journal = database.journal(database.alice)) {
      var settings = MySqlSessionJournalTest.settings(database.alice);
      UUID run = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
      MySqlSessionJournalTest.begin(journal, run, first, settings);
      journal.beginAttempt(
          run,
          second,
          2,
          new SessionPlatform.View(
              "second-answer", "q", 2, SessionPlatform.Page.READY, null, 0, null, false),
          1,
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
          new SessionPlatform.Capture(new byte[] {2}, "2".repeat(64), NOW),
          settings);
      for (UUID attempt : List.of(first, second)) {
        journal.transition(run, attempt, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
        journal.transition(
            run, attempt, SessionState.SUBMISSION_UNKNOWN, PauseReason.SUBMISSION_UNCERTAIN, NOW);
      }
      assertEquals(2, journal.unresolvedSubmissions(database.alice, "synthetic-window").size());
      var before = journal.attempts(run);
      journal.abandonUnresolvedRun(database.alice, run, "synthetic-window", UUID.randomUUID(), NOW);
      assertEquals(before, journal.attempts(run));
      assertTrue(journal.unresolvedSubmissions(database.alice, "synthetic-window").isEmpty());
      for (UUID attempt : List.of(first, second)) {
        var events =
            JournalJson.MAPPER.readTree(journal.recordJson(database.alice, attempt)).path("events");
        assertEquals(
            1,
            java.util.stream.StreamSupport.stream(events.spliterator(), false)
                .filter(event -> ManualBatchDisposition.EVENT.equals(event.path("state").asText()))
                .count());
      }
    }
  }

  @Test
  void confirmationAndHumanDispositionAreSerializedAcrossConnections() throws Exception {
    var database = new MySqlSessionJournalTest.Database();
    try (var original = database.journal(database.alice);
        var reviewer =
            new MySqlSessionJournal(database.connect(), database.alice, "b".repeat(64))) {
      var old = create(original, database.alice, TARGET, "q", HASH);
      prepare(original, old);
      original.transition(
          old.run(),
          old.attempt(),
          SessionState.SUBMISSION_UNKNOWN,
          PauseReason.SUBMISSION_UNCERTAIN,
          NOW);
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var abandoned =
            executor.submit(
                () ->
                    race(
                        ready,
                        go,
                        () ->
                            reviewer.abandonUnresolvedRun(
                                database.alice, old.run(), TARGET, UUID.randomUUID(), NOW)));
        var confirmed =
            executor.submit(
                () ->
                    race(
                        ready,
                        go,
                        () -> original.confirmSubmission(old.run(), old.attempt(), NOW)));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        boolean a = abandoned.get(15, TimeUnit.SECONDS), c = confirmed.get(15, TimeUnit.SECONDS);
        assertNotEquals(a, c);
        var record =
            JournalJson.MAPPER.readTree(original.recordJson(database.alice, old.attempt()));
        assertEquals(c, !record.path("confirmedAt").isNull());
        assertFalse(original.hasUnknownSubmission(TARGET));
      } finally {
        go.countDown();
      }
    }
  }

  @Test
  void mysqlCaptureIndexInitializationIsIdempotentAndCoversTheNewLookup() throws Exception {
    var database = new MySqlSessionJournalTest.Database();
    try (var connection = database.connect()) {
      MySqlSessionJournal.initializeSchema(connection);
      assertIndex(connection, "journal_grading_attempt", "journal_attempt_owner_capture");
      MySqlSessionJournal.initializeSchema(connection);
      assertIndex(connection, "journal_grading_attempt", "journal_attempt_owner_capture");
    }
  }

  @Test
  void h2CaptureIndexExistsAfterReopeningAnExistingJournal() throws Exception {
    try (var fixture = new Fixture(false)) {
      fixture.reopen();
      try (var connection =
          java.sql.DriverManager.getConnection(
              "jdbc:h2:file:" + fixture.path + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "")) {
        assertIndex(connection, "GRADING_ATTEMPT", "ATTEMPT_OWNER_CAPTURE_IDX");
      }
    }
  }

  private static void assertIndex(java.sql.Connection connection, String table, String index)
      throws Exception {
    var columns = new java.util.TreeMap<Integer, String>();
    try (var rows =
        connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
      while (rows.next())
        if (index.equalsIgnoreCase(rows.getString("INDEX_NAME")))
          columns.put(
              rows.getInt("ORDINAL_POSITION"),
              rows.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
    }
    assertEquals(
        List.of("owner_scope", "question_id", "capture_sha256", "run_id"),
        List.copyOf(columns.values()));
  }

  private static boolean race(CountDownLatch ready, CountDownLatch go, Runnable action)
      throws Exception {
    ready.countDown();
    if (!go.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Review test barrier timed out");
    try {
      action.run();
      return true;
    } catch (DeskGradeException rejected) {
      assertEquals(ErrorCode.STORAGE_FAILURE, rejected.code());
      return false;
    }
  }

  private static void assertDuplicate(Runnable action) {
    assertEquals(
        ErrorCode.DUPLICATE_SUBMISSION, assertThrows(DeskGradeException.class, action::run).code());
  }

  private final class Fixture implements AutoCloseable {
    final boolean mysql;
    final Path path = directory.resolve(UUID.randomUUID().toString());
    final MySqlSessionJournalTest.Database database;
    final GradingRecords.Owner owner;
    PersistentSessionJournal journal;

    Fixture(boolean mysql) throws Exception {
      this.mysql = mysql;
      database = mysql ? new MySqlSessionJournalTest.Database() : null;
      owner =
          mysql
              ? database.alice
              : new GradingRecords.Owner("http://127.0.0.1:8787", UUID.randomUUID().toString());
      journal = mysql ? database.journal(owner) : new H2SessionJournal(path);
    }

    PersistentSessionJournal anotherSession() throws Exception {
      return mysql
          ? new MySqlSessionJournal(database.connect(), owner, "b".repeat(64))
          : new H2SessionJournal(path);
    }

    void reopen() throws Exception {
      journal.close();
      journal = mysql ? database.journal(owner) : new H2SessionJournal(path);
    }

    @Override
    public void close() {
      journal.close();
    }
  }
}
