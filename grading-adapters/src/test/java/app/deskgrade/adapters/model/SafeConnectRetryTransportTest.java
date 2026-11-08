package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.SecretStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** All network and 20-second waits are injected and manually advanced; no real API or sleep. */
class SafeConnectRetryTransportTest {
  @Test
  void twoUnpublishedConnectTimeoutsThenSuccessUseOneAuthorizationAndTheSameRequestBody()
      throws Exception {
    var scheduler = new ManualScheduler();
    var attempts = new ArrayList<HttpRequest>();
    var trace = new Trace();
    var charges = new AtomicInteger();
    var body = new ArrayList<String>();
    SafeConnectRetryTransport.Sender sender =
        (request, handler) -> {
          attempts.add(request);
          if (attempts.size() < 3) return CompletableFuture.failedFuture(connectTimeout());
          body.add(body(request));
          return CompletableFuture.completedFuture(response(request));
        };
    try (var provider =
        provider(
            sender,
            scheduler,
            trace,
            charges,
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      var request = request();
      var future = provider.grade(request, CancellationToken.none()).toCompletableFuture();
      assertFalse(future.isDone());
      assertEquals(1, attempts.size());
      assertTrue(scheduler.runNext());
      assertEquals(2, attempts.size());
      assertTrue(scheduler.runNext());
      var result = future.join();
      assertEquals(request.requestId(), result.requestId());
      assertEquals(3, attempts.size());
      assertEquals(1, charges.get());
      assertEquals(1, trace.prepared.get());
      assertEquals(List.of(2, 3), trace.authorized);
      assertEquals(List.of(2, 3), trace.scheduled);
      assertEquals(List.of(Duration.ofSeconds(20), Duration.ofSeconds(20)), scheduler.delays);
      assertFalse(scheduler.runNext());
      assertEquals(1, body.size());
      var sent = StrictJson.parse(body.getFirst());
      assertEquals("synthetic-fixture-model", sent.path("model").asText());
      assertTrue(
          sent.path("messages")
              .get(1)
              .path("content")
              .get(1)
              .path("image_url")
              .path("url")
              .asText()
              .startsWith("data:image/png;base64,"));
      for (var attempt : attempts) {
        assertEquals(attempts.getFirst().uri(), attempt.uri());
        assertEquals("POST", attempt.method());
        assertEquals(
            attempts.getFirst().bodyPublisher().orElseThrow().contentLength(),
            attempt.bodyPublisher().orElseThrow().contentLength());
      }
    }
  }

  @Test
  void threeConnectFailuresExhaustTheBoundWithoutASecondDebitOrFourthSend() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    var trace = new Trace();
    var charges = new AtomicInteger();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              return CompletableFuture.failedFuture(new CompletionException(connectTimeout()));
            },
            scheduler,
            trace,
            charges,
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
      assertTrue(scheduler.runNext());
      assertTrue(scheduler.runNext());
      var failure = failure(future);
      assertEquals("MODEL_CONNECT_TIMEOUT", failure.diagnosticCode());
      assertEquals(3, sends.get());
      assertEquals(1, charges.get());
      assertEquals(1, trace.prepared.get());
      assertFalse(failure.fallbackEligible());
      assertFalse(scheduler.runNext());
    }
  }

  @Test
  void bodySubscriptionPreventsRetryEvenWhenTheFinalErrorClaimsConnectTimeout() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              assertFalse(body(request).isBlank());
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      assertEquals(
          "MODEL_CONNECT_TIMEOUT",
          failure(provider.grade(request(), CancellationToken.none()).toCompletableFuture())
              .diagnosticCode());
      assertEquals(1, sends.get());
      assertTrue(scheduler.delays.isEmpty());
    }
  }

  @Test
  void aResponseBoundaryPreventsConnectRetryEvenWithoutBodySubscription() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              handler.apply(
                  new HttpResponse.ResponseInfo() {
                    public int statusCode() {
                      return 503;
                    }

                    public HttpHeaders headers() {
                      return HttpHeaders.of(Map.of(), (name, value) -> true);
                    }

                    public HttpClient.Version version() {
                      return HttpClient.Version.HTTP_2;
                    }
                  });
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      failure(provider.grade(request(), CancellationToken.none()).toCompletableFuture());
      assertEquals(1, sends.get());
      assertTrue(scheduler.delays.isEmpty());
    }
  }

  @Test
  void lateBodyOrResponseDuringBackoffOrAuthorizationPreventsTheRetrySend() throws Exception {
    for (String phase : List.of("backoff", "authorization")) {
      for (String boundary : List.of("body", "response")) {
        var scheduler = new ManualScheduler();
        var sends = new AtomicInteger();
        var charges = new AtomicInteger();
        var trace = new Trace();
        var firstRequest = new AtomicReference<HttpRequest>();
        var firstHandler = new AtomicReference<HttpResponse.BodyHandler<byte[]>>();
        Runnable lateActivity =
            () -> {
              if (boundary.equals("body")) body(firstRequest.get());
              else
                firstHandler
                    .get()
                    .apply(
                        new HttpResponse.ResponseInfo() {
                          public int statusCode() {
                            return 503;
                          }

                          public HttpHeaders headers() {
                            return HttpHeaders.of(Map.of(), (name, value) -> true);
                          }

                          public HttpClient.Version version() {
                            return HttpClient.Version.HTTP_2;
                          }
                        });
            };
        if (phase.equals("authorization")) trace.authorizationHook = lateActivity;
        try (var provider =
            provider(
                (request, handler) -> {
                  sends.incrementAndGet();
                  firstRequest.compareAndSet(null, request);
                  firstHandler.compareAndSet(null, handler);
                  return CompletableFuture.failedFuture(connectTimeout());
                },
                scheduler,
                trace,
                charges,
                profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
          var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
          if (phase.equals("backoff")) lateActivity.run();
          assertTrue(scheduler.runNext());
          assertEquals("MODEL_CONNECT_TIMEOUT", failure(future).diagnosticCode());
          assertEquals(1, sends.get());
          assertEquals(1, charges.get());
          assertEquals(1, trace.prepared.get());
          assertEquals(phase.equals("backoff") ? List.of() : List.of(2), trace.authorized);
          assertFalse(scheduler.runNext());
        }
      }
    }
  }

  @Test
  void requestTimeoutResetDnsAndUntypedFailuresAreNeverRetried() throws Exception {
    for (Throwable original :
        List.of(
            new HttpTimeoutException("synthetic"),
            new SocketException("Connection reset"),
            new java.net.UnknownHostException("synthetic"),
            new IllegalStateException("synthetic", connectTimeout()))) {
      var scheduler = new ManualScheduler();
      var sends = new AtomicInteger();
      try (var provider =
          provider(
              (request, handler) -> {
                sends.incrementAndGet();
                return CompletableFuture.failedFuture(original);
              },
              scheduler,
              new Trace(),
              new AtomicInteger(),
              profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
        failure(provider.grade(request(), CancellationToken.none()).toCompletableFuture());
        assertEquals(1, sends.get());
        assertTrue(scheduler.delays.isEmpty());
      }
    }
  }

  @Test
  void retryAuthorizationFailureStopsBeforeTheSecondSendAndDoesNotExposeItsText() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    var trace = new Trace();
    trace.authorizationFailure = true;
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            trace,
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
      assertTrue(scheduler.runNext());
      var failure = failure(future);
      assertEquals(1, sends.get());
      assertEquals("MODEL_INTERNAL_ERROR", failure.diagnosticCode());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("private-authorization-text"));
      assertFalse(scheduler.runNext());
    }
  }

  @Test
  void cancellingReturnedGradeOrClosingProviderCancelsBackoffWithoutAnotherSend() throws Exception {
    for (boolean closeProvider : List.of(false, true)) {
      var scheduler = new ManualScheduler();
      var sends = new AtomicInteger();
      var trace = new Trace();
      try (var provider =
          provider(
              (request, handler) -> {
                sends.incrementAndGet();
                return CompletableFuture.failedFuture(connectTimeout());
              },
              scheduler,
              trace,
              new AtomicInteger(),
              profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
        var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
        if (closeProvider) provider.close();
        else assertTrue(future.cancel(true));
        assertFalse(scheduler.runNext());
        assertEquals(1, sends.get());
        assertTrue(trace.authorized.isEmpty());
        assertTrue(future.isDone());
      }
    }
  }

  @Test
  void cancellationTokenIsRecheckedBeforeAuthorizationAndReconnect() throws Exception {
    var scheduler = new ManualScheduler();
    var cancelled = new AtomicBoolean();
    var sends = new AtomicInteger();
    var trace = new Trace();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            trace,
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      var future = provider.grade(request(), cancelled::get).toCompletableFuture();
      cancelled.set(true);
      scheduler.runNext();
      assertEquals("MODEL_CANCELLED", failure(future).diagnosticCode());
      assertEquals(1, sends.get());
      assertTrue(trace.authorized.isEmpty());
    }
  }

  @Test
  void cancellationAlsoCancelsAnActiveNetworkFuture() throws Exception {
    var scheduler = new ManualScheduler();
    var network = new CompletableFuture<HttpResponse<byte[]>>();
    try (var provider =
        provider(
            (request, handler) -> network,
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
      assertTrue(future.cancel(true));
      assertTrue(network.isCancelled());
      assertFalse(scheduler.runNext());
    }
  }

  @Test
  void cancellationDuringRetryAuthorizationPreventsSendWithoutWaitingForAuthorization()
      throws Exception {
    for (String mode : List.of("future", "close", "token")) {
      var scheduler = new ManualScheduler();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var cancelled = new AtomicBoolean();
      var sends = new AtomicInteger();
      var trace = new Trace();
      trace.authorizationHook =
          () -> {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test authorization wait expired");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Test authorization interrupted");
            }
          };
      try (var provider =
              provider(
                  (request, handler) -> {
                    sends.incrementAndGet();
                    return CompletableFuture.failedFuture(connectTimeout());
                  },
                  scheduler,
                  trace,
                  new AtomicInteger(),
                  profile(Duration.ofSeconds(10), Duration.ofSeconds(180)));
          var executor = Executors.newFixedThreadPool(2)) {
        var future = provider.grade(request(), cancelled::get).toCompletableFuture();
        var retry =
            executor.submit(
                () -> {
                  return scheduler.runNext();
                });
        try {
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          var cancellation =
              executor.submit(
                  () -> {
                    switch (mode) {
                      case "future" -> assertTrue(future.cancel(true));
                      case "close" -> provider.close();
                      case "token" -> cancelled.set(true);
                      default -> fail("Unexpected test mode");
                    }
                  });
          cancellation.get(2, TimeUnit.SECONDS);
        } finally {
          release.countDown();
        }
        assertTrue(retry.get(2, TimeUnit.SECONDS));
        if (mode.equals("future")) assertTrue(future.isCancelled());
        else assertEquals("MODEL_CANCELLED", failure(future).diagnosticCode());
        assertEquals(1, sends.get());
        assertFalse(scheduler.runNext());
      }
    }
  }

  @Test
  void totalBudgetIsCappedAndEachAttemptUsesOnlyTheRemainingRequestTime() throws Exception {
    assertEquals(
        Duration.ofSeconds(240),
        OpenAICompatibleVisionProvider.gradeTransportBudget(
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180))));
    assertEquals(
        Duration.ofMinutes(10),
        OpenAICompatibleVisionProvider.gradeTransportBudget(
            profile(Duration.ofSeconds(120), Duration.ofSeconds(600))));
    assertEquals(
        Duration.ofSeconds(46),
        OpenAICompatibleVisionProvider.gradeTransportBudget(
            profile(Duration.ofSeconds(10), Duration.ofSeconds(2))));
    var scheduler = new ManualScheduler();
    var requests = new ArrayList<HttpRequest>();
    try (var provider =
        provider(
            (request, handler) -> {
              requests.add(request);
              if (requests.size() == 1) {
                scheduler.nanos.addAndGet(Duration.ofSeconds(120).toNanos());
                return CompletableFuture.failedFuture(connectTimeout());
              }
              body(request);
              return CompletableFuture.completedFuture(response(request));
            },
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(120), Duration.ofSeconds(600)))) {
      var future = provider.grade(request(), CancellationToken.none()).toCompletableFuture();
      assertTrue(scheduler.runNext());
      future.join();
      assertEquals(Duration.ofSeconds(600), requests.getFirst().timeout().orElseThrow());
      assertEquals(Duration.ofSeconds(460), requests.getLast().timeout().orElseThrow());
    }
  }

  @Test
  void exhaustedLogicalBudgetStopsBeforeSchedulingOrSendingAnotherAttempt() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              scheduler.nanos.addAndGet(Duration.ofMinutes(10).toNanos());
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      assertEquals(
          "MODEL_CONNECT_TIMEOUT",
          failure(provider.grade(request(), CancellationToken.none()).toCompletableFuture())
              .diagnosticCode());
      assertEquals(1, sends.get());
      assertTrue(scheduler.delays.isEmpty());
    }
  }

  @Test
  void rubricGenerationAndCapabilityProbeDoNotInheritGradeRetries() throws Exception {
    var scheduler = new ManualScheduler();
    var sends = new AtomicInteger();
    try (var provider =
        provider(
            (request, handler) -> {
              sends.incrementAndGet();
              return CompletableFuture.failedFuture(connectTimeout());
            },
            scheduler,
            new Trace(),
            new AtomicInteger(),
            profile(Duration.ofSeconds(10), Duration.ofSeconds(180)))) {
      failure(
          provider
              .generateRubric(
                  "synthetic", "synthetic reference", BigDecimal.ONE, CancellationToken.none())
              .toCompletableFuture());
      failure(provider.capabilityTest(CancellationToken.none()).toCompletableFuture());
      assertEquals(2, sends.get());
      assertTrue(scheduler.delays.isEmpty());
    }
  }

  private static OpenAICompatibleVisionProvider provider(
      SafeConnectRetryTransport.Sender sender,
      ManualScheduler scheduler,
      ModelCallTrace trace,
      AtomicInteger charges,
      ProviderProfile profile) {
    return new OpenAICompatibleVisionProvider(
        profile,
        new Rubric(GradeResultJsonCodecTest.QUESTION, "synthetic", BigDecimal.ONE, false, false),
        new SecretStore() {
          public void put(String id, char[] value) {
            throw new UnsupportedOperationException();
          }

          public Optional<char[]> read(String id) {
            return Optional.of("synthetic-fixture-key".toCharArray());
          }

          public void delete(String id) {
            throw new UnsupportedOperationException();
          }
        },
        Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
        charges::incrementAndGet,
        trace,
        new OpenAICompatibleVisionProvider.TransportControl(
            sender, scheduler, scheduler.nanos::get));
  }

  private static ProviderProfile profile(Duration connect, Duration request) {
    var profile =
        new ProviderProfile(
            "synthetic",
            1,
            "synthetic",
            URI.create("http://127.0.0.1:1/chat/completions"),
            "synthetic-fixture-model",
            "synthetic-ref",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            Map.of(),
            Set.of(),
            connect,
            request,
            null,
            null);
    return profile.withVerification(
        new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, true, true));
  }

  private static GradingRequest request() throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output));
    return new GradingRequest(
        UUID.randomUUID(), GradeResultJsonCodecTest.QUESTION, output.toByteArray());
  }

  private static HttpConnectTimeoutException connectTimeout() {
    var timeout = new HttpConnectTimeoutException("synthetic connect timeout");
    timeout.initCause(new java.net.ConnectException("synthetic low-level cause"));
    return timeout;
  }

  private static ProviderException failure(CompletableFuture<?> future) {
    var failure = assertThrows(CompletionException.class, future::join);
    return assertInstanceOf(ProviderException.class, failure.getCause());
  }

  private static String body(HttpRequest request) {
    var bytes = new ByteArrayOutputStream();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
              }

              public void onError(Throwable failure) {
                throw new AssertionError(failure);
              }

              public void onComplete() {}
            });
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static HttpResponse<byte[]> response(HttpRequest request) {
    var wire = StrictJson.MAPPER.createObjectNode();
    var choice = wire.putArray("choices").addObject();
    choice.put("finish_reason", "stop");
    choice.putObject("message").put("content", GradeResultJsonCodecTest.VALID);
    byte[] bytes = wire.toString().getBytes(StandardCharsets.UTF_8);
    return new HttpResponse<>() {
      public int statusCode() {
        return 200;
      }

      public HttpRequest request() {
        return request;
      }

      public Optional<HttpResponse<byte[]>> previousResponse() {
        return Optional.empty();
      }

      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
      }

      public byte[] body() {
        return bytes;
      }

      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      public URI uri() {
        return request.uri();
      }

      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }
    };
  }

  private static final class Trace implements ModelCallTrace {
    final AtomicInteger prepared = new AtomicInteger();
    final List<Integer> authorized = new ArrayList<>();
    final List<Integer> scheduled = new ArrayList<>();
    boolean authorizationFailure;
    Runnable authorizationHook = () -> {};

    public void requestPrepared(String requestJson) {
      prepared.incrementAndGet();
    }

    public void authorizeConnectRetry(int attempt) {
      authorized.add(attempt);
      authorizationHook.run();
      if (authorizationFailure) throw new IllegalStateException("private-authorization-text");
    }

    public void connectRetryScheduled(int attempt, Duration delay) {
      scheduled.add(attempt);
    }
  }

  private static final class ManualScheduler implements SafeConnectRetryTransport.Scheduler {
    final AtomicLong nanos = new AtomicLong();
    final ArrayDeque<Task> tasks = new ArrayDeque<>();
    final List<Duration> delays = new ArrayList<>();

    public SafeConnectRetryTransport.ScheduledRetry schedule(Runnable operation, Duration delay) {
      var task = new Task(operation, delay);
      tasks.add(task);
      delays.add(delay);
      return () -> task.cancelled = true;
    }

    boolean runNext() {
      while (!tasks.isEmpty()) {
        Task task = tasks.remove();
        if (task.cancelled) continue;
        nanos.addAndGet(task.delay.toNanos());
        task.operation.run();
        return true;
      }
      return false;
    }

    private static final class Task {
      final Runnable operation;
      final Duration delay;
      volatile boolean cancelled;

      Task(Runnable operation, Duration delay) {
        this.operation = operation;
        this.delay = delay;
      }
    }
  }
}
