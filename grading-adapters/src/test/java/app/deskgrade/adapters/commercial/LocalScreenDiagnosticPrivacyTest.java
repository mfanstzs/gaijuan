package app.deskgrade.adapters.commercial;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.JournalJson;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Loopback-only transport contract: local screen diagnostics must not join journal/model JSON. */
@Timeout(20)
class LocalScreenDiagnosticPrivacyTest {
  private static final String PROFILE = "synthetic-primary";
  private static final ScreenChangeDiagnostic LOCAL_DIAGNOSTIC =
      new ScreenChangeDiagnostic(
          ScreenChangeDiagnostic.Checkpoint.FILL_ACTION,
          73,
          4096,
          ScreenChangeDiagnostic.ChangeScope.LOCALIZED,
          ScreenChangeDiagnostic.TripleConsistency.MIXED,
          1317);
  private static final Set<String> LOCAL_FIELDS =
      Set.of(
          "screenChange",
          "screenChangeDiagnostic",
          "failureDiagnostic",
          "checkpoint",
          "changedPixels",
          "totalPixels",
          "changeScope",
          "tripleConsistency",
          "millisSinceFill");

  @Test
  void localFailureReachesStatusButNeverTheJournalOrSyntheticModelRequest() throws Exception {
    byte[] png = syntheticPng();
    var rubric = rubric();
    var platform = new LocalPlatform(png);
    var stopped = new CountDownLatch(1);
    var localStatus = new AtomicReference<SessionController.Status>();
    try (var server = new LoopbackGateway();
        var client = new CommercialClient(server.origin(), Clock.systemUTC())) {
      client
          .login("alice", "synthetic-test-password-only".toCharArray())
          .toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      client.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
      var settings =
          new SessionSettings(
              rubric.question(),
              PROFILE,
              1,
              1,
              "{\"synthetic\":true}",
              1,
              0,
              0,
              null,
              Duration.ofMillis(5),
              Duration.ofSeconds(5));
      try (var journal = new CommercialSessionJournal(client);
          var provider = new GatewayGradingProvider(client, rubric, PROFILE);
          var controller =
              new SessionController(
                  platform,
                  journal,
                  new MetadataOnlyEvidenceStore(),
                  provider,
                  result -> BigDecimal.ONE,
                  settings,
                  Clock.systemUTC(),
                  () -> true,
                  status -> {
                    if (status.failureDiagnostic() != null) localStatus.set(status);
                    if (status.state() == SessionState.STOPPED) stopped.countDown();
                  })) {
        controller.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(
            stopped.await(8, TimeUnit.SECONDS),
            "The synthetic local failure must stop the controller");
        var status = controller.status();
        assertEquals(PauseReason.LOCAL_FAILURE, status.reason());
        assertEquals(LOCAL_DIAGNOSTIC, status.failureDiagnostic().screenChange());
        assertEquals(LOCAL_DIAGNOSTIC, localStatus.get().failureDiagnostic().screenChange());
        assertEquals(1, status.modelCalls());
        assertEquals(0, status.confirmedSubmissions());
        assertEquals(1, platform.fillAttempts.get());
        assertEquals(0, platform.submits.get());
        assertEquals(
            1, server.gradeCalls.get(), "No retry or diagnostic-only model call is allowed");
        var transitions =
            server.requests.stream()
                .filter(request -> request.path().equals("/v1/journal"))
                .map(Request::body)
                .filter(body -> body.path("operation").asText().equals("transition"))
                .toList();
        assertFalse(transitions.isEmpty());
        assertTrue(
            transitions.stream()
                .anyMatch(body -> body.at("/arguments/state").asText().equals("STOPPED")));
        for (var transition : transitions)
          assertEquals(
              Set.of("runId", "attemptId", "state", "reason", "now"),
              fields(transition.path("arguments")));
        for (var request : server.requests) {
          assertNoLocalDiagnostic(request.body());
          if (request.path().equals("/v1/journal")) {
            assertFalse(
                request.body().toString().contains(Base64.getEncoder().encodeToString(png)));
            assertFalse(request.body().path("arguments").has("png"));
          }
        }
        var grade =
            server.requests.stream()
                .filter(request -> request.path().equals("/v1/grade"))
                .findFirst()
                .orElseThrow()
                .body();
        assertEquals(
            Set.of("requestId", "profileId", "policyRevision", "rubric", "imageBase64"),
            fields(grade));
        assertEquals(
            Base64.getEncoder().encodeToString(png),
            grade.path("imageBase64").asText(),
            "Only the original synthetic grading image is transmitted, never difference images");
      }
    }
  }

  private static void assertNoLocalDiagnostic(JsonNode node) {
    if (node.isObject()) {
      for (String name : fields(node)) {
        assertFalse(
            LOCAL_FIELDS.contains(name), "Local-only field escaped into an HTTP body: " + name);
        assertNoLocalDiagnostic(node.get(name));
      }
    } else if (node.isArray()) {
      for (var child : node) assertNoLocalDiagnostic(child);
    } else if (node.isTextual()) {
      for (String name : LOCAL_FIELDS)
        assertFalse(
            node.textValue().contains(name),
            "Local-only diagnostic escaped inside an encoded string: " + name);
    }
  }

