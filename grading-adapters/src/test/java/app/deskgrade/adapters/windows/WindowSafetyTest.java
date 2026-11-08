package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WindowSafetyTest {
  @Test
  void inconsistentSavedLogicalAndPhysicalRegionsFailBeforeNativeAccess() {
    WindowSnapshot baseline = CoordinateMapperTest.window(96);
    TargetBinding inconsistent =
        new TargetBinding(
            baseline,
            new LogicalRect(20, 20, 40, 40),
            new PixelRect(
                baseline.clientBounds().x() + 50, baseline.clientBounds().y() + 20, 40, 40));
    assertEquals(WindowSafety.Failure.GEOMETRY, new WindowsWindowService().check(inconsistent));
  }

  @Test
  void detectsIdentityGeometryDpiFocusVisibilityOcclusionAndLock() {
    WindowSnapshot baseline = CoordinateMapperTest.window(96);
    assertEquals(WindowSafety.Failure.NONE, WindowSafety.compare(baseline, baseline, false));
    assertEquals(WindowSafety.Failure.OCCLUDED, WindowSafety.compare(baseline, baseline, true));
    WindowSnapshot replaced =
        copy(
            baseline,
            new WindowIdentity(1, 2, Instant.ofEpochSecond(1)),
            baseline.clientBounds(),
            96,
            true,
            true,
            false,
            false,
            true);
    assertEquals(WindowSafety.Failure.IDENTITY, WindowSafety.compare(baseline, replaced, false));
    assertEquals(
        WindowSafety.Failure.GEOMETRY,
        WindowSafety.compare(
            baseline,
            copy(
                baseline,
                baseline.identity(),
                new PixelRect(-1399, 100, 960, 720),
                96,
                true,
                true,
                false,
                false,
                true),
            false));
    assertEquals(
        WindowSafety.Failure.DISPLAY,
        WindowSafety.compare(
            baseline,
            copy(
                baseline,
                baseline.identity(),
                baseline.clientBounds(),
                120,
                true,
                true,
                false,
                false,
                true),
            false));
    assertEquals(
        WindowSafety.Failure.FOCUS,
        WindowSafety.compare(
            baseline,
            copy(
                baseline,
                baseline.identity(),
                baseline.clientBounds(),
                96,
                false,
                true,
                false,
                false,
                true),
            false));
    for (int condition = 0; condition < 4; condition++) {
      assertEquals(
          WindowSafety.Failure.HIDDEN,
          WindowSafety.compare(
              baseline,
              copy(
                  baseline,
                  baseline.identity(),
                  baseline.clientBounds(),
                  96,
                  true,
                  condition != 0,
                  condition == 1,
                  condition == 2,
                  condition != 3),
              false));
    }
  }

  private WindowSnapshot copy(
      WindowSnapshot b,
      WindowIdentity identity,
      PixelRect client,
      int dpi,
      boolean foreground,
      boolean visible,
      boolean minimized,
      boolean cloaked,
      boolean interactive) {
    return new WindowSnapshot(
        identity,
        b.title(),
        b.windowClass(),
        b.windowBounds(),
        client,
        b.display(),
        dpi,
        foreground,
        visible,
        minimized,
        cloaked,
        interactive);
  }
}
