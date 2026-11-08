package app.deskgrade.core.domain.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualPlatformCalibrationTest {
  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

  @Test
  void capturesClientRelativePositionsWithoutRetainingOldHwndOrRestoringInputPermission() {
    var first = window(11, 22, 100, 100, 1000, 800, 96);
    var config = capture(first, null);
    assertEquals(1000, config.layout().clientWidthPixels());
    assertEquals(800, config.layout().clientHeightPixels());
    assertEquals(new LogicalRect(20, 20, 400, 300), config.answerRegion());
    assertNull(config.nextRegion());
    // A new HWND/process or screen position can share geometry. This is a review hint, not
    // identity.
    assertTrue(config.matchesLayout(window(33, 44, 900, 50, 1000, 800, 96)));
    assertTrue(
        config
            .readinessGaps()
            .contains(ManualPlatformCalibration.ReadinessGap.CURRENT_WINDOW_BINDING));
    assertTrue(
        config.readinessGaps().contains(ManualPlatformCalibration.ReadinessGap.SCORE_READBACK));
    assertTrue(
        config.readinessGaps().contains(ManualPlatformCalibration.ReadinessGap.SUBMISSION_ACK));
    assertTrue(
        config.readinessGaps().contains(ManualPlatformCalibration.ReadinessGap.STABLE_ANSWER_ID));
    assertThrows(UnsupportedOperationException.class, () -> config.readinessGaps().clear());
  }

  @Test
  void requiresExactClientSizeDpiAndWindowClassForLayoutReuse() {
    var config = capture(window(11, 22, 100, 100, 1000, 800, 96), null);
    assertFalse(config.matchesLayout(window(11, 22, 100, 100, 1001, 800, 96)));
    assertFalse(config.matchesLayout(window(11, 22, 100, 100, 1000, 801, 96)));
    assertFalse(config.matchesLayout(window(11, 22, 100, 100, 1000, 800, 120)));
    assertFalse(config.matchesLayout(null));
    var other = window(11, 22, 100, 100, 1000, 800, 96);
    assertFalse(
        config.matchesLayout(
            new WindowSnapshot(
                other.identity(),
                other.title(),
                "DifferentClass",
                other.windowBounds(),
                other.clientBounds(),
                other.display(),
                other.dpi(),
                true,
                true,
                false,
                false,
                true)));
  }

  @Test
  void validatesPhysicalPixelMinimumAtScaledDpiAndAllowsOptionalSeparateNextArea() {
    var window = window(11, 22, 100, 100, 1500, 1200, 144);
    var config = capture(window, new LogicalRect(800, 500, 100, 40));
    assertEquals(144, config.layout().dpi());
    assertNotNull(config.nextRegion());
    assertTrue(
        config
            .readinessGaps()
            .contains(ManualPlatformCalibration.ReadinessGap.NEXT_ANSWER_EVIDENCE));
    var tiny = new LogicalRect(500, 500, 10, 10); // 15 physical pixels at 150% scaling.
    assertThrows(
        IllegalArgumentException.class, () -> create(window, tiny, config.submitRegion(), null));
    var exactSixteen = new LogicalRect(500, 500, 16 / 1.5, 16 / 1.5);
    assertNotNull(create(window, exactSixteen, config.submitRegion(), null));
  }

  @Test
  void rejectsMissingOutOfBoundsOverlappingOverflowAndUnreasonablyLargeControls() {
    var window = window(11, 22, 100, 100, 1000, 800, 96);
    for (LogicalRect invalidScore :
        List.of(
            new LogicalRect(999, 500, 20, 20),
            new LogicalRect(500, 790, 20, 20),
            new LogicalRect(500, 500, 15, 20),
            new LogicalRect(400, 300, 100, 50),
            new LogicalRect(Double.MAX_VALUE, 0, Double.MAX_VALUE, 20),
            new LogicalRect(500, 0, 500, 500))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> create(window, invalidScore, new LogicalRect(650, 600, 100, 40), null));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> create(window, null, new LogicalRect(650, 500, 100, 40), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            create(
                window,
                new LogicalRect(500, 500, 100, 40),
                new LogicalRect(650, 500, 100, 40),
                new LogicalRect(660, 510, 100, 40)));
    assertThrows(IllegalArgumentException.class, () -> capture(null, null));
  }

  @Test
  void adjacentEdgesAreAllowedButPositiveOverlapIsRejectedAndMetadataCannotForgeReadiness() {
    var window = window(11, 22, 100, 100, 1000, 800, 96);
    assertNotNull(
        create(
            window, new LogicalRect(500, 500, 100, 40), new LogicalRect(600, 500, 100, 40), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            create(
                window,
                new LogicalRect(500, 500, 100, 40),
                new LogicalRect(599.9, 500, 100, 40),
                null));
    var config = capture(window, null);
    for (String id : List.of("", "../outside", "manual platform")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ManualPlatformCalibration(
                  id,
                  1,
                  "标定",
                  config.layout(),
                  config.answerRegion(),
                  config.scoreRegion(),
                  config.submitRegion(),
                  null,
                  NOW));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ManualPlatformCalibration(
                "manual",
                0,
                "标定",
                config.layout(),
                config.answerRegion(),
                config.scoreRegion(),
                config.submitRegion(),
                null,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManualPlatformCalibration.WindowLayout(1000, 800, 0, "TestClass"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManualPlatformCalibration.WindowLayout(1000, 800, 96, ""));
  }

  @Test
  void legacyIdentityRegionDoesNotReplaceAnswerContinuityAndNavigationModeIsConsistent() {
    var target = window(11, 22, 100, 100, 1000, 800, 96);
    var identity = new LogicalRect(500, 40, 180, 40);
    var answer = new LogicalRect(20, 100, 400, 300);
    var score = new LogicalRect(500, 500, 100, 40);
    var submit = new LogicalRect(650, 500, 100, 40);
    var next = new LogicalRect(800, 500, 100, 40);
    var automatic =
        ManualPlatformCalibration.capture(
            "site-a",
            1,
            "甲评分网",
            target,
            identity,
            answer,
            score,
            submit,
            null,
            null,
            ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
            NOW);
    assertTrue(automatic.hasIdentityRegion());
    assertEquals(identity, automatic.identityRegion());
    assertTrue(
        automatic
            .readinessGaps()
            .contains(ManualPlatformCalibration.ReadinessGap.ANSWER_REGION_CONTINUITY));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ManualPlatformCalibration.capture(
                "site-a",
                1,
                "甲评分网",
                target,
                identity,
                answer,
                score,
                submit,
                null,
                next,
                ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
                NOW));
    var legacyClickNext =
        ManualPlatformCalibration.capture(
            "site-a",
            1,
            "甲评分网",
            target,
            identity,
            answer,
            score,
            submit,
            null,
            null,
            ManualPlatformCalibration.NavigationMode.CLICK_NEXT,
            NOW);
    assertFalse(legacyClickNext.hasRequiredNavigationRegions());
    var completeClickNext =
        ManualPlatformCalibration.capture(
            "site-a",
            1,
            "甲评分网",
            target,
            identity,
            answer,
            score,
            submit,
            new LogicalRect(650, 600, 100, 40),
            next,
            ManualPlatformCalibration.NavigationMode.CLICK_NEXT,
            NOW);
    assertTrue(completeClickNext.hasRequiredNavigationRegions());
    var sharedAckAndNext = new LogicalRect(800, 500, 100, 40);
    assertDoesNotThrow(
        () ->
            ManualPlatformCalibration.capture(
                "site-a",
                1,
                "甲评分网",
                target,
                identity,
                answer,
                score,
                submit,
                sharedAckAndNext,
                sharedAckAndNext,
                ManualPlatformCalibration.NavigationMode.CLICK_NEXT,
                NOW));
    for (LogicalRect forbiddenSharedControl : List.of(identity, answer, score, submit)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ManualPlatformCalibration.capture(
                  "site-a",
                  1,
                  "甲评分网",
                  target,
                  identity,
                  answer,
                  score,
                  submit,
                  forbiddenSharedControl,
                  forbiddenSharedControl,
                  ManualPlatformCalibration.NavigationMode.CLICK_NEXT,
                  NOW));
    }
  }

  private static ManualPlatformCalibration capture(WindowSnapshot target, LogicalRect next) {
    return create(
        target, new LogicalRect(500, 500, 100, 40), new LogicalRect(650, 500, 100, 40), next);
  }

  private static ManualPlatformCalibration create(
      WindowSnapshot target, LogicalRect score, LogicalRect submit, LogicalRect next) {
    return ManualPlatformCalibration.capture(
        "manual-platform",
        1,
        "合成标定",
        target,
        new LogicalRect(20, 20, 400, 300),
        score,
        submit,
        next,
        NOW);
  }

  private static WindowSnapshot window(
      long hwnd, long pid, int x, int y, int width, int height, int dpi) {
    var display =
        new DisplaySnapshot(
            "test", new PixelRect(0, 0, 4000, 2400), new PixelRect(0, 0, 4000, 2400), 1, 1);
    return new WindowSnapshot(
        new WindowIdentity(hwnd, pid, NOW),
        "Synthetic window",
        "TestClass",
        new PixelRect(x, y, width, height),
        new PixelRect(x, y, width, height),
        display,
        dpi,
        true,
        true,
        false,
        false,
        true);
  }
}
