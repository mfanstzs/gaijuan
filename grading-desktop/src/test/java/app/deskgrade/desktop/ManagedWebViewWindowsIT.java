package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Own JavaFX stages only: no remote requests, desktop input, account, or grading actions. */
@EnabledOnOs(OS.WINDOWS)
class ManagedWebViewWindowsIT {
  @BeforeAll
  static void startToolkit() throws Exception {
    var started = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          started.countDown();
        });
    assertTrue(started.await(15, TimeUnit.SECONDS));
  }

  @AfterAll
  static void stopToolkit() {
    Platform.exit();
  }

  @Test
  @Timeout(30)
  void ordinaryBrowserMaximizesAndCanRestoreWithoutChangingWebZoom() throws Exception {
    Stage browser =
        onFx(
            () -> {
              var stage = new Stage();
              var view = new WebView();
              ManagedWebView.configureWindow(
                  stage,
                  view,
                  EmbeddedBrowserUrlPolicy.parse("https://example.test/grading"),
                  Screen.getPrimary().getVisualBounds());
              assertTrue(stage.isResizable());
              assertTrue(stage.isMaximized());
              assertEquals(1, view.getZoom());
              stage.show();
              return stage;
            });
    try {
      onFx(
          () -> {
            var visual = Screen.getPrimary().getVisualBounds();
            assertTrue(browser.isMaximized());
            assertTrue(
                browser.getWidth() >= visual.getWidth() - 32,
                "Maximized browser should occupy the work-area width");
            assertTrue(
                browser.getHeight() >= visual.getHeight() - 32,
                "Maximized browser should occupy the work-area height");
            browser.setMaximized(false);
            browser.setWidth(Math.min(1100, visual.getWidth()));
            browser.setHeight(Math.min(800, visual.getHeight()));
            assertTrue(browser.isResizable());
            assertEquals(1, ((WebView) browser.getScene().getRoot()).getZoom());
            return null;
          });
    } finally {
      onFx(
          () -> {
            browser.close();
            return null;
          });
    }
  }

  @Test
  @Timeout(20)
  void smallScreenMinimumSizeDoesNotPushRestoredBoundsOffScreen() throws Exception {
    onFx(
        () -> {
          var stage = new Stage();
          try {
            var visual = new Rectangle2D(0, 0, 1024, 600);
            ManagedWebView.configureWindow(
                stage,
                new WebView(),
                EmbeddedBrowserUrlPolicy.parse("https://example.test/grading"),
                visual);
            stage.setMaximized(false);
            assertTrue(stage.getMinWidth() <= stage.getWidth());
            assertTrue(stage.getMinHeight() <= stage.getHeight());
            assertTrue(stage.getX() + stage.getWidth() <= visual.getMaxX());
            assertTrue(stage.getY() + stage.getHeight() <= visual.getMaxY());
          } finally {
            stage.close();
          }
          return null;
        });
  }

  @Test
  @Timeout(20)
  void localMockKeepsTheExactProtocolClientSize() throws Exception {
    onFx(
        () -> {
          var stage = new Stage();
          try {
            ManagedWebView.configureWindow(
                stage,
                new WebView(),
                EmbeddedBrowserUrlPolicy.parse("http://127.0.0.1:8766/"),
                new Rectangle2D(-1920, 0, 1920, 1040));
            assertFalse(stage.isResizable());
            assertFalse(stage.isMaximized());
            assertEquals(900, stage.getScene().getWidth());
            assertEquals(760, stage.getScene().getHeight());
          } finally {
            stage.close();
          }
          return null;
        });
  }

  private static <T> T onFx(Callable<T> action) throws Exception {
    var task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(15, TimeUnit.SECONDS);
  }
}
