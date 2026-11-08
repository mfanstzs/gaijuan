package app.deskgrade.core.domain.desktop;

import java.time.Instant;
import java.util.Objects;

/** Process start time guards PID reuse; same-process HWND reuse still needs page/window anchors. */
public record WindowIdentity(long hwnd, long processId, Instant processStartedAt) {
  public WindowIdentity {
    if (hwnd == 0 || processId <= 0) throw new IllegalArgumentException("Invalid identity.");
    Objects.requireNonNull(processStartedAt);
  }
}
