package app.deskgrade.desktop;

import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.mock.NativeMockPlatformAdapter;
import app.deskgrade.core.domain.desktop.TargetBinding;
import java.util.Objects;
import java.util.UUID;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** A single separately bindable WebView window with no JavaScript-to-Java bridge. */
final class ManagedWebView implements AutoCloseable {
  static final String UNTRUSTED_TITLE_PREFIX = "DeskGrade 内置网页 · 会话 ";
  static final String TRUSTED_TITLE_PREFIX = NativeMockPlatformAdapter.WINDOW_TITLE + " · 会话 ";

  interface Listener {
    void loaded(Session session);

    void invalidated(Session session, String message);

    void failed(Session session, String message);

    default void geometryChanged(Session session) {}
  }

  record Session(String launchId, EmbeddedBrowserUrlPolicy.Target target, String windowTitle) {
    Session {
      Objects.requireNonNull(launchId);
      Objects.requireNonNull(target);
      Objects.requireNonNull(windowTitle);
      if (!windowTitle.equals(ManagedWebView.windowTitle(target, launchId)))
        throw new IllegalArgumentException(
            "Browser session title does not match its launch identity");
    }

    boolean trustedMockCandidate() {
      return target.localMock() && windowTitle.equals(TRUSTED_TITLE_PREFIX + launchId);
    }

    boolean matchesCurrentProcessWindow(WindowsWindowService.WindowChoice choice) {
      return choice != null
          && choice.identity().processId() == ProcessHandle.current().pid()
          && windowTitle.equals(choice.title());
    }
  }

  private final Listener listener;
  private final Stage owner;
  private volatile BrowserWindow current;

  ManagedWebView(Stage owner, Listener listener) {
    this.owner = Objects.requireNonNull(owner);
    this.listener = Objects.requireNonNull(listener);
  }

