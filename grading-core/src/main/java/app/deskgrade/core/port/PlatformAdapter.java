package app.deskgrade.core.port;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Semantic platform boundary only. No M0 implementation or automatic input permission exists.
 * Future write/submit/advance callers must use the single executor and action gate introduced in
 * M4. Unsupported capabilities must be omitted; sending an input event is never submission
 * evidence.
 */
public interface PlatformAdapter {
  Set<Capability> capabilities();

  CompletionStage<Observation> observe(CancellationToken cancellationToken);

  CompletionStage<Void> verifyBinding(AnswerBinding expected, CancellationToken cancellationToken);

  /**
   * Fresh visible identity evidence for the exact target, answer, question and calibrated region.
   * An unattended caller must compare this evidence, together with a fresh answer-pixel capture,
   * both before writing a score and immediately before submitting it.
   */
  CompletionStage<IdentityFingerprint> captureIdentity(
      AnswerBinding expected, CancellationToken cancellationToken);

  CompletionStage<Void> writeScore(
      AnswerBinding expected, BigDecimal score, CancellationToken cancellationToken);

  CompletionStage<BigDecimal> readBackScore(
      AnswerBinding expected, CancellationToken cancellationToken);

  CompletionStage<Void> submit(AnswerBinding expected, CancellationToken cancellationToken);

  CompletionStage<SubmissionStatus> observeSubmission(
      AnswerBinding expected, CancellationToken cancellationToken);

  CompletionStage<Void> advance(AnswerBinding submitted, CancellationToken cancellationToken);

  CompletionStage<Observation> observeNext(
      AnswerBinding previous, CancellationToken cancellationToken);

  enum Capability {
    READ_ANSWER,
    WRITE_SCORE,
    READ_BACK_SCORE,
    DETECT_SUBMISSION_ACK,
    DETECT_NEXT_READY,
    DETECT_COMPLETION,
    BIND_CURRENT_ANSWER
  }

  enum PageState {
    READY,
    LOADING,
    COMPLETED,
    UNKNOWN
  }

  enum SubmissionStatus {
    ACKNOWLEDGED,
    UNKNOWN
  }

  /** Initial binding identity. A screenshot hash alone is never an answer identity. */
  record AnswerBinding(String targetIdentity, String answerIdentity, long platformRevision) {
    public AnswerBinding {
      if (targetIdentity == null
          || targetIdentity.isBlank()
          || answerIdentity == null
          || answerIdentity.isBlank()
          || platformRevision < 1) {
        throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
      }
    }
  }

  record Observation(PageState state, Optional<AnswerBinding> answer, Instant observedAt) {
    public Observation {
      if (state == null
          || answer == null
          || observedAt == null
          || (state == PageState.READY && answer.isEmpty())) {
        throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
      }
    }
  }

  record IdentityFingerprint(
      AnswerBinding binding,
      String questionIdentity,
      String regionIdentity,
      String pixelSha256,
      Instant capturedAt) {
    public IdentityFingerprint {
      if (binding == null
          || questionIdentity == null
          || questionIdentity.isBlank()
          || questionIdentity.length() > 200
          || questionIdentity.chars().anyMatch(Character::isISOControl)
          || regionIdentity == null
          || regionIdentity.isBlank()
          || regionIdentity.length() > 2048
          || regionIdentity.chars().anyMatch(Character::isISOControl)
          || pixelSha256 == null
          || !pixelSha256.matches("[0-9a-f]{64}")
          || capturedAt == null) {
        throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
      }
    }
  }
}
