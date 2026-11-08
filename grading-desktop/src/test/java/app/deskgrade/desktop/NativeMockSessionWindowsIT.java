package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.MockGradingProvider;
import app.deskgrade.adapters.persistence.FileEvidenceStore;
import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.windows.WindowChangeMonitor;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowSafety;
import app.deskgrade.adapters.windows.WindowsGlobalHotkeys;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.mock.NativeMockPlatformAdapter;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.port.GradingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser.INPUT;
import java.io.File;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual screen + SendInput + H2 integration. Only the child synthetic tool receives input. HTTP is
 * restricted to test setup and independent ledger assertions, never platform observations.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class NativeMockSessionWindowsIT {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final Clock clock = Clock.systemUTC();

  @Test
  void fiveHundredActualScreenSubmissionsHaveNoDuplicatesMissingAnswersOrCrossedScores()
      throws Exception {
    try (Harness harness = new Harness(500, "submit-and-advance", "{}", null)) {
      harness.start();
      harness.awaitTerminal(Duration.ofMinutes(9));
      assertEquals(
          SessionState.COMPLETED,
          harness.controller.status().state(),
          harness.controller.status().toString());
      assertEquals(500, harness.controller.status().confirmedSubmissions());
      assertLedger(harness, 500);
      assertEquals(
          500,
          harness.journal.attempts(harness.controller.status().runId()).stream()
              .filter(row -> row.confirmed())
              .count());
    }
  }

  @Test
  void separateNextConfirmsTwentyConsecutiveIdenticalImages() throws Exception {
    try (Harness harness = new Harness(20, "submit-then-next", "{\"identicalImage\":true}", null)) {
      harness.start();
      harness.awaitTerminal(Duration.ofMinutes(2));
      assertEquals(
          SessionState.COMPLETED,
          harness.controller.status().state(),
          harness.controller.status().toString());
      assertLedger(harness, 20);
    }
  }

  @Test
  void readbackRejectionNeverClicksSubmit() throws Exception {
    try (Harness harness = new Harness(20, "submit-and-advance", "{\"rejectScore\":true}", null)) {
      harness.start();
      harness.awaitTerminal(Duration.ofSeconds(20));
      assertEquals(SessionState.PAUSED, harness.controller.status().state());
      assertEquals(PauseReason.SCORE_MISMATCH, harness.controller.status().reason());
      assertEquals(0, harness.ledger().size());
    }
  }

  @Test
  void droppedReceiptIsUnknownAndCannotResubmitOnResumeOrRestart() throws Exception {
    try (Harness harness = new Harness(20, "submit-and-advance", "{\"dropReceipt\":true}", null)) {
      harness.start();
      harness.awaitTerminal(Duration.ofSeconds(20));
      assertEquals(
          SessionState.SUBMISSION_UNKNOWN,
          harness.controller.status().state(),
          harness.controller.status().toString());
      JsonNode ledger = harness.ledger();
      assertEquals(1, ledger.size());
      assertTrue(ledger.get(0).path("accepted").asBoolean());
      assertEquals(1, ledger.get(0).path("receivedCountForAnswer").asInt());
      harness.controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.SUBMISSION_UNKNOWN, harness.controller.status().state());
      assertTrue(harness.journal.hasUnknownSubmission(harness.platform.targetIdentity()));
      harness.controller.close();
      try (SessionController restarted = harness.newController(harness.provider)) {
        restarted.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(SessionState.SUBMISSION_UNKNOWN, restarted.status().state());
      }
      assertEquals(1, harness.ledger().size());
    }
  }

  @Test
  void realGlobalStopInTargetForegroundRevokesLateModelPermission() throws Exception {
    CompletableFuture<GradingResponse> delayed = new CompletableFuture<>();
    AtomicReference<app.deskgrade.core.domain.GradingRequest> request = new AtomicReference<>();
    GradingProvider delayedProvider =
        (input, cancellation) -> {
          request.set(input);
          return delayed;
        };
    try (Harness harness = new Harness(20, "submit-and-advance", "{}", delayedProvider)) {
      harness.start();
      await(
          () -> harness.controller.status().state() == SessionState.GRADING,
          Duration.ofSeconds(10));
      assertEquals(WindowSafety.Failure.NONE, harness.windows.check(harness.binding));
      long epoch = harness.controller.actionEpoch();
      long started = System.nanoTime();
      sendSyntheticTestHotkey(0x78);
      await(() -> harness.controller.actionEpoch() > epoch, Duration.ofMillis(200));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(elapsedMs <= 200, "Stop did not revoke within 200 ms: " + elapsedMs);
      await(
          () -> harness.controller.status().state() == SessionState.STOPPED, Duration.ofSeconds(3));
      delayed.complete(
          new GradingResponse(
              request.get().requestId(), GradingResponse.Source.MOCK, result(4), clock.instant()));
      harness.controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.STOPPED, harness.controller.status().state());
      assertEquals(0, harness.ledger().size());
    }
  }

  private void assertLedger(Harness harness, int count) throws Exception {
    JsonNode ledger = harness.ledger();
    long duplicates = 0;
    long mismatches = 0;
    for (int index = 0; index < ledger.size(); index++) {
      JsonNode row = ledger.get(index);
      if (row.path("duplicate").asBoolean() || row.path("receivedCountForAnswer").asInt() != 1)
        duplicates++;
      if (!row.path("answerId")
              .asText()
              .equals("SYN-" + String.format(java.util.Locale.ROOT, "%04d", index + 1))
          || BigDecimal.valueOf(index % 5).compareTo(new BigDecimal(row.path("score").asText()))
              != 0) mismatches++;
    }
    Files.createDirectories(Path.of("target"));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            Path.of(
                    "target",
                    count == 500 ? "native-ledger-summary.json" : "native-ledger-summary-20.json")
                .toFile(),
            java.util.Map.of(
                "expectedCount",
                count,
                "receivedCount",
                ledger.size(),
                "duplicates",
                duplicates,
                "scoresOrAnswerMismatches",
                mismatches,
                "modelCalls",
                harness.controller.status().modelCalls(),
                "confirmedSubmissions",
                harness.controller.status().confirmedSubmissions(),
                "source",
                "REAL_WINDOWS_SYNTHETIC_TARGET_MOCK_MODEL"));
    assertEquals(count, ledger.size());
    for (int index = 0; index < count; index++) {
      JsonNode row = ledger.get(index);
      assertEquals(
          "SYN-" + String.format(java.util.Locale.ROOT, "%04d", index + 1),
          row.path("answerId").asText());
      assertEquals(
          0, BigDecimal.valueOf(index % 5).compareTo(new BigDecimal(row.path("score").asText())));
      assertTrue(row.path("accepted").asBoolean());
      assertFalse(row.path("duplicate").asBoolean());
      assertEquals(1, row.path("receivedCountForAnswer").asInt());
      assertEquals("COMMITTED", row.path("outcome").asText());
    }
  }

  private final class Harness implements AutoCloseable {
    final WindowsWindowService windows = new WindowsWindowService();
    final Process child;
    final URI baseUri;
    final Path directory;
    final Path output;
    final TargetBinding binding;
    final NativeMockPlatformAdapter platform;
    final H2SessionJournal journal;
    final GradingProvider provider;
    final WindowsGlobalHotkeys hotkeys;
    final SessionController controller;
    final WindowChangeMonitor monitor;
    final AtomicReference<SessionController> activeController = new AtomicReference<>();

    Harness(int count, String mode, String faults, GradingProvider providerOverride)
        throws Exception {
      directory = Files.createTempDirectory(temporary, "native-session-");
      output = directory.resolve("mock-window.log");
      int port;
      try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        port = socket.getLocalPort();
      }
      baseUri = URI.create("http://127.0.0.1:" + port);
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      List<String> javafx =
          Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
              .filter(path -> Path.of(path).getFileName().toString().matches("javafx-.+-win\\.jar"))
              .toList();
      assertFalse(
          javafx.isEmpty(), "JavaFX Windows runtime jars missing from integration classpath");
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                  "-Djdk.net.unixdomain.tmpdir="
                      + Files.createDirectories(
                              Path.of(
                                  System.getProperty(
                                      "jdk.net.unixdomain.tmpdir", "local-data/socket-temp")))
                          .toAbsolutePath(),
                  "--module-path",
                  String.join(File.pathSeparator, javafx),
                  "--add-modules",
                  "javafx.controls,javafx.web",
                  "-cp",
                  classpath,
                  "app.deskgrade.mocksite.Launcher",
                  "--window",
                  "--port=" + port,
                  "--count=" + count,
                  "--mode=" + mode,
                  "--test-ledger"));
      child =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      AtomicReference<TargetBinding> target = new AtomicReference<>();
      H2SessionJournal acquiredJournal = null;
      WindowsGlobalHotkeys acquiredHotkeys = null;
      try {
        await(
            () -> {
              assertChildHealthy();
              for (var choice : windows.listWindows()) {
                if (choice.identity().processId() == child.pid()
                    && NativeMockPlatformAdapter.WINDOW_TITLE.equals(choice.title())) {
                  try {
                    var candidate =
                        windows.bind(
                            choice.identity().hwnd(), NativeMockPlatformAdapter.ANSWER_REGION);
                    if (windows.activateBoundWindow(candidate)) {
                      target.set(candidate);
                      return true;
                    }
                  } catch (RuntimeException ignored) {
                    /* Window is still initializing. */
                  }
                }
              }
              return false;
            },
            Duration.ofSeconds(20));
        binding = target.get();
        Files.createDirectories(Path.of("target"));
        var display = binding.calibratedWindow().display();
        Files.writeString(
            Path.of("target/native-smoke-environment.txt"),
            "dpi="
                + binding.calibratedWindow().dpi()
                + "\nscaleX="
                + display.scaleX()
                + "\nscaleY="
                + display.scaleY()
                + "\nphysicalBounds="
                + display.physicalBounds()
                + "\nawtBounds="
                + display.awtBounds()
                + "\n");
        platform =
            new NativeMockPlatformAdapter(
                windows, binding, new WindowRegionCapture(windows, clock));
        await(platform::supportsUnattended, Duration.ofSeconds(15));
        request("/api/faults", faults);
        // Setup only. Observe the visible canvas, including the fault revision, before starting.
        await(
            () -> {
              try {
                return platform.observe().pageRevision() > 1;
              } catch (RuntimeException e) {
                return false;
              }
            },
            Duration.ofSeconds(5));
        journal = new H2SessionJournal(directory.resolve("records"));
        acquiredJournal = journal;
        AtomicInteger ordinal = new AtomicInteger();
        provider =
            providerOverride == null
                ? (request, cancellation) ->
                    new MockGradingProvider(
                            result(ordinal.getAndIncrement() % 5), clock, Runnable::run)
                        .grade(request, cancellation)
                : providerOverride;
        hotkeys =
            new WindowsGlobalHotkeys(
                event -> {
                  SessionController active = activeController.get();
                  if (active == null) return;
                  if (event == WindowsGlobalHotkeys.Event.STOP) active.stop();
                  else if (event == WindowsGlobalHotkeys.Event.UNAVAILABLE)
                    active.pause(PauseReason.HOTKEY_UNAVAILABLE);
                  else if (active.status().state() == SessionState.PAUSED) active.resume();
                  else active.pause(PauseReason.USER_PAUSED);
                });
        acquiredHotkeys = hotkeys;
        controller = newController(provider);
        activeController.set(controller);
        monitor =
            new WindowChangeMonitor(
                windows,
                binding,
                Duration.ofMillis(100),
                failure -> controller.pause(PauseReason.TARGET_CHANGED));
      } catch (Throwable failure) {
        try {
          if (acquiredHotkeys != null) acquiredHotkeys.close();
          if (acquiredJournal != null) acquiredJournal.close();
        } finally {
          terminateChild();
        }
        throw failure;
      }
    }

    SessionController newController(GradingProvider grading) {
      QuestionDefinition question = question();
      return new SessionController(
          platform,
          journal,
          new FileEvidenceStore(directory.resolve("images"), 200_000_000),
          grading,
          result -> GradeValidator.validate(question, result).totalScore(),
          new SessionSettings(
              question,
              "EXPLICIT-MOCK-INTEGRATION",
              1,
              1,
              "{\"source\":\"MOCK\"}",
              1000,
              1100,
              0,
              null,
              Duration.ofMillis(40),
              Duration.ofSeconds(5)),
          clock,
          hotkeys::available,
          status -> {});
    }

    void start() throws Exception {
      controller.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    void awaitTerminal(Duration timeout) throws Exception {
      await(
          () -> {
            assertChildHealthy();
            return switch (controller.status().state()) {
              case COMPLETED, PAUSED, SUBMISSION_UNKNOWN, FAILED, STOPPED -> true;
              default -> false;
            };
          },
          timeout);
    }

    JsonNode ledger() throws Exception {
      return request("/test/ledger", null);
    }

    JsonNode request(String path, String body) throws Exception {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(3));
      if (body != null)
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().length() < 1_000_000, "Bounded test ledger response exceeded");
      return json.readTree(response.body());
    }

    void assertChildHealthy() {
      assertTrue(child.isAlive(), "Synthetic child exited; inspect " + output);
      try {
        assertTrue(
            !Files.exists(output) || Files.size(output) < 1_000_000,
            "Child log exceeded bounded limit");
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Child log unavailable");
      }
    }

    @Override
    public void close() {
      try {
        monitor.close();
        controller.close();
        hotkeys.close();
        journal.close();
      } finally {
        terminateChild();
      }
    }

    private void terminateChild() {
      boolean interrupted = Thread.interrupted();
      long gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      long forcedDeadline = gracefulDeadline + TimeUnit.SECONDS.toNanos(10);
      try {
        child.destroy();
        while (child.isAlive() && System.nanoTime() < forcedDeadline) {
          if (System.nanoTime() >= gracefulDeadline) child.destroyForcibly();
          try {
            child.waitFor(250, TimeUnit.MILLISECONDS);
          } catch (InterruptedException signal) {
            interrupted = true;
            child.destroyForcibly();
          }
        }
        if (child.isAlive())
          throw new IllegalStateException(
              "Synthetic child did not terminate within its cleanup deadline.");
      } finally {
        try {
          child.getOutputStream().close();
        } catch (java.io.IOException ignored) {
          /* Process is exiting. */
        }
        try {
          child.getInputStream().close();
        } catch (java.io.IOException ignored) {
          /* Process is exiting. */
        }
        try {
          child.getErrorStream().close();
        } catch (java.io.IOException ignored) {
          /* Process is exiting. */
        }
        try {
          deleteOwnLogAfterExit();
        } finally {
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
    }

    /** JUnit will delete this same owned temporary file; verify handle release before returning. */
    private void deleteOwnLogAfterExit() {
      Path ownedDirectory = directory.toAbsolutePath().normalize();
      Path ownedLog = output.toAbsolutePath().normalize();
      if (!ownedLog.getParent().equals(ownedDirectory)
          || !ownedLog.getFileName().toString().equals("mock-window.log")) {
        throw new IllegalStateException("Unexpected synthetic cleanup path.");
      }
      long started = System.nanoTime();
      long deadline = started + TimeUnit.SECONDS.toNanos(12);
      int attempts = 0;
      boolean interrupted = Thread.interrupted();
      java.io.IOException last = null;
      try {
        while (System.nanoTime() < deadline) {
          attempts++;
          try {
            Files.deleteIfExists(ownedLog);
            Files.createDirectories(Path.of("target"));
            Files.writeString(
                Path.of("target/native-cleanup-diagnostic.jsonl"),
                "{\"processAlive\":"
                    + child.isAlive()
                    + ",\"deleteAttempts\":"
                    + attempts
                    + ",\"elapsedMillis\":"
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + "}\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            return;
          } catch (java.io.IOException busy) {
            last = busy;
          }
          try {
            Thread.sleep(100);
          } catch (InterruptedException signal) {
            interrupted = true;
          }
        }
        throw new IllegalStateException(
            "Exited synthetic child log remained locked after bounded cleanup (attempts="
                + attempts
                + ").",
            last);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  private static QuestionDefinition question() {
    return new QuestionDefinition(
        "1",
        1,
        "合成测试题",
        "合成答案",
        BigDecimal.valueOf(4),
        List.of(
            new ScoringCriterion(
                "synthetic",
                "合成固定评分点",
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
        "EXPLICIT SYNTHETIC RESULT",
        List.of(
            new GradeResult.CriterionResult(
                "synthetic", BigDecimal.valueOf(score), "synthetic", "Explicit fixture")),
        List.of());
  }

  private static void await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for actual synthetic desktop state");
  }

  /** Test stimulus only, after real registration and verified synthetic foreground ownership. */
  private static void sendSyntheticTestHotkey(int virtualKey) {
    INPUT[] events = (INPUT[]) new INPUT().toArray(6);
    int[] keys = {0x11, 0x12, virtualKey, virtualKey, 0x12, 0x11};
    for (int i = 0; i < events.length; i++) {
      events[i].type = new DWORD(1);
      events[i].input.setType("ki");
      events[i].input.ki.wVk = new WORD(keys[i]);
      events[i].input.ki.dwFlags = new DWORD(i < 3 ? 0 : 2);
    }
    assertEquals(
        events.length,
        User32.INSTANCE.SendInput(new DWORD(events.length), events, events[0].size()).intValue());
  }
}
