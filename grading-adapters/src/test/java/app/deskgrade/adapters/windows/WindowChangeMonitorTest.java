package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WindowChangeMonitorTest {
  @Test
  void thrownNativeCheckFailsClosedExactlyOnce() throws Exception {
    var callback = new CountDownLatch(1);
    var observed = new AtomicReference<WindowSafety.Failure>();
    try (var monitor =
        new WindowChangeMonitor(
            () -> {
              throw new LinkageError("synthetic native failure");
            },
            Duration.ofMillis(25),
            failure -> {
              observed.set(failure);
              callback.countDown();
            })) {
      assertTrue(callback.await(2, TimeUnit.SECONDS));
      assertTrue(monitor.invalidated());
      assertEquals(WindowSafety.Failure.UNAVAILABLE, observed.get());
    }
  }

  @Test
  void callbackFailureCannotReopenTheMonitor() throws Exception {
    var called = new CountDownLatch(1);
    try (var monitor =
        new WindowChangeMonitor(
            () -> WindowSafety.Failure.GEOMETRY,
            Duration.ofMillis(25),
            failure -> {
              called.countDown();
              throw new IllegalStateException("synthetic callback failure");
            })) {
      assertTrue(called.await(2, TimeUnit.SECONDS));
      assertTrue(monitor.invalidated());
    }
  }
}
