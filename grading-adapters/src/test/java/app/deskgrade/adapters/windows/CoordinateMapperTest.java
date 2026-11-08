package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoordinateMapperTest {
  private final CoordinateMapper mapper = new CoordinateMapper();

  @ParameterizedTest
  @ValueSource(ints = {96, 120, 144, 192})
  void roundTripsLogicalWin32AndAwtCoordinatesAtEachRequiredScale(int dpi) {
    WindowSnapshot window = window(dpi);
    LogicalRect logical = new LogicalRect(24, 32, 160, 100);
    PixelRect screen = mapper.toScreen(logical, window);
    double scale = dpi / 96.0;
    assertEquals(
        new PixelRect(
            -1360 + (int) (24 * scale),
            120 + (int) (32 * scale),
            (int) (160 * scale),
            (int) (100 * scale)),
        screen);
    assertEquals(logical, mapper.toClientLogical(screen, window));
    PixelRect awt = mapper.toAwtExact(screen, window.display());
    assertEquals(
        new PixelPoint(screen.x(), screen.y()),
        mapper.awtToPhysical(awt.x(), awt.y(), window.display()));
  }

  @Test
  void rejectsNonRepresentableAwtCaptureWithoutResamplingOrExpanding() {
    WindowSnapshot window = window(144);
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toAwtExact(new PixelRect(-1398, 120, 240, 150), window.display()));
  }

  @Test
  void rejectsOverflowOutsideClientAndCrossDisplay() {
    WindowSnapshot window = window(96);
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toScreen(new LogicalRect(900, 0, 400, 20), window));
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toScreen(new LogicalRect(Double.MAX_VALUE, 0, 20, 20), window));
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toAwt(new PixelRect(-1700, 0, 200, 100), window.display()));
    assertThrows(IllegalArgumentException.class, () -> new PixelRect(Integer.MAX_VALUE, 0, 2, 2));
  }

  static WindowSnapshot window(int dpi) {
    double scale = dpi / 96.0;
    return new WindowSnapshot(
        new WindowIdentity(1, 2, Instant.EPOCH),
        "Synthetic",
        "Test",
        new PixelRect(-1370, 90, 1000, 800),
        new PixelRect(-1360, 120, 960, 720),
        new DisplaySnapshot(
            "display",
            new PixelRect(-1600, 0, 1600, 1200),
            new PixelRect((int) (-1600 / scale), 0, (int) (1600 / scale), (int) (1200 / scale)),
            scale,
            scale),
        dpi,
        true,
        true,
        false,
        false,
        true);
  }
}
