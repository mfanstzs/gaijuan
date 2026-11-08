package app.deskgrade.core.application.session;

import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.AttemptPerformance.ModelOutcome;
import app.deskgrade.core.application.session.AttemptPerformance.ModelRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded, local timing for one answer. Phase times partition the total; inclusive child metrics
 * intentionally overlap and must not be added to the total. Timing never authorizes an action.
 */
public final class PerformanceTrace {
  private static final int MAX_MODEL_CALLS = 2;
  private static final ThreadLocal<PerformanceTrace> CURRENT = new ThreadLocal<>();

  private final UUID runId;
  private final UUID traceId = UUID.randomUUID();
  private final int ordinal;
  private final Instant startedAt;
  private final LongSupplier clock;
  private final long origin;
  private final EnumMap<SessionState, PhaseCounter> phases = new EnumMap<>(SessionState.class);
  private final EnumMap<Metric, MetricCounter> metrics = new EnumMap<>(Metric.class);
  private final LinkedHashMap<UUID, ModelCounter> modelCalls = new LinkedHashMap<>();
  private UUID attemptId;
  private SessionState phase;
  private long phaseStarted;
  private long elapsed;
  private Long acknowledged;
  private boolean finished;

  public PerformanceTrace(UUID runId, int ordinal, Instant startedAt, SessionState phase) {
    this(runId, ordinal, startedAt, phase, System::nanoTime);
  }

  PerformanceTrace(
      UUID runId, int ordinal, Instant startedAt, SessionState phase, LongSupplier clock) {
    this.runId = Objects.requireNonNull(runId);
    if (ordinal < 1) throw new IllegalArgumentException("Invalid attempt ordinal");
    this.ordinal = ordinal;
    this.startedAt = Objects.requireNonNull(startedAt);
    this.phase = Objects.requireNonNull(phase);
    this.clock = Objects.requireNonNull(clock);
    origin = clock.getAsLong();
    phases.put(phase, new PhaseCounter());
    phases.get(phase).count = 1;
  }

  public synchronized void phase(SessionState next) {
    if (finished || next == null || next == phase) return;
    long now = elapsedNow();
    closePhase(now);
    phase = next;
    phaseStarted = now;
    var counter = phases.computeIfAbsent(next, ignored -> new PhaseCounter());
    counter.count = increment(counter.count);
  }

  /** The first captured attempt owns this trace; a later callback cannot rebind its identity. */
  public synchronized void bindAttempt(UUID id) {
    if (!finished && attemptId == null && id != null) attemptId = id;
  }

  public synchronized void acknowledged() {
    if (!finished && acknowledged == null) acknowledged = elapsedNow();
  }

  /** Establishes a thread-confined context for nested platform/capture measurements. */
  public <T> T measure(Metric metric, Supplier<T> operation) {
    Objects.requireNonNull(operation);
    long start;
    synchronized (this) {
      start = finished || metric == null ? -1 : elapsedNow();
    }
    if (start < 0) return operation.get();
    PerformanceTrace previous = CURRENT.get();
    CURRENT.set(this);
    boolean successful = false;
    try {
      T result = operation.get();
      successful = true;
      return result;
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
      finishMeasurement(metric, start, successful);
    }
  }

  public void measure(Metric metric, Runnable operation) {
    Objects.requireNonNull(operation);
    measure(
        metric,
        () -> {
          operation.run();
          return null;
        });
  }

  /** With no active trace, executes directly without consulting a clock. */
  public static <T> T measureCurrent(Metric metric, Supplier<T> operation) {
    Objects.requireNonNull(operation);
    PerformanceTrace trace = CURRENT.get();
    return trace == null ? operation.get() : trace.measure(metric, operation);
  }

  public static void measureCurrent(Metric metric, Runnable operation) {
    Objects.requireNonNull(operation);
    PerformanceTrace trace = CURRENT.get();
    if (trace == null) operation.run();
    else trace.measure(metric, operation);
  }

  /** Leaf hooks may contribute only bounded enum measurements, never arbitrary payloads. */
  public static void record(Metric metric, long nanos, boolean successful) {
    PerformanceTrace trace = CURRENT.get();
    if (trace != null) trace.recordMeasurement(metric, nanos, successful);
  }

  /** Returns a monotonic offset; duplicate or excess calls are ignored, not dispatched here. */
  public synchronized long beginModel(UUID callId, ModelRole role) {
    if (finished
        || callId == null
        || role == null
        || modelCalls.containsKey(callId)
        || modelCalls.size() >= MAX_MODEL_CALLS) return -1;
    long now = elapsedNow();
    modelCalls.put(callId, new ModelCounter(callId, role, now));
    return now;
  }

