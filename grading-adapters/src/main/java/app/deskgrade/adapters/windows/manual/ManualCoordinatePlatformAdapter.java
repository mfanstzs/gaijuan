package app.deskgrade.adapters.windows.manual;

import app.deskgrade.adapters.windows.CoordinateMapper;
import app.deskgrade.adapters.windows.SafeInputException;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowSafety;
import app.deskgrade.adapters.windows.WindowsInputDriver;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.core.application.session.ActionPermission;
import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.PerformanceTrace;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.Checkpoint;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.TripleConsistency;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.TargetBinding;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs only the user's explicitly bound coordinates. Screen identities describe local captures,
 * never an exam platform's business answer IDs. A click is never reported as a platform receipt.
 */
public final class ManualCoordinatePlatformAdapter implements SessionPlatform {
  public static final Duration SCREEN_STABILITY = Duration.ofMillis(750);

  interface Io {
    void verifyWindow();

    Capture captureAnswer();

    Capture captureIdentity();

    Capture captureSubmissionAck();

    default void awaitStability(Duration duration) {
      try {
        Thread.sleep(duration.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw SafeInputException.code("SUBMISSION_ACK_CAPTURE_INTERRUPTED");
      }
    }

    void fill(BigDecimal score, ActionPermission permission);

    BigDecimal read(ActionPermission permission);

    void submit(ActionPermission permission);

    void next(ActionPermission permission);
  }

  private enum Phase {
    INITIAL,
    READY,
    WAIT_COMBINED,
    WAIT_ACK,
    SUBMITTED,
    WAIT_NEXT,
    FAILED
  }

  private enum CaptureMode {
    STRICT,
    PASSIVE_TRANSITION
  }

  private final Io io;
  private final String targetIdentity;
  private final String answerEvidenceRegionIdentity;
  private final String questionId;
  private final boolean separateNext;
  private final Clock clock;
  private final ConfirmationKind confirmationKind;
  private final Set<String> seenScreens = new HashSet<>();
  private final Set<String> submittedScreens = new HashSet<>();
  private Phase phase = Phase.INITIAL;
  private String currentScreenKey;
  private String currentAnswerHash;
  private String currentIdentityHash;
  private String currentId;
  private String acknowledged;
  private long revision;
  private int processed;
  private String candidateHash;
  private Instant candidateSince;
  private int candidateObservations;
  private Capture latestAnswer;
  private Capture latestIdentity;
  private Capture baselineCapture;
  private Capture submissionAckBaseline;
  private BigDecimal filledScore;
  private BigDecimal actualReadback;
  private boolean nextClaimed;
  private Checkpoint diagnosticCheckpoint = Checkpoint.UNSPECIFIED;
  private Instant fillStartedAt;

  public ManualCoordinatePlatformAdapter(
      WindowsWindowService windows,
      TargetBinding binding,
      ManualPlatformCalibration calibration,
      WindowRegionCapture capture,
      String questionId,
      Clock clock) {
    this(
        windows,
        binding,
        calibration,
        capture,
        nativeTargetIdentity(binding, calibration),
        questionId,
        clock);
  }

  /**
   * Uses a durable, non-secret submission scope separately from the run-only coordinate identity.
   * The caller may therefore forget every rectangle while UNKNOWN-submit protection survives a
   * restart for the same website.
   */
  public ManualCoordinatePlatformAdapter(
      WindowsWindowService windows,
      TargetBinding binding,
      ManualPlatformCalibration calibration,
      WindowRegionCapture capture,
      String durableTargetIdentity,
      String questionId,
      Clock clock) {
    this(
        new NativeIo(windows, binding, calibration, capture),
        durableTargetIdentity,
        nativeAnswerEvidenceRegion(binding, calibration),
        calibration.navigationMode() == ManualPlatformCalibration.NavigationMode.CLICK_NEXT,
        questionId,
        clock);
  }

  ManualCoordinatePlatformAdapter(
      Io io, String calibrationId, boolean separateNext, String questionId, Clock clock) {
    this(
        io,
        "manual-calibration:" + calibrationId,
        "manual-answer-evidence-region:" + calibrationId,
        separateNext,
        questionId,
        clock);
  }

