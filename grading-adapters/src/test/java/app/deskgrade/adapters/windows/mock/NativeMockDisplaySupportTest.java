package app.deskgrade.adapters.windows.mock;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NativeMockDisplaySupportTest {
  @Test
  void automaticInputRequiresThePhysicallyValidatedScale() {
    assertTrue(NativeMockPlatformAdapter.isValidatedAutomaticDisplay(window(96, 1)));
    assertFalse(NativeMockPlatformAdapter.isValidatedAutomaticDisplay(window(120, 1.25)));
    assertFalse(NativeMockPlatformAdapter.isValidatedAutomaticDisplay(window(144, 1.5)));
    assertFalse(NativeMockPlatformAdapter.isValidatedAutomaticDisplay(window(192, 2)));
    assertFalse(NativeMockPlatformAdapter.isValidatedAutomaticDisplay(window(96, 1.25)));
  }

  private WindowSnapshot window(int dpi, double scale) {
    PixelRect screen = new PixelRect(0, 0, 1920, 1080);
    return new WindowSnapshot(
        new WindowIdentity(1, 1, Instant.EPOCH),
        NativeMockPlatformAdapter.WINDOW_TITLE,
        "GlassWndClass-test",
        new PixelRect(0, 0, 900, 800),
        new PixelRect(8, 31, 884, 761),
        new DisplaySnapshot("synthetic", screen, screen, scale, scale),
        dpi,
        true,
        true,
        false,
        false,
        true);
  }
}
