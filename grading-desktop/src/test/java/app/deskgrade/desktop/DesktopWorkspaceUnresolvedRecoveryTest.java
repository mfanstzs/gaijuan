package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.PersistentSessionJournal;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.desktop.*;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic recovery races with a synthetic journal; no database, network or desktop input. */
class DesktopWorkspaceUnresolvedRecoveryTest {
  private static final String SITE_A_SCOPE = "https://site-a.example";
  private static final String SITE_B_SCOPE = "https://site-b.example";
  private static final String SITE_A_TARGET =
      DesktopWorkspace.manualTargetIdentity(GradingRecords.Owner.localDeveloper(), SITE_A_SCOPE);

  @TempDir Path directory;

  @Test
  void loadsHistoricalRowsByOwnerAndStableWebsiteIdentityAfterCurrentRunBinding() throws Exception {
    try (var fixture = new Fixture()) {
      assertNotNull(fixture.workspace.binding());
      var review = fixture.review();
      assertEquals(GradingRecords.Owner.localDeveloper(), fixture.journal.owner);
      assertEquals(SITE_A_TARGET, fixture.journal.target);
      assertEquals(SITE_A_TARGET, review.targetIdentity());
      assertNotEquals(
          "manual-calibration:" + fixture.workspace.manualCalibration().id(),
          review.targetIdentity());
      assertEquals(List.of(fixture.journal.row), review.submissions());
      assertEquals(0, fixture.journal.decisions.size());
      assertNull(fixture.workspace.status());
      assertEquals(0, fixture.workspace.actionTicket());
    }
  }