  Session open(EmbeddedBrowserUrlPolicy.Target target) {
    requireFxThread();
    Objects.requireNonNull(target);
    disposeCurrent(true, "旧的内置网页窗口已关闭，自动操作权限已撤销。");

    String launchId = UUID.randomUUID().toString();
    String title = windowTitle(target, launchId);
    Session session = new Session(launchId, target, title);
    WebView view = new WebView();
    view.setContextMenuEnabled(false);
    WebEngine engine = view.getEngine();
    engine.setJavaScriptEnabled(true);
    engine.setCreatePopupHandler(features -> null);
    engine.setOnAlert(event -> {});
    engine.setConfirmHandler(message -> false);
    engine.setPromptHandler(prompt -> null);
    engine.setOnStatusChanged(event -> {});

    Stage browser = new Stage();
    browser.setTitle(title);
    var visualBounds = ownerScreenBounds(owner);
    configureWindow(browser, view, target, visualBounds);
    AnswerScanOverlay scan = new AnswerScanOverlay();
    browser.getScene().setRoot(new StackPane(view, scan));
    BrowserWindow window = new BrowserWindow(session, browser, engine, scan);
    current = window;
    browser
        .focusedProperty()
        .addListener(
            (o, before, focused) -> {
              if (!focused) scan.clear();
            });
    browser
        .iconifiedProperty()
        .addListener(
            (o, before, iconified) -> {
              if (iconified) scan.clear();
            });

    javafx.beans.value.ChangeListener<Number> geometryChanged =
        (observable, before, after) -> {
          if (current != window
              || !window.loaded
              || !browser.isShowing()
              || Objects.equals(before, after)) return;
          scan.clear();
          try {
            listener.geometryChanged(session);
          } catch (RuntimeException callbackFailure) {
            invalidateAndClose(window, "窗口布局变更处理失败，自动操作权限已撤销。");
          }
        };
    browser.xProperty().addListener(geometryChanged);
    browser.yProperty().addListener(geometryChanged);
    browser.widthProperty().addListener(geometryChanged);
    browser.heightProperty().addListener(geometryChanged);

    engine
        .locationProperty()
        .addListener(
            (observable, before, location) -> {
              if (current != window || location == null || location.isBlank()) return;
              if (revokesForNavigation(target, location)) {
                invalidateAndClose(window, "已阻止跨站跳转或危险协议；请在顶部输入框重新打开目标网址。");
              }
            });
    engine
        .getLoadWorker()
        .stateProperty()
        .addListener(
            (observable, before, state) -> {
              if (current != window) return;
              if (state == Worker.State.SUCCEEDED) {
                if (!EmbeddedBrowserUrlPolicy.navigationAllowed(target, engine.getLocation())) {
                  invalidateAndClose(window, "网页最终地址不在允许范围内，已关闭。");
                  return;
                }
                if (!window.loaded) {
                  window.loaded = true;
                  try {
                    listener.loaded(session);
                  } catch (RuntimeException callbackFailure) {
                    invalidateAndClose(window, "网页加载回调失败，自动操作权限未启用。");
                  }
                }
              } else if (revokesForLoadState(state)) {
                try {
                  listener.failed(
                      session,
                      session.trustedMockCandidate()
                          ? "网页加载失败，请确认服务已启动且地址可访问。"
                          : "网页加载失败，请确认地址可访问。");
                } finally {
                  invalidateAndClose(window, "网页加载失败，自动操作权限未启用。");
                }
              }
            });
    browser.setOnHidden(
        event -> {
          if (current != window) return;
          window.scan.clear();
          current = null;
          engine.getLoadWorker().cancel();
          listener.invalidated(session, "内置网页窗口已关闭，自动操作权限已撤销。");
        });
    try {
      browser.show();
      browser.toFront();
      engine.load(target.uri().toASCIIString());
      return session;
    } catch (RuntimeException failure) {
      try {
        disposeCurrent(true, "内置网页打开失败，自动操作权限未启用。");
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  boolean isOpen(Session session) {
    return current != null && current.session.equals(session) && current.stage.isShowing();
  }

  /** Synchronous request ticketing; only scene-graph changes are marshalled to JavaFX. */
  void updateAnswerScan(TargetBinding binding, boolean grading) {
    BrowserWindow window = current;
    if (window == null) return;
    if (!grading
        || binding == null
        || binding.calibratedWindow().identity().processId() != ProcessHandle.current().pid()
        || !window.session.windowTitle().equals(binding.calibratedWindow().title())) {
      window.scan.clear();
      return;
    }
    window.scan.scan(binding.answerRegion());
  }

  WindowRegionCapture.CaptureScope prepareCapture(TargetBinding binding) {
    BrowserWindow window = current;
    return window == null ? () -> {} : window.scan.prepareCapture();
  }

  boolean isLoadedWindow(Session session, WindowsWindowService.WindowChoice choice) {
    BrowserWindow window = current;
    return window != null
        && window.loaded
        && window.session.equals(session)
        && window.stage.isShowing()
        && window.stage.getTitle().equals(session.windowTitle())
        && session.matchesCurrentProcessWindow(choice);
  }

  static String windowTitle(EmbeddedBrowserUrlPolicy.Target target, String launchId) {
    Objects.requireNonNull(target);
    if (launchId == null || launchId.isBlank())
      throw new IllegalArgumentException("Browser launch identity is required");
    return (target.localMock() ? TRUSTED_TITLE_PREFIX : UNTRUSTED_TITLE_PREFIX) + launchId;
  }

  static boolean revokesForNavigation(EmbeddedBrowserUrlPolicy.Target target, String destination) {
    return !EmbeddedBrowserUrlPolicy.navigationAllowed(target, destination);
  }

  static boolean revokesForLoadState(Worker.State state) {
    return state == Worker.State.FAILED;
  }

  record WindowLayout(
      double x, double y, double width, double height, boolean maximized, boolean resizable) {}

  static WindowLayout windowLayout(EmbeddedBrowserUrlPolicy.Target target, Rectangle2D visual) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(visual);
    if (visual.getWidth() <= 0
        || visual.getHeight() <= 0
        || !Double.isFinite(visual.getMinX())
        || !Double.isFinite(visual.getMinY())
        || !Double.isFinite(visual.getWidth())
        || !Double.isFinite(visual.getHeight()))
      throw new IllegalArgumentException("Screen work area must be finite and positive");
    // The local DGM1 fixture retains its exact client geometry. Public test sites are ordinary
    // HTTPS.
    double width = target.localMock() ? 900 : Math.min(1440, visual.getWidth() * .92);
    double height = target.localMock() ? 760 : Math.min(1000, visual.getHeight() * .92);
    return new WindowLayout(
        visual.getMinX() + Math.max(0, (visual.getWidth() - width) / 2),
        visual.getMinY() + Math.max(0, (visual.getHeight() - height) / 2),
        width,
        height,
        !target.localMock(),
        !target.localMock());
  }

  static void configureWindow(
      Stage browser, WebView view, EmbeddedBrowserUrlPolicy.Target target, Rectangle2D visual) {
    var layout = windowLayout(target, visual);
    browser.setResizable(layout.resizable());
    browser.setScene(new Scene(view, layout.width(), layout.height()));
    browser.setX(layout.x());
    browser.setY(layout.y());
    if (layout.resizable()) {
      browser.setMinWidth(Math.min(900, layout.width()));
      browser.setMinHeight(Math.min(600, layout.height()));
      // These are outer restore bounds; the maximized client stays inside the screen work area.
      browser.setWidth(layout.width());
      browser.setHeight(layout.height());
      browser.setMaximized(true);
    }
  }

  private static Rectangle2D ownerScreenBounds(Stage owner) {
    if (!owner.isShowing() || !Double.isFinite(owner.getX()) || !Double.isFinite(owner.getY()))
      return Screen.getPrimary().getVisualBounds();
    return Screen.getScreensForRectangle(
            owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
        .stream()
        .max(java.util.Comparator.comparingDouble(screen -> overlapArea(screen.getBounds(), owner)))
        .orElse(Screen.getPrimary())
        .getVisualBounds();
  }

  private static double overlapArea(Rectangle2D bounds, Stage owner) {
    double width =
        Math.max(
            0,
            Math.min(bounds.getMaxX(), owner.getX() + owner.getWidth())
                - Math.max(bounds.getMinX(), owner.getX()));
    double height =
        Math.max(
            0,
            Math.min(bounds.getMaxY(), owner.getY() + owner.getHeight())
                - Math.max(bounds.getMinY(), owner.getY()));
    return width * height;
  }

  private void invalidateAndClose(BrowserWindow window, String message) {
    if (current != window) return;
    current = null;
    window.scan.clear();
    window.engine.getLoadWorker().cancel();
    try {
      listener.invalidated(window.session, message);
    } finally {
      window.stage.close();
    }
  }

  private void disposeCurrent(boolean notify, String message) {
    BrowserWindow existing = current;
    if (existing == null) return;
    current = null;
    existing.scan.clear();
    existing.engine.getLoadWorker().cancel();
    try {
      if (notify) listener.invalidated(existing.session, message);
    } finally {
      existing.stage.close();
    }
  }

  @Override
  public void close() {
    requireFxThread();
    disposeCurrent(true, "内置网页窗口已关闭，自动操作权限已撤销。");
  }

  private static void requireFxThread() {
    if (!Platform.isFxApplicationThread())
      throw new IllegalStateException("Embedded browser must be controlled on the JavaFX thread.");
  }

  private static final class BrowserWindow {
    private final Session session;
    private final Stage stage;
    private final WebEngine engine;
    private final AnswerScanOverlay scan;
    private boolean loaded;

    private BrowserWindow(Session session, Stage stage, WebEngine engine, AnswerScanOverlay scan) {
      this.session = session;
      this.stage = stage;
      this.engine = engine;
      this.scan = scan;
    }
  }
}
