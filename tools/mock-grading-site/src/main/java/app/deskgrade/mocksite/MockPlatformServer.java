package app.deskgrade.mocksite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only development HTTP server, deliberately separate from production adapters. */
public final class MockPlatformServer implements AutoCloseable {
  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
          + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
          + "frame-ancestors 'none'; form-action 'none'";
  private static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), hid=(), "
          + "bluetooth=(), clipboard-read=(), clipboard-write=(), display-capture=(), "
          + "fullscreen=()";

  @FunctionalInterface
  public interface Delay {
    void await(int milliseconds) throws InterruptedException;
  }

  private record SubmitBody(String answerId, String score) {}

  private final HttpServer server;
  private final MockPlatform platform;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final boolean testLedgerEnabled;
  private final AnswerSet answerSet;
  private final Delay delay;

  public MockPlatformServer(int port, int count, SubmissionMode mode, boolean testLedgerEnabled)
      throws IOException {
    this(
        port,
        count,
        mode,
        testLedgerEnabled,
        AnswerSet.SYNTHETIC,
        Clock.systemUTC(),
        Thread::sleep);
  }

  public MockPlatformServer(
      int port, int count, SubmissionMode mode, boolean testLedgerEnabled, AnswerSet answerSet)
      throws IOException {
    this(port, count, mode, testLedgerEnabled, answerSet, Clock.systemUTC(), Thread::sleep);
  }

  public MockPlatformServer(
      int port, int count, SubmissionMode mode, boolean testLedgerEnabled, Clock clock, Delay delay)
      throws IOException {
    this(port, count, mode, testLedgerEnabled, AnswerSet.SYNTHETIC, clock, delay);
  }

  public MockPlatformServer(
      int port,
      int count,
      SubmissionMode mode,
      boolean testLedgerEnabled,
      AnswerSet answerSet,
      Clock clock,
      Delay delay)
      throws IOException {
    this.answerSet = java.util.Objects.requireNonNull(answerSet);
    this.platform = new MockPlatform(answerSet.count(count), mode, clock);
    this.testLedgerEnabled = testLedgerEnabled;
    this.delay = java.util.Objects.requireNonNull(delay);
    this.server =
        HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public MockPlatform platform() {
    return platform;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String host = exchange.getRequestHeaders().getFirst("Host");
      String origin = exchange.getRequestHeaders().getFirst("Origin");
      if (!baseUri().getAuthority().equals(host)
          || (origin != null && !baseUri().toString().equals(origin))) {
        json(exchange, 403, Map.of("error", "LOOPBACK_ORIGIN_REQUIRED"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if ("GET".equals(method) && "/".equals(path)) {
        try (var resource = getClass().getResourceAsStream("/site.html")) {
          if (resource == null) throw new IOException("Missing page resource");
          bytes(exchange, 200, "text/html; charset=utf-8", resource.readAllBytes());
        }
      } else if ("GET".equals(method) && "/api/state".equals(path)) {
        delay.await(platform.faults().delayMillis());
        json(exchange, 200, platform.state());
      } else if ("GET".equals(method) && "/answer/completed.png".equals(path)) {
        bytes(exchange, 200, "image/png", SyntheticAnswers.completionPng());
      } else if ("GET".equals(method) && path.matches("/answer/[0-9]{1,5}\\.png")) {
        int sequence = Integer.parseInt(path.substring(8, path.length() - 4));
        if (sequence < 1 || sequence > platform.count())
          json(exchange, 404, Map.of("error", "NOT_FOUND"));
        else
          bytes(
              exchange,
              200,
              "image/png",
              answerSet.png(sequence, "blank=true".equals(exchange.getRequestURI().getQuery())));
      } else if ("POST".equals(method) && "/api/submit".equals(path)) {
        SubmitBody body = body(exchange, SubmitBody.class);
        delay.await(platform.faults().delayMillis());
        MockPlatform.Reply reply = platform.submit(body.answerId(), body.score());
        if (reply.dropReceipt()) exchange.close();
        else json(exchange, reply.status(), reply);
      } else if ("POST".equals(method) && "/api/next".equals(path)) {
        delay.await(platform.faults().delayMillis());
        MockPlatform.Reply reply = platform.next();
        json(exchange, reply.status(), reply);
      } else if ("POST".equals(method) && "/api/faults".equals(path)) {
        platform.configure(body(exchange, Faults.class));
        json(exchange, 200, platform.state());
      } else if ("POST".equals(method) && "/api/force-advance".equals(path)) {
        json(exchange, 200, platform.forceAdvance());
      } else if ("POST".equals(method) && "/api/restart".equals(path)) {
        json(exchange, 200, platform.restart());
      } else if ("GET".equals(method) && "/api/scores".equals(path)) {
        json(exchange, 200, platform.acceptedScores());
      } else if ("GET".equals(method) && "/test/ledger".equals(path) && testLedgerEnabled) {
        json(exchange, 200, platform.ledger());
      } else json(exchange, 404, Map.of("error", "NOT_FOUND"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exchange.close();
    } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
      json(exchange, 400, Map.of("error", "INVALID_REQUEST"));
    } catch (IOException e) {
      exchange.close();
    } finally {
      exchange.close();
    }
  }

  private <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
    byte[] bytes = exchange.getRequestBody().readNBytes(16385);
    if (bytes.length > 16384) throw new IllegalArgumentException("Request too large");
    T value = mapper.readValue(bytes, type);
    if (value == null) throw new IllegalArgumentException("Missing body");
    return value;
  }

  private void json(HttpExchange exchange, int status, Object value) throws IOException {
    bytes(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsBytes(value));
  }

  private void bytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
    exchange.getResponseHeaders().set("Permissions-Policy", PERMISSIONS_POLICY);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    executor.close();
  }
}
