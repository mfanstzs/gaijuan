package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.SafeInputException;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.port.GradingProvider;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControllerTest {
  @TempDir Path directory;
  static final GradeResult RESULT =
      new GradeResult(
          GradeResult.Status.SCORED,
          GradeResult.Readability.CLEAR,
          "synthetic answer",
          List.of(
              new GradeResult.CriterionResult("p", new BigDecimal("4"), "synthetic", "fixture")),
          List.of());

  static SessionSettings settings(int count) {
    QuestionDefinition question =
        new QuestionDefinition(
            "1",
            1,
            "Synthetic question",
            "Synthetic answer",
            new BigDecimal("4"),
            List.of(
                new ScoringCriterion(
                    "p", "Synthetic criterion", List.of(BigDecimal.ZERO, new BigDecimal("4")))));
    return new SessionSettings(
        question,
        "mock-A",
        1,
        1,
        "{\"source\":\"MOCK\",\"revision\":1}",
        count,
        1000,
        0,
        null,
        Duration.ofMillis(1),
        Duration.ofMillis(100));
  }

  static GradingProvider immediate() {
    return (request, token) ->
        CompletableFuture.completedFuture(
            new GradingResponse(
                request.requestId(), GradingResponse.Source.MOCK, RESULT, Instant.now()));
  }

  SessionController controller(
      FakePlatform platform, SessionJournal journal, GradingProvider provider, int count) {
    return new SessionController(
        platform,
        journal,
        (run, attempt, capture) -> "synthetic.png",
        provider,
        r -> new BigDecimal("4"),
        settings(count),
        Clock.systemUTC(),
        () -> true,
        s -> {});
  }

  @Test
  void fiveHundredIdenticalPicturesHaveDistinctAnswersAndOneSubmissionEach() throws Exception {
    FakePlatform platform = new FakePlatform(500);
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 500)) {
      controller.start();
      // This durability stress case performs 500 fully committed H2 workflows. It consistently
      // completes in isolation but can exceed the normal 45-second functional-test guard when the
      // complete SessionController suite shares the same build; keep all ordinary tests at 45s.
      await(controller, SessionState.COMPLETED, Duration.ofSeconds(60));
      assertEquals(500, platform.ledger.size());
      for (int answer = 1; answer <= 500; answer++) {
        assertEquals(List.of(new BigDecimal("4")), platform.ledger.get(answer));
      }
      assertEquals(500, controller.status().confirmedSubmissions());
      assertEquals(500, journal.runs().getFirst().modelCalls());
      assertEquals(
          500,
          journal.attempts(controller.status().runId()).stream()
              .filter(SessionJournal.AttemptRecord::confirmed)
              .count());
    }
  }

  @Test
  void readbackMismatchNeverSubmits() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    platform.wrongReadback = true;
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.SCORE_MISMATCH, controller.status().reason());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
    }
  }

  @Test
  void repeatedNativePermissionChecksProbeStorageOnlyOncePerLogicalAction() throws Exception {
    AtomicInteger storageProbes = new AtomicInteger();
    AtomicInteger targetChecks = new AtomicInteger();
    FakePlatform platform =
        new FakePlatform(1) {
          private void nativeChecks(View expected, ActionPermission permission) {
            int before = storageProbes.get();
            for (int batch = 0; batch < 20; batch++) {
              permission.verifyForTarget(
                  () -> {
                    targetChecks.incrementAndGet();
                    verify(expected);
                  });
            }
            assertEquals(before, storageProbes.get(), "Native checks must not repeat HTTP probes");
          }

          @Override
          public void fillScore(View expected, BigDecimal total, ActionPermission permission) {
            nativeChecks(expected, permission);
            super.fillScore(expected, total, permission);
          }

          @Override
          public BigDecimal readScore(View expected, ActionPermission permission) {
            nativeChecks(expected, permission);
            return super.readScore(expected, permission);
          }

          @Override
          public void submit(View expected, ActionPermission permission) {
            nativeChecks(expected, permission);
            super.submit(expected, permission);
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("storage-probes"))) {
      SessionJournal counted =
          (SessionJournal)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("assertWritable")) storageProbes.incrementAndGet();
                    return method.invoke(journal, args);
                  });
      try (SessionController controller = controller(platform, counted, immediate(), 1)) {
        controller.start();
        await(controller, SessionState.COMPLETED);
        // Startup + precheck + one each for fill, readback, submit; not the 60 native batches.
        assertEquals(5, storageProbes.get());
        assertEquals(60, targetChecks.get());
        assertEquals(1, platform.inputs.get());
        assertEquals(1, platform.ledger.get(1).size());
        assertTrue(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
        assertTrue(journal.attempts(controller.status().runId()).getFirst().confirmed());
      }
    }
  }

  @Test
  void optimizedTargetGuardStillStopsWhenHotkeyIsLostDuringTheGuard() throws Exception {
    AtomicBoolean hotkeyAvailable = new AtomicBoolean(true);
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public void fillScore(View expected, BigDecimal total, ActionPermission permission) {
            permission.verifyForTarget(
                () -> {
                  verify(expected);
                  hotkeyAvailable.set(false);
                });
            super.fillScore(expected, total, permission);
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("hotkey-in-guard"));
        SessionController controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                immediate(),
                r -> new BigDecimal("4"),
                settings(1),
                Clock.systemUTC(),
                hotkeyAvailable::get,
                status -> {})) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(SessionState.FILLING, controller.status().failureDiagnostic().phase());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
    }
  }

  @Test
  void anotherQuestionsCompletionCannotFinishThisRun() throws Exception {
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public View observe() {
            return new View(null, "other-question", 1, Page.COMPLETED, null, 0, null, false);
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.QUESTION_CHANGED, controller.status().reason());
      assertEquals(0, controller.status().modelCalls());
    }
  }

  @Test
  void changedQuestionAtAcknowledgementCannotConfirmOrCompleteOriginalAnswer() throws Exception {
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public View observe() {
            View view = super.observe();
            return new View(
                view.answerId(),
                view.confirmedCount() > 0 ? "other-question" : "1",
                view.pageRevision(),
                view.page(),
                view.acknowledgedAnswerId(),
                view.confirmedCount(),
                view.displayedScore(),
                view.separateNext());
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.SUBMISSION_UNKNOWN);
      controller.reconcileUnknown().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.SUBMISSION_UNKNOWN, controller.status().state());
      assertEquals(0, controller.status().confirmedSubmissions());
      assertEquals(1, platform.ledger.size());
    }
  }

  @Test
  void waitsForVerifiedPaintAfterInputWithoutRepeatingInput() throws Exception {
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public void fillScore(View view, BigDecimal total, ActionPermission permission) {
            permission.verify();
            inputs.incrementAndGet();
            CompletableFuture.delayedExecutor(30, TimeUnit.MILLISECONDS)
                .execute(() -> score = total);
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      assertEquals(1, platform.inputs.get());
      assertEquals(1, platform.ledger.get(1).size());
    }
  }

  @Test
  void closingCompletedRunPreservesItsTerminalState() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"))) {
      SessionController controller = controller(platform, journal, immediate(), 1);
      controller.start();
      await(controller, SessionState.COMPLETED);
      controller.close();
      assertEquals(SessionState.COMPLETED, journal.runs().getFirst().state());
    }
  }

  @Test
  void modelBMayFinishBeforeRevokedModelAWithoutBeingOverwritten() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    CompletableFuture<GradingResponse> delayedA = new CompletableFuture<>();
    UUID[] callA = new UUID[1];
    CountDownLatch bCalled = new CountDownLatch(1);
    GradingProvider modelA =
        (request, token) -> {
          callA[0] = request.requestId();
          return delayedA;
        };
    GradingProvider modelB =
        (request, token) -> {
          bCalled.countDown();
          return immediate().grade(request, token);
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, modelA, 1)) {
      controller.start();
      await(controller, SessionState.GRADING);
      SessionSettings old = settings(1);
      SessionSettings next =
          new SessionSettings(
              old.question(),
              "mock-B",
              2,
              1,
              "{\"source\":\"MOCK\",\"revision\":2}",
              1,
              1000,
              0,
              null,
              old.pollInterval(),
              old.pageTimeout());
      controller
          .changeConfiguration(next, modelB, r -> r.criteria().getFirst().awarded())
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
      controller.resume();
      assertTrue(bCalled.await(2, TimeUnit.SECONDS), "Revoked A must not block a newly chosen B");
      await(controller, SessionState.COMPLETED);
      GradeResult zero =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              "old",
              List.of(new GradeResult.CriterionResult("p", BigDecimal.ZERO, "old", "old result")),
              List.of());
      delayedA.complete(
          new GradingResponse(callA[0], GradingResponse.Source.MOCK, zero, Instant.now()));
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(new BigDecimal("4"), controller.status().lastScore());
      assertEquals(List.of(new BigDecimal("4")), platform.ledger.get(1));
      assertEquals(2, journal.runs().getFirst().modelCalls());
    }
  }

  @Test
  void pauseOrStopAfterCompletionCannotReopenATerminalRun() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      controller.pause(PauseReason.CONFIG_CHANGED);
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.COMPLETED, controller.status().state());
      controller.stop();
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.COMPLETED, journal.runs().getFirst().state());
      assertEquals(1, platform.ledger.size());
    }
  }

  @Test
  void unableResultRetainsEvidenceAndNoScoreOrInput() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    GradeResult unable =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            "",
            List.of(new GradeResult.CriterionResult("p", null, "", "Unreadable")),
            List.of("Synthetic unreadable evidence"));
    GradingProvider provider =
        (request, token) ->
            CompletableFuture.completedFuture(
                new GradingResponse(
                    request.requestId(), GradingResponse.Source.MOCK, unable, Instant.now()));
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                provider,
                r ->
                    app.deskgrade.core.application.grading.GradeValidator.validate(
                            settings(1).question(), r)
                        .totalScore(),
                settings(1),
                Clock.systemUTC(),
                () -> true,
                s -> {})) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.FALLBACK_UNAVAILABLE, controller.status().reason());
      var attempt = journal.attempts(controller.status().runId()).getFirst();
      assertNull(attempt.score());
      assertNotNull(attempt.resultJson());
      assertTrue(attempt.resultJson().contains("Synthetic unreadable evidence"));
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void clearCompleteBlankFillsExactZeroOnceAndSubmitsOnce() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    SessionSettings configured = settings(1);
    AtomicInteger calls = new AtomicInteger();
    GradeResult blank =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "",
            List.of(new GradeResult.CriterionResult("p", BigDecimal.ZERO, null, "未作答")),
            List.of());
    GradingProvider provider =
        (request, token) -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(
              new GradingResponse(
                  request.requestId(), GradingResponse.Source.MOCK, blank, Instant.now()));
        };

    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("blank-zero"));
        SessionController controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                provider,
                result ->
                    app.deskgrade.core.application.grading.GradeValidator.validate(
                            configured.question(), result)
                        .totalScore(),
                configured,
                Clock.systemUTC(),
                () -> true,
                ignored -> {})) {
      controller.start();
      await(controller, SessionState.COMPLETED);

      assertEquals(1, calls.get());
      assertEquals(1, platform.inputs.get());
      assertEquals(1, platform.committed);
      assertEquals(List.of(BigDecimal.ZERO), platform.ledger.get(1));
      assertEquals(BigDecimal.ZERO, controller.status().lastScore());
      assertEquals(
          0,
          BigDecimal.ZERO.compareTo(
              journal.attempts(controller.status().runId()).getFirst().score()));
    }
  }

  @Test
  void lostReceiptIsUnknownAndResumeDoesNotResubmit() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    platform.loseAck = true;
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.SUBMISSION_UNKNOWN);
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.SUBMISSION_UNKNOWN, controller.status().state());
      assertEquals(1, platform.ledger.get(1).size());
      assertEquals(0, controller.status().confirmedSubmissions());
      assertTrue(journal.hasUnknownSubmission(platform.targetIdentity()));
    }
  }

  @Test
  void providerFailuresStopImmediatelyAndCannotBeResumedIntoAnotherPaidCall() throws Exception {
    verifyProviderFailStop(app.deskgrade.core.domain.model.ModelCallException.Kind.RATE_LIMIT);
    verifyProviderFailStop(app.deskgrade.core.domain.model.ModelCallException.Kind.NETWORK);
    verifyProviderFailStop(app.deskgrade.core.domain.model.ModelCallException.Kind.TIMEOUT);
  }

  @Test
  void authenticationFailureDoesNotRetryOrSwitchProvider() throws Exception {
    verifyProviderFailStop(app.deskgrade.core.domain.model.ModelCallException.Kind.AUTHENTICATION);
  }

  private void verifyProviderFailStop(app.deskgrade.core.domain.model.ModelCallException.Kind kind)
      throws Exception {
    FakePlatform platform = new FakePlatform(1);
    AtomicInteger invocations = new AtomicInteger();
    GradingProvider sameProvider =
        (request, token) ->
            invocations.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(
                    new app.deskgrade.core.domain.model.ModelCallException(kind, null))
                : immediate().grade(request, token);
    var old = settings(1);
    var failStopSettings =
        new SessionSettings(
            old.question(),
            old.modelProfileId(),
            old.modelRevision(),
            old.platformRevision(),
            old.configurationJson(),
            1,
            3,
            1,
            null,
            old.pollInterval(),
            old.pageTimeout());
    try (H2SessionJournal journal =
            new H2SessionJournal(
                directory.resolve("fail-stop-" + kind.name().toLowerCase(java.util.Locale.ROOT)));
        SessionController controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                sameProvider,
                r -> new BigDecimal("4"),
                failStopSettings,
                Clock.systemUTC(),
                () -> true,
                s -> {})) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.MODEL_FAILED, controller.status().reason());
      assertEquals(SessionState.GRADING, controller.status().failureDiagnostic().phase());
      assertEquals(
          List.of("MODEL_" + kind.name()), controller.status().failureDiagnostic().codes());
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(1, invocations.get());
      assertEquals(1, journal.runs().getFirst().modelCalls());
      assertTrue(platform.ledger.isEmpty());
      var attempt = journal.attempts(controller.status().runId()).getFirst();
      var calls =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(journal.attemptAuditJson(attempt.attemptId()))
              .path("modelCalls");
      assertEquals(1, calls.size());
      int failures = 0;
      for (var call : calls) {
        assertEquals(old.modelProfileId(), call.path("profileId").asText());
        if (!call.path("errorCode").isNull()) {
          assertEquals("MODEL_" + kind.name(), call.path("errorCode").asText());
          failures++;
        }
      }
      // Windows wall-clock timestamps can tie; request UUIDs and contents identify calls.
      assertEquals(1, failures);
    }
  }

  @Test
  void gatewayDispatchUnknownRemainsSpecificInStopStatusAndPersistedModelCall() throws Exception {
    var platform = new FakePlatform(1);
    GradingProvider failed =
        (request, token) ->
            CompletableFuture.failedFuture(
                new CompletionException(
                    new app.deskgrade.adapters.commercial.GatewayException(
                        app.deskgrade.adapters.commercial.GatewayException.Code.DISPATCH_UNKNOWN)));
    try (var journal = new H2SessionJournal(directory.resolve("gateway-unknown"));
        var controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                failed,
                result -> new BigDecimal("4"),
                settings(1),
                Clock.systemUTC(),
                () -> true,
                ignored -> {})) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.MODEL_FAILED, controller.status().reason());
      assertEquals(
          List.of("GATEWAY_DISPATCH_UNKNOWN"), controller.status().failureDiagnostic().codes());
      assertEquals(SessionState.GRADING, controller.status().failureDiagnostic().phase());
      String audit =
          journal.attemptAuditJson(
              journal.attempts(controller.status().runId()).getFirst().attemptId());
      assertTrue(audit.contains("GATEWAY_DISPATCH_UNKNOWN"));
      assertFalse(audit.contains("MODEL_HTTP_ERROR"));
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void pauseWhileGradingRevokesLateModelResponse() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    CompletableFuture<GradingResponse> delayed = new CompletableFuture<>();
    UUID[] requestId = new UUID[1];
    CountDownLatch requestStarted = new CountDownLatch(1);
    GradingProvider provider =
        (request, token) -> {
          requestId[0] = request.requestId();
          requestStarted.countDown();
          return delayed;
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, provider, 1)) {
      controller.start();
      await(controller, SessionState.GRADING);
      assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
      long before = controller.actionEpoch();
      controller.pause(PauseReason.USER_PAUSED);
      assertTrue(controller.actionEpoch() > before);
      await(controller, SessionState.PAUSED);
      delayed.complete(
          new GradingResponse(requestId[0], GradingResponse.Source.MOCK, RESULT, Instant.now()));
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () ->
              controller
                  .changeConfiguration(settings(1), immediate(), r -> BigDecimal.ONE)
                  .toCompletableFuture()
                  .get(2, TimeUnit.SECONDS));
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void pauseAfterFillClosesSubmitGate() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      platform.afterFill = () -> controller.pause(PauseReason.USER_PAUSED);
      controller.start();
      await(controller, SessionState.PAUSED);
      assertEquals(1, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void changedAnswerPixelsWhileModelIsRunningStopBeforeAnyInput() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    CompletableFuture<GradingResponse> delayed = new CompletableFuture<>();
    UUID[] requestId = new UUID[1];
    CountDownLatch requestStarted = new CountDownLatch(1);
    GradingProvider provider =
        (request, token) -> {
          requestId[0] = request.requestId();
          requestStarted.countDown();
          return delayed;
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("pixel-swap"));
        SessionController controller = controller(platform, journal, provider, 1)) {
      controller.start();
      await(controller, SessionState.GRADING);
      assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
      platform.answerPixels = new byte[] {9, 8, 7};
      delayed.complete(
          new GradingResponse(requestId[0], GradingResponse.Source.MOCK, RESULT, Instant.now()));
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.TARGET_CHANGED, controller.status().reason());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void changedVisibleIdentityWhileModelIsRunningStopsEvenWithIdenticalAnswerPixels()
      throws Exception {
    FakePlatform platform = new FakePlatform(1);
    GradingProvider provider =
        (request, token) -> {
          platform.identityEpoch++;
          return immediate().grade(request, token);
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("identity-swap"));
        SessionController controller = controller(platform, journal, provider, 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.TARGET_CHANGED, controller.status().reason());
      assertNull(controller.status().failureDiagnostic());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void preInputCaptureFailureRetainsPhaseAndOnlySafeCodesWithoutAnyInput() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public void verify(View expected) {
            if (calls.get() == 1)
              throw SafeInputException.stage(
                  "CAPTURE_RECHECK", SafeInputException.code("TARGET_WINDOW_UNAVAILABLE"));
            super.verify(expected);
          }
        };
    GradingProvider provider =
        (request, token) -> {
          calls.incrementAndGet();
          return immediate().grade(request, token);
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("pre-input-failure"));
        SessionController controller = controller(platform, journal, provider, 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(SessionState.BEFORE_INPUT, controller.status().failureDiagnostic().phase());
      assertEquals(
          List.of("CAPTURE_RECHECK", "TARGET_WINDOW_UNAVAILABLE"),
          controller.status().failureDiagnostic().codes());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
      // A fresh, explicitly started run must not keep an old failure's diagnostic.
      calls.incrementAndGet();
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      await(controller, SessionState.COMPLETED);
      assertNull(controller.status().failureDiagnostic());
      assertEquals(1, platform.inputs.get());
      assertEquals(1, platform.ledger.size());
    }
  }

  @Test
  void fillFailureRetainsSafeDiagnosticAndNeverSubmits() throws Exception {
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public void fillScore(View expected, BigDecimal total, ActionPermission permission) {
            permission.verify();
            throw SafeInputException.stage(
                "SCORE_INPUT", SafeInputException.code("FOCUS_REJECTED"));
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("fill-failure"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(SessionState.FILLING, controller.status().failureDiagnostic().phase());
      assertEquals(
          List.of("SCORE_INPUT", "FOCUS_REJECTED"),
          controller.status().failureDiagnostic().codes());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
    }
  }

  @Test
  void diagnosticsNeverCopyExceptionMessagesAndBoundCauseTraversal() {
    String privateText = "private-student-text api-key file-path model-response";
    FailureDiagnostic unknown =
        FailureDiagnostic.from(SessionState.BEFORE_INPUT, new IllegalStateException(privateText));
    assertEquals(List.of("LOCAL_OPERATION_FAILED"), unknown.codes());
    assertFalse(unknown.toString().contains(privateText));
    RuntimeException failure = new IllegalStateException(privateText);
    for (int level = 0; level < 20; level++)
      failure = SafeInputException.stage("INPUT_STAGE_" + level, failure);
    FailureDiagnostic bounded = FailureDiagnostic.from(SessionState.FILLING, failure);
    assertEquals(8, bounded.codes().size());
    assertTrue(bounded.codes().stream().allMatch(code -> code.matches("INPUT_STAGE_[0-9]+")));
    assertFalse(bounded.toString().contains(privateText));
    assertNull(
        new SessionController.Status(null, SessionState.IDLE, PauseReason.NONE, 0, 0, null)
            .failureDiagnostic());
  }

  @Test
  void answerChangingAfterFillIsDetectedByTheSecondRecaptureBeforeSubmit() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    platform.afterFill = () -> platform.answerPixels = new byte[] {6, 6, 6};
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("post-fill-swap"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.TARGET_CHANGED, controller.status().reason());
      assertEquals(1, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
    }
  }

  @Test
  void readbackExceptionStopsAndNeverSubmits() throws Exception {
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public BigDecimal readScore(View expected, ActionPermission permission) {
            throw new IllegalStateException("Synthetic readback failure");
          }
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("readback-failure"));
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(SessionState.VERIFYING_SCORE, controller.status().failureDiagnostic().phase());
      assertEquals(
          List.of("LOCAL_OPERATION_FAILED"), controller.status().failureDiagnostic().codes());
      assertTrue(platform.ledger.isEmpty());
      assertFalse(journal.attempts(controller.status().runId()).getFirst().submissionPrepared());
    }
  }

  @Test
  void statusListenerFailureRevokesTheGateBeforeCaptureModelOrInput() throws Exception {
    AtomicInteger captures = new AtomicInteger();
    FakePlatform platform =
        new FakePlatform(1) {
          @Override
          public Capture capture(View expected) {
            captures.incrementAndGet();
            return super.capture(expected);
          }
        };
    AtomicInteger modelCalls = new AtomicInteger();
    GradingProvider provider =
        (request, token) -> {
          modelCalls.incrementAndGet();
          return immediate().grade(request, token);
        };
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("listener-failure"));
        SessionController controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                provider,
                r -> new BigDecimal("4"),
                settings(1),
                Clock.systemUTC(),
                () -> true,
                status -> {
                  if (status.state() == SessionState.PRECHECK)
                    throw new IllegalStateException("Synthetic UI listener failure");
                })) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(0, captures.get());
      assertEquals(0, modelCalls.get());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(0, modelCalls.get());
    }
  }

  @Test
  void pauseDuringQueuedStartMustNotReopenTheGate() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (H2SessionJournal journal = new H2SessionJournal(directory.resolve("runs"))) {
      SessionJournal delayed =
          (SessionJournal)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("beginRun")) {
                      entered.countDown();
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return method.invoke(journal, args);
                  });
      try (SessionController controller = controller(platform, delayed, immediate(), 1)) {
        controller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        controller.pause(PauseReason.USER_PAUSED);
        long revoked = controller.actionEpoch();
        release.countDown();
        await(controller, SessionState.PAUSED);
        assertEquals(
            revoked,
            controller.actionEpoch(),
            "Queued start must not open or re-revoke a user's stopped epoch");
        assertEquals(0, platform.inputs.get());
      }
    }
  }

  @Test
  void restartAfterIntentDoesNotEnableSubmission() throws Exception {
    FakePlatform platform = new FakePlatform(1);
    platform.loseAck = true;
    Path db = directory.resolve("runs");
    try (H2SessionJournal journal = new H2SessionJournal(db);
        SessionController controller = controller(platform, journal, immediate(), 1)) {
      controller.start();
      await(controller, SessionState.SUBMISSION_UNKNOWN);
    }
    try (H2SessionJournal recovered = new H2SessionJournal(db)) {
      assertEquals(
          SessionState.SUBMISSION_UNKNOWN,
          recovered.recoverInterrupted(Instant.now()).getFirst().state());
      try (SessionController controller = controller(platform, recovered, immediate(), 1)) {
        controller.start();
        await(controller, SessionState.SUBMISSION_UNKNOWN);
        assertEquals(1, platform.ledger.get(1).size());
      }
    }
  }

  static void await(SessionController controller, SessionState expected) throws Exception {
    await(controller, expected, Duration.ofSeconds(45));
  }

  private static void await(SessionController controller, SessionState expected, Duration timeout)
      throws Exception {
    long until = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < until) {
      SessionController.Status observed = controller.status();
      if (observed.state() == expected) return;
      if (observed.state() == SessionState.FAILED) {
        fail("Controller failed: " + observed);
      }
      if (Set.of(
              SessionState.PAUSED,
              SessionState.SUBMISSION_UNKNOWN,
              SessionState.COMPLETED,
              SessionState.STOPPED)
          .contains(observed.state())) {
        fail("Unexpected terminal or paused state: " + observed);
      }
      Thread.sleep(5);
    }
    assertEquals(expected, controller.status().state(), controller.status().toString());
  }

  static class FakePlatform implements SessionPlatform {
    volatile int answer = 1;
    final int count;
    int committed;
    int ack;
    volatile long revision = 1;
    volatile int identityEpoch;
    volatile byte[] answerPixels = new byte[] {1, 2, 3};
    boolean wrongReadback;
    boolean loseAck;
    volatile BigDecimal score;
    AtomicInteger inputs = new AtomicInteger();
    Runnable afterFill = () -> {};
    Map<Integer, List<BigDecimal>> ledger = new ConcurrentHashMap<>();

    FakePlatform(int count) {
      this.count = count;
    }

    @Override
    public String targetIdentity() {
      return "synthetic-window";
    }

    @Override
    public boolean supportsUnattended() {
      return true;
    }

    @Override
    public Duration readinessStability() {
      return Duration.ZERO;
    }

    @Override
    public View observe() {
      return new View(
          answer > count ? null : Integer.toString(answer),
          "1",
          revision,
          answer > count ? Page.COMPLETED : Page.READY,
          ack == 0 ? null : Integer.toString(ack),
          committed,
          score == null ? null : wrongReadback ? BigDecimal.ZERO : score,
          false);
    }

    @Override
    public Capture capture(View expected) {
      verify(expected);
      return captured(answerPixels);
    }

    @Override
    public IdentityCapture captureIdentity(View expected) {
      verify(expected);
      byte[] pixels =
          ("identity:" + answer + ":" + revision + ":" + identityEpoch)
              .getBytes(StandardCharsets.UTF_8);
      return new IdentityCapture(
          targetIdentity(),
          expected.answerId(),
          expected.questionId(),
          expected.pageRevision(),
          "synthetic-identity-region",
          captured(pixels));
    }

    @Override
    public void verify(View expected) {
      if (!Objects.equals(expected.answerId(), observe().answerId())
          || expected.pageRevision() != revision)
        throw new IllegalStateException("Synthetic page changed");
    }

    @Override
    public void fillScore(View expected, BigDecimal total, ActionPermission permission) {
      permission.verify();
      inputs.incrementAndGet();
      score = total;
      afterFill.run();
    }

    @Override
    public void submit(View expected, ActionPermission permission) {
      permission.verify();
      ledger.computeIfAbsent(answer, k -> new ArrayList<>()).add(score);
      if (!loseAck) {
        ack = answer;
        answer++;
        committed++;
        revision++;
        score = null;
      }
    }

    @Override
    public void next(View view, ActionPermission permission) {
      fail("Separate next not configured");
    }

    private static Capture captured(byte[] pixels) {
      try {
        return new Capture(
            pixels,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels)),
            Instant.now());
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }
  }
}
