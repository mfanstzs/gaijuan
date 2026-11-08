package app.deskgrade.core.domain.desktop;

import java.util.Objects;

/** Win32 physical screen bounds and AWT logical bounds describe the same display. */
public record DisplaySnapshot(
    String deviceId, PixelRect physicalBounds, PixelRect awtBounds, double scaleX, double scaleY) {
  public DisplaySnapshot {
    Objects.requireNonNull(deviceId);
    Objects.requireNonNull(physicalBounds);
    Objects.requireNonNull(awtBounds);
    if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0 || scaleY <= 0) {
      throw new IllegalArgumentException("Invalid display scale.");
    }
  }
}
