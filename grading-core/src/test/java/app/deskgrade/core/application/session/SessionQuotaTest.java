package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.*;
import app.deskgrade.core.port.GradingProvider;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SessionQuotaTest {
  @Test
  void localDiagnosticCheckpointsAreScopedAndNeverChangeTheSuccessfulActionCount()
      throws Exception {
    var platform = new Platform(1, false);
    try (var controller = controller(platform, new Journal(), settings(1, 20))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.COMPLETED, controller.status().state());
      assertEquals(1, platform.submits.get());
      assertEquals(ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED, platform.checkpoint);
      assertTrue(platform.checkpoints.contains(ScreenChangeDiagnostic.Checkpoint.BEFORE_READBACK));
      assertTrue(
          platform.checkpoints.contains(ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY));
      assertTrue(
          platform.checkpoints.contains(ScreenChangeDiagnostic.Checkpoint.BEFORE_SUBMIT_CAPTURE));
      assertTrue(
          platform.checkpoints.contains(ScreenChangeDiagnostic.Checkpoint.BEFORE_SUBMIT_VERIFY));
      assertEquals(0, platform.checkpoints.size() % 2);
      for (int index = 0; index < platform.checkpoints.size(); index += 2) {
        assertNotEquals(
            ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED, platform.checkpoints.get(index));
        assertEquals(
            ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED, platform.checkpoints.get(index + 1));
      }
    }
  }

  @Test
  void optionalDiagnosticHookRuntimeAndLinkageErrorsCannotStopAnOtherwiseSuccessfulRun()
      throws Exception {
    for (boolean linkage : List.of(false, true)) {
      var platform = new Platform(1, false);
      platform.diagnosticHook =
          ignored -> {
            if (linkage) throw new LinkageError("synthetic metadata unavailable");
            throw new IllegalStateException("synthetic metadata unavailable");
          };
      try (var controller = controller(platform, new Journal(), settings(1, 20))) {
        controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitTerminal(controller);
        assertEquals(SessionState.COMPLETED, controller.status().state());
        assertEquals(1, platform.submits.get());
        assertEquals(ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED, platform.checkpoint);
        assertNull(controller.status().failureDiagnostic());
      }
    }
  }

  @Test
  void failingOperationStillStopsAndKeepsItsCheckpointWhenTheDiagnosticHookAlsoThrows()
      throws Exception {
    var platform = new Platform(1, false);
    platform.diagnosticHook =
        ignored -> {
          throw new IllegalStateException("optional metadata unavailable");
        };
    platform.onVerify =
        () -> {
          if (platform.checkpoint == ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY)
            throw new LocalScreenFailure(platform.checkpoint);
        };
    try (var controller = controller(platform, new Journal(), settings(1, 20))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(0, platform.submits.get());
      assertEquals(ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED, platform.checkpoint);
      assertEquals(SessionState.VERIFYING_SCORE, controller.status().failureDiagnostic().phase());
      assertEquals(
          ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY,
          controller.status().failureDiagnostic().screenChange().checkpoint());
    }
  }

  @Test
  void brokenOptionalMetadataGetterCannotPreventFailStopAndActionRevocation() throws Exception {
    var platform = new Platform(1, false);
    platform.onVerify =
        () -> {
          if (platform.checkpoint == ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY)
            throw new LocalScreenFailure(platform.checkpoint, true);
        };
    try (var controller = controller(platform, new Journal(), settings(1, 20))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(0, platform.submits.get());
      assertEquals(
          List.of("ANSWER_CHANGED_WITHOUT_ACTION"),
          controller.status().failureDiagnostic().codes());
      assertNull(controller.status().failureDiagnostic().screenChange());
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(0, platform.submits.get());
    }
  }

  private static SessionSettings settings(int submissions, int calls) {
    return new SessionSettings(
        new QuestionDefinition(
            "1",
            1,
            "Test",
            "One",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion("p", "One point", List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "fixture",
        1,
        1,
        "{}",
        submissions,
        calls,
        0,
        null,
        Duration.ofMillis(2),
        Duration.ofSeconds(2));
  }

  private static GradingResponse response(UUID id) {
    return new GradingResponse(
        id,
        GradingResponse.Source.MOCK,
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "One",
            List.of(new GradeResult.CriterionResult("p", BigDecimal.ONE, "One", "Correct")),
            List.of()),
        Instant.now());
  }

  private static GradingProvider immediate() {
    return (request, token) -> CompletableFuture.completedFuture(response(request.requestId()));
  }

  private static SessionController controller(
      Platform platform, Journal journal, SessionSettings settings) {
    return new SessionController(
        platform,
        journal.port(),
        (r, a, c) -> "NO_IMAGE_STORED",
        immediate(),
        result -> BigDecimal.ONE,
        settings,
        Clock.systemUTC(),
        () -> true,
        s -> {});
  }

  private static SessionController metered(
      Platform platform,
      Journal journal,
      SessionSettings settings,
      GradingProvider provider,
      AtomicBoolean available) {
    return new SessionController(
        platform,
        journal.port(),
        (r, a, c) -> "NO_IMAGE_STORED",
        provider,
        result -> BigDecimal.ONE,
        settings,
        Clock.systemUTC(),
        () -> true,
        s -> {},
        available::get);
  }

  private static void awaitTerminal(SessionController controller) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!Set.of(
                SessionState.COMPLETED,
                SessionState.STOPPED,
                SessionState.PAUSED,
                SessionState.SUBMISSION_UNKNOWN,
                SessionState.FAILED)
            .contains(controller.status().state())
        && System.nanoTime() < end) Thread.sleep(5);
  }

  @Test
  void zeroCapsAreUnlimitedAndProcessMoreThanThreeAnswers() throws Exception {
    var settings = assertDoesNotThrow(() -> settings(0, 0));
    var platform = new Platform(7, false);
    try (var controller = controller(platform, new Journal(), settings)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.COMPLETED, controller.status().state());
      assertEquals(7, controller.status().modelCalls());
      assertEquals(7, platform.submits.get());
    }
  }

  @Test
  void finiteSubmissionLimitStopsWithoutClickingSeparateNext() throws Exception {
    var platform = new Platform(7, true);
    try (var controller = controller(platform, new Journal(), settings(1, 20))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      assertEquals(1, platform.submits.get());
      assertEquals(0, platform.nexts.get());
    }
  }

  @Test
  void submissionLimitFiveStopsAfterFifthConfirmationWithoutStartingSixth() throws Exception {
    var platform = new Platform(7, true);
    try (var controller = controller(platform, new Journal(), settings(5, 0))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      assertEquals(5, controller.status().confirmedSubmissions());
      assertEquals(5, controller.status().modelCalls());
      assertEquals(5, platform.captures.get());
      assertEquals(
          Set.of("answer-1", "answer-2", "answer-3", "answer-4", "answer-5"),
          platform.capturedAnswerIds);
      assertEquals(5, platform.submits.get());
      assertEquals(4, platform.nexts.get());
    }
  }

  @Test
  void submissionLimitFiveAlsoStopsSubmitAndAdvanceBeforeSixthCapture() throws Exception {
    var platform = new Platform(7, false);
    try (var controller = controller(platform, new Journal(), settings(5, 0))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      assertEquals(5, controller.status().confirmedSubmissions());
      assertEquals(5, controller.status().modelCalls());
      assertEquals(5, platform.captures.get());
      assertEquals(
          Set.of("answer-1", "answer-2", "answer-3", "answer-4", "answer-5"),
          platform.capturedAnswerIds);
      assertEquals(5, platform.submits.get());
      assertEquals(0, platform.nexts.get());
    }
  }

  @Test
  void noCreditsCannotCaptureOrCreateAModelCall() throws Exception {
    var platform = new Platform(7, false);
    var journal = new Journal();
    try (var controller =
        metered(platform, journal, settings(0, 0), immediate(), new AtomicBoolean(false))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.CREDITS_EXHAUSTED, controller.status().reason());
      assertEquals(0, platform.captures.get());
      assertEquals(0, journal.calls.get());
      assertEquals(0, journal.attempts.get());
    }
  }

  @Test
  void creditsDepletedDuringCaptureCannotDispatch() throws Exception {
    var available = new AtomicBoolean(true);
    var platform = new Platform(7, false);
    var journal = new Journal();
    platform.onCapture = () -> available.set(false);
    try (var controller = metered(platform, journal, settings(0, 0), immediate(), available)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(PauseReason.CREDITS_EXHAUSTED, controller.status().reason());
      assertEquals(1, platform.captures.get());
      assertEquals(1, platform.captureCalls.get());
      assertEquals(0, journal.attempts.get());
      assertEquals(0, journal.calls.get());
      assertEquals(0, controller.status().modelCalls());
      assertEquals(0, platform.submits.get());
    }
  }

  @Test
  void lastPaidSuccessCompletesItsAnswerAndStopsBeforeNext() throws Exception {
    var available = new AtomicBoolean(true);
    var platform = new Platform(7, true);
    var journal = new Journal();
    GradingProvider provider =
        (request, token) -> {
          available.set(false);
          return CompletableFuture.completedFuture(response(request.requestId()));
        };
    try (var controller = metered(platform, journal, settings(0, 0), provider, available)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.CREDITS_EXHAUSTED, controller.status().reason());
      assertEquals(1, platform.submits.get());
      assertEquals(1, journal.calls.get());
      assertEquals(1, journal.attempts.get());
      assertEquals(1, journal.readbacks.get());
      assertEquals(0, platform.nexts.get());
      assertEquals(BigDecimal.ONE, controller.status().lastScore());
    }
  }

  @Test
  void failedLastPaidRequestStopsWithoutDefaultScoreOrAnotherCall() throws Exception {
    var available = new AtomicBoolean(true);
    var platform = new Platform(7, false);
    var journal = new Journal();
    GradingProvider provider =
        (request, token) -> {
          available.set(false);
          return CompletableFuture.failedFuture(
              new IllegalStateException("Synthetic model failure"));
        };
    try (var controller = metered(platform, journal, settings(0, 0), provider, available)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.CREDITS_EXHAUSTED, controller.status().reason());
      assertEquals(1, journal.calls.get());
      assertEquals(0, platform.submits.get());
      assertNull(controller.status().lastScore());
    }
  }

  @Test
  void lastPaidResultIsNotRevokedMerelyBecauseCachedBalanceBecameZero() throws Exception {
    var available = new AtomicBoolean(true);
    var platform = new Platform(7, true);
    var started = new CountDownLatch(1);
    var pending = new CompletableFuture<GradingResponse>();
    var requestId = new AtomicReference<UUID>();
    GradingProvider provider =
        (request, token) -> {
          requestId.set(request.requestId());
          available.set(false);
          started.countDown();
          return pending;
        };
    try (var controller = metered(platform, new Journal(), settings(0, 0), provider, available)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertEquals(SessionState.GRADING, controller.status().state());
      pending.complete(response(requestId.get()));
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(1, controller.status().confirmedSubmissions());
      assertEquals(0, platform.nexts.get());
    }
  }

  @Test
  void finiteCallLimitStopsAfterThePaidAnswer() throws Exception {
    var platform = new Platform(7, true);
    try (var controller = controller(platform, new Journal(), settings(0, 1))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      assertEquals(1, controller.status().modelCalls());
      assertEquals(1, platform.submits.get());
      assertEquals(0, platform.nexts.get());
    }
  }

  @Test
  void explicitQuotaStopRevokesLateResponse() throws Exception {
    var platform = new Platform(7, false);
    var started = new CountDownLatch(1);
    var pending = new CompletableFuture<GradingResponse>();
    var requestId = new AtomicReference<UUID>();
    GradingProvider provider =
        (request, token) -> {
          requestId.set(request.requestId());
          started.countDown();
          return pending;
        };
    try (var controller =
        metered(platform, new Journal(), settings(0, 0), provider, new AtomicBoolean(true))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertTrue(started.await(2, TimeUnit.SECONDS));
      controller.stop(PauseReason.CREDITS_EXHAUSTED);
      pending.complete(response(requestId.get()));
      awaitTerminal(controller);
      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(0, platform.submits.get());
      assertNull(controller.status().lastScore());
    }
  }

  private static final class Journal {
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger readbacks = new AtomicInteger();

    SessionJournal port() {
      return (SessionJournal)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {SessionJournal.class},
              (proxy, method, args) -> {
                return switch (method.getName()) {
                  case "hasUnknownSubmission", "performanceEnabled" -> false;
                  case "runs", "attempts", "recoverInterrupted" -> List.of();
                  case "beginAttempt" -> {
                    attempts.incrementAndGet();
                    yield null;
                  }
                  case "beginModelCall" -> {
                    calls.incrementAndGet();
                    yield null;
                  }
                  case "recordScoreReadback" -> {
                    readbacks.incrementAndGet();
                    yield null;
                  }
                  default -> null;
                };
              });
    }
  }

  private static final class Platform implements SessionPlatform {
    final List<ScreenChangeDiagnostic.Checkpoint> checkpoints = new CopyOnWriteArrayList<>();
    ScreenChangeDiagnostic.Checkpoint checkpoint = ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED;
    java.util.function.Consumer<ScreenChangeDiagnostic.Checkpoint> diagnosticHook = ignored -> {};
    Runnable onVerify = () -> {};
    final int size;
    final boolean separate;
    final Set<String> capturedAnswerIds = new HashSet<>();
    final AtomicInteger captures = new AtomicInteger(),
        captureCalls = new AtomicInteger(),
        submits = new AtomicInteger(),
        nexts = new AtomicInteger();
    int answer = 1, acknowledged;
    BigDecimal score;
    boolean submitted;
    Runnable onCapture = () -> {};

    Platform(int size, boolean separate) {
      this.size = size;
      this.separate = separate;
    }

    @Override
    public void diagnosticCheckpoint(ScreenChangeDiagnostic.Checkpoint value) {
      checkpoint = value;
      checkpoints.add(value);
      diagnosticHook.accept(value);
    }

    public String targetIdentity() {
      return "quota-fixture";
    }

    public boolean supportsUnattended() {
      return true;
    }

    public Duration readinessStability() {
      return Duration.ZERO;
    }

    public View observe() {
      return new View(
          "answer-" + answer,
          "1",
          answer,
          submits.get() == size ? Page.COMPLETED : submitted ? Page.SUBMITTED : Page.READY,
          acknowledged == 0 ? null : "answer-" + acknowledged,
          submits.get(),
          score,
          separate);
    }

    public Capture capture(View expected) {
      captureCalls.incrementAndGet();
      if (capturedAnswerIds.add(expected.answerId())) captures.incrementAndGet();
      onCapture.run();
      return captured(new byte[] {1});
    }

    public IdentityCapture captureIdentity(View expected) {
      return new IdentityCapture(
          targetIdentity(),
          expected.answerId(),
          expected.questionId(),
          expected.pageRevision(),
          "quota-identity-region",
          captured(("identity:" + answer).getBytes(StandardCharsets.UTF_8)));
    }

    public void verify(View expected) {
      if (!Objects.equals(expected.answerId(), "answer-" + answer))
        throw new IllegalStateException("Wrong answer");
      onVerify.run();
    }

    public void fillScore(View expected, BigDecimal value, ActionPermission permission) {
      permission.verify();
      score = value;
    }

    public void submit(View expected, ActionPermission permission) {
      permission.verify();
      acknowledged = answer;
      submits.incrementAndGet();
      if (separate) submitted = true;
      else {
        answer++;
        score = null;
      }
    }

    public void next(View expected, ActionPermission permission) {
      permission.verify();
      nexts.incrementAndGet();
      submitted = false;
      answer++;
      score = null;
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

  private static final class LocalScreenFailure extends IllegalStateException
      implements SafeFailureCode, SafeScreenChangeFailure {
    private final ScreenChangeDiagnostic screenChange;
    private final boolean brokenGetter;

    LocalScreenFailure(ScreenChangeDiagnostic.Checkpoint checkpoint) {
      this(checkpoint, false);
    }

    LocalScreenFailure(ScreenChangeDiagnostic.Checkpoint checkpoint, boolean brokenGetter) {
      super("bounded local failure");
      this.brokenGetter = brokenGetter;
      screenChange =
          new ScreenChangeDiagnostic(
              checkpoint,
              5,
              100,
              ScreenChangeDiagnostic.ChangeScope.LOCALIZED,
              ScreenChangeDiagnostic.TripleConsistency.COHERENT,
              200);
    }

    @Override
    public String diagnosticCode() {
      return "ANSWER_CHANGED_WITHOUT_ACTION";
    }

    @Override
    public ScreenChangeDiagnostic screenChangeDiagnostic() {
      if (brokenGetter) throw new IllegalStateException("optional metadata unavailable");
      return screenChange;
    }
  }
}
