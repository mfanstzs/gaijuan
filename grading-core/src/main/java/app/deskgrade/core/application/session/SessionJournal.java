package app.deskgrade.core.application.session;

import app.deskgrade.core.domain.GradingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** All writes must be durable before a corresponding platform side effect is permitted. */
public interface SessionJournal extends AutoCloseable {
  /** Optional local diagnostics only; timings never authorize or replay a business action. */
  default boolean performanceEnabled() {
    return false;
  }

  /** Implementations must not turn a diagnostic write failure into a repeated submission. */
  default void recordPerformance(AttemptPerformance summary) {}

  void assertWritable();

  boolean hasUnknownSubmission(String targetIdentity);

  void beginRun(UUID runId, String targetIdentity, SessionSettings settings, Instant now);

  void recordConfiguration(UUID runId, SessionSettings settings, Instant now);

  void beginAttempt(
      UUID runId,
      UUID attemptId,
      int ordinal,
      SessionPlatform.View view,
      long epoch,
      String evidencePath,
      SessionPlatform.Capture capture,
      SessionSettings settings);

  void transition(UUID runId, UUID attemptId, SessionState state, PauseReason reason, Instant now);

  void beginModelCall(UUID callId, UUID runId, UUID attemptId, String profileId, Instant now);

  void completeModelCall(UUID callId, GradingResponse response, String safeErrorCode, Instant now);

  void saveGrade(UUID attemptId, GradingResponse response, BigDecimal total);

  /** Called only after actual platform readback equals the Java validated total. */
  default void recordScoreReadback(UUID attemptId, BigDecimal observedScore, Instant now) {}

  void confirmSubmission(UUID runId, UUID attemptId, Instant now);

  default void confirmSubmission(
      UUID runId, UUID attemptId, Instant now, SessionPlatform.ConfirmationKind kind) {
    if (kind != SessionPlatform.ConfirmationKind.PLATFORM_ACK)
      throw new UnsupportedOperationException(
          "Journal must persist the observed progress evidence");
    confirmSubmission(runId, attemptId, now);
  }

  List<RunRecord> recoverInterrupted(Instant now);

  List<RunRecord> runs();

  List<AttemptRecord> attempts(UUID runId);

  @Override
  void close();

  record RunRecord(
      UUID runId,
      String targetIdentity,
      SessionState state,
      PauseReason reason,
      int modelCalls,
      int confirmedSubmissions,
      Instant startedAt) {}

  record AttemptRecord(
      UUID attemptId,
      UUID runId,
      String answerId,
      String questionId,
      SessionState state,
      BigDecimal score,
      boolean submissionPrepared,
      boolean confirmed,
      String evidencePath,
      String resultJson) {}
}
