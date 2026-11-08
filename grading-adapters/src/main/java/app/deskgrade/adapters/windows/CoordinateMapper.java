package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.PixelPoint;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.WindowSnapshot;

/** All desktop scaling and coordinate translation lives here. No native initialization. */
public final class CoordinateMapper {
  public PixelRect toAwtExact(PixelRect physical, DisplaySnapshot display) {
    PixelRect awt = toAwt(physical, display);
    PixelPoint first = awtToPhysical(awt.x(), awt.y(), display);
    PixelPoint last = awtToPhysical(awt.x() + awt.width(), awt.y() + awt.height(), display);
    if (first.x() != physical.x()
        || first.y() != physical.y()
        || last.x() != physical.x() + physical.width()
        || last.y() != physical.y() + physical.height()) {
      throw new IllegalArgumentException("Selection is not aligned to the AWT pixel grid.");
    }
    return awt;
  }

  public PixelPoint clientToScreen(double x, double y, WindowSnapshot window) {
    PixelRect one = toScreen(new LogicalRect(x, y, 1, 1), window);
    return new PixelPoint(one.x(), one.y());
  }

  /** SendInput absolute coordinates are normalized over the physical virtual desktop. */
  public PixelPoint toAbsoluteInput(PixelPoint point, DisplaySnapshot display) {
    PixelRect bounds = display.physicalBounds();
    if (point.x() < bounds.x()
        || point.y() < bounds.y()
        || point.x() >= (long) bounds.x() + bounds.width()
        || point.y() >= (long) bounds.y() + bounds.height()
        || bounds.width() < 2
        || bounds.height() < 2) throw new IllegalArgumentException("Input point outside display.");
    return new PixelPoint(
        exactRound((point.x() - bounds.x()) * 65535.0 / (bounds.width() - 1)),
        exactRound((point.y() - bounds.y()) * 65535.0 / (bounds.height() - 1)));
  }

  public PixelRect toScreen(LogicalRect logical, WindowSnapshot window) {
    double scale = window.dpi() / 96.0;
    int x = exactRound(window.clientBounds().x() + logical.x() * scale);
    int y = exactRound(window.clientBounds().y() + logical.y() * scale);
    int right = exactRound(window.clientBounds().x() + (logical.x() + logical.width()) * scale);
    int bottom = exactRound(window.clientBounds().y() + (logical.y() + logical.height()) * scale);
    PixelRect region = new PixelRect(x, y, right - x, bottom - y);
    if (!window.clientBounds().contains(region))
      throw new IllegalArgumentException("Region outside client.");
    return region;
  }

  public LogicalRect toClientLogical(PixelRect physical, WindowSnapshot window) {
    if (!window.clientBounds().contains(physical))
      throw new IllegalArgumentException("Region outside client.");
    double scale = window.dpi() / 96.0;
    return new LogicalRect(
        (physical.x() - window.clientBounds().x()) / scale,
        (physical.y() - window.clientBounds().y()) / scale,
        physical.width() / scale,
        physical.height() / scale);
  }

  public PixelRect toAwt(PixelRect physical, DisplaySnapshot display) {
    if (!display.physicalBounds().contains(physical))
      throw new IllegalArgumentException("Region crosses display.");
    int x =
        exactRound(
            display.awtBounds().x()
                + (physical.x() - display.physicalBounds().x()) / display.scaleX());
    int y =
        exactRound(
            display.awtBounds().y()
                + (physical.y() - display.physicalBounds().y()) / display.scaleY());
    int right =
        exactRound(
            display.awtBounds().x()
                + (physical.x() + physical.width() - display.physicalBounds().x())
                    / display.scaleX());
    int bottom =
        exactRound(
            display.awtBounds().y()
                + (physical.y() + physical.height() - display.physicalBounds().y())
                    / display.scaleY());
    return new PixelRect(x, y, right - x, bottom - y);
  }

  public PixelPoint awtToPhysical(double x, double y, DisplaySnapshot display) {
    return new PixelPoint(
        exactRound(display.physicalBounds().x() + (x - display.awtBounds().x()) * display.scaleX()),
        exactRound(
            display.physicalBounds().y() + (y - display.awtBounds().y()) * display.scaleY()));
  }

  private static int exactRound(double value) {
    if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Coordinate overflow.");
    }
    return Math.toIntExact(Math.round(value));
  }
}
