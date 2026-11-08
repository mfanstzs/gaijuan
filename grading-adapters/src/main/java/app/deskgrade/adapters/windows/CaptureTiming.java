package app.deskgrade.adapters.windows;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, non-sensitive durations only; no target, image, identity, or exception data. */
public record CaptureTiming(long elapsedNanos, boolean successful, Map<Phase, Long> phaseNanos) {
  public enum Phase {
    BOUNDARY_PREPARE,
    SAFETY_BEFORE,
    RAW_CAPTURE,
    SAFETY_AFTER,
    PIXEL_COMPARE,
    PNG_ENCODE,
    PNG_REUSE,
    DIGEST_AND_RESULT,
    BOUNDARY_CLOSE
  }

  public CaptureTiming {
    if (elapsedNanos < 0) throw new IllegalArgumentException("Negative capture duration");
    Objects.requireNonNull(phaseNanos);
    var values = new EnumMap<Phase, Long>(Phase.class);
    phaseNanos.forEach(
        (phase, nanos) -> {
          if (phase == null || nanos == null || nanos < 0)
            throw new IllegalArgumentException("Invalid capture phase duration");
          values.put(phase, nanos);
        });
    phaseNanos = Map.copyOf(values);
  }
}
