package app.deskgrade.core.application.session;

import static app.deskgrade.core.application.session.AttemptPerformance.Metric.*;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.GradingProvider;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A single serial controller owns every desktop write. UI/hotkey callers only revoke and enqueue.
 */
public final class SessionController implements AutoCloseable {
  private final SessionPlatform platform;
  private final SessionJournal journal;
  private final EvidenceStore evidence;
  private final Clock clock;
  private final BooleanSupplier stopHotkeyAvailable;
  private final BooleanSupplier modelRequestsAvailable;
  private final ScheduledExecutorService executor;
  private final InputActionGate gate = new InputActionGate();
  private final Consumer<Status> listener;
  private GradingProvider provider;
  private Function<GradeResult, BigDecimal> validator;
  private SessionSettings settings;
  private UUID runId;
  private UUID attemptId;
  private SessionPlatform.View current;
  private SessionPlatform.Capture capture;
  private SessionPlatform.IdentityCapture identityCapture;
  private String captureTargetIdentity;
  private volatile GradingResponse response;
  private long activeEpoch;
  private int calls;
  private int confirmed;
  private int ordinal;
  private boolean fallbackAttempted;
  private boolean submissionPrepared;
  private boolean nextPending;
  private boolean nextActionPrepared;
  private BigDecimal total;
  private BigDecimal spent = BigDecimal.ZERO;
  private boolean costUnknown;
  private Instant deadline;
  private SessionPlatform.View readyCandidate;
  private Instant readySince;
  private int readyObservations;
  private CompletableFuture<GradingResponse> inFlight;
  private FailureDiagnostic failureDiagnostic;
  // Executor-owned per-answer timing. It never participates in input authorization.
  private PerformanceTrace performance;
  private volatile Status status =
      new Status(null, SessionState.IDLE, PauseReason.NONE, 0, 0, null);
  private volatile boolean closed;

  public SessionController(
      SessionPlatform platform,
      SessionJournal journal,
      EvidenceStore evidence,
      GradingProvider provider,
      Function<GradeResult, BigDecimal> validator,
      SessionSettings settings,
      Clock clock,
      BooleanSupplier stopHotkeyAvailable,
      Consumer<Status> listener) {
    this(
        platform,
        journal,
        evidence,
        provider,
        validator,
        settings,
        clock,
        stopHotkeyAvailable,
        listener,
        () -> true);
  }

