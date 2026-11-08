package app.deskgrade.core.application.session;

import static app.deskgrade.core.application.session.AttemptPerformance.Metric.CAPTURE_RAW;
import static app.deskgrade.core.application.session.AttemptPerformance.Metric.CAPTURE_TOTAL;
import static app.deskgrade.core.application.session.AttemptPerformance.Metric.FILL_SCORE;
import static app.deskgrade.core.application.session.AttemptPerformance.Metric.VERIFY_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.AttemptPerformance.ModelOutcome;
import app.deskgrade.core.application.session.AttemptPerformance.ModelRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PerformanceTraceTest {
  private static final Instant START = Instant.parse("2026-10-21T00:00:00Z");

  @Test
  void phasesPartitionTotalAndOnlyCountActualTransitions() {
    var clock = new AtomicLong(100);
    UUID runId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    var trace = new PerformanceTrace(runId, 2, START, SessionState.CAPTURING, clock::get);
    trace.bindAttempt(attemptId);
    trace.bindAttempt(UUID.randomUUID());
    clock.set(110);
    trace.phase(SessionState.CAPTURING);
    clock.set(120);
    trace.phase(SessionState.GRADING);
    clock.set(160);
    trace.phase(SessionState.VALIDATING);
    clock.set(165);
    trace.phase(SessionState.GRADING);
    clock.set(180);
    trace.acknowledged();
    clock.set(190);
    trace.acknowledged();
    clock.set(200);
    var summary =
        trace.finish(
            START.minusSeconds(1),
            SessionState.COMPLETED,
            PauseReason.NONE,
            List.of("OK", "contains private text", "OK"));

    assertEquals(runId, summary.runId());
    assertEquals(attemptId, summary.attemptId());
    assertEquals(2, summary.ordinal());
    assertEquals(100, summary.totalNanos());
    assertEquals(Long.valueOf(80), summary.untilAcknowledgedNanos());
    assertEquals(List.of("OK"), summary.diagnosticCodes());
    assertEquals(
        100, summary.phases().stream().mapToLong(AttemptPerformance.PhaseTiming::nanos).sum());
    assertEquals(
        new AttemptPerformance.PhaseTiming(SessionState.CAPTURING, 1, 20),
        summary.phases().stream()
            .filter(p -> p.state() == SessionState.CAPTURING)
            .findFirst()
            .orElseThrow());
    assertEquals(
        new AttemptPerformance.PhaseTiming(SessionState.GRADING, 2, 75),
        summary.phases().stream()
            .filter(p -> p.state() == SessionState.GRADING)
            .findFirst()
            .orElseThrow());
    assertNull(trace.finish(START, SessionState.FAILED, PauseReason.LOCAL_FAILURE, List.of()));
    assertThrows(UnsupportedOperationException.class, () -> summary.phases().clear());
  }

  @Test
  void nestedMeasurementsAreInclusiveAndRestoreThreadLocalAfterFailure() {
    var clock = new AtomicLong();
    var trace =
        new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.CAPTURING, clock::get);
    var original = new IllegalStateException("synthetic failure");
    var actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                trace.measure(
                    CAPTURE_TOTAL,
                    (Supplier<Void>)
                        () -> {
                          clock.set(5);
                          PerformanceTrace.measureCurrent(CAPTURE_RAW, () -> clock.set(15));
                          PerformanceTrace.record(VERIFY_TARGET, 3, true);
                          PerformanceTrace.record(VERIFY_TARGET, -1, false);
                          PerformanceTrace.record(null, 1, false);
                          clock.set(20);
                          throw original;
                        }));
    assertSame(original, actual);
    // No context survives the failing operation, so these cannot affect this trace.
    PerformanceTrace.record(VERIFY_TARGET, 500, false);
    assertEquals("outside", PerformanceTrace.measureCurrent(FILL_SCORE, () -> "outside"));
    clock.set(25);
    var summary = trace.finish(START, SessionState.FAILED, PauseReason.LOCAL_FAILURE, List.of());
    assertEquals(
        new AttemptPerformance.MetricTiming(CAPTURE_TOTAL, 1, 20, 1),
        metric(summary, CAPTURE_TOTAL));
    assertEquals(
        new AttemptPerformance.MetricTiming(CAPTURE_RAW, 1, 10, 0), metric(summary, CAPTURE_RAW));
    assertEquals(
        new AttemptPerformance.MetricTiming(VERIFY_TARGET, 1, 3, 0),
        metric(summary, VERIFY_TARGET));
    assertEquals(25, summary.totalNanos());
    assertTrue(
        summary.metrics().stream().mapToLong(AttemptPerformance.MetricTiming::nanos).sum()
            > summary.totalNanos());
    assertFalse(summary.metrics().stream().anyMatch(m -> m.metric() == FILL_SCORE));
  }

  @Test
  void nestedDifferentTracesRestoreTheirOwnContexts() {
    var clock = new AtomicLong();
    var outer =
        new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.CAPTURING, clock::get);
    var inner = new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.GRADING, clock::get);
    outer.measure(
        CAPTURE_TOTAL,
        () -> {
          PerformanceTrace.record(VERIFY_TARGET, 1, true);
          inner.measure(FILL_SCORE, () -> PerformanceTrace.record(VERIFY_TARGET, 2, true));
          PerformanceTrace.record(VERIFY_TARGET, 3, true);
        });
    var first = outer.finish(START, SessionState.COMPLETED, PauseReason.NONE, List.of());
    var second = inner.finish(START, SessionState.COMPLETED, PauseReason.NONE, List.of());
    assertEquals(
        new AttemptPerformance.MetricTiming(VERIFY_TARGET, 2, 4, 0), metric(first, VERIFY_TARGET));
    assertEquals(
        new AttemptPerformance.MetricTiming(VERIFY_TARGET, 1, 2, 0), metric(second, VERIFY_TARGET));
  }

  @Test
  void providerCompletionAndExecutorQueueAreSeparateAcrossThreads() throws InterruptedException {
    var clock = new AtomicLong();
    var trace = new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.GRADING, clock::get);
    UUID primary = UUID.randomUUID();
    UUID fallback = UUID.randomUUID();
    clock.set(10);
    assertEquals(10, trace.beginModel(primary, ModelRole.PRIMARY));
    clock.set(30);
    var completion = new Thread(() -> trace.modelCompleted(primary, false));
    completion.start();
    completion.join();
    clock.set(35);
    trace.modelCompleted(
        primary, true); // Duplicate callback must not overwrite failure or duration.
    clock.set(40);
    trace.modelCallbackEntered(primary);
    clock.set(45);
    trace.modelCallbackEntered(primary);
    trace.beginModel(fallback, ModelRole.FALLBACK);
    clock.set(60);
    trace.modelCompleted(fallback, true);
    clock.set(65);
    trace.modelCallbackEntered(fallback);
    clock.set(70);
    var summary = trace.finish(START, SessionState.COMPLETED, PauseReason.NONE, List.of());
    assertEquals(
        List.of(
            new AttemptPerformance.ModelTiming(
                primary, ModelRole.PRIMARY, 20, 10, ModelOutcome.FAILED),
            new AttemptPerformance.ModelTiming(
                fallback, ModelRole.FALLBACK, 15, 5, ModelOutcome.SUCCEEDED)),
        summary.modelCalls());
  }

  @Test
  void stopBoundsPendingCallsAndLateCallbacksCannotContaminateNextAnswer() {
    var clock = new AtomicLong();
    var trace = new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.GRADING, clock::get);
    UUID completed = UUID.randomUUID();
    UUID pending = UUID.randomUUID();
    clock.set(10);
    trace.beginModel(completed, ModelRole.PRIMARY);
    clock.set(20);
    trace.modelCompleted(completed, false);
    clock.set(30);
    trace.beginModel(pending, ModelRole.FALLBACK);
    assertEquals(-1, trace.beginModel(UUID.randomUUID(), ModelRole.FALLBACK));
    clock.set(50);
    var summary = trace.finish(START, SessionState.STOPPED, PauseReason.USER_STOPPED, List.of());
    assertEquals(
        List.of(
            new AttemptPerformance.ModelTiming(
                completed, ModelRole.PRIMARY, 10, 30, ModelOutcome.FAILED),
            new AttemptPerformance.ModelTiming(
                pending, ModelRole.FALLBACK, 20, 0, ModelOutcome.IN_FLIGHT)),
        summary.modelCalls());
    var next =
        new PerformanceTrace(UUID.randomUUID(), 2, START, SessionState.CAPTURING, clock::get);
    next.measure(
        CAPTURE_TOTAL,
        () -> {
          clock.set(80);
          trace.modelCompleted(pending, true);
          trace.modelCallbackEntered(completed);
          trace.phase(SessionState.COMPLETED);
          trace.acknowledged();
          trace.bindAttempt(UUID.randomUUID());
          assertEquals(-1, trace.beginModel(UUID.randomUUID(), ModelRole.PRIMARY));
        });
    var nextSummary = next.finish(START, SessionState.COMPLETED, PauseReason.NONE, List.of());
    assertTrue(nextSummary.modelCalls().isEmpty());
    assertNull(summary.attemptId());
    assertNull(summary.untilAcknowledgedNanos());
    assertEquals(ModelOutcome.IN_FLIGHT, summary.modelCalls().get(1).outcome());
  }

  @Test
  void finishingInsideMeasurementDoesNotMutateSnapshotAndFinishedTraceDoesNotReadClock() {
    var clock = new AtomicLong();
    var reads = new AtomicInteger();
    var trace =
        new PerformanceTrace(
            UUID.randomUUID(),
            1,
            START,
            SessionState.CAPTURING,
            () -> {
              reads.incrementAndGet();
              return clock.get();
            });
    AttemptPerformance summary =
        trace.measure(
            CAPTURE_TOTAL,
            () -> {
              clock.set(10);
              var result =
                  trace.finish(START, SessionState.STOPPED, PauseReason.USER_STOPPED, List.of());
              PerformanceTrace.record(VERIFY_TARGET, 1, true);
              PerformanceTrace.measureCurrent(CAPTURE_RAW, () -> clock.set(100));
              return result;
            });
    int readsAfterFinish = reads.get();
    assertTrue(summary.metrics().isEmpty());
    assertEquals(10, summary.totalNanos());
    trace.measure(CAPTURE_TOTAL, () -> clock.set(200));
    PerformanceTrace.measureCurrent(CAPTURE_RAW, () -> clock.set(300));
    assertEquals(readsAfterFinish, reads.get());
  }

  @Test
  void backwardClockSamplesCannotProduceNegativeTimings() {
    var clock = new AtomicLong(100);
    var trace =
        new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.CAPTURING, clock::get);
    clock.set(120);
    trace.phase(SessionState.GRADING);
    clock.set(110);
    trace.acknowledged();
    trace.measure(CAPTURE_RAW, () -> clock.set(90));
    var summary = trace.finish(START, SessionState.FAILED, PauseReason.LOCAL_FAILURE, List.of());
    assertEquals(20, summary.totalNanos());
    assertEquals(Long.valueOf(20), summary.untilAcknowledgedNanos());
    assertEquals(
        20, summary.phases().stream().mapToLong(AttemptPerformance.PhaseTiming::nanos).sum());
    assertEquals(0, metric(summary, CAPTURE_RAW).nanos());
  }

  @Test
  void enumCountersSaturateAndDiagnosticsRemainBounded() {
    var clock = new AtomicLong();
    var trace =
        new PerformanceTrace(UUID.randomUUID(), 1, START, SessionState.CAPTURING, clock::get);
    trace.measure(
        CAPTURE_TOTAL,
        () -> {
          PerformanceTrace.record(VERIFY_TARGET, Long.MAX_VALUE, true);
          PerformanceTrace.record(VERIFY_TARGET, 1, false);
        });
    var summary =
        trace.finish(
            START,
            SessionState.COMPLETED,
            PauseReason.NONE,
            List.of("A", "B", "C", "D", "E", "F", "G", "H", "I"));
    assertEquals(
        new AttemptPerformance.MetricTiming(VERIFY_TARGET, 2, Long.MAX_VALUE, 1),
        metric(summary, VERIFY_TARGET));
    assertEquals(8, summary.diagnosticCodes().size());
  }

  private static AttemptPerformance.MetricTiming metric(AttemptPerformance summary, Metric metric) {
    return summary.metrics().stream().filter(m -> m.metric() == metric).findFirst().orElseThrow();
  }
}
