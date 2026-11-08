package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.mock.MockCanvasProtocol;
import app.deskgrade.adapters.windows.mock.NativeMockPlatformAdapter;
import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.model.ProviderProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Workspace wiring only: real Windows pixels/input, local HTTP model fixture, no paid provider. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class LiveSyntheticSessionWindowsIT {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void threeLocalHttpScoresReachOnlySyntheticWindowAndIndependentLedger() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newSingleThreadExecutor();
    model.setExecutor(executor);
    model.createContext(
        "/chat/completions",
        exchange -> {
          try {
            int ordinal = requests.incrementAndGet();
            assertTrue(ordinal <= 3, "Unexpected model request loop");
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(
                "Bearer synthetic-workspace-fixture",
                exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = exchange.getRequestBody().readNBytes(2_000_001);
            assertTrue(body.length <= 2_000_000, "Model request exceeded test limit");
            JsonNode request = json.readTree(body);
            assertEquals("local-fixture-only", request.path("model").asText());
            String data =
                request
                    .path("messages")
                    .get(1)
                    .path("content")
                    .get(1)
                    .path("image_url")
                    .path("url")
                    .asText();
            assertTrue(data.startsWith("data:image/png;base64,"));
            var image =
                ImageIO.read(
                    new ByteArrayInputStream(
                        Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1))));
            assertNotNull(image);
            assertEquals(720, image.getWidth(), "Only the answer region may reach the model");
            assertEquals(220, image.getHeight(), "The local state canvas must not be uploaded");
            var result = json.createObjectNode();
            result
                .put("status", "SCORED")
                .put("readability", "CLEAR")
                .put("studentText", "LOCAL HTTP FIXTURE " + ordinal);
            var criteria = result.putArray("criteria");
            criteria
                .addObject()
                .put("criterionId", "calculation")
                .put("awarded", 2)
                .put("evidence", "fixture")
                .put("reason", "local wiring test");
            criteria
                .addObject()
                .put("criterionId", "expression")
                .put("awarded", ordinal >= 2 ? 1 : 0)
                .put("evidence", "fixture")
                .put("reason", "local wiring test");
            criteria
                .addObject()
                .put("criterionId", "explanation")
                .put("awarded", ordinal == 3 ? 1 : 0)
                .put("evidence", "fixture")
                .put("reason", "local wiring test");
            result.putArray("issues");
            var response = json.createObjectNode();
            response
                .putArray("choices")
                .addObject()
                .put("finish_reason", "stop")
                .putObject("message")
                .put("role", "assistant")
                .put("content", result.toString());
            response.putObject("usage").put("prompt_tokens", 11).put("completion_tokens", 7);
            byte[] output = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
          } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
            try {
              exchange.sendResponseHeaders(500, -1);
            } catch (java.io.IOException ignored) {
            }
          } finally {
            exchange.close();
          }
        });
    model.start();
    Path dataDirectory = temporary.resolve("isolated-workspace");
    DesktopWorkspace workspace = DesktopTestMode.developer(dataDirectory);
    try (var target = new SyntheticWindow();
        var http = HttpClient.newHttpClient()) {
      try {
        workspace.initialize().get(10, TimeUnit.SECONDS);
        workspace.setCommercialMode(false).get(5, TimeUnit.SECONDS);
        assertNull(workspace.status(), "Startup must not create an automatic session");
        assertFalse(workspace.hotkeysAvailable());
        ProviderProfile configured =
            new ProviderProfile(
                "LOCAL_FIXTURE_ONLY",
                1,
                "LOCAL HTTP TEST FIXTURE - NOT A VERIFIED PAID MODEL",
                URI.create(
                    "http://127.0.0.1:" + model.getAddress().getPort() + "/chat/completions"),
                "local-fixture-only",
                "synthetic-workspace-fixture",
                ProviderProfile.StructuredMode.NONE,
                Map.of(),
                Set.of(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                null,
                null);
        // Explicit synthetic proof for this test endpoint only. This is not image-capability
        // evidence.
        ProviderProfile fixture =
            configured.withVerification(
                new ProviderProfile.Verification(
                    configured.configDigest(), Instant.EPOCH, true, true));
        workspace.saveProfile(fixture).get(5, TimeUnit.SECONDS);
        char[] synthetic = "synthetic-workspace-fixture".toCharArray();
        try {
          new DpapiSecretStore(dataDirectory.resolve("credentials"))
              .put(fixture.credentialRef(), synthetic);
        } finally {
          Arrays.fill(synthetic, '\0');
        }
        workspace
            .bind(
                target.binding.calibratedWindow().identity().hwnd(),
                NativeMockPlatformAdapter.ANSWER_REGION)
            .get(5, TimeUnit.SECONDS);
        workspace.registerHotkeys().get(5, TimeUnit.SECONDS);
        assertTrue(workspace.hotkeysAvailable());
        workspace
            .startLiveSynthetic(fixture, 3, 3, workspace.actionTicket())
            .get(10, TimeUnit.SECONDS);
        await(
            () -> {
              target.assertHealthy();
              var status = workspace.status();
              return status != null
                  && Set.of(
                          SessionState.COMPLETED,
                          SessionState.PAUSED,
                          SessionState.STOPPED,
                          SessionState.SUBMISSION_UNKNOWN,
                          SessionState.FAILED)
                      .contains(status.state());
            },
            Duration.ofSeconds(45));
        assertNull(serverFailure.get(), "Local model fixture rejected its request");
        assertEquals(
            SessionState.COMPLETED, workspace.status().state(), workspace.status().toString());
        assertTrue(workspace.liveSyntheticSession());
        assertEquals(3, requests.get());
        assertEquals(3, workspace.status().modelCalls());
        assertEquals(3, workspace.status().confirmedSubmissions());
        var ledgerResponse =
            http.send(
                HttpRequest.newBuilder(target.base.resolve("/test/ledger"))
                    .timeout(Duration.ofSeconds(3))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ledgerResponse.statusCode());
        assertTrue(ledgerResponse.body().length() < 100_000);
        JsonNode ledger = json.readTree(ledgerResponse.body());
        assertEquals(3, ledger.size());
        var attempts = workspace.attempts(workspace.status().runId()).get(5, TimeUnit.SECONDS);
        assertEquals(3, attempts.size());
        for (int index = 0; index < 3; index++) {
          var row = ledger.get(index);
          assertEquals(
              "SYN-" + String.format(java.util.Locale.ROOT, "%04d", index + 1),
              row.path("answerId").asText());
          assertEquals(
              0,
              BigDecimal.valueOf(index + 2).compareTo(new BigDecimal(row.path("score").asText())));
          assertTrue(row.path("accepted").asBoolean());
          assertFalse(row.path("duplicate").asBoolean());
          assertEquals(1, row.path("receivedCountForAnswer").asInt());
          assertEquals("COMMITTED", row.path("outcome").asText());
          assertTrue(attempts.get(index).confirmed());
          JsonNode audit =
              json.readTree(workspace.recordAudit(attempts.get(index)).get(5, TimeUnit.SECONDS));
          assertEquals(
              "LIVE_MODEL_SYNTHETIC_PLATFORM", audit.path("configuration").path("mode").asText());
          assertEquals(1, audit.path("modelCalls").size());
          var call = audit.path("modelCalls").get(0);
          assertEquals(fixture.id(), call.path("profileId").asText());
          assertEquals(
              "LIVE",
              call.path("source").asText(),
              "Real provider adapter must not fall back to MockGradingProvider");
          assertEquals(11, call.path("inputTokens").asInt());
          assertEquals(7, call.path("outputTokens").asInt());
          assertTrue(call.path("estimatedCost").isNull());
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter()
            .writeValue(
                Path.of("target/live-synthetic-workspace-summary.json").toFile(),
                Map.of(
                    "source",
                    "REAL_WINDOWS_SYNTHETIC_TARGET_LOCAL_HTTP_FIXTURE",
                    "paidModelTested",
                    false,
                    "expectedScores",
                    List.of(2, 3, 4),
                    "receivedCount",
                    ledger.size(),
                    "confirmedSubmissions",
                    workspace.status().confirmedSubmissions(),
                    "modelCalls",
                    requests.get(),
                    "duplicates",
                    0,
                    "scoreMismatches",
                    0));
      } finally {
        // Revoke and close all workspace resources while the target still exists.
        workspace.close();
        workspace.awaitClosed().get(10, TimeUnit.SECONDS);
      }
    } finally {
      try {
        workspace.close();
        workspace.awaitClosed().get(10, TimeUnit.SECONDS);
      } finally {
        model.stop(0);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  private final class SyntheticWindow implements AutoCloseable {
    final Path directory = Files.createTempDirectory(temporary, "synthetic-child-");
    final Path output = directory.resolve("mock-window.log");
    final Process child;
    final URI base;
    final TargetBinding binding;

    SyntheticWindow() throws Exception {
      int port;
      try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        port = socket.getLocalPort();
      }
      base = URI.create("http://127.0.0.1:" + port);
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      List<String> javafx =
          Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
              .filter(path -> Path.of(path).getFileName().toString().matches("javafx-.+-win\\.jar"))
              .toList();
      assertFalse(javafx.isEmpty());
      Path sockets =
          Files.createDirectories(
                  Path.of(System.getProperty("jdk.net.unixdomain.tmpdir", "target")))
              .toRealPath();
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                  "-Djdk.net.unixdomain.tmpdir=" + sockets,
                  "--module-path",
                  String.join(File.pathSeparator, javafx),
                  "--add-modules",
                  "javafx.controls,javafx.web",
                  "-cp",
                  classpath,
                  "app.deskgrade.mocksite.Launcher",
                  "--window",
                  "--port=" + port,
                  "--count=3",
                  "--mode=submit-and-advance",
                  "--test-ledger"));
      if (Boolean.getBoolean("deskgrade.mock.startupDiagnostics")) {
        command.add(1, "-Ddeskgrade.mock.diagnosticsDir=" + directory.toAbsolutePath());
      }
      if ("sw".equals(System.getProperty("deskgrade.mock.testPrismOrder"))) {
        command.add(1, "-Dprism.order=sw");
      }
      child =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      AtomicReference<String> preparation = new AtomicReference<>("CHILD_WINDOW_NOT_FOUND");
      AtomicReference<TargetBinding> lastBinding = new AtomicReference<>();
      try {
        var windows = new WindowsWindowService();
        AtomicReference<TargetBinding> found = new AtomicReference<>();
        await(
            () -> {
              assertHealthy();
              for (var choice : windows.listWindows()) {
                if (choice.identity().processId() == child.pid()
                    && NativeMockPlatformAdapter.WINDOW_TITLE.equals(choice.title())) {
                  try {
                    preparation.set("BINDING_CHILD_WINDOW");
                    var next =
                        windows.bind(
                            choice.identity().hwnd(), NativeMockPlatformAdapter.ANSWER_REGION);
                    lastBinding.set(next);
                    var platform =
                        new NativeMockPlatformAdapter(
                            windows, next, new WindowRegionCapture(windows, Clock.systemUTC()));
                    boolean activated = windows.activateBoundWindow(next);
                    var current = windows.snapshot(choice.identity().hwnd());
                    String geometry =
                        "activated="
                            + activated
                            + ", safety="
                            + windows.check(next)
                            + ", dpi="
                            + current.dpi()
                            + ", scale="
                            + current.display().scaleX()
                            + "/"
                            + current.display().scaleY()
                            + ", client="
                            + current.clientBounds();
                    preparation.set(geometry);
                    if (activated && platform.supportsUnattended()) {
                      found.set(next);
                      return true;
                    }
                    if (activated) {
                      try {
                        preparation.set(geometry + ", page=" + platform.observe().page());
                      } catch (RuntimeException unavailable) {
                        preparation.set(geometry + ", protocol=" + unavailable.getMessage());
                      }
                    }
                  } catch (RuntimeException initializing) {
                    preparation.set(
                        preparation.get() + ", failure=" + initializing.getClass().getSimpleName());
                  }
                }
              }
              return false;
            },
            Duration.ofSeconds(25));
        binding = found.get();
      } catch (Throwable failure) {
        // Preserve bounded diagnostics before child cleanup removes its exclusively owned log.
        // No other window titles, credentials, or model request bodies are included.
        try {
          String childLog = "";
          if (Files.exists(output)) {
            try (var input = Files.newInputStream(output)) {
              childLog =
                  new String(input.readNBytes(8_192), java.nio.charset.StandardCharsets.UTF_8);
            }
          }
          Files.createDirectories(Path.of("target"));
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("capturedAt", Instant.now().toString());
          details.put("childAlive", child.isAlive());
          details.put("preparation", preparation.get());
          details.put("childLogPrefix", childLog);
          capturePageDiagnostics(details);
          captureStartupDiagnostics(lastBinding.get(), details);
          json.writerWithDefaultPrettyPrinter()
              .writeValue(Path.of("target/live-synthetic-startup-failure.json").toFile(), details);
          failure.addSuppressed(new AssertionError("Synthetic preparation: " + preparation.get()));
        } catch (Throwable diagnosticFailure) {
          failure.addSuppressed(diagnosticFailure);
        }
        try {
          close();
        } catch (Throwable cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    private void capturePageDiagnostics(Map<String, Object> details) {
      try {
        Path metadata = directory.resolve("webview-startup.json");
        if (Files.isRegularFile(metadata) && Files.size(metadata) <= 16_384)
          details.put("webView", json.readTree(metadata.toFile()));
        Path snapshot = directory.resolve("webview-startup.png");
        if (Files.isRegularFile(snapshot) && Files.size(snapshot) <= 8_000_000)
          Files.copy(
              snapshot,
              Path.of("target/live-synthetic-startup-webview.png"),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception failure) {
        details.put("webViewDiagnosticFailure", failure.getClass().getSimpleName());
      }
      // One bounded read of this child's local HTML diagnoses transport separately from rendering.
      // It is never used for readiness, scoring, acknowledgement, or permission to send input.
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
        var response =
            client.send(
                HttpRequest.newBuilder(base).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
          byte[] html = input.readNBytes(65_537);
          details.put("httpStatus", response.statusCode());
          details.put("httpBytes", html.length);
          details.put("httpContentType", response.headers().firstValue("Content-Type").orElse(""));
          details.put(
              "httpContainsMarker",
              new String(html, java.nio.charset.StandardCharsets.UTF_8)
                  .contains("<canvas id=\"marker\""));
        }
      } catch (Exception failure) {
        details.put("httpDiagnosticFailure", failure.getClass().getSimpleName());
      }
    }

    private void captureStartupDiagnostics(TargetBinding last, Map<String, Object> details) {
      if (last == null) return;
      try {
        var windows = new WindowsWindowService();
        var current = windows.snapshot(last.calibratedWindow().identity().hwnd());
        if (current.identity().processId() != child.pid()
            || !current.identity().equals(last.calibratedWindow().identity())
            || !NativeMockPlatformAdapter.WINDOW_TITLE.equals(current.title())) {
          details.put("capture", "CHILD_IDENTITY_CHANGED");
          return;
        }
        var capture = new WindowRegionCapture(windows, Clock.systemUTC());
        var marker = windows.bind(current.identity().hwnd(), new LogicalRect(16, 16, 260, 28));
        details.put("markerSafety", windows.check(marker).name());
        var png = capture.capture(marker);
        Files.write(Path.of("target/live-synthetic-startup-marker.png"), png.png());
        var pixels = ImageIO.read(new ByteArrayInputStream(png.png()));
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
          samples.add(String.format("%08x", pixels.getRGB(6 + 8 * i, 14)));
        }
        details.put("markerCellArgb", samples);
        details.put("markerBorderArgb", String.format("%08x", pixels.getRGB(0, 14)));
        try {
          details.put("decodedPage", MockCanvasProtocol.decode(pixels).page().name());
        } catch (RuntimeException invalid) {
          details.put("decodeFailure", invalid.getMessage());
        }
        // This client belongs to the independently launched synthetic fixture only.
        // Normal capture safety still rejects changed identity, focus loss and any occlusion.
        double scale = current.dpi() / 96.0;
        var client =
            windows.bind(
                current.identity().hwnd(),
                new LogicalRect(
                    0,
                    0,
                    current.clientBounds().width() / scale,
                    current.clientBounds().height() / scale));
        Files.write(
            Path.of("target/live-synthetic-startup-client.png"), capture.capture(client).png());
      } catch (Exception failure) {
        details.put(
            "captureFailure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
      }
    }

    void assertHealthy() {
      assertTrue(child.isAlive(), "Synthetic child exited");
      try {
        assertTrue(
            !Files.exists(output) || Files.size(output) < 1_000_000,
            "Child request/log loop detected");
      } catch (java.io.IOException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public void close() throws Exception {
      boolean interrupted = Thread.interrupted();
      long graceful = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      long deadline = graceful + TimeUnit.SECONDS.toNanos(10);
      try {
        child.destroy();
        while (child.isAlive() && System.nanoTime() < deadline) {
          if (System.nanoTime() >= graceful) child.destroyForcibly();
          try {
            child.waitFor(250, TimeUnit.MILLISECONDS);
          } catch (InterruptedException signal) {
            interrupted = true;
            child.destroyForcibly();
          }
        }
        assertFalse(child.isAlive(), "Synthetic child did not terminate within deadline");
        child.getOutputStream().close();
        child.getInputStream().close();
        child.getErrorStream().close();
        Path owned = output.toAbsolutePath().normalize();
        assertEquals(directory.toAbsolutePath().normalize(), owned.getParent());
        assertEquals("mock-window.log", owned.getFileName().toString());
        long deleteDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        java.io.IOException last = null;
        while (System.nanoTime() < deleteDeadline) {
          try {
            Files.deleteIfExists(owned);
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
        throw new IllegalStateException("Exited synthetic child log remained locked", last);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(50);
    }
    fail("Condition did not become true before " + timeout);
  }
}
