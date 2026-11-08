package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.TargetBinding;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-shot invalidation. Recalibrate and create a new monitor after a failure. */
public final class WindowChangeMonitor implements AutoCloseable {
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "deskgrade-window-monitor");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean invalidated = new AtomicBoolean();

  public WindowChangeMonitor(
      WindowsWindowService windows,
      TargetBinding binding,
      Duration interval,
      Consumer<WindowSafety.Failure> onInvalidated) {
    this(checker(windows, binding), interval, onInvalidated);
  }

  WindowChangeMonitor(
      Supplier<WindowSafety.Failure> check,
      Duration interval,
      Consumer<WindowSafety.Failure> onInvalidated) {
    Objects.requireNonNull(check);
    Objects.requireNonNull(onInvalidated);
    if (interval.toMillis() < 25 || interval.toMillis() > 200)
      throw new IllegalArgumentException("Monitor interval must be 25–200 ms.");
    executor.scheduleWithFixedDelay(
        () -> {
          if (invalidated.get()) return;
          WindowSafety.Failure failure;
          try {
            failure = Objects.requireNonNull(check.get());
          } catch (RuntimeException | LinkageError unavailable) {
            failure = WindowSafety.Failure.UNAVAILABLE;
          }
          if (failure != WindowSafety.Failure.NONE && invalidated.compareAndSet(false, true)) {
            try {
              // The CAS makes invalidation one-shot before the production callback revokes input.
              onInvalidated.accept(failure);
            } catch (RuntimeException | LinkageError ignored) {
              // Callback failures must never roll back invalidation or leave polling silently live.
            }
          }
        },
        0,
        interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  public boolean invalidated() {
    return invalidated.get();
  }

  private static Supplier<WindowSafety.Failure> checker(
      WindowsWindowService windows, TargetBinding binding) {
    Objects.requireNonNull(windows);
    Objects.requireNonNull(binding);
    return () -> windows.check(binding);
  }

  @Override
  public void close() {
    invalidated.set(true);
    executor.shutdownNow();
  }
}
