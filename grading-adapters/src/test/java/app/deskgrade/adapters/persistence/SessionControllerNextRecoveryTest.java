package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.port.GradingProvider;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControllerNextRecoveryTest {
  @TempDir Path directory;

  @Test
  void pauseAfterConfirmedSubmissionCanIssueFirstNextWithoutRegradingOrResubmitting()
      throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.CONFIRMED)) {
      fixture.startAndAwaitPause();
      fixture.assertSingleSubmission(0);
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(1);
    }
  }

  @Test
  void pauseAfterNavigationIntentObservesOnlyUntilUserAdvancesThePage() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.NEXT_PREPARED)) {
      fixture.startAndAwaitPause();
      fixture.assertSingleSubmission(0);
      fixture.resumeAndAwait(PauseReason.NEXT_UNCERTAIN);
      fixture.assertSingleSubmission(0);
      fixture.platform.advance();
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(0);
    }
  }

  @Test
  void pauseAfterNextWasSentNeverResendsItEvenWhenPageHasNotChanged() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.NEXT_SENT)) {
      fixture.platform.advanceOnNext = false;
      fixture.startAndAwaitPause();
      fixture.assertSingleSubmission(1);
      fixture.resumeAndAwait(PauseReason.NEXT_UNCERTAIN);
      fixture.assertSingleSubmission(1);
      fixture.platform.advance();
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(1);
    }
  }

  @Test
  void missingPriorAcknowledgmentCannotAuthorizeNextAfterPause() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.CONFIRMED)) {
      fixture.startAndAwaitPause();
      fixture.platform.hideAcknowledgment = true;
      fixture.resumeAndAwait(PauseReason.NEXT_UNCERTAIN);
      fixture.assertSingleSubmission(0);
      assertEquals(1, fixture.modelCalls.get());
    }
  }

  @Test
  void changedConfirmedCountCannotAuthorizeNextAfterPause() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.CONFIRMED)) {
      fixture.startAndAwaitPause();
      fixture.platform.committed++;
      fixture.resumeAndAwait(PauseReason.NEXT_UNCERTAIN);
      fixture.assertSingleSubmission(0);
      assertEquals(1, fixture.modelCalls.get());
    }
  }

  @Test
  void alreadyReadyNextAnswerIsReboundWithoutSendingNext() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.CONFIRMED)) {
      fixture.startAndAwaitPause();
      fixture.platform.advance();
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(0);
    }
  }

  @Test
  void resumedPendingNextStillRequiresGlobalStopHotkeyPrecheck() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.CONFIRMED)) {
      fixture.startAndAwaitPause();
      fixture.hotkeyAvailable.set(false);
      fixture.resumeAndAwait(PauseReason.HOTKEY_UNAVAILABLE);
      fixture.assertSingleSubmission(0);
      fixture.hotkeyAvailable.set(true);
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(1);
    }
  }

  @Test
  void navigationExceptionStopsWithoutASecondModelCallOrRepeatedNext() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.NONE)) {
      fixture.platform.throwOnNext = true;
      fixture.controller.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
      awaitState(fixture.controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, fixture.controller.status().reason());
      fixture.assertSingleSubmission(1);
      assertEquals(1, fixture.modelCalls.get());
    }
  }

  @Test
  void acknowledgedUnknownSubmissionResumesItsPendingNextWithoutResubmitting() throws Exception {
    try (Fixture fixture = new Fixture(PauseAt.NONE)) {
      fixture.platform.hideAcknowledgment = true;
      fixture.controller.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
      awaitState(fixture.controller, SessionState.SUBMISSION_UNKNOWN);
      assertEquals(1, fixture.platform.ledger.get(1).size());
      fixture.platform.hideAcknowledgment = false;
      fixture.controller.reconcileUnknown().toCompletableFuture().get(3, TimeUnit.SECONDS);
      awaitState(fixture.controller, SessionState.PAUSED);
      fixture.assertSingleSubmission(0);
      fixture.resumeToNextAnswer();
      fixture.assertSingleSubmission(1);
    }
  }

  private enum PauseAt {
    NONE,
    CONFIRMED,
    NEXT_PREPARED,
    NEXT_SENT
  }

  private final class Fixture implements AutoCloseable {
    final SeparateNextPlatform platform = new SeparateNextPlatform();
    final H2SessionJournal journal;
    final SessionController controller;
    final AtomicBoolean hotkeyAvailable = new AtomicBoolean(true);
    final AtomicInteger modelCalls = new AtomicInteger();
    final CountDownLatch nextAnswerGraded = new CountDownLatch(1);

    Fixture(PauseAt pauseAt) {
      journal = new H2SessionJournal(directory.resolve(pauseAt.name()));
      AtomicReference<SessionController> active = new AtomicReference<>();
      AtomicBoolean paused = new AtomicBoolean();
      Runnable pauseOnce =
          () -> {
            if (paused.compareAndSet(false, true)) active.get().pause(PauseReason.USER_PAUSED);
          };
      SessionJournal observed =
          (SessionJournal)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, args) -> {
                    Object result = method.invoke(journal, args);
                    if (pauseAt == PauseAt.CONFIRMED
                        && method.getName().equals("confirmSubmission")) pauseOnce.run();
                    return result;
                  });
      if (pauseAt == PauseAt.NEXT_SENT) platform.afterNext = pauseOnce;
      GradingProvider provider =
          (request, token) -> {
            if (modelCalls.incrementAndGet() == 1) return immediate().grade(request, token);
            nextAnswerGraded.countDown();
            return new CompletableFuture<GradingResponse>();
          };
      SessionSettings base = settings(3);
      SessionSettings config =
          new SessionSettings(
              base.question(),
              base.modelProfileId(),
              1,
              1,
              base.configurationJson(),
              3,
              10,
              0,
              null,
              Duration.ofMillis(5),
              Duration.ofMillis(500));
      controller =
          new SessionController(
              platform,
              observed,
              (run, attempt, capture) -> "synthetic.png",
              provider,
              result -> new BigDecimal("4"),
              config,
              Clock.systemUTC(),
              hotkeyAvailable::get,
              status -> {
                if (pauseAt == PauseAt.NEXT_PREPARED && status.state() == SessionState.NAVIGATING)
                  pauseOnce.run();
              });
      active.set(controller);
    }

    void startAndAwaitPause() throws Exception {
      controller.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
      awaitState(controller, SessionState.PAUSED);
    }

    void resumeAndAwait(PauseReason reason) throws Exception {
      controller.resume().toCompletableFuture().get(3, TimeUnit.SECONDS);
      awaitState(controller, SessionState.PAUSED);
      assertEquals(reason, controller.status().reason());
    }

    void resumeToNextAnswer() throws Exception {
      controller.resume().toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertTrue(nextAnswerGraded.await(3, TimeUnit.SECONDS), controller.status().toString());
      assertEquals(2, modelCalls.get());
      var attempts = journal.attempts(controller.status().runId());
      assertEquals(2, attempts.size());
      assertTrue(attempts.getFirst().confirmed());
      assertEquals("1", attempts.getFirst().answerId());
      assertEquals("2", attempts.getLast().answerId());
    }

    void assertSingleSubmission(int expectedNextCalls) {
      assertEquals(1, platform.ledger.size());
      assertEquals(List.of(new BigDecimal("4")), platform.ledger.get(1));
      assertEquals(1, controller.status().confirmedSubmissions());
      assertEquals(expectedNextCalls, platform.nextCalls.get());
      assertEquals(1, platform.inputs.get());
      assertEquals(
          1,
          journal.attempts(controller.status().runId()).stream()
              .filter(SessionJournal.AttemptRecord::confirmed)
              .count());
    }

    @Override
    public void close() {
      controller.close();
      journal.close();
    }
  }

  private static final class SeparateNextPlatform extends FakePlatform {
    boolean submitted;
    boolean advanceOnNext = true;
    boolean throwOnNext;
    boolean hideAcknowledgment;
    final AtomicInteger nextCalls = new AtomicInteger();
    Runnable afterNext = () -> {};

    SeparateNextPlatform() {
      super(3);
    }

    @Override
    public View observe() {
      return new View(
          Integer.toString(answer),
          "1",
          revision,
          submitted ? Page.SUBMITTED : Page.READY,
          hideAcknowledgment || ack == 0 ? null : Integer.toString(ack),
          committed,
          score,
          true);
    }

    @Override
    public void submit(View expected, ActionPermission permission) {
      permission.verify();
      ledger.computeIfAbsent(answer, ignored -> new ArrayList<>()).add(score);
      ack = answer;
      committed++;
      revision++;
      submitted = true;
    }

    @Override
    public void next(View expected, ActionPermission permission) {
      permission.verify();
      nextCalls.incrementAndGet();
      if (throwOnNext) throw new IllegalStateException("Synthetic navigation failure");
      afterNext.run();
      if (advanceOnNext) advance();
    }

    void advance() {
      answer++;
      revision++;
      submitted = false;
      score = null;
    }
  }

  private static void awaitState(SessionController controller, SessionState expected)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (controller.status().state() != expected && System.nanoTime() < until) Thread.sleep(5);
    assertEquals(expected, controller.status().state(), controller.status().toString());
  }
}
