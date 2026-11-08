package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.MockGradingProvider;
import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowSafety;
import app.deskgrade.adapters.windows.WindowsGlobalHotkeys;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.manual.ManualCoordinatePlatformAdapter;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real input only into this test's isolated JavaFX stage, with synthetic scores and a temporary H2.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ManualCoordinatesWindowsIT {
  private static final Clock CLOCK = Clock.systemUTC();
  // Deliberately unrelated to the mock platform's x=16/y=144/fixed dimensions.
  private static final LogicalRect ANSWER = new LogicalRect(47, 59, 391, 187);
  private static final LogicalRect IDENTITY = new LogicalRect(470, 59, 155, 42);
  private static final LogicalRect SCORE = new LogicalRect(64, 285, 170, 42);
  private static final LogicalRect SUBMIT = new LogicalRect(265, 285, 155, 42);
  private static final LogicalRect NEXT = new LogicalRect(470, 285, 155, 42);
  private static final LogicalRect ACK = new LogicalRect(470, 350, 155, 42);
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
  void combinedSubmitProcessesFiveWithVisualEvidenceWithoutAProtocolOrSixthModelCall()
      throws Exception {
    runFive(false);
  }

  @Test
  void separateSubmitProcessesFiveAndNeverClicksNextAfterTheFifth() throws Exception {
    runFive(true);
  }

  @Test
  void nonstandardAnswerCropPreservesZoomedCanvasAtNativeResolution() throws Exception {
    try (FxTarget target = new FxTarget(false)) {
      target.awaitPaint();
      var captured = target.capture.capture(target.binding);
      try (var stream = new ByteArrayInputStream(captured.png())) {
        var pixels = ImageIO.read(stream);
        assertNotNull(pixels);
        double scale = target.binding.calibratedWindow().dpi() / 96.0;
        assertEquals(Math.round(ANSWER.width() * scale), pixels.getWidth());
        assertEquals(Math.round(ANSWER.height() * scale), pixels.getHeight());
        // The canvas contents are 200% zoomed, while the selection remains the actual displayed
        // area.
        assertBlue(pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2));
      }
      assertEquals(0, onFx(() -> target.submitted.size()));
      assertEquals(0, onFx(() -> target.nextClicks));
    }
  }

  @Test
  void workspaceRequiresExplicitFullCoordinateBindingAndRevokesItOnOrdinaryRebind()
      throws Exception {
    try (FxTarget target = new FxTarget(false)) {
      Path workspaceRoot = temporary.resolve("workspace");
      var workspace = new DesktopWorkspace(workspaceRoot, true);
      try {
        workspace.initialize().get(15, TimeUnit.SECONDS);
        var snapshot = target.windows.snapshot(target.binding.calibratedWindow().identity().hwnd());
        workspace
            .bindEphemeralManualAnswer(
                "https://grading.example.test",
                snapshot.identity(),
                ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
                snapshot,
                ANSWER,
                SCORE,
                SUBMIT,
                null,
                null)
            .get(10, TimeUnit.SECONDS);
        assertTrue(workspace.automaticManualTarget());
        assertTrue(workspace.automaticTarget());
        assertFalse(workspace.automaticSyntheticTarget());
        assertEquals(ANSWER, workspace.binding().answerRegion());
        String firstScope = workspace.manualCalibration().id();
        assertFalse(
            Files.exists(workspaceRoot.resolve("settings/platform.json")),
            "Ephemeral answer coordinates must not be persisted");
        try (var files = Files.walk(workspaceRoot)) {
          assertTrue(
              files.noneMatch(
                  path -> path.getFileName().toString().equals("manual-calibrations.json")),
              "Ephemeral full coordinates must not be persisted");
        }

        workspace.bind(snapshot, ANSWER).get(10, TimeUnit.SECONDS);
        assertFalse(
            workspace.automaticManualTarget(), "Answer-only binding cannot reuse submit rights");
        assertFalse(workspace.automaticTarget());
        workspace
            .bindEphemeralManualAnswer(
                "https://grading.example.test",
                snapshot.identity(),
                ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
                snapshot,
                ANSWER,
                SCORE,
                SUBMIT,
                null,
                null)
            .get(10, TimeUnit.SECONDS);
        assertTrue(workspace.automaticTarget());
        assertNotEquals(
            firstScope,
            workspace.manualCalibration().id(),
            "Each run-only binding needs an isolated unresolved-submission scope");
        workspace.resetEmbeddedBrowserTarget().get(10, TimeUnit.SECONDS);
        assertFalse(workspace.automaticManualTarget());
        assertFalse(workspace.automaticTarget());
        assertNull(workspace.manualCalibration());
        assertEquals(0, onFx(() -> target.submitted.size()), "Binding never begins input");
      } finally {
        workspace.close();
        workspace.awaitClosed().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private void runFive(boolean separate) throws Exception {
    try (FxTarget target = new FxTarget(separate);
        H2SessionJournal journal = new H2SessionJournal(temporary.resolve("records"))) {
      target.awaitPaint();
      var calibration =
          ManualPlatformCalibration.capture(
              "native-manual",
              1,
              "Isolated coordinate fixture",
              target.binding.calibratedWindow(),
              null,
              ANSWER,
              SCORE,
              SUBMIT,
              separate ? ACK : null,
              separate ? NEXT : null,
              separate
                  ? ManualPlatformCalibration.NavigationMode.CLICK_NEXT
                  : ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
              CLOCK.instant());
      var adapter =
          new ManualCoordinatePlatformAdapter(
              target.windows,
              target.binding,
              calibration,
              target.capture,
              "manual-question",
              CLOCK);
      assertTrue(adapter.supportsUnattended());
      AtomicInteger dispatched = new AtomicInteger();
      AtomicReference<String> platformFailure = new AtomicReference<>("none");
      var tracedPlatform =
          (app.deskgrade.core.application.session.SessionPlatform)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {app.deskgrade.core.application.session.SessionPlatform.class},
                  (proxy, method, arguments) -> {
                    try {
                      return method.invoke(adapter, arguments);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                      Throwable cause = thrown.getCause();
                      StringBuilder description = new StringBuilder(method.getName());
                      for (int depth = 0;
                          cause != null && depth < 5;
                          depth++, cause = cause.getCause())
                        description
                            .append(" / ")
                            .append(cause.getClass().getSimpleName())
                            .append(": ")
                            .append(cause.getMessage());
                      platformFailure.set(description + "; occluders=" + occluders(target.binding));
                      throw thrown.getCause();
                    }
                  });
      var stateHistory = new java.util.concurrent.CopyOnWriteArrayList<String>();
      AtomicReference<SessionController> active = new AtomicReference<>();
      try (var hotkeys =
          new WindowsGlobalHotkeys(
              event -> {
                var controller = active.get();
                if (controller == null) return;
                if (event == WindowsGlobalHotkeys.Event.STOP) controller.stop();
                else
                  controller.pause(
                      event == WindowsGlobalHotkeys.Event.UNAVAILABLE
                          ? PauseReason.HOTKEY_UNAVAILABLE
                          : PauseReason.USER_PAUSED);
              })) {
        assertTrue(hotkeys.available(), "A real global stop hotkey is required for input tests");
        var question = question();
        try (var controller =
            new SessionController(
                tracedPlatform,
                journal,
                new MetadataOnlyEvidenceStore(),
                (request, cancellation) ->
                    new MockGradingProvider(
                            result(dispatched.getAndIncrement()), CLOCK, Runnable::run)
                        .grade(request, cancellation),
                grade -> GradeValidator.validate(question, grade).totalScore(),
                new SessionSettings(
                    question,
                    "MANUAL_NATIVE_FIXTURE",
                    1,
                    1,
                    "{\"source\":\"MOCK\",\"platform\":{\"mode\":\"MANUAL_COORDINATES\"}}",
                    5,
                    0,
                    0,
                    null,
                    Duration.ofMillis(100),
                    Duration.ofSeconds(12)),
                CLOCK,
                hotkeys::available,
                status -> {
                  if (stateHistory.size() < 100) stateHistory.add(status.state().name());
                })) {
          active.set(controller);
          assertTrue(target.windows.activateBoundWindow(target.binding));
          assertEquals(WindowSafety.Failure.NONE, target.windows.check(target.binding));
          controller.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
          while (!terminal(controller.status().state()) && System.nanoTime() < deadline)
            Thread.sleep(100);
          if (controller.status().state() != SessionState.COMPLETED) {
            System.out.println("Isolated manual fixture paused: " + platformFailure.get());
          }
          assertEquals(
              SessionState.COMPLETED,
              controller.status().state(),
              controller.status()
                  + "; history="
                  + stateHistory
                  + "; failure="
                  + platformFailure.get()
                  + "; control="
                  + onFx(() -> target.score.getText()));
          assertEquals(5, controller.status().confirmedSubmissions());
          assertEquals(5, controller.status().modelCalls());
          assertEquals(5, dispatched.get());
          var submitted = onFx(() -> List.copyOf(target.submitted));
          assertEquals(5, submitted.size());
          for (int index = 0; index < 5; index++) {
            assertEquals(index, submitted.get(index).ordinal());
            assertEquals(
                0,
                BigDecimal.valueOf(index).compareTo(new BigDecimal(submitted.get(index).score())));
          }
          assertEquals(separate ? 4 : 0, onFx(() -> target.nextClicks));
          assertEquals(0, onFx(() -> target.unexpectedClicks));
          assertEquals(separate ? 4 : 5, onFx(() -> target.ordinal));
          var rows = journal.attempts(controller.status().runId());
          assertEquals(5, rows.size());
          var json = new ObjectMapper();
          for (var row : rows) {
            var record = json.readTree(journal.attemptAuditJson(row.attemptId()));
            assertEquals(
                separate ? "SUBMIT_CLICKED" : "VISUALLY_ADVANCED",
                record.path("submission").path("status").asText());
            assertEquals(
                separate ? "INPUT_DISPATCHED" : "SCREEN_TRANSITION",
                record.path("submission").path("confirmationKind").asText());
            assertEquals("NOT_STORED", record.path("imagePersistence").asText());
          }
        } finally {
          active.set(null);
        }
      }
    }
  }

  private static boolean terminal(SessionState state) {
    return switch (state) {
      case COMPLETED, STOPPED, PAUSED, FAILED, SUBMISSION_UNKNOWN -> true;
      default -> false;
    };
  }

  private static String occluders(TargetBinding binding) {
    var user = com.sun.jna.platform.win32.User32.INSTANCE;
    var target =
        new com.sun.jna.platform.win32.WinDef.HWND(
            com.sun.jna.Pointer.createConstant(binding.calibratedWindow().identity().hwnd()));
    var above = user.GetWindow(target, new com.sun.jna.platform.win32.WinDef.DWORD(3));
    var found = new ArrayList<String>();
    for (int count = 0; above != null && count < 256 && found.size() < 8; count++) {
      var rect = new com.sun.jna.platform.win32.WinDef.RECT();
      if (user.IsWindowVisible(above)
          && user.GetWindowRect(above, rect)
          && rect.right > rect.left
          && rect.bottom > rect.top
          && new app.deskgrade.core.domain.desktop.PixelRect(
                  rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
              .intersects(binding.calibratedRegion())) {
        char[] className = new char[128];
        user.GetClassName(above, className, className.length);
        found.add(
            com.sun.jna.Native.toString(className)
                + ":"
                + rect.left
                + ","
                + rect.top
                + ","
                + rect.right
                + ","
                + rect.bottom
                + ":style="
                + Integer.toHexString(user.GetWindowLong(above, -20)));
      }
      above = user.GetWindow(above, new com.sun.jna.platform.win32.WinDef.DWORD(3));
    }
    return found.toString();
  }

  private record Submitted(int ordinal, String score) {}

  private static final class FxTarget implements AutoCloseable {
    final WindowsWindowService windows = new WindowsWindowService();
    final WindowRegionCapture capture = new WindowRegionCapture(windows, CLOCK);
    final Stage stage;
    final TargetBinding binding;
    final boolean separate;
    final List<Submitted> submitted = new ArrayList<>();
    Canvas answer;
    Canvas identity;
    Canvas acknowledgement;
    TextField score;
    int ordinal, nextClicks, unexpectedClicks;
    boolean waitingForNext;

    FxTarget(boolean separate) throws Exception {
      this.separate = separate;
      String title = "Coordinate test " + UUID.randomUUID();
      stage =
          onFx(
              () -> {
                var ownedStage = new Stage(StageStyle.DECORATED);
                ownedStage.setTitle(title);
                ownedStage.setResizable(false);
                ownedStage.setAlwaysOnTop(true);
                var pane = new Pane();
                pane.setStyle("-fx-background-color: white;");
                answer = new Canvas(ANSWER.width() / 2, ANSWER.height() / 2);
                var zoomed = new Group(answer);
                zoomed.getTransforms().add(new Scale(2, 2));
                zoomed.setLayoutX(ANSWER.x());
                zoomed.setLayoutY(ANSWER.y());
                identity = new Canvas(IDENTITY.width(), IDENTITY.height());
                identity.setLayoutX(IDENTITY.x());
                identity.setLayoutY(IDENTITY.y());
                acknowledgement = new Canvas(ACK.width(), ACK.height());
                acknowledgement.setLayoutX(ACK.x());
                acknowledgement.setLayoutY(ACK.y());
                score = new TextField();
                score.setStyle("-fx-font-size: 18px;");
                place(score, SCORE);
                var submit = new Button("Submit");
                place(submit, SUBMIT);
                submit.setOnAction(
                    event -> {
                      if (waitingForNext
                          || submitted.stream().anyMatch(row -> row.ordinal() == ordinal)) {
                        unexpectedClicks++;
                        return;
                      }
                      submitted.add(new Submitted(ordinal, score.getText()));
                      if (separate) {
                        waitingForNext = true;
                        paint();
                      } else advance();
                    });
                var next = new Button("Next");
                place(next, NEXT);
                next.setVisible(separate);
                next.setOnAction(
                    event -> {
                      nextClicks++;
                      if (!waitingForNext) {
                        unexpectedClicks++;
                        return;
                      }
                      advance();
                    });
                pane.getChildren().addAll(zoomed, identity, acknowledgement, score, submit, next);
                paint();
                ownedStage.setScene(new Scene(pane, 820, 510));
                ownedStage.setX(90);
                ownedStage.setY(90);
                ownedStage.show();
                pane.applyCss();
                pane.layout();
                ownedStage.toFront();
                ownedStage.requestFocus();
                return ownedStage;
              });
      try {
        TargetBinding found = null;
        var diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("expectedTitle", title);
        diagnostics.put("processId", ProcessHandle.current().pid());
        diagnostics.put("matchingCandidateFound", false);
        int activationAttempts = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (found == null && System.nanoTime() < deadline) {
          // Filter before retaining any diagnostic information; never log other processes' windows.
          var ownChoices =
              windows.listWindows().stream()
                  .filter(choice -> choice.identity().processId() == ProcessHandle.current().pid())
                  .toList();
          diagnostics.put(
              "currentProcessWindows",
              ownChoices.stream()
                  .limit(12)
                  .map(
                      choice ->
                          Map.of(
                              "title",
                              bounded(choice.title()),
                              "windowClass",
                              bounded(choice.windowClass()),
                              "hwnd",
                              choice.identity().hwnd()))
                  .toList());
          for (var choice : ownChoices) {
            if (!title.equals(choice.title())) continue;
            diagnostics.put("matchingCandidateFound", true);
            try {
              var candidate = windows.bind(choice.identity().hwnd(), ANSWER);
              diagnostics.put("calibratedSnapshot", describe(candidate.calibratedWindow()));
              boolean activated = windows.activateBoundWindow(candidate);
              activationAttempts++;
              diagnostics.put("activated", activated);
              diagnostics.put("windowCheck", windows.check(candidate).name());
              diagnostics.put("lastSnapshot", describe(windows.snapshot(choice.identity().hwnd())));
              if (activated) found = candidate;
            } catch (RuntimeException | LinkageError unavailable) {
              // Exception messages may contain foreign state; only retain the failure class.
              diagnostics.put("candidateFailureClass", unavailable.getClass().getSimpleName());
            }
          }
          if (found == null) Thread.sleep(100);
        }
        diagnostics.put("activationAttempts", activationAttempts);
        diagnostics.put(
            "stage",
            onFx(
                () ->
                    Map.of(
                        "showing",
                        stage.isShowing(),
                        "focused",
                        stage.isFocused(),
                        "x",
                        stage.getX(),
                        "y",
                        stage.getY(),
                        "width",
                        stage.getWidth(),
                        "height",
                        stage.getHeight())));
        Path diagnosticFile = Path.of("target", "manual-window-" + UUID.randomUUID() + ".json");
        Files.createDirectories(diagnosticFile.toAbsolutePath().getParent());
        byte[] encoded =
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(diagnostics);
        assertTrue(encoded.length < 16_384, "Window diagnostic must remain bounded");
        Files.write(diagnosticFile, encoded, java.nio.file.StandardOpenOption.CREATE_NEW);
        assertNotNull(
            found,
            "Own isolated stage was not found or activated; diagnostic="
                + diagnosticFile
                + "; windowCheck="
                + diagnostics.get("windowCheck")
                + "; snapshot="
                + diagnostics.get("lastSnapshot"));
        binding = found;
      } catch (Throwable failure) {
        close();
        throw failure;
      }
    }

    private static Map<String, Object> describe(WindowSnapshot snapshot) {
      var result = new LinkedHashMap<String, Object>();
      result.put("identity", snapshot.identity().toString());
      result.put("title", bounded(snapshot.title()));
      result.put("windowClass", bounded(snapshot.windowClass()));
      result.put("windowBounds", snapshot.windowBounds().toString());
      result.put("clientBounds", snapshot.clientBounds().toString());
      result.put("dpi", snapshot.dpi());
      result.put("foreground", snapshot.foreground());
      result.put("visible", snapshot.visible());
      result.put("minimized", snapshot.minimized());
      result.put("cloaked", snapshot.cloaked());
      result.put("interactive", snapshot.interactive());
      return result;
    }

    private static String bounded(String value) {
      String safe = value.replaceAll("[\\p{Cntrl}]", " ");
      return safe.substring(0, Math.min(160, safe.length()));
    }

    void awaitPaint() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        try (var stream = new ByteArrayInputStream(capture.capture(binding).png())) {
          var image = ImageIO.read(stream);
          int rgb = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
          if (blue(rgb)) return;
        }
        Thread.sleep(100);
      }
      fail("Own canvas did not become visibly ready");
    }

    private void advance() {
      ordinal++;
      waitingForNext = false;
      score.clear();
      paint();
    }

    private void paint() {
      var graphics = answer.getGraphicsContext2D();
      graphics.setFill(ordinal % 2 == 0 ? Color.rgb(35, 95, 221) : Color.rgb(220, 95, 35));
      graphics.fillRect(0, 0, answer.getWidth(), answer.getHeight());
      graphics.setFill(Color.WHITE);
      graphics.fillRect(8, 8, 12 + ordinal * 7, 12);
      graphics.fillText("Synthetic answer " + ordinal, 8, 36);
      var identityGraphics = identity.getGraphicsContext2D();
      identityGraphics.setFill(Color.rgb(28, 42, 66));
      identityGraphics.fillRect(0, 0, identity.getWidth(), identity.getHeight());
      identityGraphics.setFill(Color.WHITE);
      identityGraphics.fillRect(8, 8, 10 + ordinal * 9, 8);
      identityGraphics.fillText("Student " + ordinal, 8, 31);
      var ackGraphics = acknowledgement.getGraphicsContext2D();
      ackGraphics.setFill(waitingForNext ? Color.rgb(35, 155, 75) : Color.rgb(120, 120, 120));
      ackGraphics.fillRect(0, 0, acknowledgement.getWidth(), acknowledgement.getHeight());
      ackGraphics.setFill(Color.WHITE);
      ackGraphics.fillText(waitingForNext ? "Saved" : "Not saved", 8, 26);
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

  private static void place(javafx.scene.layout.Region node, LogicalRect rectangle) {
    node.setManaged(false);
    node.resizeRelocate(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
  }

  private static boolean blue(int rgb) {
    return Math.abs(((rgb >>> 16) & 255) - 35) < 5
        && Math.abs(((rgb >>> 8) & 255) - 95) < 5
        && Math.abs((rgb & 255) - 221) < 5;
  }

  private static void assertBlue(int rgb) {
    assertTrue(blue(rgb), "Crop did not preserve the visible synthetic canvas");
  }

  private static <T> T onFx(Callable<T> action) throws Exception {
    if (Platform.isFxApplicationThread()) return action.call();
    FutureTask<T> future = new FutureTask<>(action);
    Platform.runLater(future);
    return future.get(15, TimeUnit.SECONDS);
  }

  private static QuestionDefinition question() {
    return new QuestionDefinition(
        "manual-question",
        1,
        "Synthetic question",
        "Synthetic answer",
        BigDecimal.valueOf(4),
        List.of(
            new ScoringCriterion(
                "score",
                "Synthetic criterion",
                List.of(
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(2),
                    BigDecimal.valueOf(3),
                    BigDecimal.valueOf(4)))));
  }

  private static GradeResult result(int score) {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "Synthetic fixture",
        List.of(
            new GradeResult.CriterionResult(
                "score", BigDecimal.valueOf(score), "fixture", "fixture")),
        List.of());
  }
}
