package app.deskgrade.desktop;

import java.util.Objects;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Window;
import javafx.util.Duration;

/** Scene-level help: disabled JavaFX controls do not receive ordinary tooltip mouse events. */
final class DisabledActionHints {
  private static final String REASON = DisabledActionHints.class.getName() + ".reason";
  private static final String INSTALLED = DisabledActionHints.class.getName() + ".installed";
  private final Scene scene;
  private final Tooltip tooltip = new Tooltip();
  private final PauseTransition delay = new PauseTransition(Duration.millis(400));
  private ButtonBase target;
  private String explanation;
  private double screenX, screenY;
  private final ChangeListener<Boolean> disableChanged =
      (o, before, disabled) -> {
        if (!disabled) hide();
      };
  private final ChangeListener<Boolean> windowInactive =
      (o, before, active) -> {
        if (!active) hide();
      };

  static void reason(Node node, String reason) {
    reason(node, () -> reason);
    node.setAccessibleHelp(reason);
  }

  static void reason(Node node, Supplier<String> reason) {
    node.getProperties().put(REASON, Objects.requireNonNull(reason));
  }

  static void install(Parent root) {
    if (root.getScene() != null) install(root.getScene());
    root.sceneProperty()
        .addListener(
            (o, before, after) -> {
              if (after != null) install(after);
            });
  }

  private static void install(Scene scene) {
    if (!scene.getProperties().containsKey(INSTALLED))
      scene.getProperties().put(INSTALLED, new DisabledActionHints(scene));
  }

  private DisabledActionHints(Scene scene) {
    this.scene = scene;
    tooltip.setWrapText(true);
    tooltip.setMaxWidth(440);
    tooltip.setAutoHide(false);
    delay.setOnFinished(event -> show());
    scene.addEventFilter(
        MouseEvent.MOUSE_MOVED, event -> hover(event.getScreenX(), event.getScreenY()));
    scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> hide());
    scene.addEventFilter(ScrollEvent.SCROLL, event -> hide());
    scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> hide());
    scene.addEventFilter(
        MouseEvent.MOUSE_EXITED,
        event -> {
          if (event.getX() <= 0
              || event.getY() <= 0
              || event.getX() >= scene.getWidth()
              || event.getY() >= scene.getHeight()) hide();
        });
    scene
        .windowProperty()
        .addListener(
            (o, before, after) -> {
              hide();
              if (before != null) {
                before.focusedProperty().removeListener(windowInactive);
                before.showingProperty().removeListener(windowInactive);
              }
              attachWindow(after);
            });
    attachWindow(scene.getWindow());
  }

  private void attachWindow(Window window) {
    if (window != null) {
      window.focusedProperty().addListener(windowInactive);
      window.showingProperty().addListener(windowInactive);
    }
  }

  private void hover(double x, double y) {
    var hovered = disabledButtonAt(scene.getRoot(), x, y);
    String nextReason = hovered == null ? null : explanation(hovered);
    screenX = x;
    screenY = y;
    if (hovered == target && Objects.equals(nextReason, explanation)) return;
    hide();
    target = hovered;
    explanation = nextReason;
    if (target != null) {
      target.disabledProperty().addListener(disableChanged);
      delay.playFromStart();
    }
  }

  private void show() {
    if (target == null
        || !target.isDisabled()
        || scene.getWindow() == null
        || !scene.getWindow().isShowing()
        || disabledButtonAt(scene.getRoot(), screenX, screenY) != target) {
      hide();
      return;
    }
    tooltip.setText(explanation(target));
    tooltip.show(target, screenX + 12, screenY + 18);
  }

  private void hide() {
    delay.stop();
    tooltip.hide();
    if (target != null) target.disabledProperty().removeListener(disableChanged);
    target = null;
    explanation = null;
  }

  static String explanation(ButtonBase button) {
    for (Node node = button; node != null; node = node.getParent()) {
      Object value = node.getProperties().get(REASON);
      if (value instanceof Supplier<?> supplier) {
        Object message = supplier.get();
        if (message instanceof String text && !text.isBlank()) return text;
      }
    }
    return "此操作暂不可用，请先完成当前页面的必需配置。";
  }

  static ButtonBase disabledButtonAt(Node node, double screenX, double screenY) {
    for (Node hit = topmostAt(node, screenX, screenY); hit != null; hit = hit.getParent())
      if (hit instanceof ButtonBase button) return button.isDisabled() ? button : null;
    return null;
  }

  private static Node topmostAt(Node node, double screenX, double screenY) {
    if (!node.isVisible() || node.getOpacity() == 0 || node.isMouseTransparent()) return null;
    Point2D local = node.screenToLocal(screenX, screenY);
    if (local == null || !node.contains(local)) return null;
    Node clip = node.getClip();
    if (clip != null && !clip.contains(clip.parentToLocal(local))) return null;
    if (node instanceof Parent parent) {
      var children = parent.getChildrenUnmodifiable();
      for (int i = children.size() - 1; i >= 0; i--) {
        var found = topmostAt(children.get(i), screenX, screenY);
        if (found != null) return found;
      }
    }
    return node;
  }
}