  public SessionController(
      SessionPlatform platform,
      SessionJournal journal,
      EvidenceStore evidence,
      GradingProvider provider,
      Function<GradeResult, BigDecimal> validator,
      SessionSettings settings,
      Clock clock,
      BooleanSupplier stopHotkeyAvailable,
      Consumer<Status> listener,
      BooleanSupplier modelRequestsAvailable) {
    this.platform = Objects.requireNonNull(platform);
    this.journal = Objects.requireNonNull(journal);
    this.evidence = Objects.requireNonNull(evidence);
    this.provider = Objects.requireNonNull(provider);
    this.validator = Objects.requireNonNull(validator);
    this.settings = Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
    this.stopHotkeyAvailable = Objects.requireNonNull(stopHotkeyAvailable);
    this.modelRequestsAvailable = Objects.requireNonNull(modelRequestsAvailable);
    this.listener = Objects.requireNonNull(listener);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "deskgrade-session-and-actions");
              thread.setDaemon(true);
              return thread;
            });
  }

  public Status status() {
    return status;
  }

  public GradingResponse lastResponse() {
    return response;
  }

  public long actionEpoch() {
    return gate.epoch();
  }

  /** Only an explicit user action may call start; startup never invokes it. */
  public CompletionStage<Void> start() {
    long requestedEpoch = gate.epoch();
    return command(
        () -> {
          if (status.state() != SessionState.IDLE
              && status.state() != SessionState.STOPPED
              && status.state() != SessionState.COMPLETED) {
            throw new IllegalStateException("Session already exists");
          }
          gate.bindExecutorThread();
          // A historical unresolved submission is not a new run. Keep the current identity empty
          // until beginRun has actually committed, so stopping this precheck cannot write a
          // phantom run or accidentally reconcile an answer from a previous session.
          runId = null;
          attemptId = null;
          current = null;
          calls = 0;
          confirmed = 0;
          ordinal = 0;
          fallbackAttempted = false;
          submissionPrepared = false;
          nextPending = false;
          nextActionPrepared = false;
          spent = BigDecimal.ZERO;
          costUnknown = false;
          response = null;
          capture = null;
          identityCapture = null;
          captureTargetIdentity = null;
          total = null;
          failureDiagnostic = null;
          journal.assertWritable();
          if (journal.hasUnknownSubmission(platform.targetIdentity())) {
            publish(SessionState.SUBMISSION_UNKNOWN, PauseReason.SUBMISSION_UNCERTAIN);
            return;
          }
          UUID nextRunId = UUID.randomUUID();
          journal.beginRun(nextRunId, platform.targetIdentity(), settings, clock.instant());
          runId = nextRunId;
          precheck(requestedEpoch);
        });
  }

  private void precheck(long requestedEpoch) {
    if (gate.epoch() != requestedEpoch) {
      return;
    }
    beginPerformance(SessionState.PRECHECK);
    transition(SessionState.PRECHECK, PauseReason.NONE);
    if (!stopHotkeyAvailable.getAsBoolean()) {
      pauseInternal(PauseReason.HOTKEY_UNAVAILABLE);
      return;
    }
    if (!platform.supportsUnattended()) {
      pauseInternal(PauseReason.PLATFORM_UNSUPPORTED);
      return;
    }
    journalWrite(journal::assertWritable);
    if (budgetExceeded() || stopBeforeNextRequest()) return;
    if (journal.hasUnknownSubmission(platform.targetIdentity())) {
      transition(SessionState.SUBMISSION_UNKNOWN, PauseReason.SUBMISSION_UNCERTAIN);
      return;
    }
    activeEpoch = gate.openIfUnchanged(requestedEpoch);
    if (activeEpoch < 0) {
      return;
    }
    deadline = clock.instant().plus(settings.pageTimeout());
    readyCandidate = null;
    readyObservations = 0;
    if (nextPending) {
      transition(SessionState.WAIT_NEXT, PauseReason.NONE);
      schedule(this::waitNext);
    } else {
      transition(SessionState.WAIT_READY, PauseReason.NONE);
      schedule(this::waitReady);
    }
  }

  public void pause(PauseReason reason) {
    gate.revoke();
    // Cancellation is best effort and separate from action revocation. A late call is still
    // recorded.
    command(() -> pauseInternal(reason));
  }

  public void stop() {
    stop(PauseReason.USER_STOPPED);
  }

  public void stop(PauseReason reason) {
    Objects.requireNonNull(reason);
    gate.revoke();
    command(() -> stopInternal(reason));
  }

  /** Emergency entry point: retain safe context while synchronously revoking input permission. */
  public void stop(PauseReason reason, Throwable failure) {
    Objects.requireNonNull(reason);
    gate.revoke();
    FailureDiagnostic diagnostic = FailureDiagnostic.from(status.state(), failure);
    command(
        () -> {
          failureDiagnostic = diagnostic;
          stopInternal(reason);
        });
  }

  public CompletionStage<Void> resume() {
    long requestedEpoch = gate.epoch();
    return command(
        () -> {
          if (runId == null || status.state() != SessionState.PAUSED || submissionPrepared) {
            return;
          }
          // A confirmed submission keeps its binding until the next page is reconciled.
          // Unsubmitted attempts are recaptured; their old input queue is never replayed.
          if (!nextPending) {
            attemptId = null;
            capture = null;
            identityCapture = null;
            captureTargetIdentity = null;
            response = null;
            total = null;
          }
          precheck(requestedEpoch);
        });
  }

  public CompletionStage<Void> changeConfiguration(
      SessionSettings next,
      GradingProvider nextProvider,
      Function<GradeResult, BigDecimal> nextValidator) {
    gate.revoke();
    return command(
        () -> {
          pauseInternal(PauseReason.CONFIG_CHANGED);
          Objects.requireNonNull(next);
          Objects.requireNonNull(nextProvider);
          Objects.requireNonNull(nextValidator);
          validateConfigurationChange(next, nextProvider);
          if (runId != null) {
            journal.recordConfiguration(runId, next, clock.instant());
          }
          settings = next;
          provider = nextProvider;
          validator = nextValidator;
        },
        true);
  }

  /** Only observes the original answer's acknowledgment; never resends submit or next. */
  public CompletionStage<Void> reconcileUnknown() {
    return command(
        () -> {
          if (status.state() != SessionState.SUBMISSION_UNKNOWN
              || current == null
              || attemptId == null) {
            return;
          }
          SessionPlatform.View view = platform.observe();
          if (Objects.equals(view.questionId(), current.questionId())
              && Objects.equals(view.acknowledgedAnswerId(), current.answerId())
              && view.confirmedCount() == current.confirmedCount() + 1) {
            journal.confirmSubmission(runId, attemptId, clock.instant(), view.confirmationKind());
            confirmed++;
            submissionPrepared = false;
            nextPending = true;
            nextActionPrepared = false;
            transition(SessionState.PAUSED, PauseReason.RECOVERED);
          }
        });
  }

  private void waitReady() {
    if (!active()) {
      return;
    }
    if (budgetExceeded() || stopBeforeNextRequest()) return;
    SessionPlatform.View view = measured(OBSERVE, platform::observe);
    if (!settings.question().id().equals(view.questionId())) {
      stopInternal(PauseReason.QUESTION_CHANGED);
      return;
    }
    if (view.page() == SessionPlatform.Page.COMPLETED) {
      finish();
      return;
    }
    if (view.page() != SessionPlatform.Page.READY) {
      readyCandidate = null;
      readyObservations = 0;
      if (view.page() != SessionPlatform.Page.LOADING) {
        stopInternal(PauseReason.TARGET_CHANGED);
        return;
      }
      if (expired()) {
        stopInternal(PauseReason.PAGE_TIMEOUT);
      } else {
        schedule(this::waitReady);
      }
      return;
    }
    if (readyCandidate == null
        || !Objects.equals(view.answerId(), readyCandidate.answerId())
        || view.pageRevision() != readyCandidate.pageRevision()) {
      readyCandidate = view;
      readySince = clock.instant();
      readyObservations = 1;
    } else {
      readyObservations++;
    }
    if (readyObservations < 3
        || clock.instant().isBefore(readySince.plus(platform.readinessStability()))) {
      if (expired()) {
        stopInternal(PauseReason.PAGE_TIMEOUT);
      } else {
        schedule(this::waitReady);
      }
      return;
    }
    current = view;
    transition(SessionState.CAPTURING, PauseReason.NONE);
    captureTargetIdentity = requireTargetIdentity();
    SessionPlatform.CaptureEvidence acquired =
        measured(CAPTURE_EVIDENCE, () -> platform.captureEvidence(current));
    capture = acquired.answer();
    identityCapture = acquired.identity();
    requireCaptureEvidence(current, captureTargetIdentity, capture, identityCapture);
    if (!captureTargetIdentity.equals(requireTargetIdentity())) {
      throw new IllegalStateException("Capture target changed while acquiring evidence");
    }
    if (!active()) {
      return;
    }
    // Quota/credit availability can change while the screenshots are being acquired. Re-check
    // before creating an attempt or persisting evidence so a capture-side balance refresh cannot
    // leak through into a paid model request.
    if (stopBeforeNextRequest()) return;
    attemptId = UUID.randomUUID();
    ordinal++;
    if (performance != null) performance.bindAttempt(attemptId);
    fallbackAttempted = false;
    String path = measured(EVIDENCE_SAVE, () -> evidence.save(runId, attemptId, capture));
    journalWrite(
        () ->
            journal.beginAttempt(
                runId, attemptId, ordinal, current, activeEpoch, path, capture, settings));
    grade();
  }

  private void grade() {
    dispatchModelCall(null, null, null);
  }

  private void dispatchFallback(UUID primaryRequestId) {
    dispatchFallback(primaryRequestId, null);
  }

  private void dispatchFallback(UUID primaryRequestId, Throwable primaryFailure) {
    if (!active()) {
      return;
    }
    if (fallbackAttempted) {
      if (primaryFailure != null) recordFailure(primaryFailure);
      stopInternal(PauseReason.FALLBACK_FAILED);
      return;
    }
    if (!(provider instanceof FallbackGradingProvider fallbackProvider)) {
      if (primaryFailure != null) recordFailure(primaryFailure);
      stopInternal(PauseReason.FALLBACK_UNAVAILABLE);
      return;
    }
    String fallbackProfileId = fallbackProvider.fallbackProfileId().orElse(null);
    if (fallbackProfileId == null) {
      if (primaryFailure != null) recordFailure(primaryFailure);
      stopInternal(PauseReason.FALLBACK_UNAVAILABLE);
      return;
    }
    if (stopBeforeNextRequest(primaryFailure)) return;
    // Retry the immutable image already bound to this attempt. Re-capturing here made harmless
    // page animation prevent the one allowed fallback. The target is still checked strictly,
    // including exact answer and identity pixels, immediately before any score input.
    fallbackAttempted = true;
    dispatchModelCall(primaryRequestId, fallbackProvider, fallbackProfileId);
  }

  private void dispatchModelCall(
      UUID primaryRequestId, FallbackGradingProvider fallbackProvider, String callProfileId) {
    if (!active()) return;
    // This is the final common quota/credit gate for both primary and fallback calls.
    if (stopBeforeNextRequest()) return;
    boolean fallback = primaryRequestId != null;
    transition(SessionState.GRADING, PauseReason.NONE);
    UUID callId =
        fallback ? FallbackGradingProvider.fallbackRequestId(primaryRequestId) : UUID.randomUUID();
    UUID boundAttempt = attemptId;
    UUID boundRun = runId;
    long boundEpoch = activeEpoch;
    journalWrite(
        () ->
            journal.beginModelCall(
                callId,
                runId,
                boundAttempt,
                fallback ? callProfileId : settings.modelProfileId(),
                clock.instant()));
    calls++;
    publish(SessionState.GRADING, PauseReason.NONE);
    GradingRequest request = new GradingRequest(callId, settings.question(), capture.png());
    // Capture the original trace: a stopped call must never be charged to a later answer.
    PerformanceTrace callPerformance = performance;
    if (callPerformance != null)
      callPerformance.beginModel(
          callId,
          fallback ? AttemptPerformance.ModelRole.FALLBACK : AttemptPerformance.ModelRole.PRIMARY);
    try {
      inFlight =
          measured(
              MODEL_DISPATCH,
              () ->
                  (fallback
                          ? fallbackProvider.gradeFallback(
                              primaryRequestId, request, () -> !gate.accepts(boundEpoch))
                          : provider.grade(request, () -> !gate.accepts(boundEpoch)))
                      .toCompletableFuture());
    } catch (RuntimeException failure) {
      inFlight = CompletableFuture.failedFuture(failure);
    }
    inFlight.whenComplete(
        (result, failure) -> {
          if (callPerformance != null)
            callPerformance.modelCompleted(callId, failure == null && result != null);
          command(
              () -> {
                if (callPerformance != null) callPerformance.modelCallbackEntered(callId);
                // A late completion is still durably recorded, but not timed against a new trace.
                Runnable completeCall =
                    () ->
                        journal.completeModelCall(
                            callId,
                            result,
                            failure == null ? null : safeCode(failure),
                            clock.instant());
                if (callPerformance == null) completeCall.run();
                else callPerformance.measure(JOURNAL_WRITE, completeCall);
                if (Objects.equals(boundRun, runId)) {
                  if (result != null
                      && result.usage() != null
                      && result.usage().estimatedCost() != null) {
                    spent = spent.add(result.usage().estimatedCost());
                  } else {
                    costUnknown = true;
                  }
                  if (active() && budgetExceeded()) return;
                }
                if (!gate.accepts(boundEpoch) || !Objects.equals(attemptId, boundAttempt)) {
                  return;
                }
                if (failure != null) {
                  if (!modelRequestsAvailable.getAsBoolean()) {
                    recordFailure(failure);
                    stopInternal(PauseReason.CREDITS_EXHAUSTED);
                    return;
                  }
                  if (!fallback && strictResultFailure(failure)) {
                    dispatchFallback(callId, failure);
                  } else {
                    recordFailure(failure);
                    if (fallback) stopInternal(PauseReason.FALLBACK_FAILED);
                    else stopInternal(PauseReason.MODEL_FAILED);
                  }
                  return;
                }
                if (result == null || !callId.equals(result.requestId())) {
                  if (fallback) stopInternal(PauseReason.FALLBACK_FAILED);
                  else dispatchFallback(callId);
                  return;
                }
                response = result;
                if (result.result().status() == GradeResult.Status.UNABLE) {
                  journalWrite(() -> journal.saveGrade(attemptId, result, null));
                  if (fallback) stopInternal(PauseReason.FALLBACK_FAILED);
                  else dispatchFallback(callId);
                  return;
                }
                transition(SessionState.VALIDATING, PauseReason.NONE);
                try {
                  total = measured(VALIDATE_GRADE, () -> validator.apply(result.result()));
                } catch (RuntimeException invalid) {
                  if (fallback) stopInternal(PauseReason.FALLBACK_FAILED);
                  else dispatchFallback(callId);
                  return;
                }
                if (total == null) {
                  if (fallback) stopInternal(PauseReason.FALLBACK_FAILED);
                  else dispatchFallback(callId);
                  return;
                }
                journalWrite(() -> journal.saveGrade(attemptId, result, total));
                if (!active()) {
                  return;
                }
                fillAndSubmit();
              });
        });
  }

  private void fillAndSubmit() {
    transition(SessionState.BEFORE_INPUT, PauseReason.NONE);
    if (!originalCaptureStillCurrent()) {
      return;
    }
    ActionPermission permission = permission(current);
    permission.verify();
    transition(SessionState.FILLING, PauseReason.NONE);
    measured(FILL_SCORE, () -> platform.fillScore(current, total, permission));
    if (!active()) {
      return;
    }
    transition(SessionState.VERIFYING_SCORE, PauseReason.NONE);
    deadline = clock.instant().plus(settings.pageTimeout());
    schedule(this::verifyScoreAndSubmit);
  }

  private void verifyScoreAndSubmit() {
    if (!active()) {
      return;
    }
    BigDecimal readback =
        withDiagnosticCheckpoint(
            ScreenChangeDiagnostic.Checkpoint.BEFORE_READBACK,
            () -> measured(READ_SCORE, () -> platform.readScore(current, permission(current))));
    withDiagnosticCheckpoint(
        ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY,
        () -> {
          measured(VERIFY_TARGET, () -> platform.verify(current));
          return null;
        });
    if (readback == null || total.compareTo(readback) != 0) {
      if (expired()) {
        stopInternal(PauseReason.SCORE_MISMATCH);
      } else {
        schedule(this::verifyScoreAndSubmit);
      }
      return;
    }
    // Filling the score must not have changed the bound answer or its visible page identity. This
    // second passive recapture happens immediately before any durable submit intent is recorded.
    if (!withDiagnosticCheckpoint(
        ScreenChangeDiagnostic.Checkpoint.BEFORE_SUBMIT_CAPTURE,
        this::originalCaptureStillCurrent)) {
      return;
    }
    ActionPermission permission = permission(current);
    withDiagnosticCheckpoint(
        ScreenChangeDiagnostic.Checkpoint.BEFORE_SUBMIT_VERIFY,
        () -> {
          permission.verify();
          return null;
        });
    // Persist the intent first. A crash from here onward is conservatively unknown.
    journalWrite(() -> journal.recordScoreReadback(attemptId, readback, clock.instant()));
    transition(SessionState.SUBMIT_PREPARED, PauseReason.NONE);
    submissionPrepared = true;
    withDiagnosticCheckpoint(
        ScreenChangeDiagnostic.Checkpoint.BEFORE_SUBMIT_VERIFY,
        () -> {
          permission.verify();
          return null;
        });
    transition(SessionState.SUBMIT_SENT, PauseReason.NONE);
    withDiagnosticCheckpoint(
        ScreenChangeDiagnostic.Checkpoint.SUBMIT_ACTION,
        () -> {
          measured(SUBMIT_ACTION, () -> platform.submit(current, permission));
          return null;
        });
    if (!active()) {
      pauseInternal(PauseReason.SUBMISSION_UNCERTAIN);
      return;
    }
    transition(SessionState.WAIT_ACK, PauseReason.NONE);
    deadline = clock.instant().plus(settings.pageTimeout());
    schedule(this::waitAck);
  }

  private void waitAck() {
    if (!active()) {
      return;
    }
    SessionPlatform.View view = measured(OBSERVE, platform::observe);
    if (!Objects.equals(view.questionId(), current.questionId())) {
      stopInternal(PauseReason.QUESTION_CHANGED);
      return;
    }
    if (Objects.equals(view.acknowledgedAnswerId(), current.answerId())
        && view.confirmedCount() == current.confirmedCount() + 1) {
      journalWrite(
          () ->
              journal.confirmSubmission(
                  runId, attemptId, clock.instant(), view.confirmationKind()));
      if (performance != null) performance.acknowledged();
      confirmed++;
      submissionPrepared = false;
      nextPending = true;
      nextActionPrepared = false;
      if (!active()) {
        return;
      }
      if (view.page() == SessionPlatform.Page.COMPLETED) {
        finish();
        return;
      }
      // The last paid answer is committed, but neither next nor another capture is authorized.
      if (stopBeforeNextRequest()) return;
      if (view.separateNext()) {
        if (view.page() != SessionPlatform.Page.SUBMITTED
            || !Objects.equals(view.answerId(), current.answerId())) {
          stopInternal(PauseReason.NEXT_UNCERTAIN);
          return;
        }
        nextOnce(view);
      }
      if (!active()) {
        return;
      }
      transition(SessionState.WAIT_NEXT, PauseReason.NONE);
      deadline = clock.instant().plus(settings.pageTimeout());
      schedule(this::waitNext);
    } else if (expired()) {
      stopInternal(PauseReason.SUBMISSION_UNCERTAIN);
    } else {
      schedule(this::waitAck);
    }
  }

  private <T> T withDiagnosticCheckpoint(
      ScreenChangeDiagnostic.Checkpoint checkpoint, Supplier<T> operation) {
    setDiagnosticCheckpoint(checkpoint);
    try {
      return operation.get();
    } finally {
      setDiagnosticCheckpoint(ScreenChangeDiagnostic.Checkpoint.UNSPECIFIED);
    }
  }

  private void setDiagnosticCheckpoint(ScreenChangeDiagnostic.Checkpoint checkpoint) {
    try {
      platform.diagnosticCheckpoint(checkpoint);
    } catch (RuntimeException | LinkageError ignored) {
      // Optional local metadata cannot suppress or replace an actual action/permission failure.
    }
  }

  private void waitNext() {
    if (!active()) {
      return;
    }
    SessionPlatform.View view = measured(OBSERVE, platform::observe);
    if (!settings.question().id().equals(view.questionId())) {
      stopInternal(PauseReason.QUESTION_CHANGED);
      return;
    }
    if (view.page() == SessionPlatform.Page.COMPLETED) {
      finish();
      return;
    }
    if (nextPending
        && !nextActionPrepared
        && view.separateNext()
        && view.page() == SessionPlatform.Page.SUBMITTED
        && Objects.equals(view.answerId(), current.answerId())
        && Objects.equals(view.acknowledgedAnswerId(), current.answerId())
        && view.confirmedCount() == current.confirmedCount() + 1) {
      nextOnce(view);
      if (!active()) {
        return;
      }
      transition(SessionState.WAIT_NEXT, PauseReason.NONE);
      deadline = clock.instant().plus(settings.pageTimeout());
      schedule(this::waitNext);
      return;
    }
    if (view.page() == SessionPlatform.Page.READY
        && !Objects.equals(view.answerId(), current.answerId())
        && view.pageRevision() != current.pageRevision()
        && view.confirmedCount() == current.confirmedCount() + 1) {
      if (!nextAnswerIdentityIsFresh(view)) return;
      completePerformance(SessionState.WAIT_NEXT, PauseReason.NONE);
      nextPending = false;
      nextActionPrepared = false;
      attemptId = null;
      capture = null;
      identityCapture = null;
      captureTargetIdentity = null;
      readyCandidate = null;
      readyObservations = 0;
      beginPerformance(SessionState.WAIT_READY);
      transition(SessionState.WAIT_READY, PauseReason.NONE);
      deadline = clock.instant().plus(settings.pageTimeout());
      schedule(this::waitReady);
    } else if (expired()) {
      // A timeout is ambiguous rather than an adapter failure. The gate remains revoked until the
      // user explicitly resumes, and recovery is observation-only when a next action was prepared.
      pauseInternal(PauseReason.NEXT_UNCERTAIN);
    } else {
      schedule(this::waitNext);
    }
  }

  private void nextOnce(SessionPlatform.View submitted) {
    if (!active() || nextActionPrepared) {
      return;
    }
    ActionPermission permission = permission(submitted);
    permission.verify();
    // Persist preparation before input. Once prepared, an ambiguous next is observation-only.
    nextActionPrepared = true;
    transition(SessionState.NAVIGATING, PauseReason.NONE);
    if (!active()) {
      return;
    }
    measured(NEXT_ACTION, () -> platform.next(submitted, permission));
  }

  private boolean nextAnswerIdentityIsFresh(SessionPlatform.View next) {
    if (!active() || identityCapture == null || captureTargetIdentity == null) {
      failureDiagnostic =
          new FailureDiagnostic(status.state(), List.of("CAPTURE_EVIDENCE_UNAVAILABLE"));
      stopInternal(PauseReason.LOCAL_FAILURE);
      return false;
    }
    try {
      String target = requireTargetIdentity();
      SessionPlatform.CaptureEvidence acquired =
          measured(CAPTURE_EVIDENCE, () -> platform.captureEvidence(next));
      SessionPlatform.IdentityCapture nextIdentity = acquired.identity();
      requireCaptureEvidence(next, target, acquired.answer(), nextIdentity);
      if (!target.equals(requireTargetIdentity())
          || !captureTargetIdentity.equals(target)
          || !identityCapture.regionIdentity().equals(nextIdentity.regionIdentity())
          || sameCapture(identityCapture.capture(), nextIdentity.capture())) {
        stopInternal(PauseReason.NEXT_UNCERTAIN);
        return false;
      }
      return active();
    } catch (RuntimeException | LinkageError failure) {
      recordFailure(failure);
      stopInternal(PauseReason.LOCAL_FAILURE);
      return false;
    }
  }

  private boolean originalCaptureStillCurrent() {
    if (!active()
        || current == null
        || capture == null
        || identityCapture == null
        || captureTargetIdentity == null) {
      failureDiagnostic =
          new FailureDiagnostic(status.state(), List.of("CAPTURE_EVIDENCE_UNAVAILABLE"));
      stopInternal(PauseReason.LOCAL_FAILURE);
      return false;
    }
    try {
      String target = requireTargetIdentity();
      SessionPlatform.CaptureEvidence acquired =
          measured(CAPTURE_EVIDENCE, () -> platform.captureEvidence(current));
      SessionPlatform.Capture repeated = acquired.answer();
      SessionPlatform.IdentityCapture repeatedIdentity = acquired.identity();
      requireCaptureEvidence(current, target, repeated, repeatedIdentity);
      if (!target.equals(requireTargetIdentity())
          || !captureTargetIdentity.equals(target)
          || !sameCapture(capture, repeated)
          || !sameIdentityEvidence(identityCapture, repeatedIdentity)) {
        stopInternal(PauseReason.TARGET_CHANGED);
        return false;
      }
      return active();
    } catch (RuntimeException | LinkageError failure) {
      recordFailure(failure);
      stopInternal(PauseReason.LOCAL_FAILURE);
      return false;
    }
  }

  private String requireTargetIdentity() {
    String identity = platform.targetIdentity();
    if (identity == null || identity.isBlank() || identity.length() > 2048)
      throw new IllegalStateException("Platform target identity is unavailable");
    return identity;
  }

  private static void requireCaptureEvidence(
      SessionPlatform.View expected,
      String targetIdentity,
      SessionPlatform.Capture answer,
      SessionPlatform.IdentityCapture identity) {
    Objects.requireNonNull(expected);
    requireCaptureHash(answer);
    Objects.requireNonNull(identity);
    requireCaptureHash(identity.capture());
    if (!targetIdentity.equals(identity.targetIdentity())
        || !Objects.equals(expected.answerId(), identity.answerId())
        || !expected.questionId().equals(identity.questionId())
        || expected.pageRevision() != identity.pageRevision()) {
      throw new IllegalStateException("Visible identity evidence does not match the bound answer");
    }
  }

  private static void requireCaptureHash(SessionPlatform.Capture captured) {
    Objects.requireNonNull(captured);
    if (!captured.sha256().matches("[0-9a-f]{64}")
        || !captured.sha256().equals(sha256(captured.png()))) {
      throw new IllegalStateException("Capture digest is invalid");
    }
  }

  private static boolean sameCapture(
      SessionPlatform.Capture expected, SessionPlatform.Capture actual) {
    return expected.sha256().equals(actual.sha256())
        && MessageDigest.isEqual(expected.png(), actual.png());
  }

  private static boolean sameIdentityEvidence(
      SessionPlatform.IdentityCapture expected, SessionPlatform.IdentityCapture actual) {
    return expected.targetIdentity().equals(actual.targetIdentity())
        && expected.answerId().equals(actual.answerId())
        && expected.questionId().equals(actual.questionId())
        && expected.pageRevision() == actual.pageRevision()
        && expected.regionIdentity().equals(actual.regionIdentity())
        && sameCapture(expected.capture(), actual.capture());
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private ActionPermission permission(SessionPlatform.View expected) {
    ActionPermission authority =
        gate.permission(
            activeEpoch,
            () -> {
              if (!stopHotkeyAvailable.getAsBoolean()) {
                throw new IllegalStateException("Stop hotkey unavailable");
              }
            });
    // Probe durable storage once per logical action, not once for every native key in that action.
    // Actual journal transitions and submit intent remain synchronously committed as before.
    authority.verify();
    journalWrite(journal::assertWritable);
    authority.verify();
    return new ActionPermission() {
      @Override
      public void verify() {
        verifyForTarget(() -> platform.verify(expected));
      }

      @Override
      public void verifyForTarget(Runnable targetCheck) {
        authority.verify();
        measured(VERIFY_TARGET, Objects.requireNonNull(targetCheck));
        authority.verify();
      }
    };
  }

  private boolean active() {
    return !closed && gate.accepts(activeEpoch);
  }

  private boolean expired() {
    return !clock.instant().isBefore(deadline);
  }

  private void finish() {
    gate.revoke();
    transition(SessionState.COMPLETED, PauseReason.NONE);
  }

  private boolean stopBeforeNextRequest() {
    return stopBeforeNextRequest(null);
  }

  private boolean stopBeforeNextRequest(Throwable priorFailure) {
    if (!modelRequestsAvailable.getAsBoolean()) {
      if (priorFailure != null) recordFailure(priorFailure);
      stopInternal(PauseReason.CREDITS_EXHAUSTED);
      return true;
    }
    if (settings.submissionLimitReached(confirmed) || settings.callLimitReached(calls)) {
      if (priorFailure != null) recordFailure(priorFailure);
      stopInternal(PauseReason.LIMIT_REACHED);
      return true;
    }
    return false;
  }

  private void stopInternal(PauseReason reason) {
    gate.revokeIfOpen();
    if (terminal()) return;
    if (runId == null) {
      publish(SessionState.STOPPED, reason);
    } else {
      transition(
          submissionPrepared ? SessionState.SUBMISSION_UNKNOWN : SessionState.STOPPED,
          submissionPrepared ? PauseReason.SUBMISSION_UNCERTAIN : reason);
    }
  }

  private void pauseInternal(PauseReason reason) {
    gate.revokeIfOpen();
    if (terminal()) return;
    if (runId == null) {
      publish(SessionState.PAUSED, reason);
      return;
    }
    transition(
        submissionPrepared ? SessionState.SUBMISSION_UNKNOWN : SessionState.PAUSED,
        submissionPrepared ? PauseReason.SUBMISSION_UNCERTAIN : reason);
  }

  private boolean terminal() {
    return status.state() == SessionState.COMPLETED || status.state() == SessionState.STOPPED;
  }

  private void transition(SessionState state, PauseReason reason) {
    if (performance != null) performance.phase(state);
    journalWrite(() -> journal.transition(runId, attemptId, state, reason, clock.instant()));
    publish(state, reason);
  }

  private void publish(SessionState state, PauseReason reason) {
    status = new Status(runId, state, reason, calls, confirmed, total, failureDiagnostic);
    try {
      listener.accept(status);
    } catch (RuntimeException | LinkageError listenerFailure) {
      recordFailure(listenerFailure);
      failStopWithoutPublishing();
      // Abort the current serialized operation immediately. safe(...) observes this sentinel and
      // cannot reopen the already revoked action gate.
      throw new ListenerFailure();
    }
  }

  private void failStopWithoutPublishing() {
    gate.revoke();
    if (status.state() == SessionState.COMPLETED
        || status.state() == SessionState.STOPPED
        || status.state() == SessionState.SUBMISSION_UNKNOWN) return;
    SessionState stopped =
        submissionPrepared ? SessionState.SUBMISSION_UNKNOWN : SessionState.STOPPED;
    PauseReason reason =
        submissionPrepared ? PauseReason.SUBMISSION_UNCERTAIN : PauseReason.LOCAL_FAILURE;
    if (runId != null) {
      try {
        journalWrite(() -> journal.transition(runId, attemptId, stopped, reason, clock.instant()));
      } catch (RuntimeException | LinkageError unavailable) {
        status =
            new Status(
                runId,
                SessionState.FAILED,
                PauseReason.STORAGE_UNAVAILABLE,
                calls,
                confirmed,
                total,
                failureDiagnostic);
        return;
      }
    }
    status = new Status(runId, stopped, reason, calls, confirmed, total, failureDiagnostic);
  }

  private void schedule(Runnable operation) {
    if (!closed) {
      long scheduledEpoch = activeEpoch;
      executor.schedule(
          () -> {
            if (!closed && gate.accepts(scheduledEpoch)) safe(operation);
          },
          settings.pollInterval().toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  private boolean budgetExceeded() {
    if (settings.maxCost() == null) return false;
    if (costUnknown) {
      pauseInternal(PauseReason.COST_UNKNOWN);
      return true;
    }
    if (spent.compareTo(settings.maxCost()) >= 0) {
      pauseInternal(PauseReason.LIMIT_REACHED);
      return true;
    }
    return false;
  }

  private void validateConfigurationChange(SessionSettings next, GradingProvider nextProvider) {
    boolean ruleChanged = !next.question().equals(settings.question());
    boolean modelChanged =
        !next.modelProfileId().equals(settings.modelProfileId()) || nextProvider != provider;
    boolean anyRevisionIncreased =
        next.question().revision() > settings.question().revision()
            || next.modelRevision() > settings.modelRevision()
            || next.platformRevision() > settings.platformRevision();
    if (next.question().revision() < settings.question().revision()
        || next.modelRevision() < settings.modelRevision()
        || next.platformRevision() < settings.platformRevision()
        || (ruleChanged && next.question().revision() <= settings.question().revision())
        || (modelChanged && next.modelRevision() <= settings.modelRevision())
        || (!next.equals(settings) && !anyRevisionIncreased)) {
      throw new IllegalArgumentException("Configuration changes require increasing revisions");
    }
  }

  private CompletionStage<Void> command(Runnable operation) {
    return command(operation, false);
  }

  private CompletionStage<Void> command(Runnable operation, boolean reportFailure) {
    if (closed) {
      return CompletableFuture.failedFuture(new IllegalStateException("Controller closed"));
    }
    CompletableFuture<Void> result = new CompletableFuture<>();
    executor.execute(
        () -> {
          try {
            safe(operation, reportFailure);
            result.complete(null);
          } catch (RuntimeException | LinkageError failure) {
            result.completeExceptionally(failure);
          }
        });
    return result;
  }

  private void safe(Runnable operation) {
    safe(operation, false);
  }

  private void safe(Runnable operation, boolean reportFailure) {
    try {
      operation.run();
    } catch (RuntimeException failure) {
      stopForUnhandledFailure(failure);
      if (reportFailure) throw failure;
    } catch (LinkageError failure) {
      stopForUnhandledFailure(failure);
      if (reportFailure) throw failure;
    } finally {
      if (performance != null
          && switch (status.state()) {
            case PAUSED, SUBMISSION_UNKNOWN, STOPPED, COMPLETED, FAILED -> true;
            default -> false;
          }) completePerformance(status.state(), status.reason());
    }
  }

  private void beginPerformance(SessionState phase) {
    // Confirmed answers are already summarized at pause. Observation-only recovery is not
    // another answer; wait until the next answer is fresh before starting a new timing trace.
    if (performance != null || runId == null || nextPending) return;
    try {
      if (journal.performanceEnabled())
        performance = new PerformanceTrace(runId, ordinal + 1, clock.instant(), phase);
    } catch (RuntimeException | LinkageError unavailable) {
      warnPerformanceUnavailable();
    }
  }

  private <T> T measured(AttemptPerformance.Metric metric, Supplier<T> operation) {
    PerformanceTrace trace = performance;
    return trace == null ? operation.get() : trace.measure(metric, operation);
  }

  private void measured(AttemptPerformance.Metric metric, Runnable operation) {
    PerformanceTrace trace = performance;
    if (trace == null) operation.run();
    else trace.measure(metric, operation);
  }

  private void journalWrite(Runnable operation) {
    measured(JOURNAL_WRITE, operation);
  }

  private void completePerformance(SessionState state, PauseReason reason) {
    PerformanceTrace completed = performance;
    performance = null;
    if (completed == null) return;
    try {
      journal.recordPerformance(
          completed.finish(
              clock.instant(),
              state,
              reason,
              failureDiagnostic == null ? List.of() : failureDiagnostic.codes()));
    } catch (RuntimeException | LinkageError unavailable) {
      // Timing is auxiliary: never change a score, authorize input, or replay an action for it.
      warnPerformanceUnavailable();
    }
  }

  private static void warnPerformanceUnavailable() {
    try {
      System.getLogger(SessionController.class.getName())
          .log(System.Logger.Level.WARNING, "LOCAL_PERFORMANCE_LOG_UNAVAILABLE");
    } catch (RuntimeException | LinkageError ignored) {
      // Even logging diagnostics is optional; durable submission records retain their own policy.
    }
  }

  private void stopForUnhandledFailure(Throwable failure) {
    if (failure instanceof ListenerFailure) return;
    recordFailure(failure);
    gate.revoke();
    PauseReason reason = PauseReason.LOCAL_FAILURE;
    if (failure instanceof DeskGradeException e) {
      if (e.code().name().equals("STORAGE_FAILURE")) reason = PauseReason.STORAGE_UNAVAILABLE;
      if (e.code().name().equals("DUPLICATE_SUBMISSION")) reason = PauseReason.DUPLICATE_SCREEN;
    }
    try {
      stopInternal(reason);
    } catch (RuntimeException | LinkageError unavailable) {
      publish(SessionState.FAILED, PauseReason.STORAGE_UNAVAILABLE);
    }
  }

  private void recordFailure(Throwable failure) {
    // Preserve the first failing operation if writing its stop transition also fails.
    if (failureDiagnostic == null)
      failureDiagnostic = FailureDiagnostic.from(status.state(), failure);
  }

  private static String safeCode(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 16; depth++) {
      if (cause instanceof SafeFailureCode safe) {
        String code = safe.diagnosticCode();
        if (code != null && code.matches("[A-Z0-9_:-]{1,120}")) return code;
      }
      if (cause instanceof ModelCallException e) return "MODEL_" + e.kind().name();
      if (cause instanceof DeskGradeException e) return e.code().name();
      Throwable next = cause.getCause();
      if (next == cause) break;
      cause = next;
    }
    return "PROVIDER_FAILURE";
  }

  private static boolean strictResultFailure(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 16; depth++) {
      if (cause instanceof ModelCallException model) return model.fallbackEligible();
      Throwable next = cause.getCause();
      if (next == cause) break;
      cause = next;
    }
    return false;
  }

  private static final class ListenerFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private ListenerFailure() {
      super(null, null, false, false);
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    gate.revoke();
    try {
      command(
              () -> {
                if (status.state() != SessionState.COMPLETED
                    && status.state() != SessionState.STOPPED
                    && status.state() != SessionState.SUBMISSION_UNKNOWN
                    && status.state() != SessionState.IDLE) {
                  pauseInternal(PauseReason.RECOVERED);
                }
              })
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      /* Persisted pre-submit intent remains conservatively recoverable. */
    }
    closed = true;
    executor.shutdownNow();
  }

  public record Status(
      UUID runId,
      SessionState state,
      PauseReason reason,
      int modelCalls,
      int confirmedSubmissions,
      BigDecimal lastScore,
      FailureDiagnostic failureDiagnostic) {
    public Status(
        UUID runId,
        SessionState state,
        PauseReason reason,
        int modelCalls,
        int confirmedSubmissions,
        BigDecimal lastScore) {
      this(runId, state, reason, modelCalls, confirmedSubmissions, lastScore, null);
    }
  }
}
