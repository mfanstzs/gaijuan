package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Own JavaFX scenes only; synthetic events never move the system mouse or inject system keys. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(20)
class DisabledActionHintsWindowsIT {
  private static final int FX_TIMEOUT_SECONDS = 5;

  @BeforeAll
  static void startToolkit() throws Exception {
    var started = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          started.countDown();
        });
    assertTrue(started.await(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @AfterEach
  void closeOwnWindows() throws Exception {
    onFx(
        () -> {
          for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
          return null;
        });
  }

  @AfterAll
  static void stopToolkit() {
    Platform.exit();
  }

  @Test
  void sceneHoverExplainsDisabledActionWithoutExecutingItAndStopsWhenEnabled() throws Exception {
    AtomicInteger actions = new AtomicInteger();
    Fixture fixture = onFx(() -> fixture(false));
    String text = "Synthetic requirement is missing";
    onFx(
        () -> {
          fixture.button.setOnAction(event -> actions.incrementAndGet());
          DisabledActionHints.reason(fixture.button, text);
          fixture.button.setDisable(true);
          move(fixture.scene, center(fixture.button));
          fixture.button.fire();
          return null;
        });
    awaitFx(() -> tooltipShowing(text), true);
    assertEquals(0, actions.get(), "Explaining a disabled button must never execute its action");
    onFx(
        () -> {
          fixture.button.setDisable(false);
          return null;
        });
    awaitFx(() -> tooltipShowing(text), false);
    onFx(
        () -> {
          assertNull(
              DisabledActionHints.disabledButtonAt(
                  fixture.root, center(fixture.button).getX(), center(fixture.button).getY()));
          fixture.button.fire();
          return null;
        });
    assertEquals(1, actions.get());
  }

  @Test
  void inheritedDisabledStateHasAHintAndFiltersNeverConsumeMouseOrKeyboard() throws Exception {
    Fixture fixture = onFx(() -> fixture(true));
    AtomicInteger mouseEvents = new AtomicInteger(),
        keyEvents = new AtomicInteger(),
        actions = new AtomicInteger();
    String text = "Synthetic parent is not ready";
    onFx(
        () -> {
          DisabledActionHints.reason(fixture.container, text);
          fixture.container.setDisable(true);
          fixture.button.setOnAction(event -> actions.incrementAndGet());
          fixture.scene.addEventHandler(
              MouseEvent.MOUSE_PRESSED, event -> mouseEvents.incrementAndGet());
          fixture.scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> keyEvents.incrementAndGet());
          assertFalse(fixture.button.isDisable());
          assertTrue(fixture.button.isDisabled());
          assertEquals(text, DisabledActionHints.explanation(fixture.button));
          assertSame(
              fixture.button,
              DisabledActionHints.disabledButtonAt(
                  fixture.root, center(fixture.button).getX(), center(fixture.button).getY()));
          move(fixture.scene, center(fixture.button));
          return null;
        });
    awaitFx(() -> tooltipShowing(text), true);
    onFx(
        () -> {
          Event.fireEvent(
              fixture.scene,
              mouse(fixture.scene, MouseEvent.MOUSE_PRESSED, center(fixture.button)));
          return null;
        });
    awaitFx(() -> tooltipShowing(text), false);
    assertEquals(1, mouseEvents.get(), "Scene help must not consume pointer presses");
    onFx(
        () -> {
          move(fixture.scene, center(fixture.button));
          return null;
        });
    awaitFx(() -> tooltipShowing(text), true);
    onFx(
        () -> {
          Event.fireEvent(
              fixture.scene,
              new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, false, false, false));
          fixture.button.fire();
          return null;
        });
    awaitFx(() -> tooltipShowing(text), false);
    assertEquals(1, keyEvents.get(), "Scene help must not consume keyboard events");
    assertEquals(0, actions.get(), "An inherited disabled button is still not actionable");
    onFx(
        () -> {
          fixture.container.setDisable(false);
          fixture.button.fire();
          return null;
        });
    assertEquals(1, actions.get());
  }

  @Test
  void scrollViewportClippingPreventsHintsForOffViewportButtons() throws Exception {
    var clipped =
        onFx(
            () -> {
              Pane content = new Pane();
              content.setPrefSize(240, 800);
              Button visible = new Button("Visible synthetic action"),
                  offViewport = new Button("Clipped synthetic action");
              visible.setDisable(true);
              offViewport.setDisable(true);
              visible.resizeRelocate(8, 8, 190, 26);
              offViewport.resizeRelocate(8, 200, 190, 26);
              content.getChildren().addAll(visible, offViewport);
              ScrollPane scroll = new ScrollPane(content);
              scroll.setPrefSize(300, 200);
              scroll.setMinSize(300, 200);
              scroll.setMaxSize(300, 200);
              scroll.setPadding(new Insets(24));
              scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
              scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
              scroll.resizeRelocate(20, 20, 300, 200);
              Pane root = new Pane(scroll);
              Scene scene = new Scene(root, 420, 320);
              Stage stage = new Stage();
              stage.setScene(scene);
              stage.show();
              root.applyCss();
              root.layout();
              DisabledActionHints.install(root);
              offViewport.setLayoutY(scroll.getViewportBounds().getHeight() + 3);
              root.layout();
              Point2D offPoint = center(offViewport);
              Bounds scrollOnScreen = scroll.localToScreen(scroll.getBoundsInLocal());
              assertTrue(
                  scrollOnScreen.contains(offPoint),
                  "The test point must be in scroll-pane padding, not outside the whole scene");
              assertSame(
                  visible,
                  DisabledActionHints.disabledButtonAt(
                      root, center(visible).getX(), center(visible).getY()));
              assertNull(
                  DisabledActionHints.disabledButtonAt(root, offPoint.getX(), offPoint.getY()),
                  "A control outside the clipped viewport must not be selected through scroll-pane padding");
              DisabledActionHints.reason(offViewport, "Clipped action must never show a hint");
              move(scene, offPoint);
              return scene;
            });
    waitForPulseAndDelay(clipped);
    assertFalse(onFx(() -> tooltipShowing("Clipped action must never show a hint")));
  }

  @Test
  void losingFocusOfAnAlreadyAttachedOwnerHidesTheHintWithoutAnotherMouseMove() throws Exception {
    Fixture fixture = onFx(() -> fixture(true));
    awaitFx(fixture.stage::isFocused, true);
    String text = "Synthetic owner focus requirement";
    onFx(
        () -> {
          fixture.button.setDisable(true);
          DisabledActionHints.reason(fixture.button, text);
          move(fixture.scene, center(fixture.button));
          return null;
        });
    awaitFx(() -> tooltipShowing(text), true);
    onFx(
        () -> {
          Stage otherOwnStage = new Stage();
          otherOwnStage.setScene(new Scene(new Pane(), 160, 100));
          otherOwnStage.show();
          otherOwnStage.requestFocus();
          return null;
        });
    awaitFx(fixture.stage::isFocused, false);
    awaitFx(() -> tooltipShowing(text), false);
    assertTrue(
        onFx(fixture.stage::isShowing), "The owner remains open: hiding must follow focus loss");
  }

  private static Fixture fixture(boolean installAfterWindow) {
    Button button = new Button("Synthetic action");
    button.resizeRelocate(12, 12, 180, 36);
    Pane container = new Pane(button);
    container.resizeRelocate(20, 20, 230, 90);
    Pane root = new Pane(container);
    Scene scene = new Scene(root, 400, 240);
    Stage stage = new Stage();
    if (!installAfterWindow) DisabledActionHints.install(root);
    stage.setScene(scene);
    stage.show();
    stage.requestFocus();
    root.applyCss();
    root.layout();
    if (installAfterWindow) DisabledActionHints.install(root);
    return new Fixture(root, container, button, scene, stage);
  }

  private static void move(Scene scene, Point2D point) {
    Event.fireEvent(scene, mouse(scene, MouseEvent.MOUSE_MOVED, point));
  }

  private static MouseEvent mouse(
      Scene scene, javafx.event.EventType<MouseEvent> type, Point2D point) {
    Point2D local = scene.getRoot().screenToLocal(point);
    return new MouseEvent(
        type,
        local.getX(),
        local.getY(),
        point.getX(),
        point.getY(),
        type == MouseEvent.MOUSE_PRESSED ? MouseButton.PRIMARY : MouseButton.NONE,
        0,
        false,
        false,
        false,
        false,
        type == MouseEvent.MOUSE_PRESSED,
        false,
        false,
        true,
        false,
        true,
        new PickResult(scene.getRoot(), local.getX(), local.getY()));
  }

  private static Point2D center(javafx.scene.Node node) {
    return node.localToScreen(
        node.getBoundsInLocal().getCenterX(), node.getBoundsInLocal().getCenterY());
  }

  private static boolean tooltipShowing(String text) {
    return Window.getWindows().stream()
        .anyMatch(
            window ->
                window instanceof Tooltip tooltip
                    && tooltip.isShowing()
                    && text.equals(tooltip.getText()));
  }

  private static void awaitFx(Callable<Boolean> condition, boolean expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (onFx(condition) == expected) return;
      Thread.sleep(40);
    }
    assertEquals(expected, onFx(condition), "Synthetic hover did not settle within three seconds");
  }

  private static void waitForPulseAndDelay(Scene scene) throws Exception {
    CountDownLatch elapsed = new CountDownLatch(1);
    onFx(
        () -> {
          var wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
          wait.setOnFinished(event -> elapsed.countDown());
          wait.play();
          return scene;
        });
    assertTrue(elapsed.await(3, TimeUnit.SECONDS));
  }

  private static <T> T onFx(Callable<T> work) throws Exception {
    var task = new FutureTask<T>(work);
    Platform.runLater(task);
    return task.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private record Fixture(Pane root, Pane container, Button button, Scene scene, Stage stage) {}
}
