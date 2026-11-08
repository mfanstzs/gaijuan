package app.deskgrade.core.domain.desktop;

public record PixelRect(int x, int y, int width, int height) {
  public PixelRect {
    if (width <= 0
        || height <= 0
        || (long) x + width > Integer.MAX_VALUE
        || (long) y + height > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid rectangle.");
    }
  }

  public boolean contains(PixelRect other) {
    return other.x >= x
        && other.y >= y
        && (long) other.x + other.width <= (long) x + width
        && (long) other.y + other.height <= (long) y + height;
  }

  public boolean intersects(PixelRect other) {
    return x < (long) other.x + other.width
        && (long) x + width > other.x
        && y < (long) other.y + other.height
        && (long) y + height > other.y;
  }
}
