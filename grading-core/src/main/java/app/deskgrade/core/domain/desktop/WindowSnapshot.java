package app.deskgrade.core.domain.desktop;

import java.util.Objects;

public record WindowSnapshot(
    WindowIdentity identity,
    String title,
    String windowClass,
    PixelRect windowBounds,
    PixelRect clientBounds,
    DisplaySnapshot display,
    int dpi,
    boolean foreground,
    boolean visible,
    boolean minimized,
    boolean cloaked,
    boolean interactive) {
  public WindowSnapshot {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(title);
    Objects.requireNonNull(windowClass);
    Objects.requireNonNull(windowBounds);
    Objects.requireNonNull(clientBounds);
    Objects.requireNonNull(display);
    if (dpi <= 0) throw new IllegalArgumentException("Invalid DPI.");
  }
}
