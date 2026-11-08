package app.deskgrade.adapters.windows.manual;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.SafeInputException;
import app.deskgrade.core.application.session.ActionPermission;
import app.deskgrade.core.application.session.AttemptPerformance;
import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.PerformanceTrace;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.ChangeScope;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.Checkpoint;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.TripleConsistency;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ManualCoordinatePlatformAdapterTest {
  private static final ActionPermission ALLOW = () -> {};

  @Test
  void coherentEvidenceUsesThreeFreshFramesWithoutNestedDuplicateRecaptures() {
    Fixture fixture = new Fixture(false);
    var current = fixture.ready();
    int answersBefore = fixture.io.captures;
    int identitiesBefore = fixture.io.guardCaptures;
    var evidence = fixture.adapter.captureEvidence(current);
    assertEquals(1, fixture.io.captures - answersBefore);
    assertEquals(2, fixture.io.guardCaptures - identitiesBefore);
    assertArrayEquals(evidence.answer().png(), evidence.identity().capture().png());
    assertEquals(current.answerId(), evidence.identity().answerId());
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void coherentEvidenceStillRejectsAnswerAtoBtoAAndStaleExpectedPage() {
    Fixture fixture = new Fixture(false);
    var current = fixture.ready();
    fixture.io.captureFrames = new String[] {"screen-a", "screen-b", "screen-a"};
    assertThrows(IllegalStateException.class, () -> fixture.adapter.captureEvidence(current));
    assertEquals(0, fixture.io.inputs);
    assertFalse(fixture.adapter.supportsUnattended());
  }

  @Test
  void passiveReadinessAndStableLocalIdentityNeverInjectInputOrClaimPlatformAcknowledgements() {
    Fixture fixture = new Fixture(false);
    assertTrue(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.inputs);
    var current = fixture.ready();
    assertTrue(current.answerId().startsWith("screen:"));
    assertNull(current.displayedScore());
    assertNull(current.acknowledgedAnswerId());
    assertEquals(SessionPlatform.ConfirmationKind.SCREEN_TRANSITION, current.confirmationKind());
    assertEquals(fixture.adapter.targetIdentity(), new Fixture(true).adapter.targetIdentity());
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void nativeTargetIdentityUsesStableProfileScopeAcrossWindowAndRevisionChanges() {
    var first = nativeBinding(101, 201, "2026-09-12T01:00:00Z");
    var restarted = nativeBinding(102, 202, "2026-09-12T02:00:00Z");
    var revisionOne = nativeCalibration("site-stable", 1);
    var revisionTwo = nativeCalibration("site-stable", 2);

    assertEquals(
        "manual-calibration:site-stable",
        ManualCoordinatePlatformAdapter.nativeTargetIdentity(first, revisionOne));
    assertEquals(
        ManualCoordinatePlatformAdapter.nativeTargetIdentity(first, revisionOne),
        ManualCoordinatePlatformAdapter.nativeTargetIdentity(restarted, revisionTwo));
    assertNotEquals(
        ManualCoordinatePlatformAdapter.nativeTargetIdentity(first, revisionOne),
        ManualCoordinatePlatformAdapter.nativeTargetIdentity(
            restarted, nativeCalibration("site-other", 1)));
  }

  @Test
  void combinedSubmitCountsOnlyAfterAStableChangedAnswerRegion() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.fillAndRead(first, "5");
    fixture.io.afterSubmit = "screen-b";
    fixture.adapter.submit(first, ALLOW);
    assertEquals(1, fixture.io.submits);
    assertEquals(2, fixture.io.reads); // Controller readback plus a fresh pre-submit recheck.
    var loading = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.LOADING, loading.page());
    assertEquals(0, loading.confirmedCount());
    fixture.clock.advance();
    var next = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.READY, next.page());
    assertNotEquals(first.answerId(), next.answerId());
    assertEquals(first.answerId(), next.acknowledgedAnswerId());
    assertEquals(1, next.confirmedCount());
    assertEquals(SessionPlatform.ConfirmationKind.SCREEN_TRANSITION, next.confirmationKind());
    assertEquals(1, fixture.adapter.observe().confirmedCount());
    assertThrows(IllegalStateException.class, () -> fixture.adapter.submit(first, ALLOW));
    assertEquals(1, fixture.io.submits);
  }

  @Test
  void combinedTransitionMixedFramesRemainPendingUntilANewStableScreenCrossesTheWindow() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.fillAndRead(first, "5");
    fixture.io.afterSubmit = "screen-b";
    fixture.adapter.submit(first, ALLOW);
    fixture.io.captureFrames = new String[] {"screen-a", "blank", "screen-b"};
    fixture.io.captureFrameIndex = 0;

    var transientView = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.LOADING, transientView.page());
    assertEquals(0, transientView.confirmedCount());
    assertTrue(fixture.adapter.supportsUnattended());

    // Time spent on a mixed transition is not credited to the stable-screen window.
    fixture.clock.advance();
    var firstStableObservation = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.LOADING, firstStableObservation.page());
    assertEquals(0, firstStableObservation.confirmedCount());
    fixture.clock.advance();
    var next = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.READY, next.page());
    assertEquals(first.answerId(), next.acknowledgedAnswerId());
    assertEquals(1, next.confirmedCount());
    assertEquals(1, fixture.io.submits);
  }

  @Test
  void persistentlyMixedCombinedTransitionNeverConfirmsOrFailsTheAdapter() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.fillAndRead(first, "5");
    fixture.io.afterSubmit = "screen-b";
    fixture.adapter.submit(first, ALLOW);

    for (int index = 0; index < 4; index++) {
      fixture.io.captureFrames = new String[] {"screen-a", "transition-" + index, "screen-b"};
      fixture.io.captureFrameIndex = 0;
      fixture.clock.now = fixture.clock.now.plusSeconds(10);
      var waiting = fixture.adapter.observe();
      assertEquals(SessionPlatform.Page.LOADING, waiting.page());
      assertEquals(0, waiting.confirmedCount());
      assertNull(waiting.acknowledgedAnswerId());
    }
    assertTrue(fixture.adapter.supportsUnattended());
    assertEquals(1, fixture.io.submits);
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void explicitVerifyKeepsStrictTripleCaptureChecksDuringAPassiveTransitionPhase() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.fillAndRead(first, "5");
    fixture.io.afterSubmit = "screen-b";
    fixture.adapter.submit(first, ALLOW);
    fixture.io.captureFrames = new String[] {"screen-a", "screen-b", "screen-a"};
    fixture.io.captureFrameIndex = 0;

    fixture.adapter.diagnosticCheckpoint(Checkpoint.BEFORE_SUBMIT_VERIFY);
    SafeInputException failure =
        assertThrows(SafeInputException.class, () -> fixture.adapter.verify(first));
    assertEquals(ChangeScope.UNAVAILABLE, failure.screenChangeDiagnostic().changeScope());
    assertEquals(TripleConsistency.MIXED, failure.screenChangeDiagnostic().tripleConsistency());
    assertEquals(Checkpoint.BEFORE_SUBMIT_VERIFY, failure.screenChangeDiagnostic().checkpoint());
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(1, fixture.io.submits);
  }

  @Test
  void coherentChangedScreenReportsBoundedPixelsCheckpointAndElapsedTimeWithoutRecapture() {
    Fixture fixture = new Fixture(false);
    fixture.io.pngFrames = true;
    var first = fixture.ready();
    fixture.adapter.fillScore(first, BigDecimal.valueOf(3), ALLOW);
    fixture.clock.now = fixture.clock.now.plusMillis(237);
    fixture.adapter.diagnosticCheckpoint(Checkpoint.AFTER_FILL_CHECK);
    int answersBefore = fixture.io.captures;
    int identitiesBefore = fixture.io.guardCaptures;
    fixture.io.frame = "screen-b";

    SafeInputException failure =
        assertThrows(SafeInputException.class, () -> fixture.adapter.verify(first));

    var diagnostic = failure.screenChangeDiagnostic();
    assertNotNull(diagnostic);
    assertEquals(Checkpoint.AFTER_FILL_CHECK, diagnostic.checkpoint());
    assertEquals(240, diagnostic.changedPixels());
    assertEquals(240, diagnostic.totalPixels());
    assertEquals(ChangeScope.BROAD, diagnostic.changeScope());
    assertEquals(TripleConsistency.COHERENT, diagnostic.tripleConsistency());
    assertEquals(237, diagnostic.millisSinceFill());
    assertEquals(1, fixture.io.captures - answersBefore);
    assertEquals(2, fixture.io.guardCaptures - identitiesBefore);
    assertFalse(fixture.adapter.supportsUnattended());
  }

  @Test
  void mixedTripleUsesTheFirstFrameThatDiffersFromBaselineForItsBoundedDiagnostic() {
    Fixture fixture = new Fixture(false);
    fixture.io.pngFrames = true;
    var first = fixture.ready();
    fixture.adapter.diagnosticCheckpoint(Checkpoint.BEFORE_READBACK);
    // The answer (middle) frame equals baseline. The first frame is the actual changed screen.
    fixture.io.captureFrames = new String[] {"screen-b", "screen-a", "screen-a"};
    fixture.io.captureFrameIndex = 0;

    SafeInputException failure =
        assertThrows(SafeInputException.class, () -> fixture.adapter.verify(first));

    var diagnostic = failure.screenChangeDiagnostic();
    assertNotNull(diagnostic);
    assertEquals(Checkpoint.BEFORE_READBACK, diagnostic.checkpoint());
    assertEquals(240, diagnostic.changedPixels());
    assertEquals(240, diagnostic.totalPixels());
    assertEquals(ChangeScope.BROAD, diagnostic.changeScope());
    assertEquals(TripleConsistency.MIXED, diagnostic.tripleConsistency());
    assertEquals(-1, diagnostic.millisSinceFill());
    assertEquals(3, fixture.io.captureFrameIndex);
    assertFalse(fixture.adapter.supportsUnattended());
  }

  @Test
  void answerRegionAtoBtoAWithinOneTripleCaptureFailsClosed() {
    Fixture fixture = new Fixture(false);
    fixture.io.captureFrames = new String[] {"screen-a", "screen-b", "screen-a"};

    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    assertEquals(3, fixture.io.captureFrameIndex);
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void threeIdenticalAnswerRegionCapturesRemainUsable() {
    Fixture fixture = new Fixture(false);
    fixture.io.captureFrames = new String[] {"screen-a", "screen-a", "screen-a"};

    assertEquals(SessionPlatform.Page.LOADING, fixture.adapter.observe().page());
    assertEquals(3, fixture.io.captureFrameIndex);
    assertTrue(fixture.adapter.supportsUnattended());
    fixture.clock.advance();
    assertEquals(SessionPlatform.Page.READY, fixture.adapter.observe().page());
  }

  @Test
  void identicalBlankHashInCombinedModeNeverConfirmsASecondStudent() {
    Fixture fixture = new Fixture(false);
    fixture.io.pngFrames = true;
    var first = fixture.ready();
    fixture.fillAndRead(first, "0");
    fixture.adapter.submit(first, ALLOW);
    for (int i = 0; i < 5; i++) {
      fixture.clock.now = fixture.clock.now.plusSeconds(3600);
      var view = fixture.adapter.observe();
      assertEquals(SessionPlatform.Page.LOADING, view.page());
      assertEquals(0, view.confirmedCount());
      assertNull(view.acknowledgedAnswerId());
    }
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void separateSubmitIsExplicitlyClickEvidenceAndDoesNotNavigateTheFinalPage() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2.5");
    fixture.adapter.submit(first, ALLOW);
    var submitted = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.SUBMITTED, submitted.page());
    assertEquals(1, submitted.confirmedCount());
    assertEquals(SessionPlatform.ConfirmationKind.INPUT_DISPATCHED, submitted.confirmationKind());
    assertEquals(first.answerId(), submitted.acknowledgedAnswerId());
    assertEquals(0, fixture.io.nexts);
    assertThrows(
        IllegalStateException.class, () -> fixture.adapter.fillScore(first, BigDecimal.ONE, ALLOW));
    fixture.io.afterNext = "screen-b";
    fixture.adapter.next(submitted, ALLOW);
    assertThrows(IllegalStateException.class, () -> fixture.adapter.next(submitted, ALLOW));
    assertEquals(SessionPlatform.Page.LOADING, fixture.adapter.observe().page());
    fixture.clock.advance();
    var next = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.READY, next.page());
    assertEquals(1, next.confirmedCount());
    assertEquals(1, fixture.io.nexts);
  }

  @Test
  void identicalBlankHashAfterConfiguredNextNeverBecomesANewReadyStudent() {
    Fixture fixture = new Fixture(true);
    fixture.io.pngFrames = true;
    var first = fixture.ready();
    fixture.fillAndRead(first, "0");
    fixture.adapter.submit(first, ALLOW);
    var submitted = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.SUBMITTED, submitted.page());
    assertEquals(1, submitted.confirmedCount());

    fixture.adapter.next(submitted, ALLOW);
    for (int index = 0; index < 5; index++) {
      fixture.clock.now = fixture.clock.now.plusSeconds(3600);
      var waiting = fixture.adapter.observe();
      assertEquals(SessionPlatform.Page.SUBMITTED, waiting.page());
      assertEquals(1, waiting.confirmedCount());
      assertEquals(first.answerId(), waiting.answerId());
    }
    assertEquals(1, fixture.io.submits);
    assertEquals(1, fixture.io.nexts);
    assertTrue(fixture.adapter.supportsUnattended());
  }

  @Test
  void nextTransitionMixedFramesRemainPendingUntilANewStableScreenCrossesTheWindow() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2.5");
    fixture.adapter.submit(first, ALLOW);
    var submitted = fixture.adapter.observe();
    fixture.io.afterNext = "screen-b";
    fixture.adapter.next(submitted, ALLOW);
    fixture.io.captureFrames = new String[] {"screen-a", "blank", "screen-b"};
    fixture.io.captureFrameIndex = 0;

    assertEquals(SessionPlatform.Page.LOADING, fixture.adapter.observe().page());
    assertTrue(fixture.adapter.supportsUnattended());
    fixture.clock.advance();
    assertEquals(SessionPlatform.Page.LOADING, fixture.adapter.observe().page());
    fixture.clock.advance();
    var next = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.READY, next.page());
    assertEquals(1, next.confirmedCount());
    assertEquals(1, fixture.io.nexts);
  }

  @Test
  void separateModeRejectsAChangedPageUntilItsConfiguredNextActionIsAuthorized() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    assertEquals(SessionPlatform.Page.SUBMITTED, fixture.adapter.observe().page());
    fixture.io.frame = "screen-b";
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void separateModeNeverClicksNextWhenSubmissionAcknowledgementDoesNotChange() {
    Fixture fixture = new Fixture(true);
    fixture.io.afterSubmitAck = null;
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    for (int index = 0; index < 3; index++) {
      var waiting = fixture.adapter.observe();
      assertEquals(SessionPlatform.Page.LOADING, waiting.page());
      assertEquals(0, waiting.confirmedCount());
    }
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void unstableSubmissionAcknowledgementRemainsPendingBeforeNext() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    fixture.io.changeAckEveryCapture = true;
    for (int index = 0; index < 3; index++) {
      var waiting = fixture.adapter.observe();
      assertEquals(SessionPlatform.Page.LOADING, waiting.page());
      assertEquals(0, waiting.confirmedCount());
    }
    assertTrue(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void mixedAnswerFramesWhileWaitingForAcknowledgementRemainPending() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    int baselineAckCaptures = fixture.io.ackCaptures;
    fixture.io.captureFrames = new String[] {"screen-a", "blank", "screen-a"};
    fixture.io.captureFrameIndex = 0;

    var waiting = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.LOADING, waiting.page());
    assertEquals(0, waiting.confirmedCount());
    assertEquals(baselineAckCaptures, fixture.io.ackCaptures);
    assertTrue(fixture.adapter.supportsUnattended());

    var submitted = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.SUBMITTED, submitted.page());
    assertEquals(1, submitted.confirmedCount());
  }

  @Test
  void stableAnswerReplacementBeforeAcknowledgementStillFailsClosed() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    fixture.io.frame = "screen-b";

    assertEquals(SessionPlatform.Page.LOADING, fixture.adapter.observe().page());
    fixture.clock.advance();
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void pageChangingDuringAcknowledgementStabilityWindowFailsBeforeConfirmation() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.fillAndRead(first, "2");
    fixture.adapter.submit(first, ALLOW);
    fixture.io.changeScreenDuringAckWait = true;
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.nexts);
  }

  @Test
  void unsolicitedAnswerReplacementStopsBeforeAnyFillOrSubmission() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.io.frame = "unexpected";
    assertThrows(
        IllegalStateException.class, () -> fixture.adapter.fillScore(first, BigDecimal.ONE, ALLOW));
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void requestedScoreIsNeverUsedAsTheReadbackAndChangedScoreCannotSubmit() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.adapter.fillScore(first, BigDecimal.valueOf(5), ALLOW);
    assertNull(fixture.adapter.observe().displayedScore());
    fixture.io.controlValue = BigDecimal.valueOf(4);
    assertEquals(BigDecimal.valueOf(4), fixture.adapter.readScore(first, ALLOW));
    assertThrows(IllegalStateException.class, () -> fixture.adapter.submit(first, ALLOW));
    fixture.io.controlValue = BigDecimal.valueOf(5);
    fixture.adapter.readScore(first, ALLOW);
    fixture.io.controlValue = BigDecimal.valueOf(4);
    assertThrows(IllegalStateException.class, () -> fixture.adapter.submit(first, ALLOW));
    assertEquals(0, fixture.io.submits);
  }

  @Test
  void revokedGateAndUnavailableReadbackNeverReachTheSubmitButton() {
    Fixture revoked = new Fixture(false);
    var first = revoked.ready();
    assertThrows(
        IllegalStateException.class,
        () ->
            revoked.adapter.fillScore(
                first,
                BigDecimal.ONE,
                () -> {
                  throw new IllegalStateException();
                }));
    assertEquals(0, revoked.io.inputs);
    Fixture unreadable = new Fixture(false);
    var current = unreadable.ready();
    unreadable.adapter.fillScore(current, BigDecimal.ONE, ALLOW);
    unreadable.io.controlValue = null;
    assertThrows(IllegalStateException.class, () -> unreadable.adapter.readScore(current, ALLOW));
    assertFalse(unreadable.adapter.supportsUnattended());
    assertEquals(0, unreadable.io.submits);
  }

  @Test
  void temporaryFocusLossPreservesReadyAndAlreadySubmittedPagesForExplicitResume() {
    Fixture fixture = new Fixture(true);
    var first = fixture.ready();
    fixture.io.windowSafe = false;
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    assertFalse(fixture.adapter.supportsUnattended());
    fixture.io.windowSafe = true;
    fixture.adapter.verify(first);
    fixture.fillAndRead(first, "5");
    fixture.adapter.submit(first, ALLOW);
    fixture.io.windowSafe = false;
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
    fixture.io.windowSafe = true;
    var submitted = fixture.adapter.observe();
    assertEquals(SessionPlatform.Page.SUBMITTED, submitted.page());
    assertEquals(1, submitted.confirmedCount());
    assertThrows(
        IllegalStateException.class, () -> fixture.adapter.fillScore(first, BigDecimal.ONE, ALLOW));
    assertEquals(1, fixture.io.submits);
  }

  @Test
  void answerChangingDuringCaptureCannotBeSentToTheModelAsTheExpectedAnswer() {
    Fixture fixture = new Fixture(false);
    var current = fixture.ready();
    fixture.io.changeOnCapture = fixture.io.captures + 2;
    assertThrows(IllegalStateException.class, () -> fixture.adapter.capture(current));
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void answerChangingOnFinalGuardCaptureFailsClosedBeforeInput() {
    Fixture fixture = new Fixture(false);
    var current = fixture.ready();
    fixture.io.changeOnGuardCapture = fixture.io.guardCaptures + 2;
    assertThrows(IllegalStateException.class, () -> fixture.adapter.captureIdentity(current));
    assertFalse(fixture.adapter.supportsUnattended());
    assertEquals(0, fixture.io.inputs);
  }

  @Test
  void returningToAnAlreadyProcessedImageIsNotAcceptedAsAnotherStudent() {
    Fixture fixture = new Fixture(false);
    var first = fixture.ready();
    fixture.fillAndRead(first, "5");
    fixture.io.afterSubmit = "screen-b";
    fixture.adapter.submit(first, ALLOW);
    fixture.adapter.observe();
    fixture.clock.advance();
    var second = fixture.adapter.observe();
    fixture.fillAndRead(second, "4");
    fixture.io.afterSubmit = "screen-a";
    fixture.adapter.submit(second, ALLOW);
    fixture.adapter.observe();
    fixture.clock.advance();
    assertThrows(IllegalStateException.class, fixture.adapter::observe);
  }

  @Test
  void tracedFinalReadbackAndSubmitRetainExactlyTheOriginalGuardAndInputCounts() {
    Fixture baseline = new Fixture(false);
    var baselineView = baseline.ready();
    baseline.fillAndRead(baselineView, "5");
    baseline.adapter.submit(baselineView, ALLOW);
    Fixture measured = new Fixture(false);
    var view = measured.ready();
    measured.fillAndRead(view, "5");
    var trace =
        new PerformanceTrace(java.util.UUID.randomUUID(), 1, Instant.EPOCH, SessionState.CAPTURING);
    trace.measure(Metric.SUBMIT_ACTION, () -> measured.adapter.submit(view, ALLOW));
    var summary =
        trace.finish(
            Instant.EPOCH.plusSeconds(1),
            SessionState.SUBMIT_SENT,
            PauseReason.NONE,
            java.util.List.of());
    assertEquals(1, metric(summary, Metric.FINAL_SCORE_READBACK).count());
    assertEquals(0, metric(summary, Metric.FINAL_SCORE_READBACK).failures());
    assertEquals(1, metric(summary, Metric.NATIVE_SUBMIT_CLICK).count());
    assertEquals(0, metric(summary, Metric.NATIVE_SUBMIT_CLICK).failures());
    assertEquals(baseline.io.inputs, measured.io.inputs);
    assertEquals(baseline.io.reads, measured.io.reads);
    assertEquals(baseline.io.submits, measured.io.submits);
    assertEquals(baseline.io.captures, measured.io.captures);
    assertEquals(baseline.io.guardCaptures, measured.io.guardCaptures);
  }

  @Test
  void tracedFinalReadbackDenialStillPreventsSubmissionAndPreservesSafeFailureChain() {
    Fixture fixture = new Fixture(false);
    var view = fixture.ready();
    fixture.fillAndRead(view, "5");
    var marker = SafeInputException.code("TEST_PERMISSION_DENIED");
    ActionPermission denied =
        () -> {
          throw marker;
        };
    var trace =
        new PerformanceTrace(java.util.UUID.randomUUID(), 1, Instant.EPOCH, SessionState.CAPTURING);
    var failure =
        assertThrows(
            SafeInputException.class,
            () -> trace.measure(Metric.SUBMIT_ACTION, () -> fixture.adapter.submit(view, denied)));
    assertEquals("MANUAL_READBACK", failure.diagnosticCode());
    assertSame(marker, failure.getCause());
    var summary =
        trace.finish(
            Instant.EPOCH.plusSeconds(1),
            SessionState.STOPPED,
            PauseReason.LOCAL_FAILURE,
            java.util.List.of());
    assertEquals(1, metric(summary, Metric.FINAL_SCORE_READBACK).count());
    assertEquals(1, metric(summary, Metric.FINAL_SCORE_READBACK).failures());
    assertTrue(
        summary.metrics().stream()
            .noneMatch(value -> value.metric() == Metric.NATIVE_SUBMIT_CLICK));
    assertEquals(1, fixture.io.reads);
    assertEquals(0, fixture.io.submits);
  }

  @Test
  void tracedNativeSubmitFailureDoesNotRetryOrReplaceTheOriginalFailure() {
    Fixture fixture = new Fixture(false);
    var view = fixture.ready();
    fixture.fillAndRead(view, "5");
    var marker = SafeInputException.code("TEST_NATIVE_SUBMIT_FAILED");
    fixture.io.submitFailure = marker;
    var trace =
        new PerformanceTrace(java.util.UUID.randomUUID(), 1, Instant.EPOCH, SessionState.CAPTURING);
    var failure =
        assertThrows(
            SafeInputException.class,
            () -> trace.measure(Metric.SUBMIT_ACTION, () -> fixture.adapter.submit(view, ALLOW)));
    assertEquals("MANUAL_SUBMIT", failure.diagnosticCode());
    assertSame(marker, failure.getCause());
    var summary =
        trace.finish(
            Instant.EPOCH.plusSeconds(1),
            SessionState.STOPPED,
            PauseReason.LOCAL_FAILURE,
            java.util.List.of());
    assertEquals(0, metric(summary, Metric.FINAL_SCORE_READBACK).failures());
    assertEquals(1, metric(summary, Metric.NATIVE_SUBMIT_CLICK).count());
    assertEquals(1, metric(summary, Metric.NATIVE_SUBMIT_CLICK).failures());
    assertEquals(2, fixture.io.reads);
    assertEquals(0, fixture.io.submits);
  }

  private static AttemptPerformance.MetricTiming metric(AttemptPerformance summary, Metric metric) {
    return summary.metrics().stream()
        .filter(value -> value.metric() == metric)
        .findFirst()
        .orElseThrow();
  }

  private static final class Fixture {
    final MutableClock clock = new MutableClock();
    final FakeIo io = new FakeIo(clock);
    final ManualCoordinatePlatformAdapter adapter;

    Fixture(boolean separate) {
      if (separate) io.afterSubmitAck = "ack-confirmed";
      adapter = new ManualCoordinatePlatformAdapter(io, "saved-platform", separate, "q1", clock);
    }

    SessionPlatform.View ready() {
      assertEquals(SessionPlatform.Page.LOADING, adapter.observe().page());
      clock.advance();
      var ready = adapter.observe();
      assertEquals(SessionPlatform.Page.READY, ready.page());
      return ready;
    }

    void fillAndRead(SessionPlatform.View view, String score) {
      adapter.fillScore(view, new BigDecimal(score), ALLOW);
      assertEquals(new BigDecimal(score), adapter.readScore(view, ALLOW));
    }
  }

  private static ManualPlatformCalibration nativeCalibration(String id, long revision) {
    return new ManualPlatformCalibration(
        id,
        revision,
        "Synthetic website",
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

  private static TargetBinding nativeBinding(long hwnd, long pid, String startedAt) {
    var bounds = new PixelRect(0, 0, 1000, 700);
    var snapshot =
        new WindowSnapshot(
            new WindowIdentity(hwnd, pid, Instant.parse(startedAt)),
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
    var answer = new LogicalRect(20, 100, 500, 300);
    return new TargetBinding(snapshot, answer, new PixelRect(20, 100, 500, 300));
  }

  private static final class FakeIo implements ManualCoordinatePlatformAdapter.Io {
    final Clock clock;
    String frame = "screen-a", ackFrame = "ack-ready", afterSubmit, afterSubmitAck, afterNext;
    String[] captureFrames;
    boolean windowSafe = true;
    boolean pngFrames;
    boolean changeAckEveryCapture, changeScreenDuringAckWait;
    BigDecimal controlValue;
    RuntimeException submitFailure;
    int inputs,
        submits,
        nexts,
        reads,
        captures,
        guardCaptures,
        captureFrameIndex,
        ackCaptures,
        changeOnCapture = -1,
        changeOnGuardCapture = -1;

    FakeIo(Clock clock) {
      this.clock = clock;
    }

    @Override
    public void verifyWindow() {
      if (!windowSafe) throw new IllegalStateException("Window unavailable");
    }

    @Override
    public SessionPlatform.Capture captureAnswer() {
      captures++;
      if (captures == changeOnCapture) frame = "replaced-during-capture";
      return captured(nextCaptureFrame());
    }

    @Override
    public SessionPlatform.Capture captureIdentity() {
      guardCaptures++;
      if (guardCaptures == changeOnGuardCapture) frame = "replaced-on-guard-capture";
      return captured(nextCaptureFrame());
    }

    @Override
    public SessionPlatform.Capture captureSubmissionAck() {
      ackCaptures++;
      return captured(changeAckEveryCapture ? "ack-animation-" + ackCaptures : ackFrame);
    }

    @Override
    public void awaitStability(java.time.Duration duration) {
      if (changeScreenDuringAckWait) frame = "changed-during-ack";
    }

    @Override
    public void fill(BigDecimal score, ActionPermission permission) {
      permission.verify();
      inputs++;
      controlValue = score;
    }

    @Override
    public BigDecimal read(ActionPermission permission) {
      permission.verify();
      inputs++;
      reads++;
      return controlValue;
    }

    @Override
    public void submit(ActionPermission permission) {
      permission.verify();
      if (submitFailure != null) throw submitFailure;
      inputs++;
      submits++;
      if (afterSubmit != null) frame = afterSubmit;
      if (afterSubmitAck != null) ackFrame = afterSubmitAck;
    }

    @Override
    public void next(ActionPermission permission) {
      permission.verify();
      inputs++;
      nexts++;
      if (afterNext != null) frame = afterNext;
    }

    private String nextCaptureFrame() {
      if (captureFrames != null && captureFrameIndex < captureFrames.length)
        return captureFrames[captureFrameIndex++];
      return frame;
    }

    private SessionPlatform.Capture captured(String value) {
      try {
        byte[] png = pngFrames ? png(value) : value.getBytes(StandardCharsets.UTF_8);
        return new SessionPlatform.Capture(
            png,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)),
            clock.instant());
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    private static byte[] png(String value) throws Exception {
      var image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_ARGB);
      int base =
          switch (value) {
            case "screen-a", "screen-edge", "screen-local" -> 0xffffffff;
            case "screen-b" -> 0xff000000;
            default -> 0xff000000 | (value.hashCode() & 0x00ffffff);
          };
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, base);
      }
      if ("screen-edge".equals(value)) {
        for (int y = 0; y < image.getHeight(); y++) image.setRGB(0, y, 0xff000000);
      } else if ("screen-local".equals(value)) {
        for (int y = 5; y < 7; y++) {
          for (int x = 8; x < 11; x++) image.setRGB(x, y, 0xff000000);
        }
      }
      var bytes = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", bytes)) throw new AssertionError("PNG writer unavailable");
      return bytes.toByteArray();
    }
  }

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-09-12T00:00:00Z");

    void advance() {
      now = now.plusMillis(751);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
