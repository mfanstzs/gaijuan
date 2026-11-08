package app.deskgrade.mocksite;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockPlatformServerTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpClient client;
  private MockPlatformServer server;
  private AtomicInteger delayed;

  @BeforeEach
  void start() throws Exception {
    delayed = new AtomicInteger();
    server =
        new MockPlatformServer(
            0, 20, SubmissionMode.SUBMIT_AND_ADVANCE, true, Clock.systemUTC(), delayed::addAndGet);
    server.start();
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  @Test
  void loopbackPageAndTwentyDistinctSyntheticPngsAreServed() throws Exception {
    assertEquals("127.0.0.1", server.baseUri().getHost());
    var page = get("/");
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("DGM1"));
    assertTrue(page.body().contains("连续 5 次观察失败"));
    assertTrue(page.body().contains("type=\"number\""));
    assertTrue(page.body().contains("left:16px;top:144px;width:720px;height:220px"));
    assertTrue(page.body().contains("left:16px;top:402px;width:160px;height:44px"));
    assertTrue(page.body().contains("left:192px;top:402px;width:144px;height:44px"));
    assertTrue(page.body().contains("/answer/completed.png"));
    var state = json(get("/api/state"));
    assertEquals(20, state.get("totalCount").intValue());
    var hashes = new HashSet<String>();
    for (int i = 1; i <= 20; i++) {
      var response =
          client.send(
              HttpRequest.newBuilder(server.baseUri().resolve("/answer/" + i + ".png"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      var image = ImageIO.read(new ByteArrayInputStream(response.body()));
      assertEquals(720, image.getWidth());
      assertEquals(220, image.getHeight());
      hashes.add(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())));
    }
    assertEquals(20, hashes.size());
    var completed =
        client.send(
            HttpRequest.newBuilder(server.baseUri().resolve("/answer/completed.png")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, completed.statusCode());
    var completedImage = ImageIO.read(new ByteArrayInputStream(completed.body()));
    assertEquals(720, completedImage.getWidth());
    assertEquals(220, completedImage.getHeight());
    assertFalse(
        hashes.contains(
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(completed.body()))));
    assertEquals(404, get("/answer/21.png").statusCode());
  }

  @Test
  void everyHttpResponseCarriesRestrictiveBrowserSecurityHeaders() throws Exception {
    assertSecurityHeaders(get("/"));
    assertSecurityHeaders(get("/api/state"));
    assertSecurityHeaders(get("/answer/1.png"));
    assertSecurityHeaders(get("/missing"));
  }

  @Test
  void handwrittenSixServesProvidedImagesAndSubmitAdvancesThroughEachOne() throws Exception {
    try (var photoServer =
        new MockPlatformServer(
            0, 6, SubmissionMode.SUBMIT_AND_ADVANCE, true, AnswerSet.HANDWRITTEN_SIX)) {
      photoServer.start();
      var state = json(get(photoServer, "/api/state"));
      assertEquals(6, state.get("totalCount").intValue());
      var hashes = new HashSet<String>();
      for (int sequence = 1; sequence <= 6; sequence++) {
        var response =
            client.send(
                HttpRequest.newBuilder(
                        photoServer.baseUri().resolve("/answer/" + sequence + ".png"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        var image = ImageIO.read(new ByteArrayInputStream(response.body()));
        assertEquals(828, image.getWidth());
        assertEquals(209, image.getHeight());
        hashes.add(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())));
        var reply =
            json(
                post(
                    photoServer,
                    "/api/submit",
                    "{\"answerId\":\"SYN-%04d\",\"score\":\"%d\"}"
                        .formatted(sequence, sequence % 5)));
        assertEquals(sequence, reply.get("state").get("confirmedCount").intValue());
      }
      assertEquals(6, hashes.size());
      assertEquals(3, json(get(photoServer, "/api/state")).get("pageState").intValue());
      var completed =
          client.send(
              HttpRequest.newBuilder(photoServer.baseUri().resolve("/answer/completed.png"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, completed.statusCode());
      assertFalse(
          hashes.contains(
              HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(completed.body()))));
      assertEquals(6, json(get(photoServer, "/test/ledger")).size());
      assertEquals(404, get(photoServer, "/answer/7.png").statusCode());
    }
  }

  @Test
  void independentHttpLedgerExposesDuplicateAttemptsAndScore() throws Exception {
    assertEquals(
        200, post("/api/submit", "{\"answerId\":\"SYN-0001\",\"score\":\"3\"}").statusCode());
    assertEquals(
        409, post("/api/submit", "{\"answerId\":\"SYN-0001\",\"score\":\"4\"}").statusCode());
    var ledger = json(get("/test/ledger"));
    assertEquals(2, ledger.size());
    assertEquals("3", ledger.get(0).get("score").textValue());
    assertTrue(ledger.get(0).get("accepted").booleanValue());
    assertTrue(ledger.get(1).get("duplicate").booleanValue());
    assertEquals(2, ledger.get(1).get("receivedCountForAnswer").intValue());
  }

  @Test
  void visibleScoresContainAcceptedGradesAndRestartStartsFresh() throws Exception {
    assertEquals(
        200, post("/api/submit", "{\"answerId\":\"SYN-0001\",\"score\":\"3\"}").statusCode());
    assertEquals(
        409, post("/api/submit", "{\"answerId\":\"SYN-0001\",\"score\":\"4\"}").statusCode());

    var scores = json(get("/api/scores"));
    assertEquals(1, scores.size());
    assertEquals("SYN-0001", scores.get(0).get("answerId").textValue());
    assertEquals("3", scores.get(0).get("score").textValue());

    var restarted = json(post("/api/restart", "{}"));
    assertEquals(1, restarted.get("answerSequence").intValue());
    assertEquals(0, restarted.get("confirmedCount").intValue());
    assertEquals(0, json(get("/api/scores")).size());
  }

  @Test
  void injectedDelayBlankAndIdenticalImagesAreRepeatable() throws Exception {
    post(
        "/api/faults",
        mapper.writeValueAsString(
            new Faults(1234, true, true, false, false, false, false, false, false)));
    var first = json(get("/api/state"));
    assertEquals(1234, delayed.get());
    assertEquals("/answer/1.png?blank=true", first.get("imageUrl").textValue());
    post("/api/force-advance", "{}");
    var second = json(get("/api/state"));
    assertEquals(2, second.get("answerSequence").intValue());
    assertEquals(first.get("imageUrl"), second.get("imageUrl"));
    var image = ImageIO.read(new ByteArrayInputStream(SyntheticAnswers.png(1, true)));
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++)
        assertEquals(0xffffff, image.getRGB(x, y) & 0xffffff);
    }
  }

  @Test
  void lostHttpReceiptDoesNotLoseTheIndependentCommit() throws Exception {
    post(
        "/api/faults",
        mapper.writeValueAsString(
            new Faults(0, false, false, false, true, false, false, false, false)));
    assertThrows(
        IOException.class,
        () -> post("/api/submit", "{\"answerId\":\"SYN-0001\",\"score\":\"4\"}"));
    assertTrue(json(get("/test/ledger")).get(0).get("accepted").booleanValue());
    assertEquals(0, json(get("/api/state")).get("ackAnswerSequence").intValue());
  }

  @Test
  void malformedRequestsAndCrossOriginMutationAreRejected() throws Exception {
    assertEquals(400, post("/api/submit", "not-json").statusCode());
    var request =
        HttpRequest.newBuilder(server.baseUri().resolve("/api/submit"))
            .header("Origin", "https://untrusted.invalid")
            .POST(
                HttpRequest.BodyPublishers.ofString("{\"answerId\":\"SYN-0001\",\"score\":\"4\"}"))
            .build();
    assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(0, json(get("/test/ledger")).size());
  }

  @Test
  void ledgerRouteIsUnavailableWithoutExplicitTestOption() throws Exception {
    try (var privateServer =
        new MockPlatformServer(0, 20, SubmissionMode.SUBMIT_THEN_NEXT, false)) {
      privateServer.start();
      var response =
          client.send(
              HttpRequest.newBuilder(privateServer.baseUri().resolve("/test/ledger")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, response.statusCode());
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    return get(server, path);
  }

  private HttpResponse<String> get(MockPlatformServer target, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(target.baseUri().resolve(path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return post(server, path, body);
  }

  private HttpResponse<String> post(MockPlatformServer target, String path, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(target.baseUri().resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    return mapper.readTree(response.body());
  }

  private static void assertSecurityHeaders(HttpResponse<?> response) {
    String csp =
        response
            .headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow(() -> new AssertionError("Missing Content-Security-Policy"));
    assertTrue(csp.contains("default-src 'none'"));
    assertTrue(csp.contains("script-src 'unsafe-inline'"));
    assertTrue(csp.contains("style-src 'unsafe-inline'"));
    assertTrue(csp.contains("img-src 'self' data:"));
    assertTrue(csp.contains("connect-src 'self'"));
    assertTrue(csp.contains("object-src 'none'"));
    assertTrue(csp.contains("base-uri 'none'"));
    assertTrue(csp.contains("frame-ancestors 'none'"));
    assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
    assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
    assertEquals(
        "same-origin", response.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
    String permissions = response.headers().firstValue("Permissions-Policy").orElseThrow();
    assertTrue(permissions.contains("camera=()"));
    assertTrue(permissions.contains("microphone=()"));
    assertTrue(permissions.contains("geolocation=()"));
    assertTrue(permissions.contains("payment=()"));
    assertTrue(permissions.contains("clipboard-read=()"));
    assertTrue(permissions.contains("clipboard-write=()"));
  }
}