  private static Set<String> fields(JsonNode node) {
    var result = new HashSet<String>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private static Rubric rubric() {
    var question =
        new QuestionDefinition(
            "synthetic-q",
            1,
            "Synthetic question",
            "Synthetic answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "p", "Synthetic criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    return new Rubric(question, "Synthetic scoring rule", BigDecimal.ONE, false, false);
  }

  private static byte[] syntheticPng() throws IOException {
    var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
      assertTrue(ImageIO.write(image, "png", imageOutput));
      imageOutput.flush();
      return output.toByteArray();
    } finally {
      image.flush();
    }
  }

  private static final class LocalChangeFailure extends RuntimeException
      implements SafeFailureCode, SafeScreenChangeFailure {
    private static final long serialVersionUID = 1L;

    LocalChangeFailure() {
      super("Synthetic local change only", null, false, false);
    }

    @Override
    public String diagnosticCode() {
      return "ANSWER_CHANGED_BEFORE_INPUT";
    }

    @Override
    public ScreenChangeDiagnostic screenChangeDiagnostic() {
      return LOCAL_DIAGNOSTIC;
    }
  }

  private static final class LocalPlatform implements SessionPlatform {
    private final Capture capture;
    final AtomicInteger fillAttempts = new AtomicInteger(), submits = new AtomicInteger();

    LocalPlatform(byte[] png) throws Exception {
      capture =
          new Capture(
              png,
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)),
              Instant.now());
    }

    @Override
    public String targetIdentity() {
      return "synthetic-local-window";
    }

    @Override
    public boolean supportsUnattended() {
      return true;
    }

    @Override
    public Duration readinessStability() {
      return Duration.ZERO;
    }

    @Override
    public View observe() {
      return new View("synthetic-answer", "synthetic-q", 1, Page.READY, null, 0, null, false);
    }

    @Override
    public Capture capture(View expected) {
      return capture;
    }

    @Override
    public IdentityCapture captureIdentity(View expected) {
      return new IdentityCapture(
          targetIdentity(),
          expected.answerId(),
          expected.questionId(),
          expected.pageRevision(),
          "synthetic-region",
          capture);
    }

    @Override
    public void verify(View expected) {
      assertEquals(observe(), expected);
    }

    @Override
    public void fillScore(View expected, BigDecimal score, ActionPermission permission) {
      permission.verify();
      fillAttempts.incrementAndGet();
      throw new LocalChangeFailure();
    }

    @Override
    public void submit(View expected, ActionPermission permission) {
      submits.incrementAndGet();
      fail("Must not submit after local diagnostic");
    }

    @Override
    public void next(View expected, ActionPermission permission) {
      fail("Must not navigate after local diagnostic");
    }
  }

  private record Request(String path, JsonNode body) {}

  private static final class LoopbackGateway implements AutoCloseable {
    final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger gradeCalls = new AtomicInteger();

    LoopbackGateway() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    URI origin() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
      try {
        byte[] raw = exchange.getRequestBody().readNBytes(JournalJson.MAX_BYTES + 1);
        if (raw.length > JournalJson.MAX_BYTES || requests.size() >= 64) {
          exchange.sendResponseHeaders(429, -1);
          return;
        }
        JsonNode body =
            raw.length == 0 ? JournalJson.MAPPER.nullNode() : JournalJson.MAPPER.readTree(raw);
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(path, body));
        JsonNode result =
            switch (path) {
              case "/v1/auth/login" -> {
                var reply =
                    JournalJson.MAPPER
                        .createObjectNode()
                        .put("token", "synthetic-local-token")
                        .put("expiresAt", Instant.now().plusSeconds(600).toString());
                reply
                    .putObject("account")
                    .put("id", "synthetic-account")
                    .put("username", "alice")
                    .put("role", "USER")
                    .put("credits", 10);
                yield reply;
              }
              case "/v1/model-policy" ->
                  JournalJson.MAPPER
                      .createObjectNode()
                      .put("revision", 1)
                      .put("profileId", PROFILE)
                      .put("modelId", "synthetic-vision");
              case "/v1/models" ->
                  new PublicModelProfileJsonCodec()
                      .write(
                          List.of(
                              new PublicModelProfile(
                                  PROFILE,
                                  1,
                                  "Synthetic local test",
                                  "synthetic-vision",
                                  Duration.ofSeconds(3),
                                  true,
                                  PublicModelProfile.Role.PRIMARY)));
              case "/v1/journal" -> {
                var reply = JournalJson.MAPPER.createObjectNode();
                if (body.path("operation").asText().equals("hasUnknownSubmission"))
                  reply.put("result", false);
                else reply.putNull("result");
                yield reply;
              }
              case "/v1/grade" -> {
                gradeCalls.incrementAndGet();
                var reply =
                    JournalJson.MAPPER
                        .createObjectNode()
                        .put("requestId", body.path("requestId").asText())
                        .put("source", "MOCK")
                        .put("completedAt", Instant.now().toString())
                        .put("elapsedMs", 1)
                        .putNull("usage")
                        .put("creditsRemaining", 9)
                        .put("chargedCredits", 1)
                        .put("replayed", false);
                var grade =
                    reply
                        .putObject("result")
                        .put("status", "SCORED")
                        .put("readability", "CLEAR")
                        .put("studentText", "Synthetic answer");
                grade
                    .putArray("criteria")
                    .addObject()
                    .put("criterionId", "p")
                    .put("awarded", 1)
                    .put("evidence", "Synthetic answer")
                    .put("reason", "Synthetic match");
                grade.putArray("issues");
                yield reply;
              }
              default -> throw new IOException("Unexpected synthetic endpoint");
            };
        byte[] output = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, output.length);
        exchange.getResponseBody().write(output);
      } finally {
        exchange.close();
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
