package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.MockGradingProvider;
import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.windows.SafeInputException;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowSafety;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.manual.ManualCoordinatePlatformAdapter;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.TargetBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real native input into this process's isolated WebView only. All content, model responses and
 * persistence are synthetic/local; the fixture never loads a network URL or an existing profile.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ManualWebInputWindowsIT {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final LogicalRect ANSWER = new LogicalRect(47, 59, 640, 185);
  private static final LogicalRect SCORE = new LogicalRect(64, 290, 180, 50);
  private static final LogicalRect SUBMIT = new LogicalRect(275, 290, 160, 50);
  private static final List<BigDecimal> SCORES =
      List.of(
          BigDecimal.ZERO,
          BigDecimal.ONE,
          BigDecimal.valueOf(2),
          BigDecimal.valueOf(3),
          BigDecimal.valueOf(4),
          new BigDecimal("2.5"));
  private static boolean ownsToolkit;
  @TempDir Path temporary;

  @BeforeAll
  static void startToolkit() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    try {
      Platform.startup(
          () -> {
            Platform.setImplicitExit(false);
            started.countDown();
          });
      ownsToolkit = true;
    } catch (IllegalStateException alreadyStarted) {
      Platform.runLater(started::countDown);
    }
    assertTrue(started.await(15, TimeUnit.SECONDS), "JavaFX startup timed out");
  }

  @AfterAll
  static void stopToolkit() {
    if (ownsToolkit) Platform.exit();
  }

  @Test
  void nativeUnicodeFillClipboardReadbackAndSubmitAdvanceSixSyntheticWebAnswers() throws Exception {
    AtomicBoolean inputEnabled = new AtomicBoolean(true);
    try (WebTarget target = new WebTarget();
        H2SessionJournal journal = new H2SessionJournal(temporary.resolve("web-records"))) {
      target.awaitPaint();
      var calibration =
          ManualPlatformCalibration.capture(
              "isolated-web-input",
              1,
              "Synthetic WebView input regression",
              target.binding.calibratedWindow(),
              null,
              ANSWER,
              SCORE,
              SUBMIT,
              null,
              null,
              ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
              CLOCK.instant());
      var adapter =
          new ManualCoordinatePlatformAdapter(
              target.windows,
              target.binding,
              calibration,
              target.capture,
              "web-native-question",
              CLOCK);
      AtomicReference<String> failure = new AtomicReference<>("none");
      SessionPlatform traced =
          (SessionPlatform)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionPlatform.class},
                  (proxy, method, arguments) -> {
                    try {
                      return method.invoke(adapter, arguments);
                    } catch (InvocationTargetException invocation) {
                      failure.compareAndSet(
                          "none", method.getName() + ": " + safeFailure(invocation.getCause()));
                      throw invocation.getCause();
                    }
                  });
      AtomicInteger calls = new AtomicInteger();
      var history = new CopyOnWriteArrayList<String>();
      var question = question();
      try (var controller =
          new SessionController(
              traced,
              journal,
              new MetadataOnlyEvidenceStore(),
              (request, cancellation) -> {
                int ordinal = calls.getAndIncrement();
                if (ordinal >= SCORES.size())
                  throw new AssertionError("Unexpected seventh synthetic model call");
                return new MockGradingProvider(result(SCORES.get(ordinal)), CLOCK, Runnable::run)
                    .grade(request, cancellation);
              },
              grade -> GradeValidator.validate(question, grade).totalScore(),
              new SessionSettings(
                  question,
                  "WEBVIEW_NATIVE_MOCK",
                  1,
                  1,
                  "{\"source\":\"MOCK\",\"platform\":{\"mode\":\"MANUAL_COORDINATES\"}}",
                  SCORES.size(),
                  0,
                  0,
                  null,
                  Duration.ofMillis(100),
                  Duration.ofSeconds(8)),
              CLOCK,
              inputEnabled::get,
              status -> {
                if (history.size() < 100) history.add(status.state() + ":" + status.reason());
              })) {
        // No global hotkey is registered: the serial controller owns an explicit test-only gate.
        // All native batches additionally validate the exact HWND/PID, focus and bound geometry.
        assertEquals(
            ProcessHandle.current().pid(),
            target.binding.calibratedWindow().identity().processId());
        assertTrue(target.windows.activateBoundWindow(target.binding));
        assertEquals(WindowSafety.Failure.NONE, target.windows.check(target.binding));
        controller.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        while (!terminal(controller.status().state()) && System.nanoTime() < deadline)
          Thread.sleep(100);
        if (controller.status().state() != SessionState.COMPLETED)
          System.out.println("Isolated WebView native input failure: " + failure.get());
        assertEquals(
            SessionState.COMPLETED,
            controller.status().state(),
            () -> controller.status() + "; history=" + history + "; failure=" + failure.get());
        assertEquals(SCORES.size(), controller.status().modelCalls());
        assertEquals(SCORES.size(), controller.status().confirmedSubmissions());
        assertEquals(SCORES.size(), calls.get());
        var submitted =
            new ObjectMapper()
                .readTree(
                    onFx(
                        () ->
                            String.valueOf(
                                target
                                    .view
                                    .getEngine()
                                    .executeScript("JSON.stringify(window.fixture.submitted)"))));
        assertEquals(SCORES.size(), submitted.size());
        for (int index = 0; index < SCORES.size(); index++) {
          assertEquals(index, submitted.get(index).path("ordinal").asInt());
          assertEquals(
              0,
              SCORES
                  .get(index)
                  .compareTo(new BigDecimal(submitted.get(index).path("score").asText())));
        }
        assertEquals(SCORES.size(), journal.attempts(controller.status().runId()).size());
        assertEquals(
            0,
            onFx(() -> (Number) target.view.getEngine().executeScript("window.fixture.unexpected"))
                .intValue());
        assertEquals(
            SCORES.size(),
            onFx(() -> (Number) target.view.getEngine().executeScript("window.fixture.ordinal"))
                .intValue());
      } finally {
        inputEnabled.set(false);
      }
    } finally {
      inputEnabled.set(false);
    }
  }

  private static String safeFailure(Throwable failure) {
    StringBuilder description = new StringBuilder();
    for (int depth = 0; failure != null && depth < 6; depth++, failure = failure.getCause()) {
      if (depth > 0) description.append(" / ");
      description.append(failure.getClass().getSimpleName());
      // Only our bounded application-defined codes can be printed, never clipboard/page content.
      if (failure instanceof SafeInputException) {
        String message = failure.getMessage();
        if (message != null && message.matches("桌面操作已停止 \\[[A-Z0-9_:-]{1,120}\\]"))
          description.append(": ").append(message);
      }
    }
    return description.toString();
  }

  private static boolean terminal(SessionState state) {
    return switch (state) {
      case COMPLETED, STOPPED, PAUSED, FAILED, SUBMISSION_UNKNOWN -> true;
      default -> false;
    };
  }

  private static final class WebTarget implements AutoCloseable {
    final WindowsWindowService windows = new WindowsWindowService();
    final WindowRegionCapture capture = new WindowRegionCapture(windows, CLOCK);
    final Stage stage;
    final WebView view;
    final TargetBinding binding;

    WebTarget() throws Exception {
      String title = "Isolated WebView input " + UUID.randomUUID();
      CountDownLatch loaded = new CountDownLatch(1);
      AtomicReference<Worker.State> loadState = new AtomicReference<>();
      view = onFx(WebView::new);
      stage =
          onFx(
              () -> {
                var owned = new Stage();
                owned.setTitle(title);
                owned.setResizable(false);
                owned.setAlwaysOnTop(true);
                owned.setScene(new Scene(view, 820, 510));
                owned.setX(80);
                owned.setY(70);
                view.getEngine()
                    .getLoadWorker()
                    .stateProperty()
                    .addListener(
                        (observable, previous, current) -> {
                          if (current == Worker.State.SUCCEEDED || current == Worker.State.FAILED) {
                            loadState.set(current);
                            loaded.countDown();
                          }
                        });
                owned.show();
                owned.toFront();
                owned.requestFocus();
                view.getEngine().loadContent(html());
                return owned;
              });
      try {
        assertTrue(loaded.await(12, TimeUnit.SECONDS), "Synthetic inline page did not load");
        assertEquals(Worker.State.SUCCEEDED, loadState.get());
        assertEquals(1.0, onFx(view::getZoom).doubleValue());
        TargetBinding found = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (found == null && System.nanoTime() < deadline) {
          var own =
              windows.listWindows().stream()
                  .filter(choice -> choice.identity().processId() == ProcessHandle.current().pid())
                  .filter(choice -> title.equals(choice.title()))
                  .findFirst();
          if (own.isPresent()) {
            var candidate = windows.bind(own.get().identity().hwnd(), ANSWER);
            if (windows.activateBoundWindow(candidate)
                && windows.check(candidate) == WindowSafety.Failure.NONE) found = candidate;
          }
          if (found == null) Thread.sleep(100);
        }
        assertNotNull(found, "Only this test's own uniquely titled stage may receive input");
        binding = found;
      } catch (Exception | Error failure) {
        close();
        throw failure;
      }
    }

    void awaitPaint() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while (System.nanoTime() < deadline) {
        try (var stream = new ByteArrayInputStream(capture.capture(binding).png())) {
          var pixels = ImageIO.read(stream);
          int rgb = pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2);
          if ((rgb & 0x00ffffff) == 0x00235fdd) return;
        }
        Thread.sleep(100);
      }
      fail("Synthetic WebView answer did not become visibly ready");
    }

    @Override
    public void close() throws Exception {
      onFx(
          () -> {
            stage.close();
            return null;
          });
    }
  }

  private static String html() {
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>
          * { box-sizing: border-box; }
          html,body { margin:0; width:100%; height:100%; overflow:hidden; background:#fff; font:18px sans-serif; }
          h1 { position:absolute; left:47px; top:12px; margin:0; font-size:22px; }
          #answer { position:absolute; left:47px; top:59px; width:640px; height:185px; }
          #score { position:absolute; left:64px; top:290px; width:180px; height:50px; font-size:24px; }
          #submit { position:absolute; left:275px; top:290px; width:160px; height:50px; font-size:20px; }
          #help { position:absolute; left:47px; top:385px; font-size:16px; }
        </style></head><body>
          <h1>Local synthetic grading fixture</h1>
          <canvas id="answer" width="640" height="185"></canvas>
          <input id="score" type="number" min="0" max="4" step="0.5" autocomplete="off" aria-label="Score">
          <button id="submit" type="button">Submit and next</button>
          <p id="help">No network, accounts, real answers or model requests.</p>
          <script>
            window.fixture = {ordinal:0, submitted:[], unexpected:0};
            function paint() {
              var f=window.fixture, g=document.getElementById('answer').getContext('2d');
              g.fillStyle=f.ordinal%2===0 ? '#235fdd' : '#dc5f23'; g.fillRect(0,0,640,185);
              g.fillStyle='#fff'; g.fillRect(15,15,20+f.ordinal*13,14);
              g.font='22px sans-serif'; g.fillText('Synthetic student answer '+f.ordinal,15,60);
              g.font='18px sans-serif'; g.fillText('This answer belongs to fixture student '+f.ordinal+'.',15,150);
            }
            document.getElementById('submit').onclick=function() {
              var f=window.fixture, input=document.getElementById('score'), value=input.value;
              if(f.submitted.some(function(x){return x.ordinal===f.ordinal;}) || value==='' || !input.checkValidity()) {
                f.unexpected++; return;
              }
              f.submitted.push({ordinal:f.ordinal,score:value});
              f.ordinal++; input.value=''; paint();
            };
            paint();
          </script>
        </body></html>
        """;
  }

  private static QuestionDefinition question() {
    return new QuestionDefinition(
        "web-native-question",
        1,
        "Synthetic local question",
        "Synthetic local answer",
        BigDecimal.valueOf(4),
        List.of(new ScoringCriterion("score", "Synthetic criterion", SCORES)));
  }

  private static GradeResult result(BigDecimal score) {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "Synthetic fixture",
        List.of(new GradeResult.CriterionResult("score", score, "fixture", "fixture")),
        List.of());
  }

  private static <T> T onFx(Callable<T> action) throws Exception {
    if (Platform.isFxApplicationThread()) return action.call();
    FutureTask<T> task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(12, TimeUnit.SECONDS);
  }
}
