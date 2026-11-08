package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.WindowSnapshot;

/** Pure comparison permits default tests without loading user32. */
public final class WindowSafety {
  public enum Failure {
    NONE,
    IDENTITY,
    GEOMETRY,
    DISPLAY,
    FOCUS,
    HIDDEN,
    OCCLUDED,
    UNAVAILABLE
  }

  private WindowSafety() {}

  public static Failure compare(
      WindowSnapshot calibrated, WindowSnapshot current, boolean occluded) {
    if (!calibrated.identity().equals(current.identity())
        || !calibrated.windowClass().equals(current.windowClass())) return Failure.IDENTITY;
    if (!calibrated.clientBounds().equals(current.clientBounds())
        || !calibrated.windowBounds().equals(current.windowBounds())) return Failure.GEOMETRY;
    if (calibrated.dpi() != current.dpi() || !calibrated.display().equals(current.display()))
      return Failure.DISPLAY;
    if (!current.visible() || current.minimized() || current.cloaked() || !current.interactive())
      return Failure.HIDDEN;
    if (!current.foreground()) return Failure.FOCUS;
    if (occluded) return Failure.OCCLUDED;
    return Failure.NONE;
  }
}
