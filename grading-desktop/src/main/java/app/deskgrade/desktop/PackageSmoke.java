package app.deskgrade.desktop;

import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.windows.WindowsRuntimePaths;
import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Explicit packaged-runtime diagnostic. It never binds a platform, registers hotkeys or grades. */
final class PackageSmoke {
  private final Path root;
  private final Map<String, Boolean> checks = new ConcurrentHashMap<>();
  private volatile String failureCode;
  private PauseTransition deadline;
  // The probe is not attached to a Scene. Keep it strongly reachable until smoke shutdown;
  // WebKit's asynchronous load callbacks alone do not retain an otherwise unowned WebView.
  private WebView smokeWebView;

  PackageSmoke(Path root) {
    this.root = root;
    for (String check :
        List.of(
            "javaFxVisible",
            "javaFxWebLocalContent",
            "localWorkspaceVisible",
            "localModeDefault",
            "developerModeDisabled",
            "defaultActionsDisabled",
            "noGatewayClient",
            "mainDatabaseReady",
            "emptyRuns",
            "h2Writable",
            "nativeLibrary",
            "dpapiRoundTrip",
            "syntheticCredentialRemoved",
            "nioClientCreated",
            "noSessionOrHotkeys",
            "resourcesClosed",
            "instanceLockReleased")) checks.put(check, false);
  }

