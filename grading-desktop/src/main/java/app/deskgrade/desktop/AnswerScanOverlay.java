package app.deskgrade.desktop;

import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.core.domain.desktop.LogicalRect;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/** Visual decoration only. The raw-screen capture boundary always removes it first. */
final class AnswerScanOverlay extends Pane {
  private final ScanCaptureFence fence = new ScanCaptureFence();
  private final Pane answer = new Pane();
  private final Rectangle border = new Rectangle(), beam = new Rectangle();
  private final TranslateTransition motion = new TranslateTransition(Duration.seconds(1.6), beam);
  private volatile LogicalRect requestedRegion;
  private volatile CompletableFuture<Void> lastFlushed = fence.clearRequested().cleanFrame();

  AnswerScanOverlay() {
    setId("answer-scanning-overlay");
    setMouseTransparent(true);
    setPickOnBounds(false);
    setFocusTraversable(false);
    setMinSize(0, 0);
    setPrefSize(0, 0);
    answer.setManaged(false);
    answer.setMouseTransparent(true);
    border.setFill(Color.TRANSPARENT);
    border.setStroke(Color.web("#f065aa", .9));
    border.setStrokeWidth(2);
    border.setArcWidth(8);
    border.setArcHeight(8);
    beam.setFill(
        new LinearGradient(
            0,
            0,
            0,
            1,
            true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.TRANSPARENT),
            new Stop(.9, Color.web("#f472b6", .18)),
            new Stop(1, Color.web("#ef5aa1", .9))));
    answer.getChildren().addAll(beam, border);
    getChildren().add(answer);
    setVisible(false);
    motion.setAutoReverse(true);
    motion.setCycleCount(Animation.INDEFINITE);
  }

  void scan(LogicalRect region) {
    requestedRegion = region;
    long ticket = fence.showRequested();
    runFx(() -> showIfCurrent(ticket, region));
  }

  void clear() {
    requestedRegion = null;
    hideAndAwaitFrame(fence.clearRequested());
  }

  WindowRegionCapture.CaptureScope prepareCapture() {
    if (Platform.isFxApplicationThread())
      throw new IllegalStateException("Screen capture cannot wait on its own render thread");
    var request = fence.beginCapture();
    var released = new AtomicBoolean();
    WindowRegionCapture.CaptureScope scope =
        () -> {
          if (!released.compareAndSet(false, true)) return;
          long pending = fence.endCapture();
          LogicalRect region = requestedRegion;
          if (pending >= 0 && region != null) runFx(() -> showIfCurrent(pending, region));
        };
    try {
      hideAndAwaitFrame(request);
      request.cleanFrame().get(2, TimeUnit.SECONDS);
      if (lastFlushed != request.cleanFrame()) {
        WindowRegionCapture.flushDesktopComposition();
        lastFlushed = request.cleanFrame();
      }
      return scope;
    } catch (Exception | LinkageError failure) {
      scope.close();
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Scanning decoration could not be excluded from capture");
    }
  }

  private void showIfCurrent(long ticket, LogicalRect region) {
    if (!fence.mayShow(ticket)
        || getScene() == null
        || getScene().getWindow() == null
        || !getScene().getWindow().isShowing()
        || !getScene().getWindow().isFocused()) return;
    if (region == null
        || region.x() < 0
        || region.y() < 0
        || region.width() < 4
        || region.height() < 4
        || region.x() + region.width() > getWidth() + .5
        || region.y() + region.height() > getHeight() + .5) return;
    motion.stop();
    answer.relocate(region.x(), region.y());
    answer.resize(region.width(), region.height());
    answer.setClip(new Rectangle(region.width(), region.height()));
    border.setX(1);
    border.setY(1);
    border.setWidth(region.width() - 2);
    border.setHeight(region.height() - 2);
    beam.setWidth(region.width());
    beam.setHeight(Math.min(42, region.height()));
    motion.setFromY(-beam.getHeight() + 2);
    motion.setToY(region.height() - 2);
    setVisible(true);
    motion.playFromStart();
  }

  private void hideAndAwaitFrame(ScanCaptureFence.Clear request) {
    if (!request.renderRequired()) return;
    runFx(
        () -> {
          if (!fence.shouldHide()) {
            request.cleanFrame().complete(null);
            return;
          }
          motion.stop();
          setVisible(false);
          if (getScene() == null
              || getScene().getWindow() == null
              || !getScene().getWindow().isShowing()) {
            request.cleanFrame().complete(null);
            return;
          }
          // Three distinct render pulses plus the following event turn; native capture then waits
          // for DWM presentation. A stalled/failed barrier never permits a screen capture.
          new AnimationTimer() {
            private int pulses;

            @Override
            public void handle(long now) {
              if (++pulses < 3) return;
              stop();
              Platform.runLater(() -> request.cleanFrame().complete(null));
            }
          }.start();
          Platform.requestNextPulse();
        });
  }

  private static void runFx(Runnable action) {
    if (Platform.isFxApplicationThread()) action.run();
    else Platform.runLater(action);
  }
}
