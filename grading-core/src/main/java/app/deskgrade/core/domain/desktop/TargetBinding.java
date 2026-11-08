package app.deskgrade.core.domain.desktop;

import java.util.Objects;

public record TargetBinding(
    WindowSnapshot calibratedWindow, LogicalRect answerRegion, PixelRect calibratedRegion) {
  public TargetBinding {
    Objects.requireNonNull(calibratedWindow);
    Objects.requireNonNull(answerRegion);
    Objects.requireNonNull(calibratedRegion);
    if (!calibratedWindow.clientBounds().contains(calibratedRegion)) {
      throw new IllegalArgumentException("Region is outside target client area.");
    }
  }
}