  void start(Stage stage, DesktopWorkspace workspace) {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
    checks.put("javaFxVisible", stage.isShowing());
    CompletableFuture<Boolean> webReady = verifyLocalWebView();
    var rootNode = stage.getScene().getRoot();
    checks.put(
        "localWorkspaceVisible",
        find(rootNode, "login-surface") == null
            && find(rootNode, "main-tabs") != null
            && find(rootNode, "gateway-address") == null
            && find(rootNode, "run-mode") == null
            && find(rootNode, "model-configuration-overview") != null
            && find(rootNode, "model-setup-wizard") != null
            && !find(rootNode, "model-setup-wizard").isVisible()
            && !find(rootNode, "model-setup-wizard").isManaged()
            && find(rootNode, "embedded-browser-entry") != null
            && find(rootNode, "embedded-browser-entry").isVisible()
            && find(rootNode, "embedded-browser-entry").isManaged());
    checks.put(
        "localModeDefault",
        !workspace.commercialMode() && workspace.accountView().account() == null);
    checks.put("developerModeDisabled", !workspace.developerMode());
    boolean disabled = true;
    for (String id : List.of("start-auto", "pause", "stop", "reconcile")) {
      var found = find(stage.getScene().getRoot(), id);
      disabled &= found == null || found.isDisabled();
    }
    disabled &= find(rootNode, "trial") == null && find(rootNode, "bind-mock") == null;
    checks.put("defaultActionsDisabled", disabled);
    checks.put(
        "noGatewayClient", workspace.localOnlyRuntime() && workspace.gatewayAddress().isEmpty());
    deadline = new PauseTransition(Duration.seconds(40));
    deadline.setOnFinished(
        event -> {
          failureCode = "SMOKE_TIMEOUT";
          Platform.exit();
        });
    deadline.play();
    Thread.ofPlatform()
        .daemon(true)
        .name("deskgrade-package-smoke")
        .start(
            () -> {
              String checking = "WORKSPACE";
              try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!workspace.ready() && System.nanoTime() < until) Thread.sleep(50);
                if (!workspace.ready()) throw new IllegalStateException();
                checks.put("mainDatabaseReady", true);
                checking = "LOCAL_WEBVIEW";
                checks.put("javaFxWebLocalContent", webReady.get(10, TimeUnit.SECONDS));
                checking = "LOCAL_RECORDS";
                var page =
                    workspace
                        .recordsSource()
                        .query(
                            app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                checks.put("emptyRuns", page.total() == 0);
                checks.put(
                    "noGatewayClient",
                    workspace.localOnlyRuntime() && workspace.gatewayAddress().isEmpty());
                checking = "STORAGE";
                try (var journal = new H2SessionJournal(root.resolve("diagnostic/storage"))) {
                  journal.assertWritable();
                  checks.put("h2Writable", journal.recoverInterrupted(Instant.now()).isEmpty());
                }
                checks.put("nativeLibrary", WindowsRuntimePaths.currentProcessAvailable());
                checking = "DPAPI";
                var store = new DpapiSecretStore(root.resolve("diagnostic/credentials"));
                char[] synthetic = "DeskGrade synthetic package check".toCharArray();
                char[] restored = null;
                try {
                  store.put("synthetic-smoke", synthetic);
                  restored = store.read("synthetic-smoke").orElseThrow();
                  checks.put("dpapiRoundTrip", Arrays.equals(synthetic, restored));
                } finally {
                  Arrays.fill(synthetic, '\0');
                  if (restored != null) Arrays.fill(restored, '\0');
                  store.delete("synthetic-smoke");
                  checks.put(
                      "syntheticCredentialRemoved",
                      !Files.exists(root.resolve("diagnostic/credentials/synthetic-smoke.dpapi")));
                }
                // Exercises the bundled NIO runtime without sending a request or opening a remote
                // endpoint.
                checking = "HTTP_RUNTIME";
                try (HttpClient client = HttpClient.newHttpClient()) {
                  checks.put("nioClientCreated", true);
                }
                checks.put(
                    "noSessionOrHotkeys",
                    workspace.status() == null && !workspace.hotkeysAvailable());
              } catch (Exception | LinkageError failure) {
                failureCode =
                    "SMOKE_"
                        + checking
                        + (failure instanceof java.util.concurrent.TimeoutException
                            ? "_TIMEOUT"
                            : "_FAILED");
              } finally {
                Platform.runLater(
                    () -> {
                      deadline.stop();
                      Platform.exit();
                    });
              }
            });
  }

  private CompletableFuture<Boolean> verifyLocalWebView() {
    var completed = new CompletableFuture<Boolean>();
    try {
      WebView webView = new WebView();
      smokeWebView = webView;
      webView.setContextMenuEnabled(false);
      webView
          .getEngine()
          .getLoadWorker()
          .stateProperty()
          .addListener(
              (observable, before, after) -> {
                if (after == Worker.State.SUCCEEDED) {
                  try {
                    Object marker =
                        webView
                            .getEngine()
                            .executeScript("document.getElementById('dg-smoke').textContent");
                    Object title = webView.getEngine().executeScript("document.title");
                    completed.complete(
                        "DeskGrade WebView smoke".equals(title) && "local-only".equals(marker));
                  } catch (RuntimeException | LinkageError failure) {
                    completed.complete(false);
                  }
                } else if (after == Worker.State.FAILED || after == Worker.State.CANCELLED) {
                  completed.complete(false);
                }
              });
      // Fixed inline content exercises the bundled WebKit runtime without opening a socket or
      // resolving an external resource.
      webView
          .getEngine()
          .loadContent(
              "<!doctype html><html><head><title>DeskGrade WebView smoke</title></head>"
                  + "<body><span id='dg-smoke'>local-only</span></body></html>");
    } catch (RuntimeException | LinkageError failure) {
      completed.complete(false);
    }
    return completed;
  }

  private static Node find(Node node, String id) {
    if (id.equals(node.getId())) return node;
    if (node instanceof TabPane tabs) {
      for (var tab : tabs.getTabs()) {
        Node found = find(tab.getContent(), id);
        if (found != null) return found;
      }
      return null;
    }
    if (node instanceof ScrollPane scroll) return find(scroll.getContent(), id);
    if (node instanceof Parent parent) {
      for (Node child : parent.getChildrenUnmodifiable()) {
        Node found = find(child, id);
        if (found != null) return found;
      }
    }
    return null;
  }

  /** Called after the application's bounded workspace shutdown and lock release. */
  void finish(boolean resourcesClosed, boolean instanceLockReleased) {
    if (deadline != null) deadline.stop();
    smokeWebView = null;
    checks.put("resourcesClosed", resourcesClosed);
    checks.put("instanceLockReleased", instanceLockReleased);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put(
        "status",
        failureCode == null && checks.values().stream().allMatch(Boolean.TRUE::equals)
            ? "PASS"
            : "FAIL");
    report.put("checks", new java.util.TreeMap<>(checks));
    report.put("failureCode", failureCode);
    report.put("javaHome", System.getProperty("java.home"));
    report.put("javaVersion", System.getProperty("java.version"));
    report.put("osArch", System.getProperty("os.arch"));
    report.put("dataDirectory", root.resolve("data").toString());
    report.put("socketDirectory", System.getProperty("jdk.net.unixdomain.tmpdir"));
    report.put("source", "PACKAGED_RUNTIME_SYNTHETIC_LOCAL_ONLY");
    Path temporary = root.resolve("report.json.tmp");
    try {
      byte[] json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
      try (var output =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(json);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      Files.move(temporary, root.resolve("report.json"), StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception failure) {
      // Missing/partial report is a failed smoke, never an inferred success.
      System.err.println("DeskGrade package smoke report could not be saved.");
    }
  }
}