  @Test
  void lookupWithoutCurrentRunBindingFailsClosedBeforeReadingHistory() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.clearCurrentBinding();
      var pending = fixture.workspace.unresolvedSubmissions();
      Throwable failure = assertFailure(pending);
      assertInstanceOf(NullPointerException.class, failure);
      assertTrue(failure.getMessage().contains("请先完成本次框选并绑定"));
      assertNull(fixture.journal.owner);
      assertNull(fixture.journal.target);
    }
  }

  @Test
  void accountOrCurrentRunBindingChangesDuringLookupDiscardLateHistory() throws Exception {
    for (boolean accountChange : List.of(true, false)) {
      try (var fixture = new Fixture()) {
        fixture.journal.onList = () -> fixture.changeScope(accountChange);
        var pending = fixture.workspace.unresolvedSubmissions();
        fixture.queue.drain();
        assertCancelled(pending);
        assertTrue(fixture.journal.decisions.isEmpty());
      }
    }
  }

  @Test
  void staleReviewCannotEndAnyBatchAfterAStopOrWebsiteChange() throws Exception {
    for (boolean stopped : List.of(true, false)) {
      try (var fixture = new Fixture()) {
        var review = fixture.review();
        if (stopped) fixture.workspace.stop();
        else fixture.changeScope(false);
        var pending =
            fixture.workspace.abandonUnresolvedRun(review, fixture.journal.row.run().runId());
        fixture.queue.drain();
        assertCancelled(pending);
        assertTrue(fixture.journal.decisions.isEmpty());
        assertFalse((boolean) get(fixture.workspace, "unresolvedRecoveryActive"));
      }
    }
  }

  @Test
  void finishingRevokesAndClosesTheOldControllerBeforeRpcThenClearsBindingOnlyAfterSuccess()
      throws Exception {
    try (var fixture = new Fixture()) {
      var oldController = fixture.installUnknownControllerAndBinding();
      var review = fixture.review();
      long oldAction = fixture.workspace.actionTicket();
      fixture.journal.onAbandon =
          () -> {
            assertTrue((boolean) getUnchecked(oldController, "closed"));
            assertTrue(fixture.workspace.actionTicket() > oldAction);
            assertFalse(fixture.workspace.automaticTarget());
            assertNotNull(
                fixture.workspace.binding(), "Do not clear the display binding before the receipt");
          };
      var pending =
          fixture.workspace.abandonUnresolvedRun(review, fixture.journal.row.run().runId());
      assertTrue((boolean) get(oldController, "closed"), "Closing precedes even the queued RPC");
      assertTrue(fixture.journal.decisions.isEmpty());
      fixture.queue.drain();
      pending.get(2, TimeUnit.SECONDS);
      assertEquals(1, fixture.journal.decisions.size());
      assertNull(fixture.workspace.binding());
      assertNull(fixture.workspace.status());
      assertFalse(fixture.workspace.automaticTarget());
      assertEquals(SessionState.SUBMISSION_UNKNOWN, fixture.journal.row.run().state());
      assertEquals(BigDecimal.ONE, fixture.journal.row.score());
    }
  }

  @Test
  void failedReceiptLeavesUnknownBlockedAndExplicitRefreshReusesTheDecisionId() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.installUnknownControllerAndBinding();
      var review = fixture.review();
      TargetBinding original = fixture.workspace.binding();
      fixture.journal.failNextReceipt = true;
      var first = fixture.workspace.abandonUnresolvedRun(review, fixture.journal.row.run().runId());
      fixture.queue.drain();
      assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
      assertSame(original, fixture.workspace.binding());
      assertEquals(SessionState.SUBMISSION_UNKNOWN, fixture.workspace.status().state());
      assertFalse(fixture.workspace.automaticTarget());
      assertEquals(1, fixture.journal.decisions.size());

      var refreshed = fixture.review();
      var retry =
          fixture.workspace.abandonUnresolvedRun(refreshed, fixture.journal.row.run().runId());
      fixture.queue.drain();
      retry.get(2, TimeUnit.SECONDS);
      assertEquals(2, fixture.journal.decisions.size());
      assertEquals(fixture.journal.decisions.getFirst(), fixture.journal.decisions.getLast());
      assertNull(fixture.workspace.binding());
      assertNull(fixture.workspace.status());
    }
  }

  @Test
  void lateReceiptAfterAccountOrBindingChangeCannotClearTheNewBinding() throws Exception {
    for (boolean accountChange : List.of(true, false)) {
      try (var fixture = new Fixture()) {
        fixture.installUnknownControllerAndBinding();
        var review = fixture.review();
        TargetBinding replacement = binding(999);
        fixture.journal.onAbandon =
            () -> {
              fixture.changeScope(accountChange);
              setUnchecked(fixture.workspace, "binding", replacement);
            };
        var pending =
            fixture.workspace.abandonUnresolvedRun(review, fixture.journal.row.run().runId());
        fixture.queue.drain();
        assertCancelled(pending);
        assertSame(replacement, fixture.workspace.binding());
        assertFalse((boolean) get(fixture.workspace, "unresolvedRecoveryActive"));
      }
    }
  }

  @Test
  void recoverySummaryRetainsRealIdsUnknownResultAndDistinguishesMissingScoreFromZero() {
    var run =
        new SessionJournal.RunRecord(
            UUID.randomUUID(),
            SITE_A_TARGET,
            SessionState.SUBMISSION_UNKNOWN,
            PauseReason.SUBMISSION_UNCERTAIN,
            1,
            0,
            Instant.parse("2026-09-10T12:00:00Z"));
    var row =
        new PersistentSessionJournal.UnresolvedSubmission(
            run, UUID.randomUUID(), "旧答卷-7", "题目-2", BigDecimal.ZERO);
    String text = DeskGradeShell.unresolvedSubmissionDescription(row);
    assertTrue(text.contains("旧答卷-7"));
    assertTrue(text.contains("题目-2"));
    assertTrue(text.contains(run.runId().toString()));
    assertTrue(text.contains("Java 校验分数：0 分"));
    assertTrue(text.contains("提交结果：未知（未确认平台成功）"));
    var missing =
        new PersistentSessionJournal.UnresolvedSubmission(
            run, row.attemptId(), row.answerId(), row.questionId(), null);
    assertTrue(DeskGradeShell.unresolvedSubmissionDescription(missing).contains("未保存有效评分"));
    assertFalse(DeskGradeShell.unresolvedSubmissionDescription(missing).contains("0 分"));
  }

  private final class Fixture implements AutoCloseable {
    final QueueWorker queue = new QueueWorker();
    final DesktopWorkspace workspace = new DesktopWorkspace(directory, queue, true);
    final JournalFixture journal = new JournalFixture();

    Fixture() throws Exception {
      set(workspace, "commercialMode", false);
      set(workspace, "journal", journal.proxy);
      installCurrentBinding(SITE_A_SCOPE, "run-a", 123);
    }

    DesktopWorkspace.UnresolvedSubmissionReview review() throws Exception {
      var pending = workspace.unresolvedSubmissions();
      queue.drain();
      return pending.get(2, TimeUnit.SECONDS);
    }

    void changeScope(boolean accountChange) {
      if (accountChange) ((AtomicLong) getUnchecked(workspace, "accountEpoch")).incrementAndGet();
      else installCurrentBindingUnchecked(SITE_B_SCOPE, "run-b", 999);
    }

    SessionController installUnknownControllerAndBinding() throws Exception {
      var question =
          new QuestionDefinition(
              "q1",
              1,
              "Question",
              "Answer",
              BigDecimal.ONE,
              List.of(
                  new ScoringCriterion("p1", "Rule", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
      var settings =
          new SessionSettings(
              question,
              "synthetic",
              1,
              1,
              "{}",
              1,
              1,
              0,
              null,
              Duration.ofMillis(5),
              Duration.ofSeconds(1));
      var platform =
          (SessionPlatform)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionPlatform.class},
                  (proxy, method, args) -> {
                    throw new AssertionError(
                        "Recovery must never read or input the platform: " + method.getName());
                  });
      var controller =
          new SessionController(
              platform,
              journal.proxy,
              (run, attempt, image) -> {
                throw new AssertionError("No capture during recovery");
              },
              (request, cancellation) -> {
                throw new AssertionError("No model during recovery");
              },
              result -> {
                throw new AssertionError("No grading during recovery");
              },
              settings,
              Clock.systemUTC(),
              () -> false,
              ignored -> {});
      set(
          controller,
          "status",
          new SessionController.Status(
              journal.row.run().runId(),
              SessionState.SUBMISSION_UNKNOWN,
              PauseReason.SUBMISSION_UNCERTAIN,
              1,
              0,
              BigDecimal.ONE));
      set(workspace, "controller", controller);
      set(workspace, "activeSessionAccountEpoch", workspace.accountTicket());
      installCurrentBinding(SITE_A_SCOPE, "run-a", 123);
      assertTrue(workspace.automaticTarget());
      return controller;
    }

    void clearCurrentBinding() throws Exception {
      ((AtomicLong) get(workspace, "bindingGeneration")).incrementAndGet();
      set(workspace, "binding", null);
      set(workspace, "manualBinding", null);
    }

    private void installCurrentBindingUnchecked(
        String targetScope, String calibrationId, long hwnd) {
      try {
        installCurrentBinding(targetScope, calibrationId, hwnd);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    private void installCurrentBinding(String targetScope, String calibrationId, long hwnd)
        throws Exception {
      ManualPlatformCalibration positions = calibration(calibrationId);
      TargetBinding target = binding(hwnd);
      long generation = ((AtomicLong) get(workspace, "bindingGeneration")).incrementAndGet();
      set(workspace, "manualCalibration", positions);
      set(workspace, "binding", target);
      var constructor =
          Class.forName(DesktopWorkspace.class.getName() + "$ManualBinding")
              .getDeclaredConstructor(
                  TargetBinding.class,
                  ManualPlatformCalibration.class,
                  String.class,
                  long.class,
                  long.class,
                  long.class);
      constructor.setAccessible(true);
      set(
          workspace,
          "manualBinding",
          constructor.newInstance(
              target,
              positions,
              DesktopWorkspace.manualTargetIdentity(
                  GradingRecords.Owner.localDeveloper(), targetScope),
              generation,
              workspace.actionTicket(),
              workspace.accountTicket()));
    }

    @Override
    public void close() throws Exception {
      workspace.close();
      queue.drain();
      workspace.awaitClosed().get(2, TimeUnit.SECONDS);
    }
  }

  private static final class JournalFixture {
    final PersistentSessionJournal.UnresolvedSubmission row =
        new PersistentSessionJournal.UnresolvedSubmission(
            new SessionJournal.RunRecord(
                UUID.randomUUID(),
                SITE_A_TARGET,
                SessionState.SUBMISSION_UNKNOWN,
                PauseReason.SUBMISSION_UNCERTAIN,
                1,
                0,
                Instant.parse("2026-09-10T12:00:00Z")),
            UUID.randomUUID(),
            "historic-answer",
            "q1",
            BigDecimal.ONE);
    final List<UUID> decisions = new ArrayList<>();
    GradingRecords.Owner owner;
    String target;
    Runnable onList = () -> {}, onAbandon = () -> {};
    boolean failNextReceipt;
    final PersistentSessionJournal proxy =
        (PersistentSessionJournal)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {PersistentSessionJournal.class},
                (ignored, method, args) -> {
                  return switch (method.getName()) {
                    case "unresolvedSubmissions" -> {
                      owner = (GradingRecords.Owner) args[0];
                      target = (String) args[1];
                      onList.run();
                      yield List.of(row);
                    }
                    case "abandonUnresolvedRun" -> {
                      assertEquals(owner, args[0]);
                      assertEquals(row.run().runId(), args[1]);
                      assertEquals(target, args[2]);
                      decisions.add((UUID) args[3]);
                      onAbandon.run();
                      if (failNextReceipt) {
                        failNextReceipt = false;
                        throw new IllegalStateException("Synthetic receipt unavailable");
                      }
                      yield null;
                    }
                    case "close" -> null;
                    default ->
                        throw new AssertionError(
                            "Unexpected recovery side effect: " + method.getName());
                  };
                });
  }

  private static ManualPlatformCalibration calibration(String id) {
    return new ManualPlatformCalibration(
        id,
        1,
        "本次手工框选",
        new ManualPlatformCalibration.WindowLayout(1000, 700, 96, "SyntheticWindow"),
        null,
        new LogicalRect(20, 100, 500, 300),
        new LogicalRect(600, 500, 100, 40),
        new LogicalRect(750, 500, 100, 40),
        null,
        null,
        ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
        Instant.parse("2026-09-12T12:00:00Z"));
  }

  private static TargetBinding binding(long hwnd) {
    var bounds = new PixelRect(0, 0, 1000, 700);
    var target =
        new WindowSnapshot(
            new WindowIdentity(hwnd, 456, Instant.parse("2026-09-12T10:00:00Z")),
            "Synthetic target",
            "SyntheticWindow",
            bounds,
            bounds,
            new DisplaySnapshot("test", bounds, bounds, 1, 1),
            96,
            true,
            true,
            false,
            false,
            true);
    return new TargetBinding(
        target, new LogicalRect(20, 100, 500, 300), new PixelRect(20, 100, 500, 300));
  }

  private static void assertCancelled(CompletableFuture<?> pending) {
    Throwable failure = assertFailure(pending);
    assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
  }

  private static Throwable assertFailure(CompletableFuture<?> pending) {
    Throwable failure = assertThrows(Exception.class, () -> pending.get(2, TimeUnit.SECONDS));
    while (failure.getCause() != null && failure != failure.getCause())
      failure = failure.getCause();
    return failure;
  }

  private static Object get(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getUnchecked(Object target, String name) {
    try {
      return get(target, name);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void setUnchecked(Object target, String name, Object value) {
    try {
      set(target, name, value);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class QueueWorker extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    void drain() {
      while (!tasks.isEmpty()) tasks.removeFirst().run();
    }

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.copyOf(tasks);
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }
}
