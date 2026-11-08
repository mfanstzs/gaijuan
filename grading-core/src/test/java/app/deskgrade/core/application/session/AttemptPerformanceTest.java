package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttemptPerformanceTest {
  @Test
  void captureFailureMayHaveNoAttemptAndWallClockMayMoveBackwards() {
    var summary = summary(null, 100L, null, List.of(), List.of(), List.of(), List.of());
    assertNull(summary.attemptId());
    assertEquals(100L, summary.totalNanos());
    assertTrue(summary.completedAt().isBefore(summary.startedAt()));
  }

  @Test
  void measurementsAreImmutableAndDuplicatePhasesOrMetricsAreRejected() {
    var input = new ArrayList<AttemptPerformance.PhaseTiming>();
    input.add(new AttemptPerformance.PhaseTiming(SessionState.WAIT_READY, 1, 10));
    var summary = summary(null, 100L, null, input, List.of(), List.of(), List.of());
    input.clear();
    assertEquals(1, summary.phases().size());
    assertThrows(UnsupportedOperationException.class, () -> summary.phases().clear());
    var phase = summary.phases().getFirst();
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, 100L, null, List.of(phase, phase), List.of(), List.of(), List.of()));
    var metric = new AttemptPerformance.MetricTiming(AttemptPerformance.Metric.OBSERVE, 1, 10, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, 100L, null, List.of(), List.of(metric, metric), List.of(), List.of()));
  }

  @Test
  void durationsAndFailureCountsAreBoundedAndAcknowledgementCannotExceedTotal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, -1L, null, List.of(), List.of(), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, 100L, 101L, List.of(), List.of(), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AttemptPerformance.PhaseTiming(SessionState.GRADING, -1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AttemptPerformance.PhaseTiming(SessionState.GRADING, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AttemptPerformance.MetricTiming(AttemptPerformance.Metric.OBSERVE, 1, 10, 2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttemptPerformance.ModelTiming(
                UUID.randomUUID(),
                AttemptPerformance.ModelRole.PRIMARY,
                -1,
                0,
                AttemptPerformance.ModelOutcome.FAILED));
  }

  @Test
  void modelCallsAreUniqueAndBoundedInsteadOfRetainingUnboundedHistory() {
    var call =
        new AttemptPerformance.ModelTiming(
            UUID.randomUUID(),
            AttemptPerformance.ModelRole.PRIMARY,
            50,
            0,
            AttemptPerformance.ModelOutcome.SUCCEEDED);
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, 100L, null, List.of(), List.of(), List.of(call, call), List.of()));
    List<AttemptPerformance.ModelTiming> many = new ArrayList<>();
    for (int i = 0; i <= AttemptPerformance.MAX_MODEL_CALLS; i++)
      many.add(
          new AttemptPerformance.ModelTiming(
              UUID.randomUUID(),
              AttemptPerformance.ModelRole.PRIMARY,
              0,
              0,
              AttemptPerformance.ModelOutcome.IN_FLIGHT));
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(null, 100L, null, List.of(), List.of(), many, List.of()));
  }

  @Test
  void diagnosticFieldsCannotAcceptErrorBodiesUrlsOrStudentText() {
    for (String value :
        List.of(
            "https://private.invalid/key",
            "Authorization: sk-private",
            "学生答案",
            "data:image/png;base64,private",
            "X".repeat(121)))
      assertThrows(
          IllegalArgumentException.class,
          () -> summary(null, 100L, null, List.of(), List.of(), List.of(), List.of(value)));
    assertEquals(
        List.of("MODEL_CONNECT_TIMEOUT"),
        summary(null, 100L, null, List.of(), List.of(), List.of(), List.of("MODEL_CONNECT_TIMEOUT"))
            .diagnosticCodes());
  }

  private static AttemptPerformance summary(
      UUID attempt,
      long total,
      Long acknowledged,
      List<AttemptPerformance.PhaseTiming> phases,
      List<AttemptPerformance.MetricTiming> metrics,
      List<AttemptPerformance.ModelTiming> calls,
      List<String> codes) {
    return new AttemptPerformance(
        UUID.randomUUID(),
        UUID.randomUUID(),
        attempt,
        1,
        Instant.parse("2026-10-21T01:00:01Z"),
        Instant.parse("2026-10-21T01:00:00Z"),
        total,
        acknowledged,
        SessionState.STOPPED,
        PauseReason.MODEL_FAILED,
        phases,
        metrics,
        calls,
        codes);
  }
}
