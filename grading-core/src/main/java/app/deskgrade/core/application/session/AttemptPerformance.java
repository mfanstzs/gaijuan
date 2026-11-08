package app.deskgrade.core.application.session;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local-only, bounded timing metadata. Durations are measured with a monotonic clock; wall-clock
 * timestamps are display metadata and never authorize a platform action. Nested metric durations
 * may overlap, while phase durations are exclusive. This object never accepts student text, image
 * data, endpoint addresses, credentials, or exception messages.
 */
public record AttemptPerformance(
    UUID runId,
    UUID traceId,
    UUID attemptId,
    int ordinal,
    Instant startedAt,
    Instant completedAt,
    long totalNanos,
    Long untilAcknowledgedNanos,
    SessionState finalState,
    PauseReason pauseReason,
    List<PhaseTiming> phases,
    List<MetricTiming> metrics,
    List<ModelTiming> modelCalls,
    List<String> diagnosticCodes) {
  public static final int MAX_MODEL_CALLS = 16;
  public static final int MAX_DIAGNOSTIC_CODES = 8;

  public AttemptPerformance {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(traceId);
    Objects.requireNonNull(startedAt);
    Objects.requireNonNull(completedAt);
    Objects.requireNonNull(finalState);
    Objects.requireNonNull(pauseReason);
    if (ordinal < 1
        || totalNanos < 0
        || untilAcknowledgedNanos != null
            && (untilAcknowledgedNanos < 0 || untilAcknowledgedNanos > totalNanos))
      throw new IllegalArgumentException("Invalid attempt timing");
    phases = List.copyOf(phases);
    metrics = List.copyOf(metrics);
    modelCalls = List.copyOf(modelCalls);
    diagnosticCodes = List.copyOf(diagnosticCodes);
    if (phases.size() > SessionState.values().length
        || metrics.size() > Metric.values().length
        || modelCalls.size() > MAX_MODEL_CALLS
        || diagnosticCodes.size() > MAX_DIAGNOSTIC_CODES)
      throw new IllegalArgumentException("Too many timing measurements");
    var states = new HashSet<SessionState>();
    for (var phase : phases)
      if (!states.add(phase.state())) throw new IllegalArgumentException("Duplicate timing phase");
    var measured = new HashSet<Metric>();
    for (var metric : metrics)
      if (!measured.add(metric.metric()))
        throw new IllegalArgumentException("Duplicate timing metric");
    var calls = new HashSet<UUID>();
    for (var call : modelCalls)
      if (!calls.add(call.callId()))
        throw new IllegalArgumentException("Duplicate timed model call");
    for (String code : diagnosticCodes)
      if (!code.matches("[A-Z0-9_:-]{1,120}"))
        throw new IllegalArgumentException("Invalid timing diagnostic code");
  }

  public record PhaseTiming(SessionState state, int count, long nanos) {
    public PhaseTiming {
      Objects.requireNonNull(state);
      if (count < 0 || nanos < 0 || count == 0 && nanos != 0)
        throw new IllegalArgumentException("Invalid phase timing");
    }
  }

  public record MetricTiming(Metric metric, int count, long nanos, int failures) {
    public MetricTiming {
      Objects.requireNonNull(metric);
      if (count < 0 || nanos < 0 || failures < 0 || failures > count || count == 0 && nanos != 0)
        throw new IllegalArgumentException("Invalid metric timing");
    }
  }

  public record ModelTiming(
      UUID callId, ModelRole role, long nanos, long callbackQueueNanos, ModelOutcome outcome) {
    public ModelTiming {
      Objects.requireNonNull(callId);
      Objects.requireNonNull(role);
      Objects.requireNonNull(outcome);
      if (nanos < 0 || callbackQueueNanos < 0)
        throw new IllegalArgumentException("Invalid model timing");
    }
  }

  public enum ModelRole {
    PRIMARY,
    FALLBACK
  }

  public enum ModelOutcome {
    IN_FLIGHT,
    SUCCEEDED,
    FAILED
  }

  public enum Metric {
    OBSERVE,
    CAPTURE_EVIDENCE,
    EVIDENCE_SAVE,
    JOURNAL_WRITE,
    MODEL_DISPATCH,
    VALIDATE_GRADE,
    FILL_SCORE,
    READ_SCORE,
    VERIFY_TARGET,
    SUBMIT_ACTION,
    NEXT_ACTION,
    CAPTURE_TOTAL,
    CAPTURE_BOUNDARY_PREPARE,
    CAPTURE_SAFETY_BEFORE,
    CAPTURE_RAW,
    CAPTURE_SAFETY_AFTER,
    CAPTURE_PIXEL_COMPARE,
    CAPTURE_PNG_ENCODE,
    CAPTURE_PNG_REUSE,
    CAPTURE_DIGEST_AND_RESULT,
    CAPTURE_BOUNDARY_CLOSE,
    NATIVE_SUBMIT_CLICK,
    FINAL_SCORE_READBACK,
    CLIPBOARD_READ
  }
}
