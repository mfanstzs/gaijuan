package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.CaptureTiming;
import app.deskgrade.core.application.session.AttemptPerformance;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.PerformanceTrace;
import app.deskgrade.core.application.session.SessionState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapturePerformanceMappingTest {
  @Test
  void freshCaptureAndReuseAreRecordedWithoutInventingAnEncode() {
    var trace = new PerformanceTrace(UUID.randomUUID(), 1, Instant.EPOCH, SessionState.CAPTURING);
    var reused =
        new CaptureTiming(
            31,
            true,
            Map.of(
                CaptureTiming.Phase.RAW_CAPTURE, 20L,
                CaptureTiming.Phase.PIXEL_COMPARE, 10L,
                CaptureTiming.Phase.PNG_REUSE, 1L));
    trace.measure(
        AttemptPerformance.Metric.CAPTURE_EVIDENCE,
        () -> DesktopWorkspace.recordCaptureTiming(reused));
    var summary =
        trace.finish(Instant.EPOCH, SessionState.STOPPED, PauseReason.USER_STOPPED, List.of());
    assertEquals(
        new AttemptPerformance.MetricTiming(AttemptPerformance.Metric.CAPTURE_RAW, 1, 20, 0),
        metric(summary, AttemptPerformance.Metric.CAPTURE_RAW));
    assertEquals(
        new AttemptPerformance.MetricTiming(
            AttemptPerformance.Metric.CAPTURE_PIXEL_COMPARE, 1, 10, 0),
        metric(summary, AttemptPerformance.Metric.CAPTURE_PIXEL_COMPARE));
    assertEquals(
        new AttemptPerformance.MetricTiming(AttemptPerformance.Metric.CAPTURE_PNG_REUSE, 1, 1, 0),
        metric(summary, AttemptPerformance.Metric.CAPTURE_PNG_REUSE));
    assertTrue(
        summary.metrics().stream()
            .noneMatch(m -> m.metric() == AttemptPerformance.Metric.CAPTURE_PNG_ENCODE));
  }

  @Test
  void failedEncodeIsNotAReuseAndUnrelatedUiCapturesDoNotAttachToNextAnswer() {
    DesktopWorkspace.recordCaptureTiming(
        new CaptureTiming(1, true, Map.of(CaptureTiming.Phase.PNG_REUSE, 1L)));
    var trace = new PerformanceTrace(UUID.randomUUID(), 2, Instant.EPOCH, SessionState.CAPTURING);
    trace.measure(
        AttemptPerformance.Metric.CAPTURE_EVIDENCE,
        () ->
            DesktopWorkspace.recordCaptureTiming(
                new CaptureTiming(5, false, Map.of(CaptureTiming.Phase.PNG_ENCODE, 5L))));
    var summary =
        trace.finish(Instant.EPOCH, SessionState.FAILED, PauseReason.LOCAL_FAILURE, List.of());
    assertEquals(1, metric(summary, AttemptPerformance.Metric.CAPTURE_TOTAL).failures());
    assertEquals(1, metric(summary, AttemptPerformance.Metric.CAPTURE_PNG_ENCODE).count());
    assertTrue(
        summary.metrics().stream()
            .noneMatch(m -> m.metric() == AttemptPerformance.Metric.CAPTURE_PNG_REUSE));
  }

  private static AttemptPerformance.MetricTiming metric(
      AttemptPerformance summary, AttemptPerformance.Metric name) {
    return summary.metrics().stream().filter(m -> m.metric() == name).findFirst().orElseThrow();
  }
}