  /** Call on the provider completion thread, before posting work to the session executor. */
  public synchronized void modelCompleted(UUID callId, boolean successful) {
    if (finished) return;
    ModelCounter call = modelCalls.get(callId);
    if (call == null || call.completed != null) return;
    call.completed = elapsedNow();
    call.outcome = successful ? ModelOutcome.SUCCEEDED : ModelOutcome.FAILED;
  }

  /** Separately measures executor queue delay; duplicate/late callbacks cannot change a result. */
  public synchronized void modelCallbackEntered(UUID callId) {
    if (finished) return;
    ModelCounter call = modelCalls.get(callId);
    if (call == null || call.completed == null || call.callbackEntered != null) return;
    call.callbackEntered = elapsedNow();
  }

  /**
   * Produces exactly one immutable summary. A call still pending at stop is IN_FLIGHT, not failed;
   * a completed call whose callback is still queued records that queue wait through the stop.
   */
  public synchronized AttemptPerformance finish(
      Instant completedAt,
      SessionState finalState,
      PauseReason pauseReason,
      List<String> diagnosticCodes) {
    if (finished) return null;
    Objects.requireNonNull(completedAt);
    Objects.requireNonNull(finalState);
    Objects.requireNonNull(pauseReason);
    long now = elapsedNow();
    closePhase(now);
    var phaseTimings = new ArrayList<AttemptPerformance.PhaseTiming>(phases.size());
    phases.forEach(
        (state, value) ->
            phaseTimings.add(new AttemptPerformance.PhaseTiming(state, value.count, value.nanos)));
    var metricTimings = new ArrayList<AttemptPerformance.MetricTiming>(metrics.size());
    metrics.forEach(
        (metric, value) ->
            metricTimings.add(
                new AttemptPerformance.MetricTiming(
                    metric, value.count, value.nanos, value.failures)));
    var modelTimings = new ArrayList<AttemptPerformance.ModelTiming>(modelCalls.size());
    for (var call : modelCalls.values()) {
      long end = call.completed == null ? now : call.completed;
      long queue =
          call.completed == null
              ? 0
              : (call.callbackEntered == null ? now : call.callbackEntered) - call.completed;
      modelTimings.add(
          new AttemptPerformance.ModelTiming(
              call.callId, call.role, end - call.started, queue, call.outcome));
    }
    var safeCodes = new ArrayList<String>(AttemptPerformance.MAX_DIAGNOSTIC_CODES);
    if (diagnosticCodes != null) {
      for (String code : diagnosticCodes) {
        if (safeCodes.size() >= AttemptPerformance.MAX_DIAGNOSTIC_CODES) break;
        if (code != null && code.matches("[A-Z0-9_:-]{1,120}") && !safeCodes.contains(code))
          safeCodes.add(code);
      }
    }
    var result =
        new AttemptPerformance(
            runId,
            traceId,
            attemptId,
            ordinal,
            startedAt,
            completedAt,
            now,
            acknowledged,
            finalState,
            pauseReason,
            phaseTimings,
            metricTimings,
            modelTimings,
            safeCodes);
    finished = true;
    return result;
  }

  private synchronized void finishMeasurement(Metric metric, long start, boolean successful) {
    if (!finished) recordMeasurement(metric, elapsedNow() - start, successful);
  }

  private synchronized void recordMeasurement(Metric metric, long nanos, boolean successful) {
    if (finished || metric == null || nanos < 0) return;
    var counter = metrics.computeIfAbsent(metric, ignored -> new MetricCounter());
    counter.count = increment(counter.count);
    counter.nanos = add(counter.nanos, nanos);
    if (!successful) counter.failures = Math.min(counter.count, increment(counter.failures));
  }

  private long elapsedNow() {
    // Subtraction remains valid across nanoTime's signed wrap for realistic (<292 year) runs.
    long sample = clock.getAsLong() - origin;
    if (sample > elapsed) elapsed = sample;
    return elapsed;
  }

  private void closePhase(long now) {
    var counter = phases.get(phase);
    counter.nanos = add(counter.nanos, now - phaseStarted);
  }

  private static int increment(int value) {
    return value == Integer.MAX_VALUE ? value : value + 1;
  }

  private static long add(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }

  private static final class PhaseCounter {
    private int count;
    private long nanos;
  }

  private static final class MetricCounter {
    private int count;
    private long nanos;
    private int failures;
  }

  private static final class ModelCounter {
    private final UUID callId;
    private final ModelRole role;
    private final long started;
    private Long completed;
    private Long callbackEntered;
    private ModelOutcome outcome = ModelOutcome.IN_FLIGHT;

    private ModelCounter(UUID callId, ModelRole role, long started) {
      this.callId = callId;
      this.role = role;
      this.started = started;
    }
  }
}