  private ManualCoordinatePlatformAdapter(
      Io io,
      String exactTargetIdentity,
      String answerEvidenceRegionIdentity,
      boolean separateNext,
      String questionId,
      Clock clock) {
    this.io = Objects.requireNonNull(io);
    if (exactTargetIdentity == null
        || exactTargetIdentity.isBlank()
        || exactTargetIdentity.length() > 2048
        || answerEvidenceRegionIdentity == null
        || answerEvidenceRegionIdentity.isBlank()
        || answerEvidenceRegionIdentity.length() > 2048
        || questionId == null
        || questionId.isBlank()
        || questionId.length() > 200) throw new IllegalArgumentException("手动坐标配置或题目标识无效。");
    this.targetIdentity = exactTargetIdentity;
    this.answerEvidenceRegionIdentity = answerEvidenceRegionIdentity;
    this.questionId = questionId;
    this.separateNext = separateNext;
    this.clock = Objects.requireNonNull(clock);
    this.confirmationKind =
        separateNext ? ConfirmationKind.INPUT_DISPATCHED : ConfirmationKind.SCREEN_TRANSITION;
  }

  @Override
  public String targetIdentity() {
    return targetIdentity;
  }

  @Override
  public synchronized boolean supportsUnattended() {
    if (phase == Phase.FAILED) return false;
    try {
      io.verifyWindow();
      return true;
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }

  @Override
  public Duration readinessStability() {
    return SCREEN_STABILITY;
  }

  @Override
  public synchronized void diagnosticCheckpoint(Checkpoint checkpoint) {
    diagnosticCheckpoint = Objects.requireNonNull(checkpoint);
  }

  @Override
  public synchronized View observe() {
    diagnosticCheckpoint = Checkpoint.OBSERVE;
    return observeInternal(true);
  }

  private View observeInternal(boolean passiveObservation) {
    requireOperational();
    // Focus/visibility may be restored by the user. A failed passive read does not erase the last
    // known submitted state or permit replay; the next explicit resume must verify the same screen.
    io.verifyWindow();
    CaptureMode captureMode =
        passiveObservation && passiveTransitionPhase()
            ? CaptureMode.PASSIVE_TRANSITION
            : CaptureMode.STRICT;
    Optional<ScreenCapture> captured = captureScreen(captureMode);
    if (captured.isEmpty()) {
      resetCandidate();
      return view(Page.LOADING);
    }
    ScreenCapture observed = captured.orElseThrow();
    try {
      latestAnswer = observed.answer();
      latestIdentity = observed.identity();
      String screenKey = screenKey(observed);
      boolean stable = stable(screenKey);
      if (phase == Phase.INITIAL) {
        if (stable) acceptNewScreen(observed);
        return view(phase == Phase.READY ? Page.READY : Page.LOADING);
      }
      boolean changed = !currentScreenKey.equals(screenKey);
      boolean identityChanged = !currentIdentityHash.equals(latestIdentity.sha256());
      if (phase == Phase.READY) {
        if (changed)
          throw screenChanged(
              "ANSWER_CHANGED_WITHOUT_ACTION", observed.answer(), TripleConsistency.COHERENT);
        return view(Page.READY);
      }
      if (phase == Phase.WAIT_COMBINED) {
        if (!changed || !identityChanged) {
          resetCandidate();
          return view(Page.LOADING);
        }
        if (stable) {
          acknowledged = currentId;
          processed = Math.addExact(processed, 1);
          acceptNewScreen(observed);
          return view(Page.READY);
        }
        return view(Page.LOADING);
      }
      if (phase == Phase.WAIT_ACK) {
        if (changed) {
          if (!identityChanged) {
            resetCandidate();
            return view(Page.LOADING);
          }
          if (stable)
            throw screenChanged(
                "SCREEN_CHANGED_BEFORE_SUBMISSION_ACK",
                observed.answer(),
                TripleConsistency.COHERENT);
          return view(Page.LOADING);
        }
        resetCandidate();
        Optional<Capture> capturedAcknowledgement = captureStableSubmissionAck(captureMode);
        if (capturedAcknowledgement.isEmpty()) return view(Page.LOADING);
        Capture acknowledgedCapture = capturedAcknowledgement.orElseThrow();
        if (!sameCapture(submissionAckBaseline, acknowledgedCapture)) {
          Optional<ScreenCapture> afterCapture = captureScreen(captureMode);
          if (afterCapture.isEmpty()) {
            resetCandidate();
            return view(Page.LOADING);
          }
          ScreenCapture afterAcknowledgement = afterCapture.orElseThrow();
          if (!sameCurrent(afterAcknowledgement))
            throw screenChanged(
                "SCREEN_CHANGED_DURING_SUBMISSION_ACK",
                afterAcknowledgement.answer(),
                TripleConsistency.COHERENT);
          acknowledged = currentId;
          processed = Math.addExact(processed, 1);
          phase = Phase.SUBMITTED;
          return view(Page.SUBMITTED);
        }
        return view(Page.LOADING);
      }
      if (phase == Phase.SUBMITTED) {
        if (changed)
          throw screenChanged(
              "SCREEN_CHANGED_BEFORE_NEXT_ACTION", observed.answer(), TripleConsistency.COHERENT);
        return view(Page.SUBMITTED);
      }
      if (phase == Phase.WAIT_NEXT) {
        if (!changed || !identityChanged) {
          resetCandidate();
          return view(changed ? Page.LOADING : Page.SUBMITTED);
        }
        if (stable) {
          acceptNewScreen(observed);
          return view(Page.READY);
        }
        return view(Page.LOADING);
      }
      throw failure();
    } catch (RuntimeException | LinkageError problem) {
      phase = Phase.FAILED;
      throw SafeInputException.stage("MANUAL_OBSERVE", problem);
    }
  }

  @Override
  public synchronized Capture capture(View expected) {
    useCaptureCheckpoint();
    verify(expected);
    if (expected.page() != Page.READY) throw failure();
    ScreenCapture result = captureScreen();
    if (!sameCurrent(result)) {
      phase = Phase.FAILED;
      throw screenChanged(
          "ANSWER_CHANGED_DURING_CAPTURE", result.answer(), TripleConsistency.COHERENT);
    }
    verify(expected);
    return result.answer();
  }

  @Override
  public synchronized IdentityCapture captureIdentity(View expected) {
    useCaptureCheckpoint();
    verify(expected);
    if (expected.page() != Page.READY) throw failure();
    ScreenCapture result = captureScreen();
    if (!sameCurrent(result)) {
      phase = Phase.FAILED;
      throw screenChanged(
          "ANSWER_CHANGED_DURING_CAPTURE", result.answer(), TripleConsistency.COHERENT);
    }
    verify(expected);
    return new IdentityCapture(
        targetIdentity,
        expected.answerId(),
        expected.questionId(),
        expected.pageRevision(),
        answerEvidenceRegionIdentity,
        result.identity());
  }

  @Override
  public synchronized CaptureEvidence captureEvidence(View expected) {
    useCaptureCheckpoint();
    requireOperational();
    Objects.requireNonNull(expected);
    if (phase != Phase.READY || !samePage(expected, view(Page.READY)))
      throw SafeInputException.code("EXPECTED_PAGE_MISMATCH");
    // Identity is the exact answer rectangle, not another independently changing region. Keep the
    // A/B/A triple-frame guard, but acquire it only once for this coherent evidence pair.
    ScreenCapture result = captureScreen();
    if (!sameCurrent(result)) {
      phase = Phase.FAILED;
      throw screenChanged(
          "ANSWER_CHANGED_DURING_CAPTURE", result.answer(), TripleConsistency.COHERENT);
    }
    io.verifyWindow();
    return new CaptureEvidence(
        result.answer(),
        new IdentityCapture(
            targetIdentity,
            expected.answerId(),
            expected.questionId(),
            expected.pageRevision(),
            answerEvidenceRegionIdentity,
            result.identity()));
  }

  @Override
  public synchronized void verify(View expected) {
    Objects.requireNonNull(expected);
    View current = observeInternal(false);
    if (!samePage(expected, current)
        || latestAnswer == null
        || latestIdentity == null
        || !sameCurrent(new ScreenCapture(latestAnswer, latestIdentity)))
      throw SafeInputException.code("EXPECTED_PAGE_MISMATCH");
  }

  @Override
  public synchronized void fillScore(View expected, BigDecimal score, ActionPermission permission) {
    diagnosticCheckpoint = Checkpoint.BEFORE_FILL;
    requireReady(expected);
    requireNumber(score);
    actualReadback = null;
    filledScore = null;
    try {
      fillStartedAt = clock.instant();
      diagnosticCheckpoint = Checkpoint.FILL_ACTION;
      io.fill(score, guarded(expected, permission, Checkpoint.FILL_ACTION));
      diagnosticCheckpoint = Checkpoint.AFTER_FILL_CHECK;
      verify(expected);
      filledScore = score;
    } catch (RuntimeException | LinkageError problem) {
      phase = Phase.FAILED;
      throw SafeInputException.stage("MANUAL_FILL", problem);
    }
  }

  @Override
  public synchronized BigDecimal readScore(View expected, ActionPermission permission) {
    return readScore(expected, permission, false);
  }

  private BigDecimal readScore(
      View expected, ActionPermission permission, boolean finalPreSubmitReadback) {
    diagnosticCheckpoint =
        finalPreSubmitReadback ? Checkpoint.BEFORE_SUBMIT_READBACK : Checkpoint.BEFORE_READBACK;
    requireReady(expected);
    if (filledScore == null) throw failure();
    try {
      Checkpoint actionCheckpoint =
          finalPreSubmitReadback ? Checkpoint.BEFORE_SUBMIT_READBACK : Checkpoint.READBACK_ACTION;
      diagnosticCheckpoint = actionCheckpoint;
      BigDecimal copied = io.read(guarded(expected, permission, actionCheckpoint));
      requireNumber(copied);
      diagnosticCheckpoint =
          finalPreSubmitReadback
              ? Checkpoint.BEFORE_SUBMIT_VERIFY
              : Checkpoint.AFTER_READBACK_CHECK;
      verify(expected);
      actualReadback = copied;
      diagnosticCheckpoint =
          finalPreSubmitReadback
              ? Checkpoint.BEFORE_SUBMIT_VERIFY
              : Checkpoint.AFTER_READBACK_VERIFY;
      return copied;
    } catch (RuntimeException | LinkageError problem) {
      actualReadback = null;
      phase = Phase.FAILED;
      throw SafeInputException.stage("MANUAL_READBACK", problem);
    }
  }

  @Override
  public synchronized void submit(View expected, ActionPermission permission) {
    diagnosticCheckpoint = Checkpoint.BEFORE_SUBMIT_VERIFY;
    requireReady(expected);
    if (filledScore == null || actualReadback == null || filledScore.compareTo(actualReadback) != 0)
      throw SafeInputException.code("SCORE_READBACK_MISMATCH");
    // Read the real control again immediately before dispatch; the requested score is not evidence.
    if (filledScore.compareTo(
            PerformanceTrace.measureCurrent(
                Metric.FINAL_SCORE_READBACK, () -> readScore(expected, permission, true)))
        != 0) throw failure();
    if (!submittedScreens.add(currentId)) throw failure();
    try {
      if (separateNext) {
        diagnosticCheckpoint = Checkpoint.BEFORE_SUBMIT_ACK;
        submissionAckBaseline =
            captureStableSubmissionAck(CaptureMode.STRICT)
                .orElseThrow(ManualCoordinatePlatformAdapter::failure);
      }
      diagnosticCheckpoint = Checkpoint.SUBMIT_ACTION;
      // The existing native submission call still owns its exact permission/window guards.
      PerformanceTrace.measureCurrent(
          Metric.NATIVE_SUBMIT_CLICK,
          () -> io.submit(guarded(expected, permission, Checkpoint.SUBMIT_ACTION)));
    } catch (RuntimeException | LinkageError problem) {
      phase = Phase.FAILED;
      throw SafeInputException.stage("MANUAL_SUBMIT", problem);
    }
    nextClaimed = false;
    resetCandidate();
    if (separateNext) {
      phase = Phase.WAIT_ACK;
    } else phase = Phase.WAIT_COMBINED;
  }

  @Override
  public synchronized void next(View submitted, ActionPermission permission) {
    diagnosticCheckpoint = Checkpoint.BEFORE_NEXT;
    if (!separateNext
        || phase != Phase.SUBMITTED
        || nextClaimed
        || submitted.page() != Page.SUBMITTED
        || !Objects.equals(submitted.answerId(), acknowledged)) throw failure();
    verify(submitted);
    nextClaimed = true;
    try {
      diagnosticCheckpoint = Checkpoint.NEXT_ACTION;
      io.next(guarded(submitted, permission, Checkpoint.NEXT_ACTION));
    } catch (RuntimeException | LinkageError problem) {
      phase = Phase.FAILED;
      throw SafeInputException.stage("MANUAL_NEXT", problem);
    }
    resetCandidate();
    phase = Phase.WAIT_NEXT;
  }

  private ActionPermission guarded(
      View expected, ActionPermission permission, Checkpoint checkpoint) {
    Objects.requireNonNull(permission);
    Objects.requireNonNull(checkpoint);
    return () ->
        permission.verifyForTarget(
            () -> {
              diagnosticCheckpoint = checkpoint;
              verify(expected);
            });
  }

  private void requireReady(View expected) {
    if (phase != Phase.READY || expected.page() != Page.READY) throw failure();
    verify(expected);
  }

  private boolean stable(String hash) {
    if (!Objects.equals(candidateHash, hash)) {
      candidateHash = hash;
      candidateSince = clock.instant();
      candidateObservations = 1;
    } else if (candidateObservations < 1000) candidateObservations++;
    return candidateObservations >= 2
        && !clock.instant().isBefore(candidateSince.plus(SCREEN_STABILITY));
  }

  private void resetCandidate() {
    candidateHash = null;
    candidateSince = null;
    candidateObservations = 0;
  }

  private void acceptNewScreen(ScreenCapture screen) {
    String screenKey = screenKey(screen);
    if (!seenScreens.add(screenKey)) throw failure();
    currentScreenKey = screenKey;
    currentAnswerHash = screen.answer().sha256();
    currentIdentityHash = screen.identity().sha256();
    baselineCapture = screen.answer();
    currentId = "screen:" + screenKey;
    revision = Math.addExact(revision, 1);
    filledScore = null;
    actualReadback = null;
    nextClaimed = false;
    fillStartedAt = null;
    phase = Phase.READY;
  }

  private View view(Page page) {
    return new View(
        currentId,
        questionId,
        revision,
        page,
        acknowledged,
        processed,
        actualReadback,
        separateNext,
        confirmationKind);
  }

  private static boolean samePage(View expected, View current) {
    return Objects.equals(expected.answerId(), current.answerId())
        && expected.questionId().equals(current.questionId())
        && expected.pageRevision() == current.pageRevision()
        && expected.page() == current.page()
        && expected.confirmedCount() == current.confirmedCount()
        && Objects.equals(expected.acknowledgedAnswerId(), current.acknowledgedAnswerId())
        && expected.separateNext() == current.separateNext()
        && expected.confirmationKind() == current.confirmationKind();
  }

  private void requireOperational() {
    if (phase == Phase.FAILED) throw failure();
  }

  private void useCaptureCheckpoint() {
    if (diagnosticCheckpoint != Checkpoint.BEFORE_SUBMIT_CAPTURE)
      diagnosticCheckpoint = Checkpoint.CAPTURE_EVIDENCE;
  }

  private SafeInputException screenChanged(
      String code, Capture observed, TripleConsistency tripleConsistency) {
    ScreenChangeDiagnostic diagnostic;
    try {
      BoundedPngDifference.Result difference =
          BoundedPngDifference.compare(
              baselineCapture == null ? null : baselineCapture.png(),
              observed == null ? null : observed.png());
      diagnostic =
          new ScreenChangeDiagnostic(
              diagnosticCheckpoint,
              difference.changedPixels(),
              difference.totalPixels(),
              difference.changeScope(),
              tripleConsistency,
              millisSinceFill());
    } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
      diagnostic =
          new ScreenChangeDiagnostic(
              diagnosticCheckpoint,
              -1,
              -1,
              ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE,
              tripleConsistency,
              millisSinceFill());
    }
    return SafeInputException.screenChange(code, diagnostic);
  }

