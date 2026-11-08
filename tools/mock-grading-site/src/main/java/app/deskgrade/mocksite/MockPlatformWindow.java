package app.deskgrade.mocksite;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Separate test target window. Production code has no access to this WebView or its DOM. */
public final class MockPlatformWindow extends Application {
  private MockPlatformServer server;

  @Override
  public void start(Stage stage) throws Exception {
    server = Launcher.createServer(getParameters().getRaw());
    server.start();
    WebView webView = new WebView();
    webView.setContextMenuEnabled(false);
    AtomicBoolean diagnosticsScheduled = new AtomicBoolean();
    webView
        .getEngine()
        .getLoadWorker()
        .stateProperty()
        .addListener(
            (observable, before, after) -> {
              // One navigation only; terminal load diagnostics cannot become a request log loop.
              if (after == Worker.State.SUCCEEDED
                  || after == Worker.State.FAILED
                  || after == Worker.State.CANCELLED) {
                Throwable failure = webView.getEngine().getLoadWorker().getException();
                System.out.println(
                    "Synthetic page load: "
                        + after
                        + (failure == null ? "" : " (" + failure.getClass().getSimpleName() + ")"));
                if (getParameters().getRaw().contains("--test-ledger")
                    && System.getProperty("deskgrade.mock.diagnosticsDir") != null
                    && diagnosticsScheduled.compareAndSet(false, true)) {
                  PauseTransition settled = new PauseTransition(Duration.seconds(2));
                  settled.setOnFinished(event -> writeStartupDiagnostics(webView));
                  settled.play();
                }
              }
            });
    webView.getEngine().load(server.baseUri().toString());
    stage.setTitle("DeskGrade Mock Platform · 合成答卷测试");
    stage.setScene(new Scene(new StackPane(webView), 900, 760));
    stage.setResizable(false);
    stage.setX(80);
    stage.setY(60);
    stage.show();
    System.out.println("Synthetic mock platform: " + server.baseUri());
  }

  /** Test-only diagnostics; no production adapter can read this WebView or its DOM. */
  private void writeStartupDiagnostics(WebView webView) {
    var details = new LinkedHashMap<String, Object>();
    try {
      Path directory = Path.of(System.getProperty("deskgrade.mock.diagnosticsDir"));
      if (!directory.isAbsolute() || !Files.isDirectory(directory))
        throw new IllegalArgumentException("Explicit existing diagnostic directory required");
      var engine = webView.getEngine();
      details.put("loadState", engine.getLoadWorker().getState().name());
      details.put("location", engine.getLocation());
      details.put("title", engine.getTitle());
      details.put("webViewWidth", webView.getWidth());
      details.put("webViewHeight", webView.getHeight());
      try {
        String metadata =
            (String)
                engine.executeScript(
                    """
            JSON.stringify({readyState:document.readyState,
              documentLength:document.documentElement?document.documentElement.outerHTML.length:0,
              bodyLength:document.body?document.body.innerHTML.length:0,
              title:document.title, markerPresent:!!document.getElementById('marker'),
              paintType:typeof paint, currentType:typeof current,
              answerSequence:typeof current==='object'?current.answerSequence:null,
              pageState:typeof current==='object'?current.pageState:null})
            """);
        details.put("document", new ObjectMapper().readTree(metadata));
      } catch (RuntimeException scriptFailure) {
        details.put("documentFailure", scriptFailure.getClass().getSimpleName());
      }
      var snapshot = webView.snapshot(null, null);
      int width = (int) snapshot.getWidth();
      int height = (int) snapshot.getHeight();
      if (width < 1 || height < 1 || (long) width * height > 1_000_000)
        throw new IllegalStateException("Unexpected fixture snapshot dimensions");
      var buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      var pixels = snapshot.getPixelReader();
      for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++) buffered.setRGB(x, y, pixels.getArgb(x, y));
      ImageIO.write(buffered, "png", directory.resolve("webview-startup.png").toFile());
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("webview-startup.json").toFile(), details);
      System.out.println("Synthetic startup diagnostics saved");
    } catch (Exception failure) {
      System.out.println(
          "Synthetic startup diagnostics failed: " + failure.getClass().getSimpleName());
    }
  }

  @Override
  public void stop() {
    if (server != null) server.close();
  }
}
