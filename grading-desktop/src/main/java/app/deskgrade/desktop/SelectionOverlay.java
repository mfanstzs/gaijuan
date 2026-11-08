package app.deskgrade.desktop;

import app.deskgrade.adapters.windows.CoordinateMapper;
import app.deskgrade.core.domain.desktop.*;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** Single-display transparent selector. It never captures an image while its overlay is present. */
final class SelectionOverlay {
  private SelectionOverlay() {}

  static Stage show(WindowSnapshot target, Consumer<Optional<LogicalRect>> completed) {
    return show(target, "当前单题答题区域", completed);
  }

  static Stage show(
      WindowSnapshot target, String regionName, Consumer<Optional<LogicalRect>> completed) {
    Stage overlay = new Stage(StageStyle.TRANSPARENT);
    PixelRect bounds = target.display().awtBounds();
    Pane pane = new Pane();
    pane.setStyle("-fx-background-color: rgba(8,25,45,0.26);");
    Label help = new Label("拖拽框选" + regionName + " · Esc 取消");
    help.setStyle(
        "-fx-background-color:#173954;-fx-text-fill:white;-fx-padding:12 20;-fx-font-size:18;");
    help.relocate(20, 20);
    Rectangle selection = new Rectangle();
    selection.setFill(Color.color(.2, .7, 1, .14));
    selection.setStroke(Color.CYAN);
    selection.setStrokeWidth(2);
    pane.getChildren().addAll(help, selection);
    double[] start = new double[2];
    boolean[] done = {false};
    Consumer<Optional<LogicalRect>> finish =
        value -> {
          if (done[0]) return;
          done[0] = true;
          overlay.hide();
          overlay.close();
          Platform.runLater(() -> completed.accept(value));
        };
    pane.setOnMousePressed(
        e -> {
          start[0] = Math.rint(e.getScreenX());
          start[1] = Math.rint(e.getScreenY());
        });
    pane.setOnMouseDragged(
        e -> {
          selection.setX(Math.min(start[0], e.getScreenX()) - bounds.x());
          selection.setY(Math.min(start[1], e.getScreenY()) - bounds.y());
          selection.setWidth(Math.abs(e.getScreenX() - start[0]));
          selection.setHeight(Math.abs(e.getScreenY() - start[1]));
        });
    pane.setOnMouseReleased(
        e -> {
          try {
            var mapper = new CoordinateMapper();
            PixelPoint first =
                mapper.awtToPhysical(
                    Math.min(start[0], Math.rint(e.getScreenX())),
                    Math.min(start[1], Math.rint(e.getScreenY())),
                    target.display());
            PixelPoint last =
                mapper.awtToPhysical(
                    Math.max(start[0], Math.rint(e.getScreenX())),
                    Math.max(start[1], Math.rint(e.getScreenY())),
                    target.display());
            if (last.x() - first.x() < 16 || last.y() - first.y() < 16) {
              help.setText("选区太小，请拖拽选择至少 16×16 像素；Esc 取消");
              return;
            }
            LogicalRect area =
                mapper.toClientLogical(
                    new PixelRect(first.x(), first.y(), last.x() - first.x(), last.y() - first.y()),
                    target);
            finish.accept(Optional.of(area));
          } catch (IllegalArgumentException invalid) {
            help.setText("请在目标窗口客户区内选择至少 16×16 像素；Esc 取消");
          }
        });
    Scene scene = new Scene(pane, bounds.width(), bounds.height(), Color.TRANSPARENT);
    scene.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ESCAPE) finish.accept(Optional.empty());
        });
    overlay.setOnCloseRequest(e -> finish.accept(Optional.empty()));
    overlay.setScene(scene);
    overlay.setX(bounds.x());
    overlay.setY(bounds.y());
    overlay.setWidth(bounds.width());
    overlay.setHeight(bounds.height());
    overlay.setAlwaysOnTop(true);
    overlay.setOnShown(
        e -> {
          overlay.toFront();
          overlay.requestFocus();
          pane.requestFocus();
        });
    overlay.show();
    return overlay;
  }
}
