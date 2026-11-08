package app.deskgrade.core.domain.desktop;

/** Coordinates relative to the target client area, in 96-DPI logical units. */
public record LogicalRect(double x, double y, double width, double height) {
  public LogicalRect {
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(width)
        || !Double.isFinite(height)
        || x < 0
        || y < 0
        || width <= 0
        || height <= 0) {
      throw new IllegalArgumentException("Invalid logical rectangle.");
    }
  }
}