  private long millisSinceFill() {
    if (fillStartedAt == null) return -1;
    try {
      long elapsed = Duration.between(fillStartedAt, clock.instant()).toMillis();
      return Math.max(0, Math.min(ScreenChangeDiagnostic.MAX_MILLIS_SINCE_FILL, elapsed));
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }

  private boolean passiveTransitionPhase() {
    return phase == Phase.WAIT_COMBINED || phase == Phase.WAIT_ACK || phase == Phase.WAIT_NEXT;
  }

  private static void requireNumber(BigDecimal score) {
    if (score == null || score.signum() < 0 || score.toPlainString().length() > 24) throw failure();
  }

  private static SafeInputException failure() {
    return SafeInputException.code("MANUAL_STATE_UNVERIFIED");
  }

  private ScreenCapture captureScreen() {
    return captureScreen(CaptureMode.STRICT).orElseThrow(ManualCoordinatePlatformAdapter::failure);
  }

  private Optional<ScreenCapture> captureScreen(CaptureMode mode) {
    io.verifyWindow();
    Capture identityBefore = requireCapture(io.captureIdentity());
    Capture answer = requireCapture(io.captureAnswer());
    Capture identityAfter = requireCapture(io.captureIdentity());
    if (!sameCapture(identityBefore, answer) || !sameCapture(answer, identityAfter)) {
      if (mode == CaptureMode.PASSIVE_TRANSITION) return Optional.empty();
      phase = Phase.FAILED;
      throw screenChanged(
          "ANSWER_CHANGED_DURING_CAPTURE",
          changedMixedCapture(identityBefore, answer, identityAfter),
          TripleConsistency.MIXED);
    }
    return Optional.of(new ScreenCapture(answer, identityAfter));
  }

  private Capture changedMixedCapture(
      Capture identityBefore, Capture answer, Capture identityAfter) {
    if (baselineCapture == null) return null;
    for (Capture candidate : List.of(identityBefore, answer, identityAfter)) {
      if (!sameCapture(baselineCapture, candidate)) return candidate;
    }
    return null;
  }

  private Optional<Capture> captureStableSubmissionAck(CaptureMode mode) {
    io.verifyWindow();
    Capture first = requireCapture(io.captureSubmissionAck());
    io.awaitStability(SCREEN_STABILITY);
    io.verifyWindow();
    Capture second = requireCapture(io.captureSubmissionAck());
    if (!sameCapture(first, second)) {
      if (mode == CaptureMode.PASSIVE_TRANSITION) return Optional.empty();
      throw SafeInputException.code("SUBMISSION_ACK_REGION_UNSTABLE");
    }
    return Optional.of(second);
  }

  private boolean sameCurrent(ScreenCapture screen) {
    return Objects.equals(currentScreenKey, screenKey(screen))
        && Objects.equals(currentAnswerHash, screen.answer().sha256())
        && Objects.equals(currentIdentityHash, screen.identity().sha256());
  }

  private static String screenKey(ScreenCapture screen) {
    return screen.answer().sha256() + ":" + screen.identity().sha256();
  }

  private static Capture requireCapture(Capture captured) {
    Objects.requireNonNull(captured);
    if (!captured.sha256().matches("[0-9a-f]{64}")
        || !captured.sha256().equals(sha256(captured.png())))
      throw SafeInputException.code("CAPTURE_HASH_INVALID");
    return captured;
  }

  private static boolean sameCapture(Capture first, Capture second) {
    return first.sha256().equals(second.sha256())
        && MessageDigest.isEqual(first.png(), second.png());
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static String nativeTargetIdentity(TargetBinding binding, ManualPlatformCalibration calibration) {
    Objects.requireNonNull(binding);
    Objects.requireNonNull(calibration);
    return "manual-calibration:" + calibration.id();
  }

  private static String nativeAnswerEvidenceRegion(
      TargetBinding binding, ManualPlatformCalibration calibration) {
    Objects.requireNonNull(binding);
    Objects.requireNonNull(calibration);
    LogicalRect logical = calibration.answerRegion();
    var physical = new CoordinateMapper().toScreen(logical, binding.calibratedWindow());
    var window = binding.calibratedWindow().identity();
    return "manual-answer-window:"
        + calibration.id()
        + ":"
        + calibration.revision()
        + ":"
        + Long.toUnsignedString(window.hwnd())
        + ":"
        + window.processId()
        + ":"
        + window.processStartedAt()
        + ":answer-evidence:"
        + Double.toHexString(logical.x())
        + ","
        + Double.toHexString(logical.y())
        + ","
        + Double.toHexString(logical.width())
        + ","
        + Double.toHexString(logical.height())
        + ":"
        + physical.x()
        + ","
        + physical.y()
        + ","
        + physical.width()
        + ","
        + physical.height();
  }

  private record ScreenCapture(Capture answer, Capture identity) {
    private ScreenCapture {
      Objects.requireNonNull(answer);
      Objects.requireNonNull(identity);
    }
  }

  private static final class NativeIo implements Io {
    private final WindowsWindowService windows;
    private final TargetBinding answer;
    private final TargetBinding identity;
    private final TargetBinding submissionAck;
    private final ManualPlatformCalibration calibration;
    private final WindowRegionCapture capture;
    private final WindowsInputDriver input;
    private final List<TargetBinding> guardedRegions;

    private NativeIo(
        WindowsWindowService windows,
        TargetBinding binding,
        ManualPlatformCalibration calibration,
        WindowRegionCapture capture) {
      this.windows = Objects.requireNonNull(windows);
      this.answer = Objects.requireNonNull(binding);
      this.calibration = Objects.requireNonNull(calibration);
      this.capture = Objects.requireNonNull(capture);
      if (!calibration.hasRequiredNavigationRegions()
          || !calibration.matchesLayout(binding.calibratedWindow())
          || !calibration.answerRegion().equals(binding.answerRegion()))
        throw new IllegalArgumentException("保存的完整坐标与当前绑定窗口不一致。");
      input = new WindowsInputDriver(windows);
      var mapper = new CoordinateMapper();
      identity = answer;
      submissionAck =
          calibration.submissionAckRegion() == null
              ? null
              : new TargetBinding(
                  binding.calibratedWindow(),
                  calibration.submissionAckRegion(),
                  mapper.toScreen(calibration.submissionAckRegion(), binding.calibratedWindow()));
      var regions = new ArrayList<TargetBinding>();
      regions.add(binding);
      if (submissionAck != null) regions.add(submissionAck);
      var controls =
          new ArrayList<LogicalRect>(
              List.of(calibration.scoreRegion(), calibration.submitRegion()));
      if (calibration.nextRegion() != null) controls.add(calibration.nextRegion());
      for (var control : controls)
        regions.add(
            new TargetBinding(
                binding.calibratedWindow(),
                control,
                mapper.toScreen(control, binding.calibratedWindow())));
      guardedRegions = List.copyOf(regions);
    }

    @Override
    public void verifyWindow() {
      for (int index = 0; index < guardedRegions.size(); index++) {
        var safety = windows.check(guardedRegions.get(index));
        if (safety != WindowSafety.Failure.NONE)
          throw SafeInputException.code("REGION_" + index + "_UNSAFE:" + safety.name());
      }
    }

    @Override
    public Capture captureAnswer() {
      verifyWindow();
      var image = capture.capture(answer);
      return new Capture(image.png(), image.sha256(), image.capturedAt());
    }

    @Override
    public Capture captureIdentity() {
      verifyWindow();
      var image = capture.capture(identity);
      return new Capture(image.png(), image.sha256(), image.capturedAt());
    }

    @Override
    public Capture captureSubmissionAck() {
      verifyWindow();
      if (submissionAck == null) throw failure();
      var image = capture.capture(submissionAck);
      return new Capture(image.png(), image.sha256(), image.capturedAt());
    }

    @Override
    public void fill(BigDecimal score, ActionPermission permission) {
      verifyWindow();
      input.fillNumber(answer, calibration.scoreRegion(), score, permission);
    }

    @Override
    public BigDecimal read(ActionPermission permission) {
      verifyWindow();
      return input.readNumber(answer, calibration.scoreRegion(), permission);
    }

    @Override
    public void submit(ActionPermission permission) {
      verifyWindow();
      input.click(answer, calibration.submitRegion(), permission);
    }

    @Override
    public void next(ActionPermission permission) {
      verifyWindow();
      if (calibration.nextRegion() == null) throw failure();
      input.click(answer, calibration.nextRegion(), permission);
    }
  }
}
