package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GatewayBillingBoundaryTest {
  @TempDir Path temp;

  @Test
  void gradeAuthenticationStorageFailureDoesNotClaimExistingRequestWasFree() throws Exception {
    Path database = temp.resolve("unavailable");
    UUID userId, id = UUID.randomUUID();
    try (var store = new GatewayStore(database, Clock.systemUTC());
        var http = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                store,
                new GatewayModelService(
                    store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      userId = user.id();
      store.adjust(admin.id(), UUID.randomUUID(), userId, 1, "synthetic request budget");
      String token = store.login("alice", GatewayFixtures.password()).token();
      store.prepare(userId, id, "a".repeat(64), "synthetic-model");
      store.beforeDispatch(userId, id, token);
      server.start();
      store.close();
      var response =
          http.send(
              post(server, token, GatewayFixtures.publicBody(id).toString()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      var json = GatewayJson.MAPPER.readTree(response.body());
      assertEquals("STORE_UNAVAILABLE", json.path("error").path("code").asText());
      assertTrue(json.path("chargedCredits").isNull());
    }
    try (var reopened = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals(0, reopened.account(userId).balance());
      assertEquals("UNKNOWN", reopened.request(userId, id).state());
      assertTrue(reopened.request(userId, id).charged());
    }
  }

  @Test
  void httpConcurrentReplayAndConflictReachTheLedgerBeforePerUserAdmission() throws Exception {
    try (var store = new GatewayStore(temp.resolve("concurrent-http"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic request budget");
      String token = store.login("alice", GatewayFixtures.password()).token();
      var entered = new CountDownLatch(1);
      var release = new CompletableFuture<GradingResponse>();
      var captured =
          new java.util.concurrent.atomic.AtomicReference<
              app.deskgrade.core.domain.GradingRequest>();
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                captured.set(q);
                entered.countDown();
                return release;
              });
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        var body = GatewayFixtures.publicBody(UUID.randomUUID());
        var first =
            http.sendAsync(
                post(server, token, body.toString()), HttpResponse.BodyHandlers.ofString());
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var replay =
              http.send(post(server, token, body.toString()), HttpResponse.BodyHandlers.ofString());
          assertEquals(409, replay.statusCode());
          var replayJson = GatewayJson.MAPPER.readTree(replay.body());
          assertEquals("REQUEST_IN_PROGRESS", replayJson.path("error").path("code").asText());
          assertEquals(1, replayJson.path("chargedCredits").asInt());
          assertTrue(replayJson.path("replayed").asBoolean());
          var changed = body.deepCopy();
          changed.withObject("rubric").put("rawRubric", "different synthetic fixed rule");
          var conflict =
              http.send(
                  post(server, token, changed.toString()), HttpResponse.BodyHandlers.ofString());
          assertEquals(409, conflict.statusCode());
          assertEquals(
              "IDEMPOTENCY_CONFLICT",
              GatewayJson.MAPPER.readTree(conflict.body()).path("error").path("code").asText());
        } finally {
          if (captured.get() != null) release.complete(GatewayFixtures.response(captured.get()));
          else release.cancel(true);
        }
        assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(1, calls.get());
        assertEquals(1, store.account(user.id()).balance());
      }
    }
  }

  @Test
  @Timeout(15)
  void supplierDeadlineCancelsOriginalFutureAndKeepsOneUnknownDebit() {
    try (var store = new GatewayStore(temp.resolve("timeout"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 1, "synthetic request budget");
      var original = GatewayFixtures.profile();
      var shortProfile =
          new ProviderProfile(
              original.id(),
              original.revision(),
              original.displayName(),
              original.endpoint(),
              original.modelId(),
              original.credentialRef(),
              original.structuredMode(),
              original.parameters(),
              original.allowedParameters(),
              original.connectTimeout(),
              Duration.ofMillis(100),
              null,
              null);
      shortProfile =
          shortProfile.withVerification(
              new ProviderProfile.Verification(
                  shortProfile.configDigest(), Instant.EPOCH, true, true));
      GatewayFixtures.activate(store, admin.id(), shortProfile);
      var never = new CompletableFuture<GradingResponse>();
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(shortProfile),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return never;
              });
      var body = GatewayFixtures.body(UUID.randomUUID());
      var reply = models.grade(user.id(), body);
      assertEquals(409, reply.status());
      assertEquals("DISPATCH_UNKNOWN", reply.body().path("error").path("code").asText());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      assertTrue(never.isCancelled());
      assertEquals(0, store.account(user.id()).balance());
      assertEquals(409, models.grade(user.id(), body).status());
      assertEquals(1, calls.get());
    }
  }

  private static HttpRequest post(GatewayServer server, String token, String body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/grade"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
}
