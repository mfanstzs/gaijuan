package app.deskgrade.core.application.session;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Screen-backed semantics. Test-only HTTP/ledger adapters must not be shipped as screen readers.
 */
public interface SessionPlatform {
  /** Local diagnostic context only; it cannot change authorization or be sent to a journal. */
  default void diagnosticCheckpoint(ScreenChangeDiagnostic.Checkpoint checkpoint) {}

  String targetIdentity();

  boolean supportsUnattended();

  default Duration readinessStability() {
    return Duration.ofMillis(500);
  }

  View observe();

  Capture capture(View expected);

  /**
   * Passively captures fresh visible continuity evidence for the current answer. Implementations
   * may reuse the calibrated answer region, but must bind {@code regionIdentity} to the exact
   * process, window and region used for the capture. Returning an answer or page identifier without
   * fresh pixels is not sufficient for unattended input.
   */
  IdentityCapture captureIdentity(View expected);

  /**
   * Coherent fresh answer and continuity proof. Adapters using one region for both may acquire one
   * strictly checked set of frames rather than recapturing it through two nested verification
   * paths.
   */
  default CaptureEvidence captureEvidence(View expected) {
    verify(expected);
    Capture answer = capture(expected);
    IdentityCapture identity = captureIdentity(expected);
    verify(expected);
    return new CaptureEvidence(answer, identity);
  }

  void verify(View expected);

  void fillScore(View expected, BigDecimal score, ActionPermission permission);

  /** Reading via selection/copy is an input action and must use the same revocable gate. */
  default BigDecimal readScore(View expected, ActionPermission permission) {
    permission.verify();
    return observe().displayedScore();
  }

  void submit(View expected, ActionPermission permission);

  void next(View submitted, ActionPermission permission);

  enum Page {
    READY,
    SUBMITTED,
    COMPLETED,
    LOADING,
    LOGIN_EXPIRED,
    POPUP,
    ERROR,
    UNKNOWN
  }

  /** Observable progress is not necessarily an acknowledgement from the grading service. */
  enum ConfirmationKind {
    PLATFORM_ACK,
    SCREEN_TRANSITION,
    INPUT_DISPATCHED
  }

  record View(
      String answerId,
      String questionId,
      long pageRevision,
      Page page,
      String acknowledgedAnswerId,
      int confirmedCount,
      BigDecimal displayedScore,
      boolean separateNext,
      ConfirmationKind confirmationKind) {
    public View(
        String answerId,
        String questionId,
        long pageRevision,
        Page page,
        String acknowledgedAnswerId,
        int confirmedCount,
        BigDecimal displayedScore,
        boolean separateNext) {
      this(
          answerId,
          questionId,
          pageRevision,
          page,
          acknowledgedAnswerId,
          confirmedCount,
          displayedScore,
          separateNext,
          ConfirmationKind.PLATFORM_ACK);
    }

    public View {
      Objects.requireNonNull(page);
      Objects.requireNonNull(questionId);
      Objects.requireNonNull(confirmationKind);
    }
  }

  record Capture(byte[] png, String sha256, Instant capturedAt) {
    public Capture {
      if (png == null || png.length == 0) {
        throw new IllegalArgumentException("Empty capture");
      }
      png = png.clone();
      Objects.requireNonNull(sha256);
      Objects.requireNonNull(capturedAt);
    }

    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  record CaptureEvidence(Capture answer, IdentityCapture identity) {
    public CaptureEvidence {
      Objects.requireNonNull(answer);
      Objects.requireNonNull(identity);
    }
  }

  /** Visible continuity evidence for the exact view and capture region. */
  record IdentityCapture(
      String targetIdentity,
      String answerId,
      String questionId,
      long pageRevision,
      String regionIdentity,
      Capture capture) {
    public IdentityCapture {
      if (targetIdentity == null
          || targetIdentity.isBlank()
          || targetIdentity.length() > 2048
          || targetIdentity.chars().anyMatch(Character::isISOControl)
          || answerId == null
          || answerId.isBlank()
          || answerId.length() > 2048
          || answerId.chars().anyMatch(Character::isISOControl)
          || questionId == null
          || questionId.isBlank()
          || questionId.length() > 200
          || questionId.chars().anyMatch(Character::isISOControl)
          || pageRevision < 1
          || regionIdentity == null
          || regionIdentity.isBlank()
          || regionIdentity.length() > 2048
          || regionIdentity.chars().anyMatch(Character::isISOControl)
          || capture == null) {
        throw new IllegalArgumentException("Invalid visible answer continuity capture");
      }
    }
  }
}
