package app.deskgrade.adapters.calibration;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualCalibrationCatalogStoreTest {
  private static final String OWNER_A = "a".repeat(64);
  private static final String OWNER_B = "b".repeat(64);
  @TempDir Path directory;

  @Test
  void namedProfilesSurviveRestartAndSelectionAndDeletionArePersisted() {
    Path file = directory.resolve("accounts").resolve(OWNER_A).resolve("manual-calibrations.json");
    var store = new ManualCalibrationCatalogStore(file, OWNER_A);

    var first = calibration("site-a", 1, "甲评分网", false);
    var second = calibration("site-b", 1, "乙评分网", true);
    assertEquals(first, store.save(first).profiles().getFirst());
    assertEquals(second.id(), store.save(second).selectedId());

    var restarted = new ManualCalibrationCatalogStore(file, OWNER_A);
    assertEquals(2, restarted.load().profiles().size());
    assertEquals(first.id(), restarted.select(first.id()).selectedId());
    var remaining = restarted.delete(first.id());
    assertNull(remaining.selectedId());
    assertEquals(java.util.List.of(second), remaining.profiles());
    assertEquals(remaining, new ManualCalibrationCatalogStore(file, OWNER_A).load());
  }

  @Test
  void ownerMismatchAndNormalizedDuplicateNamesFailClosedWithoutReplacingCatalog()
      throws Exception {
    Path file = directory.resolve("manual-calibrations.json");
    var store = new ManualCalibrationCatalogStore(file, OWNER_A);
    var first = calibration("site-a", 1, "ＡＢＣ评分网", false);
    store.save(first);
    byte[] original = Files.readAllBytes(file);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.save(calibration("site-b", 1, "abc评分网", false)));
    assertArrayEquals(original, Files.readAllBytes(file));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManualCalibrationCatalogStore(file, OWNER_B).load());
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  @Test
  void schemaTamperingNeverGrantsASelectionOrOverwritesTheFile() throws Exception {
    Path file = directory.resolve("manual-calibrations.json");
    var store = new ManualCalibrationCatalogStore(file, OWNER_A);
    store.save(calibration("site-a", 1, "测试站", false));
    String valid = Files.readString(file, StandardCharsets.UTF_8);

    for (String invalid :
        java.util.List.of(
            valid.replace(
                "\"ownerKey\" : \"" + OWNER_A + "\"", "\"ownerKey\" : \"" + OWNER_B + "\""),
            valid.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 1.0"),
            valid.replace("\"selectedId\" : \"site-a\"", "\"selectedId\" : \"missing\""),
            valid.replaceFirst("\\{", "{\"unexpected\":true,"))) {
      Files.writeString(file, invalid, StandardCharsets.UTF_8);
      assertThrows(RuntimeException.class, store::load);
      assertThrows(
          RuntimeException.class, () -> store.save(calibration("site-b", 1, "另一个站", true)));
      assertEquals(invalid, Files.readString(file, StandardCharsets.UTF_8));
    }
  }

  private static ManualPlatformCalibration calibration(
      String id, long revision, String name, boolean clickNext) {
    return new ManualPlatformCalibration(
        id,
        revision,
        name,
        new ManualPlatformCalibration.WindowLayout(1200, 800, 96, "WebViewWindow"),
        null,
        new LogicalRect(30, 100, 600, 300),
        new LogicalRect(700, 500, 120, 40),
        new LogicalRect(850, 500, 120, 40),
        clickNext ? new LogicalRect(850, 600, 120, 40) : null,
        clickNext ? new LogicalRect(1000, 500, 120, 40) : null,
        clickNext
            ? ManualPlatformCalibration.NavigationMode.CLICK_NEXT
            : ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
        Instant.parse("2026-09-12T12:00:00Z"));
  }
}
