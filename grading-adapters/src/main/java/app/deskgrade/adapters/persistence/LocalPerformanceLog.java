package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.AttemptPerformance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * One forced JSONL append for a finished timing trace, not for every observation/phase. It never
 * opens a capture, sends a request, replays a platform action, or changes the authoritative ledger.
 */
public final class LocalPerformanceLog implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final LocalGradingLog writer;

  public LocalPerformanceLog(Path directory) {
    this(directory, Clock.systemDefaultZone(), LocalGradingLog.MAX_FILE_BYTES);
  }

  LocalPerformanceLog(Path directory, Clock clock, long maxFileBytes) {
    writer = new LocalGradingLog(directory, clock, maxFileBytes, "performance");
  }

  public void append(AttemptPerformance summary) {
    Objects.requireNonNull(summary);
    var event = JSON.createObjectNode();
    event.put("schemaVersion", 1);
    event.put("event", "ATTEMPT_TIMING_SUMMARY");
    event.put("storage", "LOCAL_ONLY");
    event.put("timingClock", "MONOTONIC");
    event.put("durationUnit", "milliseconds");
    event.put("phaseExclusive", true);
    event.put("nestedMetricsOverlap", true);
    event.put("runId", summary.runId().toString());
    event.put("traceId", summary.traceId().toString());
    event.put("attemptId", summary.attemptId() == null ? null : summary.attemptId().toString());
    event.put("ordinal", summary.ordinal());
    event.put("startedAt", summary.startedAt().toString());
    event.put("completedAt", summary.completedAt().toString());
    event.put("totalMs", millis(summary.totalNanos()));
    event.put(
        "untilAcknowledgedMs",
        summary.untilAcknowledgedNanos() == null ? null : millis(summary.untilAcknowledgedNanos()));
    event.put("finalState", summary.finalState().name());
    event.put("pauseReason", summary.pauseReason().name());
    var phases = event.putArray("phases");
    for (var phase : summary.phases()) {
      var item = phases.addObject();
      item.put("state", phase.state().name());
      item.put("count", phase.count());
      item.put("elapsedMs", millis(phase.nanos()));
    }
    var metrics = event.putArray("metrics");
    for (var metric : summary.metrics()) {
      var item = metrics.addObject();
      item.put("metric", metric.metric().name());
      item.put("count", metric.count());
      item.put("failures", metric.failures());
      item.put("elapsedMs", millis(metric.nanos()));
    }
    var modelCalls = event.putArray("modelCalls");
    for (var call : summary.modelCalls()) {
      var item = modelCalls.addObject();
      item.put("callId", call.callId().toString());
      item.put("role", call.role().name());
      item.put("elapsedMs", millis(call.nanos()));
      item.put("callbackQueueMs", millis(call.callbackQueueNanos()));
      item.put("outcome", call.outcome().name());
    }
    var codes = event.putArray("diagnosticCodes");
    summary.diagnosticCodes().forEach(codes::add);
    writer.append(event);
  }

  @Override
  public void close() {
    writer.close();
  }

  private static BigDecimal millis(long nanos) {
    return BigDecimal.valueOf(nanos, 6).setScale(3, RoundingMode.HALF_UP);
  }
}
