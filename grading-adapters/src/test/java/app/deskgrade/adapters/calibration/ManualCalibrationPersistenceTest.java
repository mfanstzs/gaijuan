package app.deskgrade.adapters.calibration;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualCalibrationPersistenceTest {
  @TempDir Path directory;
  private final ManualCalibrationJsonCodec codec = new ManualCalibrationJsonCodec();

  @Test
  void versionFourRoundTripRetainsRegionsAndNeverWritesLegacyIdentityRegion() {
    for (boolean withNext : List.of(false, true)) {
      var value = calibration(1, withNext);
      String json = codec.write(value);
      assertEquals(value, codec.read(json));
      for (String forbidden :
          List.of(
              "hwnd",
              "processId",
              "processStartedAt",
              "foreground",
              "authorized",
              "verified",
              "automatic",
              "identityRegion")) assertFalse(json.contains("\"" + forbidden + "\""));
      assertTrue(json.contains("\"schemaVersion\" : 4"));
      assertNull(codec.read(json).identityRegion());
      assertTrue(
          codec
              .read(json)
              .readinessGaps()
              .contains(ManualPlatformCalibration.ReadinessGap.ANSWER_REGION_CONTINUITY));
    }

    var legacyInMemoryValue = legacyCalibration(1, true);
    String canonical = codec.write(legacyInMemoryValue);
    assertFalse(canonical.contains("\"identityRegion\""));
    assertNull(codec.read(canonical).identityRegion());
  }

  @Test
  void versionTwoAndThreeStrictlyLoadButDiscardLegacyIdentityRegion() {
    String versionTwo =
        """
        {"schemaVersion":2,"id":"legacy","revision":1,"displayName":"旧配置",
        "layout":{"clientWidthPixels":1000,"clientHeightPixels":700,"dpi":96,"windowClass":"Web"},
        "identityRegion":{"x":600,"y":40,"width":200,"height":40},
        "answerRegion":{"x":20,"y":100,"width":500,"height":300},
        "scoreRegion":{"x":600,"y":500,"width":100,"height":40},
        "submitRegion":{"x":750,"y":500,"width":100,"height":40},
        "nextRegion":{"x":870,"y":500,"width":100,"height":40},
        "navigationMode":"CLICK_NEXT","savedAt":"2026-09-12T12:00:00Z"}
        """;
    String versionThree =
        """
        {"schemaVersion":3,"id":"legacy","revision":2,"displayName":"旧配置",
        "layout":{"clientWidthPixels":1000,"clientHeightPixels":700,"dpi":96,"windowClass":"Web"},
        "identityRegion":{"x":600,"y":40,"width":200,"height":40},
        "answerRegion":{"x":20,"y":100,"width":500,"height":300},
        "scoreRegion":{"x":600,"y":500,"width":100,"height":40},
        "submitRegion":{"x":750,"y":500,"width":100,"height":40},
        "submissionAckRegion":{"x":600,"y":600,"width":100,"height":40},
        "nextRegion":{"x":870,"y":500,"width":100,"height":40},
        "navigationMode":"CLICK_NEXT","savedAt":"2026-09-12T12:00:00Z"}
        """;

    var loadedTwo = codec.read(versionTwo);
    assertNull(loadedTwo.identityRegion());
    assertNull(loadedTwo.submissionAckRegion());
    assertFalse(loadedTwo.hasRequiredNavigationRegions());

    var loadedThree = codec.read(versionThree);
    assertNull(loadedThree.identityRegion());
    assertNotNull(loadedThree.submissionAckRegion());
    assertTrue(loadedThree.hasRequiredNavigationRegions());

    for (String legacy : List.of(versionTwo, versionThree)) {
      String rewritten = codec.write(codec.read(legacy));
      assertTrue(rewritten.contains("\"schemaVersion\" : 4"));
      assertFalse(rewritten.contains("\"identityRegion\""));
      assertEquals(codec.read(legacy), codec.read(rewritten));
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.read(legacy.replaceFirst("\\{", "{\"unexpected\":true,")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              codec.read(
                  legacy.replace(
                      "\"identityRegion\":{\"x\":600,\"y\":40,\"width\":200,\"height\":40}",
                      "\"identityRegion\":{\"x\":20,\"y\":100,\"width\":500,\"height\":300}")));
    }
  }

  @Test
  void rejectsUnknownFieldsDuplicateKeysTrailingJsonWrongVersionAndMalformedRegions() {
    String json = codec.write(calibration(1, true));
    for (String bad :
        List.of(
            json.replace("\"schemaVersion\" : 4", "\"schemaVersion\" : 5"),
            json.replace("\"schemaVersion\" : 4", "\"schemaVersion\" : 4294967297"),
            json.replace("\"schemaVersion\" : 4", "\"schemaVersion\" : 4.0"),
            json.replace("\"revision\" : 1", "\"revision\" : 0"),
            json.replace("\"revision\" : 1", "\"revision\" : 9223372036854775808"),
            json.replace("\"dpi\" : 144", "\"dpi\" : \"144\""),
            json.replace("\"x\" : 20.0", "\"x\" : -1"),
            json.replace("\"x\" : 20.0", "\"x\" : 1e309"),
            json.replaceFirst("\\{", "{\"identityRegion\":null,"),
            json.replaceFirst("\\{", "{\"hwnd\":123,"),
            json.replaceFirst("\\{", "{\"verified\":true,"),
            json.replaceFirst("\\{", "{\"schemaVersion\":4,"),
            json + " {}",
            json.substring(0, json.length() - 1),
            "null",
            "[]",
            " ".repeat(65537))) {
      assertThrows(IllegalArgumentException.class, () -> codec.read(bad));
    }
  }

  @Test
  void savesReloadsAfterRestartAndNeverOverwritesDifferentContentAtSameOrOlderRevision()
      throws Exception {
    Path file = directory.resolve("中文 空格/settings/manual-calibration.json");
    var store = new ManualCalibrationStore(file);
    assertTrue(store.load().isEmpty());
    var first = calibration(1, false);
    store.save(first);
    byte[] original = Files.readAllBytes(file);
    store.save(first); // Exact immutable snapshot replay is idempotent.
    assertArrayEquals(original, Files.readAllBytes(file));
    assertThrows(IllegalArgumentException.class, () -> store.save(calibration(1, true)));
    assertArrayEquals(original, Files.readAllBytes(file));
    assertThrows(IllegalArgumentException.class, () -> store.save(calibration(3, true)));
    var second = calibration(2, true);
    store.save(second);
    assertEquals(second, new ManualCalibrationStore(file).load().orElseThrow());
    assertThrows(IllegalArgumentException.class, () -> store.save(first));
    assertEquals(second, store.load().orElseThrow());
    try (var entries = Files.list(file.getParent())) {
      assertEquals(
          List.of("manual-calibration.json", "manual-calibration.json.lock"),
          entries.map(p -> p.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void refusesToReplaceCorruptOrOversizedSavedConfigAndRejectsMalformedUtf8() throws Exception {
    Path file = directory.resolve("manual.json");
    var store = new ManualCalibrationStore(file);
    for (byte[] data :
        List.of(
            "{broken".getBytes(StandardCharsets.UTF_8),
            new byte[] {(byte) 0xC3, (byte) 0x28},
            new byte[ManualCalibrationJsonCodec.MAX_BYTES + 1])) {
      Files.write(file, data);
      assertThrows(RuntimeException.class, store::load);
      assertThrows(RuntimeException.class, () -> store.save(calibration(1, true)));
      assertArrayEquals(data, Files.readAllBytes(file));
    }
  }

  @Test
  void lockedStoreDirectoryAndIdChangesFailClosedWithoutReplacingPriorFile() throws Exception {
    Path file = directory.resolve("manual.json");
    var store = new ManualCalibrationStore(file);
    assertThrows(IllegalArgumentException.class, () -> store.save(calibration(2, false)));
    store.save(calibration(1, false));
    try (var channel =
            FileChannel.open(file.resolveSibling("manual.json.lock"), StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertThrows(
          IllegalStateException.class,
          () -> new ManualCalibrationStore(file).save(calibration(2, true)));
    }
    assertEquals(calibration(1, false), store.load().orElseThrow());
    var renamed = calibration(2, false);
    var differentId =
        new ManualPlatformCalibration(
            "other",
            2,
            renamed.displayName(),
            renamed.layout(),
            renamed.answerRegion(),
            renamed.scoreRegion(),
            renamed.submitRegion(),
            renamed.nextRegion(),
            renamed.savedAt());
    assertThrows(IllegalArgumentException.class, () -> store.save(differentId));
    Path isDirectory = directory.resolve("directory.json");
    Files.createDirectory(isDirectory);
    assertThrows(IllegalStateException.class, () -> new ManualCalibrationStore(isDirectory).load());
    assertThrows(
        IllegalStateException.class,
        () -> new ManualCalibrationStore(isDirectory).save(calibration(1, false)));
  }

  private static ManualPlatformCalibration calibration(long revision, boolean next) {
    return new ManualPlatformCalibration(
        "manual-platform",
        revision,
        "合成标定",
        new ManualPlatformCalibration.WindowLayout(1500, 1200, 144, "SyntheticClass"),
        null,
        new LogicalRect(20, 20, 400, 300),
        new LogicalRect(500, 500, 100, 40),
        new LogicalRect(650, 500, 100, 40),
        next ? new LogicalRect(650, 600, 100, 40) : null,
        next ? new LogicalRect(800, 500, 100, 40) : null,
        next
            ? ManualPlatformCalibration.NavigationMode.CLICK_NEXT
            : ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
        Instant.parse("2026-09-10T12:00:00Z"));
  }

  private static ManualPlatformCalibration legacyCalibration(long revision, boolean next) {
    var current = calibration(revision, next);
    return new ManualPlatformCalibration(
        current.id(),
        current.revision(),
        current.displayName(),
        current.layout(),
        new LogicalRect(500, 20, 160, 60),
        current.answerRegion(),
        current.scoreRegion(),
        current.submitRegion(),
        current.submissionAckRegion(),
        current.nextRegion(),
        current.navigationMode(),
        current.savedAt());
  }
}
