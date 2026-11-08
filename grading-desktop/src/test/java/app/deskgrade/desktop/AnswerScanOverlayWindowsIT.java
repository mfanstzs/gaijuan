package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.CaptureTiming;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.core.domain.desktop.LogicalRect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Raw OS captures of this process's synthetic window only. No websites, model calls, or input. */
@EnabledOnOs(OS.WINDOWS)
class AnswerScanOverlayWindowsIT {
  @BeforeAll
  static void startToolkit() throws Exception {
    var ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          ready.countDown();
        });
    assertTrue(ready.await(15, TimeUnit.SECONDS));
  }

  @AfterAll
  static void stopToolkit() {
    Platform.exit();
  }

  @Test
  @Timeout(60)
  void animatedPixelsAreVisibleButEveryGuardedRawCaptureIsExactlyTheOriginal() throws Exception {
    var overlay = onFx(AnswerScanOverlay::new);
    String title = "DeskGrade own scanning capture fixture " + UUID.randomUUID();
    Stage stage =
        onFx(
            () -> {
              var background = new Pane();
              background.setStyle("-fx-background-color: #e5f5e9;");
              var focus = new TextField("Synthetic fixture only");
              focus.relocate(20, 250);
              background.getChildren().add(focus);
              var shown = new Stage();
              shown.setTitle(title);
              shown.setAlwaysOnTop(true);
              shown.setScene(new Scene(new StackPane(background, overlay), 500, 360));
              shown.show();
              shown.toFront();
              shown.requestFocus();
              focus.requestFocus();
              return shown;
            });
    var windows = new WindowsWindowService();
    try {
      long handle =
          windows.listWindows().stream()
              .filter(
                  choice ->
                      choice.title().equals(title)
                          && choice.identity().processId() == ProcessHandle.current().pid())
              .findFirst()
              .orElseThrow()
              .identity()
              .hwnd();
      onFx(
          () -> {
            stage.toFront();
            stage.requestFocus();
            return null;
          });
      boolean activationRequested =
          windows.activateForSelection(windows.snapshot(handle).identity());
      await(
          () -> windows.snapshot(handle).foreground(),
          () ->
              "OWN_SYNTHETIC_FOCUS_NOT_ACQUIRED: activationReturned="
                  + activationRequested
                  + "; "
                  + syntheticWindowFlags(windows, handle),
          30);
      awaitSyntheticScenePresentation();
      var roi = new LogicalRect(60, 65, 280, 140);
      var binding = windows.bind(handle, roi);
      var raw = new WindowRegionCapture(windows, Clock.systemUTC());
      var rawTiming = new java.util.concurrent.atomic.AtomicReference<CaptureTiming>();
      raw.setTimingObserver(rawTiming::set);
      var baselineDiagnostic = new java.util.concurrent.atomic.AtomicReference<>("NOT_ATTEMPTED");
      WindowRegionCapture.flushDesktopComposition();
      await(
          () -> {
            try {
              var first = raw.capture(binding);
              var pixels =
                  javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(first.png()));
              int center = pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2);
              boolean same = first.sha256().equals(raw.capture(binding).sha256());
              baselineDiagnostic.set(
                  "center=" + Integer.toHexString(center) + "; twoFreshFramesEqual=" + same);
              return center == 0xffe5f5e9 && same;
            } catch (Exception transientFrame) {
              baselineDiagnostic.set(
                  "exceptionType="
                      + transientFrame.getClass().getSimpleName()
                      + "; windowSafety="
                      + windows.check(binding).name()
                      + "; capturePhases="
                      + (rawTiming.get() == null ? "NONE" : rawTiming.get().phaseNanos().keySet()));
              return false;
            }
          },
          () -> "OWN_SYNTHETIC_BASELINE_NOT_READY: " + baselineDiagnostic.get());
      var baseline = raw.capture(binding);
      var originalFocus = onFx(() -> stage.getScene().getFocusOwner());
      var guarded = new WindowRegionCapture(windows, Clock.systemUTC());
      guarded.setCaptureBoundary(ignored -> overlay.prepareCapture());
      var timingEvents = new java.util.ArrayList<CaptureTiming>();
      Thread captureCaller = Thread.currentThread();
      guarded.setTimingObserver(
          summary -> {
            assertSame(captureCaller, Thread.currentThread());
            timingEvents.add(summary);
          });
      for (int phase = 0; phase < 4; phase++) {
        overlay.scan(roi);
        await(
            () -> {
              try {
                return !baseline.sha256().equals(raw.capture(binding).sha256());
              } catch (Exception notPaintedYet) {
                return false;
              }
            });
        if (phase == 0) {
          Files.createDirectories(Path.of("target/scan-animation-test"));
          Files.write(
              Path.of("target/scan-animation-test/visible-synthetic-roi.png"),
              raw.capture(binding).png());
        }
        assertTrue(onFx(overlay::isMouseTransparent));
        assertFalse(onFx(overlay::isFocusTraversable));
        assertSame(originalFocus, onFx(() -> stage.getScene().getFocusOwner()));
        var clean = guarded.capture(binding);
        assertEquals(
            baseline.sha256(), clean.sha256(), "Animation must not enter the actual OS capture");
        assertArrayEquals(baseline.png(), clean.png());
        assertFalse(onFx(overlay::isVisible));
        assertEquals(baseline.sha256(), guarded.capture(binding).sha256());
      }
      try (var scope = overlay.prepareCapture()) {
        overlay.scan(roi);
        onFx(() -> null);
        assertFalse(onFx(overlay::isVisible), "A late show cannot enter an active capture scope");
        assertEquals(baseline.sha256(), raw.capture(binding).sha256());
      }
      await(
          () -> {
            try {
              return onFx(overlay::isVisible);
            } catch (Exception failure) {
              return false;
            }
          });
      overlay.clear();
      assertEquals(baseline.sha256(), guarded.capture(binding).sha256());
      assertEquals(
          9,
          timingEvents.size(),
          "One summary per existing guarded capture, with no extra capture");
      for (var summary : timingEvents) {
        assertTrue(summary.successful());
        assertEquals(CaptureTiming.Phase.values().length - 1, summary.phaseNanos().size());
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.RAW_CAPTURE));
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.SAFETY_BEFORE));
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.SAFETY_AFTER));
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.BOUNDARY_PREPARE));
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.BOUNDARY_CLOSE));
        assertTrue(summary.phaseNanos().containsKey(CaptureTiming.Phase.PIXEL_COMPARE));
        assertNotEquals(
            summary.phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE),
            summary.phaseNanos().containsKey(CaptureTiming.Phase.PNG_REUSE));
        assertTrue(summary.phaseNanos().values().stream().allMatch(nanos -> nanos >= 0));
        assertTrue(
            summary.elapsedNanos()
                >= summary.phaseNanos().values().stream().mapToLong(Long::longValue).sum());
      }
      assertEquals(
          1,
          timingEvents.stream()
              .filter(summary -> summary.phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE))
              .count());
      assertEquals(
          8,
          timingEvents.stream()
              .filter(summary -> summary.phaseNanos().containsKey(CaptureTiming.Phase.PNG_REUSE))
              .count());
      guarded.setTimingObserver(
          summary -> {
            throw new IllegalStateException("Synthetic observer failure");
          });
      assertArrayEquals(
          baseline.png(),
          guarded.capture(binding).png(),
          "Observer failure cannot change pixels or a successful capture");
      var failedTiming = new java.util.concurrent.atomic.AtomicReference<CaptureTiming>();
      guarded.setTimingObserver(failedTiming::set);
      var preparationFailure = new IllegalStateException("Synthetic boundary preparation failure");
      guarded.setCaptureBoundary(
          target -> {
            throw preparationFailure;
          });
      assertSame(
          preparationFailure,
          assertThrows(IllegalStateException.class, () -> guarded.capture(binding)));
      assertFalse(failedTiming.get().successful());
      assertEquals(
          java.util.Set.of(CaptureTiming.Phase.BOUNDARY_PREPARE),
          failedTiming.get().phaseNanos().keySet());
      var closeFailure = new IllegalStateException("Synthetic original close failure");
      guarded.setCaptureBoundary(
          target ->
              () -> {
                throw closeFailure;
              });
      assertSame(
          closeFailure, assertThrows(IllegalStateException.class, () -> guarded.capture(binding)));
      assertFalse(failedTiming.get().successful());
      assertTrue(
          failedTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.DIGEST_AND_RESULT));
      var invalid =
          new app.deskgrade.core.domain.desktop.TargetBinding(
              binding.calibratedWindow(), new LogicalRect(0, 0, 1, 1), binding.calibratedRegion());
      var originalSafety =
          assertThrows(IllegalStateException.class, () -> guarded.capture(invalid));
      assertEquals("Target window is unsafe for capture.", originalSafety.getMessage());
      assertArrayEquals(new Throwable[] {closeFailure}, originalSafety.getSuppressed());
      assertFalse(failedTiming.get().successful());
      assertTrue(failedTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.SAFETY_BEFORE));
      assertFalse(failedTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.RAW_CAPTURE));
      assertFalse(failedTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PIXEL_COMPARE));

      // A failed scope must not publish a prepared miss. The next successful fresh capture still
      // encodes, and only a later identical fresh capture can reuse that verified PNG.
      var publicationProbe = new WindowRegionCapture(windows, new IncrementingClock());
      var publicationTiming = new java.util.concurrent.atomic.AtomicReference<CaptureTiming>();
      publicationProbe.setTimingObserver(publicationTiming::set);
      publicationProbe.setCaptureBoundary(
          target ->
              () -> {
                throw closeFailure;
              });
      assertSame(
          closeFailure,
          assertThrows(IllegalStateException.class, () -> publicationProbe.capture(binding)));
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE));
      assertFalse(publicationTiming.get().successful());
      var entered = new java.util.concurrent.atomic.AtomicInteger();
      var exited = new java.util.concurrent.atomic.AtomicInteger();
      publicationProbe.setCaptureBoundary(
          target -> {
            entered.incrementAndGet();
            var scope = overlay.prepareCapture();
            return () -> {
              try {
                scope.close();
              } finally {
                exited.incrementAndGet();
              }
            };
          });
      var firstVerified = publicationProbe.capture(binding);
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE));
      var identicalFresh = publicationProbe.capture(binding);
      assertArrayEquals(firstVerified.png(), identicalFresh.png());
      assertEquals(firstVerified.sha256(), identicalFresh.sha256());
      assertNotEquals(firstVerified.capturedAt(), identicalFresh.capturedAt());
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_REUSE));
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.RAW_CAPTURE));
      assertTrue(
          publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.SAFETY_BEFORE));
      assertTrue(
          publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.SAFETY_AFTER));
      assertEquals(2, entered.get());
      assertEquals(2, exited.get());

      // Changing actual synthetic scene pixels must defeat the memo, even with the same binding.
      var changedPixel =
          onFx(
              () -> {
                var mark = new Pane();
                mark.setStyle("-fx-background-color: #000000;");
                mark.resizeRelocate(roi.x() + 10, roi.y() + 10, 1, 1);
                mark.setManaged(false);
                var background =
                    (Pane) ((StackPane) stage.getScene().getRoot()).getChildren().get(0);
                background.getChildren().add(mark);
                return mark;
              });
      awaitSyntheticScenePresentation();
      WindowRegionCapture.flushDesktopComposition();
      var changedFresh = publicationProbe.capture(binding);
      assertNotEquals(firstVerified.sha256(), changedFresh.sha256());
      assertFalse(java.util.Arrays.equals(firstVerified.png(), changedFresh.png()));
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE));
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.RAW_CAPTURE));
      onFx(
          () -> {
            ((Pane) changedPixel.getParent()).getChildren().remove(changedPixel);
            return null;
          });
      awaitSyntheticScenePresentation();
      WindowRegionCapture.flushDesktopComposition();
      assertArrayEquals(baseline.png(), publicationProbe.capture(binding).png());
      assertTrue(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE));
      assertEquals(4, entered.get());
      assertEquals(4, exited.get());
      assertEquals(
          "Target window is unsafe for capture.",
          assertThrows(IllegalStateException.class, () -> publicationProbe.capture(invalid))
              .getMessage());
      assertFalse(publicationTiming.get().successful());
      assertFalse(
          publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.RAW_CAPTURE));
      assertFalse(publicationTiming.get().phaseNanos().containsKey(CaptureTiming.Phase.PNG_REUSE));
    } finally {
      onFx(
          () -> {
            overlay.clear();
            stage.close();
            return null;
          });
    }
  }

  private static void await(BooleanSupplier predicate) throws Exception {
    await(predicate, () -> "Own synthetic window did not reach the expected state");
  }

  private static void await(
      BooleanSupplier predicate, java.util.function.Supplier<String> diagnostic) throws Exception {
    await(predicate, diagnostic, 5);
  }

  private static void await(
      BooleanSupplier predicate, java.util.function.Supplier<String> diagnostic, long seconds)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    boolean matched = predicate.getAsBoolean();
    while (!matched && System.nanoTime() < deadline) {
      Thread.sleep(40);
      matched = predicate.getAsBoolean();
    }
    assertTrue(matched, diagnostic);
  }

  private static String syntheticWindowFlags(WindowsWindowService windows, long handle) {
    try {
      var snapshot = windows.snapshot(handle);
      return "foreground="
          + snapshot.foreground()
          + "; visible="
          + snapshot.visible()
          + "; minimized="
          + snapshot.minimized()
          + "; cloaked="
          + snapshot.cloaked()
          + "; interactive="
          + snapshot.interactive();
    } catch (RuntimeException failure) {
      return "snapshotExceptionType=" + failure.getClass().getSimpleName();
    }
  }

  private static <T> T onFx(Callable<T> action) throws Exception {
    var task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  /** Test-owned scene changes are independent of the scan overlay's clean-frame fence. */
  private static void awaitSyntheticScenePresentation() throws Exception {
    var rendered = new java.util.concurrent.CompletableFuture<Void>();
    onFx(
        () -> {
          new javafx.animation.AnimationTimer() {
            private int pulses;

            @Override
            public void handle(long now) {
              if (++pulses < 3) return;
              stop();
              Platform.runLater(() -> rendered.complete(null));
            }
          }.start();
          Platform.requestNextPulse();
          return null;
        });
    rendered.get(5, TimeUnit.SECONDS);
  }

  private static final class IncrementingClock extends Clock {
    private final java.util.concurrent.atomic.AtomicLong tick =
        new java.util.concurrent.atomic.AtomicLong();

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return java.time.Instant.EPOCH.plusNanos(tick.getAndIncrement());
    }
  }
}
