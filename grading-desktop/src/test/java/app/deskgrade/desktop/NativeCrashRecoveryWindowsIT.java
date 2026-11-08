package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.MockGradingProvider;
import app.deskgrade.adapters.persistence.FileEvidenceStore;
import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.windows.WindowChangeMonitor;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowsGlobalHotkeys;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.mock.NativeMockPlatformAdapter;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.port.GradingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Real GUI actions occur only against a separately launched synthetic target that survives the
 * killed worker.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class NativeCrashRecoveryWindowsIT {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @EnumSource(
      value = SessionState.class,
      names = {
        "FILLING",
        "VERIFYING_SCORE",
        "SUBMIT_PREPARED",
        "WAIT_ACK",
        "NAVIGATING",
        "WAIT_NEXT"
      })
  void killedNativeWorkerCannotReplaySubmissionAcrossDurableBoundary(SessionState boundary)
      throws Exception {
    assertTrue(
        System.getProperty("os.name", "").startsWith("Windows"),
        "This opt-in test requires an interactive Windows desktop.");
    try (Harness harness = new Harness(boundary)) {
      harness.startWorker();
      JsonNode marker = harness.awaitMarker();
      assertEquals(
          boundary.name(),
          marker.path("boundary").asText(),
          "Worker failed before the requested durable boundary: " + marker);
      UUID runId = UUID.fromString(marker.path("runId").asText());
      UUID attemptId = UUID.fromString(marker.path("attemptId").asText());
      boolean confirmed = boundary == SessionState.NAVIGATING || boundary == SessionState.WAIT_NEXT;
      boolean submitted = boundary == SessionState.WAIT_ACK || confirmed;
      if (submitted) {
        // A completed SendInput call alone is not proof that the service accepted a submission.
        await(
            () -> harness.ledgerUnchecked().size() == 1,
            Duration.ofSeconds(10),
            harness::assertHealthy);
      }
      JsonNode before = harness.ledger();
      assertLedger(before, submitted ? 1 : 0);
      if (boundary == SessionState.VERIFYING_SCORE || boundary == SessionState.SUBMIT_PREPARED) {
        await(
            () -> {
              try {
                var view = harness.platform.observe();
                return view.displayedScore() != null
                    && view.displayedScore().compareTo(new BigDecimal("2")) == 0;
              } catch (RuntimeException e) {
                return false;
              }
            },
            Duration.ofSeconds(5),
            harness::assertHealthy);
      }
      assertTrue(harness.worker.isAlive());
      harness.worker.destroyForcibly();
      assertTrue(
          harness.worker.waitFor(10, TimeUnit.SECONDS),
          "Worker did not terminate after forced kill.");
      assertTrue(
          harness.mock.isAlive(), "The independent mock service must survive the grading worker.");
      boolean unknown =
          boundary == SessionState.SUBMIT_PREPARED || boundary == SessionState.WAIT_ACK;
      AtomicInteger unexpectedModelCalls = new AtomicInteger();
      try (H2SessionJournal recovered = new H2SessionJournal(harness.database)) {
        var runs = recovered.recoverInterrupted(Instant.now());
        var run = runs.stream().filter(r -> r.runId().equals(runId)).findFirst().orElseThrow();
        var attempt =
            recovered.attempts(runId).stream()
                .filter(a -> a.attemptId().equals(attemptId))
                .findFirst()
                .orElseThrow();
        assertEquals(unknown ? SessionState.SUBMISSION_UNKNOWN : SessionState.PAUSED, run.state());
        assertEquals(unknown, recovered.hasUnknownSubmission(harness.platform.targetIdentity()));
        assertEquals(confirmed, attempt.confirmed());
        assertEquals(
            boundary == SessionState.SUBMIT_PREPARED || submitted, attempt.submissionPrepared());
        assertEquals(confirmed ? 1 : 0, run.confirmedSubmissions());
        GradingProvider unused =
            (request, token) -> {
              unexpectedModelCalls.incrementAndGet();
              return new MockGradingProvider(syntheticResult(), Clock.systemUTC(), Runnable::run)
                  .grade(request, token);
            };
        try (SessionController restarted =
            new SessionController(
                harness.platform,
                recovered,
                new FileEvidenceStore(harness.directory.resolve("restart-images"), 10000000),
                unused,
                r -> GradeValidator.validate(question(), r).totalScore(),
                settings(),
                Clock.systemUTC(),
                () -> false,
                status -> {})) {
          // No hotkeys are registered in this parent: constructing a controller is never permission
          // to start.
          assertEquals(SessionState.IDLE, restarted.status().state());
          Thread.sleep(300);
          assertEquals(SessionState.IDLE, restarted.status().state());
          if (unknown) {
            restarted.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(SessionState.SUBMISSION_UNKNOWN, restarted.status().state());
            restarted.resume().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(SessionState.SUBMISSION_UNKNOWN, restarted.status().state());
          }
          assertEquals(0, unexpectedModelCalls.get());
          assertEquals(
              before, harness.ledger(), "Restart must not add, alter or duplicate a submission.");
          assertLedger(harness.ledger(), submitted ? 1 : 0);
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter()
            .writeValue(
                Path.of("target", "native-crash-" + boundary + ".json").toFile(),
                Map.of(
                    "boundary",
                    boundary.name(),
                    "workerForciblyKilled",
                    true,
                    "mockServiceSurvived",
                    true,
                    "actualSubmissions",
                    before.size(),
                    "recoveredState",
                    run.state().name(),
                    "confirmedSubmissions",
                    run.confirmedSubmissions(),
                    "restartModelCalls",
                    unexpectedModelCalls.get(),
                    "source",
                    "REAL_WINDOWS_SYNTHETIC_GUI_PROCESS_CRASH"));
      }
    }
  }

  private static void assertLedger(JsonNode ledger, int count) {
    assertTrue(ledger.isArray());
    assertEquals(count, ledger.size());
    if (count == 1) {
      var row = ledger.get(0);
      assertEquals("SYN-0001", row.path("answerId").asText());
      assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(row.path("score").asText())));
      assertTrue(row.path("accepted").asBoolean());
      assertFalse(row.path("duplicate").asBoolean());
      assertEquals(1, row.path("receivedCountForAnswer").asInt());
      assertEquals("COMMITTED", row.path("outcome").asText());
    }
  }

  private final class Harness implements AutoCloseable {
    final SessionState boundary;
    final Path directory, database, marker, mockOutput, workerOutput;
    final Process mock;
    Process worker;
    final URI baseUri;
    final WindowsWindowService windows = new WindowsWindowService();
    final TargetBinding binding;
    final NativeMockPlatformAdapter platform;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    Harness(SessionState boundary) throws Exception {
      this.boundary = boundary;
      directory = Files.createTempDirectory(temporary, "native-crash-");
      database = directory.resolve("records");
      marker = directory.resolve("durable-marker.json");
      mockOutput = directory.resolve("mock.log");
      workerOutput = directory.resolve("worker.log");
      int port;
      try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        port = socket.getLocalPort();
      }
      baseUri = URI.create("http://127.0.0.1:" + port);
      List<String> command = javaCommand();
      boolean separateNext =
          boundary == SessionState.NAVIGATING || boundary == SessionState.WAIT_NEXT;
      command.addAll(
          List.of(
              "app.deskgrade.mocksite.Launcher",
              "--window",
              "--port=" + port,
              "--count=3",
              "--mode=" + (separateNext ? "submit-then-next" : "submit-and-advance"),
              "--test-ledger"));
      mock =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(mockOutput.toFile())
              .start();
      try {
        AtomicReference<TargetBinding> found = new AtomicReference<>();
        await(
            () -> {
              for (var choice : windows.listWindows()) {
                if (choice.identity().processId() == mock.pid()
                    && NativeMockPlatformAdapter.WINDOW_TITLE.equals(choice.title())) {
                  try {
                    var candidate =
                        windows.bind(
                            choice.identity().hwnd(), NativeMockPlatformAdapter.ANSWER_REGION);
                    if (windows.activateBoundWindow(candidate)) {
                      found.set(candidate);
                      return true;
                    }
                  } catch (RuntimeException ignored) {
                  }
                }
              }
              return false;
            },
            Duration.ofSeconds(25),
            this::assertHealthy);
        binding = found.get();
        platform =
            new NativeMockPlatformAdapter(
                windows, binding, new WindowRegionCapture(windows, Clock.systemUTC()));
        await(platform::supportsUnattended, Duration.ofSeconds(10), this::assertHealthy);
        assertLedger(ledger(), 0);
      } catch (Throwable failure) {
        try {
          terminateOwned(mock, mockOutput, "mock.log");
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        } finally {
          http.shutdownNow();
        }
        throw failure;
      }
    }

    void startWorker() throws Exception {
      List<String> command = javaCommand();
      command.addAll(
          List.of(
              Worker.class.getName(),
              Long.toString(binding.calibratedWindow().identity().hwnd()),
              Long.toString(mock.pid()),
              database.toString(),
              marker.toString(),
              boundary.name(),
              directory.resolve("images").toString()));
      worker =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(workerOutput.toFile())
              .start();
    }

    JsonNode awaitMarker() throws Exception {
      await(() -> Files.isRegularFile(marker), Duration.ofSeconds(30), this::assertHealthy);
      // The atomic move publishes a complete, bounded marker only after the journal transition
      // committed.
      assertTrue(Files.size(marker) < 8192);
      return json.readTree(Files.readString(marker));
    }

    JsonNode ledger() throws Exception {
      var request =
          HttpRequest.newBuilder(baseUri.resolve("/test/ledger"))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().length() < 100000);
      return json.readTree(response.body());
    }

    JsonNode ledgerUnchecked() {
      try {
        return ledger();
      } catch (Exception e) {
        throw new IllegalStateException("Synthetic ledger unavailable");
      }
    }

    void assertHealthy() {
      assertTrue(mock.isAlive(), "Synthetic mock exited; inspect " + mockOutput);
      if (worker != null)
        assertTrue(worker.isAlive(), "Native worker exited; inspect " + workerOutput);
      try {
        assertTrue(
            !Files.exists(mockOutput) || Files.size(mockOutput) < 1000000,
            "Mock log limit exceeded");
        assertTrue(
            !Files.exists(workerOutput) || Files.size(workerOutput) < 1000000,
            "Worker log limit exceeded");
      } catch (java.io.IOException e) {
        throw new IllegalStateException("Bounded process log unavailable");
      }
    }

    @Override
    public void close() {
      try {
        if (worker != null) terminateOwned(worker, workerOutput, "worker.log");
      } finally {
        try {
          terminateOwned(mock, mockOutput, "mock.log");
        } finally {
          http.shutdownNow();
        }
      }
    }

    private void terminateOwned(Process process, Path output, String expectedFileName) {
      terminate(process);
      deleteOwnLogAfterExit(process, output, expectedFileName);
    }

    /** Wait for release of only this fixture's exact log before JUnit removes its directory. */
    private void deleteOwnLogAfterExit(Process process, Path output, String expectedFileName) {
      Path ownedDirectory = directory.toAbsolutePath().normalize();
      Path ownedLog = output.toAbsolutePath().normalize();
      if (!ownedLog.getParent().equals(ownedDirectory)
          || !ownedLog.getFileName().toString().equals(expectedFileName)
          || !(expectedFileName.equals("mock.log") || expectedFileName.equals("worker.log"))) {
        throw new IllegalStateException("Unexpected synthetic cleanup path.");
      }
      if (process.isAlive()) throw new IllegalStateException("Synthetic process is still running.");
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
                Path.of("target/native-crash-cleanup-diagnostic.jsonl"),
                "{\"boundary\":\""
                    + boundary.name()
                    + "\",\"log\":\""
                    + expectedFileName
                    + "\",\"processAlive\":"
                    + process.isAlive()
                    + ",\"deleteAttempts\":"
                    + attempts
                    + ",\"elapsedMillis\":"
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + "}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
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
            "Exited synthetic process log remained locked after bounded cleanup (attempts="
                + attempts
                + ").",
            last);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  private static List<String> javaCommand() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    List<String> javafx =
        Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .filter(path -> Path.of(path).getFileName().toString().matches("javafx-.+-win\\.jar"))
            .toList();
    if (javafx.isEmpty()) throw new IllegalStateException("JavaFX Windows runtime missing");
    Path sockets =
        Files.createDirectories(Path.of(System.getProperty("jdk.net.unixdomain.tmpdir", "target")))
            .toAbsolutePath();
    return new ArrayList<>(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Djava.awt.headless=false",
            "-Djdk.net.unixdomain.tmpdir=" + sockets,
            "--module-path",
            String.join(File.pathSeparator, javafx),
            "--add-modules",
            "javafx.controls,javafx.web",
            "-cp",
            classpath));
  }

  private static void terminate(Process process) {
    boolean interrupted = Thread.interrupted();
    long gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    long forcedDeadline = gracefulDeadline + TimeUnit.SECONDS.toNanos(10);
    try {
      process.destroy();
      while (process.isAlive() && System.nanoTime() < forcedDeadline) {
        if (System.nanoTime() >= gracefulDeadline) process.destroyForcibly();
        try {
          process.waitFor(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException signal) {
          interrupted = true;
          process.destroyForcibly();
        }
      }
      if (process.isAlive())
        throw new IllegalStateException(
            "Synthetic process did not terminate within its cleanup deadline.");
    } finally {
      try {
        process.getOutputStream().close();
      } catch (java.io.IOException ignored) {
        /* Process is exiting. */
      }
      try {
        process.getInputStream().close();
      } catch (java.io.IOException ignored) {
        /* Process is exiting. */
      }
      try {
        process.getErrorStream().close();
      } catch (java.io.IOException ignored) {
        /* Process is exiting. */
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void await(BooleanSupplier condition, Duration timeout, Runnable health)
      throws InterruptedException {
    long until = System.nanoTime() + timeout.toNanos();
    do {
      health.run();
      if (condition.getAsBoolean()) return;
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    fail("Timed out waiting for durable native boundary or independent ledger.");
  }

  private static QuestionDefinition question() {
    return new QuestionDefinition(
        "1",
        1,
        "合成崩溃恢复测试题",
        "合成答案",
        new BigDecimal("4"),
        List.of(
            new ScoringCriterion(
                "synthetic",
                "合成固定评分点",
                List.of(
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    new BigDecimal("2"),
                    new BigDecimal("3"),
                    new BigDecimal("4")))));
  }

  private static GradeResult syntheticResult() {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "SYNTHETIC CRASH FIXTURE",
        List.of(
            new GradeResult.CriterionResult("synthetic", new BigDecimal("2"), "合成证据", "显式固定测试分")),
        List.of());
  }

  private static SessionSettings settings() {
    return new SessionSettings(
        question(),
        "EXPLICIT-NATIVE-CRASH-MOCK",
        1,
        1,
        "{\"source\":\"MOCK\",\"purpose\":\"native-process-crash\"}",
        3,
        3,
        0,
        null,
        Duration.ofMillis(50),
        Duration.ofSeconds(10));
  }

  /** Child JVM owns actual input, hotkeys and durable journal; it never reads the test ledger. */
  public static final class Worker {
    public static void main(String[] args) throws Exception {
      long hwnd = Long.parseLong(args[0]), expectedPid = Long.parseLong(args[1]);
      Path database = Path.of(args[2]), marker = Path.of(args[3]), images = Path.of(args[5]);
      SessionState boundary = SessionState.valueOf(args[4]);
      var windows = new WindowsWindowService();
      var snapshot = windows.snapshot(hwnd);
      if (snapshot.identity().processId() != expectedPid
          || !NativeMockPlatformAdapter.WINDOW_TITLE.equals(snapshot.title()))
        throw new IllegalStateException("Only the dedicated synthetic target is authorized.");
      TargetBinding binding = windows.bind(hwnd, NativeMockPlatformAdapter.ANSWER_REGION);
      if (!windows.activateBoundWindow(binding))
        throw new IllegalStateException("Synthetic foreground activation failed.");
      var platform =
          new NativeMockPlatformAdapter(
              windows, binding, new WindowRegionCapture(windows, Clock.systemUTC()));
      var active = new AtomicReference<SessionController>();
      var markerWritten = new AtomicBoolean();
      try (var journal = new H2SessionJournal(database);
          var hotkeys =
              new WindowsGlobalHotkeys(
                  event -> {
                    var controller = active.get();
                    if (controller == null) return;
                    if (event == WindowsGlobalHotkeys.Event.STOP) controller.stop();
                    else controller.pause(PauseReason.USER_PAUSED);
                  })) {
        if (!hotkeys.available())
          throw new IllegalStateException("Real global hotkeys are required.");
        var provider = new MockGradingProvider(syntheticResult(), Clock.systemUTC(), Runnable::run);
        try (var controller =
                new SessionController(
                    platform,
                    journal,
                    new FileEvidenceStore(images, 10000000),
                    provider,
                    r -> GradeValidator.validate(question(), r).totalScore(),
                    settings(),
                    Clock.systemUTC(),
                    hotkeys::available,
                    status -> {
                      boolean failed =
                          Set.of(
                                  SessionState.PAUSED,
                                  SessionState.STOPPED,
                                  SessionState.FAILED,
                                  SessionState.SUBMISSION_UNKNOWN)
                              .contains(status.state());
                      if ((status.state() == boundary || failed)
                          && markerWritten.compareAndSet(false, true)) {
                        try {
                          var attempts =
                              status.runId() == null
                                  ? List.<SessionJournal.AttemptRecord>of()
                                  : journal.attempts(status.runId());
                          var object = new ObjectMapper().createObjectNode();
                          object.put("boundary", status.state().name());
                          object.put("reason", status.reason().name());
                          object.put(
                              "runId", status.runId() == null ? "" : status.runId().toString());
                          object.put(
                              "attemptId",
                              attempts.isEmpty() ? "" : attempts.getLast().attemptId().toString());
                          byte[] bytes =
                              object.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                          Path staged = marker.resolveSibling(marker.getFileName() + ".tmp");
                          try (FileChannel file =
                              FileChannel.open(
                                  staged,
                                  StandardOpenOption.CREATE_NEW,
                                  StandardOpenOption.WRITE)) {
                            ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            while (buffer.hasRemaining()) file.write(buffer);
                            file.force(true);
                          }
                          Files.move(staged, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                          new CountDownLatch(1).await();
                        } catch (Exception e) {
                          throw new IllegalStateException("Durable boundary marker failed.");
                        }
                      }
                    });
            var monitor =
                new WindowChangeMonitor(
                    windows,
                    binding,
                    Duration.ofMillis(100),
                    failure -> {
                      var c = active.get();
                      if (c != null) c.pause(PauseReason.TARGET_CHANGED);
                    })) {
          active.set(controller);
          controller.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
          new CountDownLatch(1).await();
        }
      }
    }
  }
}
