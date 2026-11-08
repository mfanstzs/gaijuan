package app.deskgrade.adapters.windows;

import java.util.EnumMap;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One caller-thread accumulator; used only when a capture observer has been installed. */
final class CaptureTimingCollector {
  private final LongSupplier nanoClock;
  private final long started;
  private final EnumMap<CaptureTiming.Phase, Long> phases =
      new EnumMap<>(CaptureTiming.Phase.class);
  private CaptureTiming.Phase active;
  private long phaseStarted;

  CaptureTimingCollector(LongSupplier nanoClock) {
    this.nanoClock = Objects.requireNonNull(nanoClock);
    started = nanoClock.getAsLong();
  }

  void begin(CaptureTiming.Phase phase) {
    long now = nanoClock.getAsLong();
    endAt(now);
    active = Objects.requireNonNull(phase);
    phaseStarted = now;
  }

  void end() {
    endAt(nanoClock.getAsLong());
  }

  CaptureTiming finish(boolean successful) {
    long now = nanoClock.getAsLong();
    endAt(now);
    return new CaptureTiming(elapsed(started, now), successful, phases);
  }

  private void endAt(long now) {
    if (active == null) return;
    phases.merge(active, elapsed(phaseStarted, now), CaptureTimingCollector::sum);
    active = null;
  }

  private static long elapsed(long from, long to) {
    return Math.max(0, to - from);
  }

  private static long sum(long first, long second) {
    return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
  }
}
