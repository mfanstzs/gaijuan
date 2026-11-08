package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GatewayServerCapacityTest {
  @TempDir Path temp;

  private record FundedAccount(UUID id, String token) {}

  @Test
  @Timeout(20)
  void sixDifferentAccountsCanReachTheProviderConcurrently() throws Exception {
    try (var store = new GatewayStore(temp.resolve("six-users"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      var tokens = createFundedUsers(store, admin.id(), 6, 1);
      var entered = new CountDownLatch(6);
      var release = new CompletableFuture<Void>();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                hook.run();
                entered.countDown();
                return release.thenApply(ignored -> GatewayFixtures.response(q));
              });
      var capacity = new GatewayCapacity.Configuration(6, 2, 5_000);
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC(), null, capacity)) {
        server.start();
        var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (String token : tokens)
          responses.add(
              http.sendAsync(
                  post(server, token, GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                  HttpResponse.BodyHandlers.ofString()));
        try {
          assertTrue(entered.await(8, TimeUnit.SECONDS));
        } finally {
          release.complete(null);
        }
        for (var response : responses)
          assertEquals(200, response.get(8, TimeUnit.SECONDS).statusCode());
      }
    }
  }

  @Test
  @Timeout(20)
  void fullWaitingQueueReturns429WithRetryAfterButHealthStillWorks() throws Exception {
    try (var store = new GatewayStore(temp.resolve("queue-full"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      var tokens = createFundedUsers(store, admin.id(), 3, 1);
      var firstEntered = new CountDownLatch(1);
      var release = new CompletableFuture<Void>();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                hook.run();
                firstEntered.countDown();
                return release.thenApply(ignored -> GatewayFixtures.response(q));
              });
      var capacity = new GatewayCapacity.Configuration(1, 1, 2_000);
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC(), null, capacity)) {
        server.start();
        var first =
            http.sendAsync(
                post(
                    server,
                    tokens.get(0),
                    GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        var second =
            http.sendAsync(
                post(
                    server,
                    tokens.get(1),
                    GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.queuedGradeRequests() != 1 && System.nanoTime() < deadline)
          Thread.onSpinWait();
        assertEquals(1, server.queuedGradeRequests());

        var rejected =
            http.send(
                post(
                    server,
                    tokens.get(2),
                    GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(429, rejected.statusCode());
        assertEquals("2", rejected.headers().firstValue("Retry-After").orElseThrow());
        JsonNode error = GatewayJson.MAPPER.readTree(rejected.body());
        assertEquals("RATE_LIMITED", error.path("error").path("code").asText());
        assertEquals(0, error.path("chargedCredits").asInt());

        var health =
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        release.complete(null);
        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
      } finally {
        release.complete(null);
      }
    }
  }

  @Test
  @Timeout(20)
  void sameAccountGetsExplicitConflictAndProviderFailureDoesNotLeakPermit() throws Exception {
    try (var store = new GatewayStore(temp.resolve("account-and-release"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      var tokens = createFundedUsers(store, admin.id(), 2, 2);
      var firstEntered = new CountDownLatch(1);
      var releaseFirst = new CompletableFuture<GradingResponse>();
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                hook.run();
                if (calls.incrementAndGet() == 1) {
                  firstEntered.countDown();
                  return releaseFirst;
                }
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var capacity = new GatewayCapacity.Configuration(1, 0, 100);
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC(), null, capacity)) {
        server.start();
        UUID firstId = UUID.randomUUID();
        var first =
            http.sendAsync(
                post(server, tokens.get(0), GatewayFixtures.publicBody(firstId).toString(), "1"),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        var sameAccount =
            http.send(
                post(
                    server,
                    tokens.get(0),
                    GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, sameAccount.statusCode());
        assertEquals(
            "REQUEST_IN_PROGRESS",
            GatewayJson.MAPPER.readTree(sameAccount.body()).path("error").path("code").asText());
        assertEquals(
            0, GatewayJson.MAPPER.readTree(sameAccount.body()).path("chargedCredits").asInt());

        releaseFirst.completeExceptionally(new IllegalStateException("synthetic provider failure"));
        var failed = first.get(5, TimeUnit.SECONDS);
        assertEquals(409, failed.statusCode());
        var failure = GatewayJson.MAPPER.readTree(failed.body());
        assertEquals("DISPATCH_UNKNOWN", failure.path("error").path("code").asText());
        assertEquals(
            "GATEWAY_UNEXPECTED_ERROR", failure.path("error").path("diagnosticCode").asText());
        assertEquals(1, failure.path("chargedCredits").asInt());
        assertFalse(failed.body().contains("synthetic provider failure"));
        UUID firstUser = store.authenticate(tokens.get(0)).id();
        assertEquals("UNKNOWN", store.request(firstUser, firstId).state());
        assertEquals(1, store.account(firstUser).balance());
        var afterFailure =
            http.send(
                post(
                    server,
                    tokens.get(1),
                    GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, afterFailure.statusCode());
        assertEquals(2, calls.get());
      } finally {
        releaseFirst.cancel(true);
      }
    }
  }

  @Test
  @Timeout(20)
  void slowGradeBodiesCannotBlockHealthAndPerAccountIngressIsBoundedAndReleased() throws Exception {
    try (var store = new GatewayStore(temp.resolve("slow-account"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      var account = createFundedAccounts(store, admin.id(), 1, 2).getFirst();
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var capacity = new GatewayCapacity.Configuration(3, 0, 100);
      try (var server =
          new GatewayServer(
              0, store, models, Clock.systemUTC(), null, capacity, Duration.ofSeconds(2), 4)) {
        server.start();
        try (var first = slowGrade(server, account.token());
            var second = slowGrade(server, account.token())) {
          await(() -> server.activeGradeIngressRequests(account.id()) == 2, 5_000);

          var refused =
              http.send(
                  post(
                      server,
                      account.token(),
                      GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(409, refused.statusCode());
          assertEquals(
              "REQUEST_IN_PROGRESS",
              GatewayJson.MAPPER.readTree(refused.body()).path("error").path("code").asText());
          assertTrue(GatewayJson.MAPPER.readTree(refused.body()).path("chargedCredits").isNull());
          assertEquals(0, calls.get());
          assertEquals(200, health(http, server).statusCode());

          await(() -> server.activeGradeIngressRequests(account.id()) == 0, 5_000);
          var afterDeadlines =
              http.send(
                  post(
                      server,
                      account.token(),
                      GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, afterDeadlines.statusCode());
          assertEquals(1, calls.get());
        }
      }
    }
  }

  @Test
  @Timeout(30)
  void dedicatedGradeIngressQueueRejectsOverflowWhileControlPlaneRemainsAvailable()
      throws Exception {
    try (var store = new GatewayStore(temp.resolve("ingress-overflow"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      var accounts = createFundedAccounts(store, admin.id(), 7, 1);
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                throw new AssertionError("slow request must not reach the provider");
              });
      var capacity = new GatewayCapacity.Configuration(1, 0, 100);
      var sockets = new ArrayList<Socket>();
      try (var server =
          new GatewayServer(
              0, store, models, Clock.systemUTC(), null, capacity, Duration.ofSeconds(5), 1)) {
        server.start();
        try {
          // One model worker plus four grade admission workers are isolated from control traffic.
          for (int index = 0; index < 5; index++)
            sockets.add(slowGrade(server, accounts.get(index).token()));
          for (int index = 0; index < 5; index++) {
            int selected = index;
            await(() -> server.activeGradeIngressRequests(accounts.get(selected).id()) == 1, 5_000);
          }
          sockets.add(slowGrade(server, accounts.get(5).token()));
          await(() -> server.queuedGradeIngressRequests() == 1, 5_000);

          var overflow =
              http.send(
                  post(
                      server,
                      accounts.get(6).token(),
                      GatewayFixtures.publicBody(UUID.randomUUID()).toString()),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(429, overflow.statusCode());
          assertEquals("1", overflow.headers().firstValue("Retry-After").orElseThrow());
          JsonNode error = GatewayJson.MAPPER.readTree(overflow.body());
          assertEquals("RATE_LIMITED", error.path("error").path("code").asText());
          assertTrue(error.path("chargedCredits").isNull());
          assertEquals(200, health(http, server).statusCode());
        } finally {
          for (var socket : sockets) socket.close();
        }
      }
    }
  }

  private static List<String> createFundedUsers(
      GatewayStore store, UUID adminId, int count, long credits) {
    return createFundedAccounts(store, adminId, count, credits).stream()
        .map(FundedAccount::token)
        .toList();
  }

  private static List<FundedAccount> createFundedAccounts(
      GatewayStore store, UUID adminId, int count, long credits) {
    var accounts = new ArrayList<FundedAccount>();
    for (int index = 0; index < count; index++) {
      String username = "capacity_user_" + index;
      var account = store.register(username, GatewayFixtures.password());
      store.adjust(adminId, UUID.randomUUID(), account.id(), credits, "synthetic capacity budget");
      accounts.add(
          new FundedAccount(
              account.id(), store.login(username, GatewayFixtures.password()).token()));
    }
    return List.copyOf(accounts);
  }

  private static Socket slowGrade(GatewayServer server, String token) throws IOException {
    var socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2_000);
    String headers =
        "POST /v1/grade HTTP/1.1\r\n"
            + "Host: 127.0.0.1:"
            + server.port()
            + "\r\nAuthorization: Bearer "
            + token
            + "\r\nContent-Type: application/json\r\nContent-Length: "
            + GatewayModelService.MAX_REQUEST_BYTES
            + "\r\nConnection: close\r\n\r\n{";
    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
    return socket;
  }

  private static HttpResponse<String> health(HttpClient http, GatewayServer server)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void await(BooleanSupplier condition, long timeoutMillis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  private static HttpRequest post(
      GatewayServer server, String token, String body, String... diagnosticsVersions) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/grade"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    for (String version : diagnosticsVersions) request.header("X-DeskGrade-Diagnostics", version);
    return request.build();
  }
}
