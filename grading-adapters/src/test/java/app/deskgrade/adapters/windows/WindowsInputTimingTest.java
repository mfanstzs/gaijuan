package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.PerformanceTrace;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.desktop.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WindowsInputTimingTest {
  @Test
  void tracingARejectedReadNeverReachesClipboardOrNativeInputAndPreservesPermissionFailure() {
    var driver = new WindowsInputDriver(new WindowsWindowService());
    var original = SafeInputException.code("TEST_PERMISSION_DENIED");
    var checked = new AtomicInteger();
    var trace = new PerformanceTrace(UUID.randomUUID(), 1, Instant.EPOCH, SessionState.CAPTURING);
    var failure =
        assertThrows(
            SafeInputException.class,
            () ->
                trace.measure(
                    Metric.READ_SCORE,
                    () ->
                        driver.readNumber(
                            binding(),
                            new LogicalRect(600, 500, 100, 40),
                            () -> {
                              checked.incrementAndGet();
                              throw original;
                            })));
    assertEquals("INPUT_PERMISSION", failure.diagnosticCode());
    assertSame(original, failure.getCause());
    assertEquals(1, checked.get());
    var result =
        trace.finish(
            Instant.EPOCH.plusSeconds(1),
            SessionState.STOPPED,
            PauseReason.LOCAL_FAILURE,
            List.of());
    var clipboard =
        result.metrics().stream()
            .filter(value -> value.metric() == Metric.CLIPBOARD_READ)
            .findFirst()
            .orElseThrow();
    assertEquals(1, clipboard.count());
    assertEquals(1, clipboard.failures());
    assertTrue(clipboard.nanos() >= 0);
  }

  @Test
  void withoutAnActiveTraceTheOriginalPermissionGateStillRunsExactlyOnce() {
    var driver = new WindowsInputDriver(new WindowsWindowService());
    var original = SafeInputException.code("TEST_PERMISSION_DENIED");
    var checked = new AtomicInteger();
    var failure =
        assertThrows(
            SafeInputException.class,
            () ->
                driver.readNumber(
                    binding(),
                    new LogicalRect(600, 500, 100, 40),
                    () -> {
                      checked.incrementAndGet();
                      throw original;
                    }));
    assertEquals("INPUT_PERMISSION", failure.diagnosticCode());
    assertSame(original, failure.getCause());
    assertEquals(1, checked.get());
  }

  private static TargetBinding binding() {
    var bounds = new PixelRect(0, 0, 1000, 700);
    var window =
        new WindowSnapshot(
            new WindowIdentity(101, 201, Instant.EPOCH),
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
    return new TargetBinding(
        window, new LogicalRect(20, 100, 500, 300), new PixelRect(20, 100, 500, 300));
  }
}
