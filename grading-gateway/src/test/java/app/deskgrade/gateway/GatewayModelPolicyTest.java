package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class GatewayModelPolicyTest {
  @TempDir Path temp;

  @Test
  void policyIsAdminOnlyVerifiedAvailableExplicitAuditedAndSurvivesRestart() {
    Path database = temp.resolve("policy");
    UUID admin, firstChange = UUID.randomUUID();
    var first = GatewayFixtures.profile();
    var candidate = GatewayFixtures.profile("unverified", "synthetic-candidate", false);
    var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      var user = store.register("alice", GatewayFixtures.password()).id();
      var service =
          new GatewayModelService(
              store,
              List.of(first, candidate, second),
              (p, r, q, h) -> {
                throw new AssertionError("Policy operations cannot invoke models");
              },
              p -> !p.id().equals(second.id()));
      assertEquals(
          new GatewayModelService.ModelPolicy(0, null, null, null, null), service.policy());
      assertTrue(service.publicProfiles().isEmpty());
      assertEquals(
          GatewayException.Code.FORBIDDEN,
          assertThrows(GatewayException.class, () -> service.adminModels(user)).code());
      assertEquals(
          GatewayException.Code.FORBIDDEN,
          assertThrows(
                  GatewayException.class,
                  () ->
                      service.selectPolicy(user, firstChange, first.id(), 0, "synthetic selection"))
              .code());
      assertEquals(3, service.adminModels(admin).path("models").size());
      assertEquals(
          GatewayException.Code.MODEL_UNAVAILABLE,
          assertThrows(
                  GatewayException.class,
                  () ->
                      service.selectPolicy(
                          admin, UUID.randomUUID(), candidate.id(), 0, "unverified candidate"))
              .code());
      assertEquals(
          GatewayException.Code.MODEL_UNAVAILABLE,
          assertThrows(
                  GatewayException.class,
                  () ->
                      service.selectPolicy(
                          admin, UUID.randomUUID(), second.id(), 0, "unavailable candidate"))
              .code());
      assertTrue(store.modelPolicyAudit(admin).isEmpty());
      var selected = service.selectPolicy(admin, firstChange, first.id(), 0, "synthetic selection");
      assertEquals(1, selected.policy().revision());
      assertFalse(selected.replayed());
      assertEquals(
          List.of("service_primary"), service.publicProfiles().stream().map(p -> p.id()).toList());
      var replay = service.selectPolicy(admin, firstChange, first.id(), 0, "synthetic selection");
      assertTrue(replay.replayed());
      assertEquals(selected.policy(), replay.policy());
      assertEquals(selected.auditEntryId(), replay.auditEntryId());
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(
                  GatewayException.class,
                  () -> service.selectPolicy(admin, firstChange, first.id(), 0, "changed reason"))
              .code());
      assertEquals(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          assertThrows(
                  GatewayException.class,
                  () ->
                      service.selectPolicy(
                          admin, UUID.randomUUID(), first.id(), 0, "stale selection"))
              .code());
      var audit = store.modelPolicyAudit(admin);
      assertEquals(1, audit.size());
      assertEquals(admin, audit.getFirst().adminId());
      assertEquals("synthetic selection", audit.getFirst().reason());
      assertEquals(0, audit.getFirst().previousRevision());
      assertNull(audit.getFirst().previousProfileId());
    }
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      var service =
          new GatewayModelService(
              store,
              List.of(first, second),
              (p, r, q, h) -> {
                throw new AssertionError();
              });
      assertEquals(
          new GatewayModelService.ModelPolicy(
              1, first.id(), first.modelId(), second.id(), second.modelId()),
          service.policy());
      assertEquals(
          List.of("service_primary", "service_fallback"),
          service.publicProfiles().stream().map(p -> p.id()).toList());
      service.selectPolicy(admin, UUID.randomUUID(), second.id(), 1, "synthetic replacement");
      var replay = service.selectPolicy(admin, firstChange, first.id(), 0, "synthetic selection");
      assertTrue(replay.replayed());
      assertEquals(1, replay.policy().revision());
      assertEquals(2, store.modelPolicy().revision());
      assertEquals(
          List.of("service_primary", "service_fallback"),
          service.publicProfiles().stream().map(p -> p.id()).toList());
      assertEquals(2, store.modelPolicyAudit(admin).size());
    }
  }

  @Test
  void fallbackIdentityIsFrozenForTheGatewayProcessWhenAvailabilityDrifts() {
    try (var store = new GatewayStore(temp.resolve("frozen-fallback"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var primary = GatewayFixtures.profile("primary", "model-primary", true);
      var firstFallback = GatewayFixtures.profile("fallback-a", "model-a", true);
      var laterFallback = GatewayFixtures.profile("fallback-b", "model-b", true);
      var available =
          new AtomicReference<>(Set.of(primary.id(), firstFallback.id(), laterFallback.id()));
      var service =
          new GatewayModelService(
              store,
              List.of(primary, firstFallback, laterFallback),
              (p, r, q, h) -> {
                throw new AssertionError("Policy reads cannot invoke models");
              },
              profile -> available.get().contains(profile.id()));

      service.selectPolicy(admin.id(), UUID.randomUUID(), primary.id(), 0, "select primary");
      assertEquals(firstFallback.id(), service.policy().fallbackProfileId());

      available.set(Set.of(primary.id(), laterFallback.id()));
      assertEquals(firstFallback.id(), service.policy().fallbackProfileId());
      assertEquals(
          List.of("service_primary"), service.publicProfiles().stream().map(p -> p.id()).toList());
      assertTrue(service.publicPolicyJson().path("fallbackProfileId").isNull());
      assertTrue(service.publicPolicyJson().path("fallbackModelId").isNull());
      var models = service.adminModels(admin.id()).path("models");
      assertTrue(models.findValuesAsText("profileId").contains(laterFallback.id()));
      for (var model : models) {
        if (model.path("profileId").asText().equals(firstFallback.id())) {
          assertTrue(model.path("fallback").asBoolean());
          assertFalse(model.path("available").asBoolean());
        }
        if (model.path("profileId").asText().equals(laterFallback.id())) {
          assertFalse(model.path("fallback").asBoolean());
          assertTrue(model.path("available").asBoolean());
        }
      }
    }
  }

  @Test
  void forgedProfileStaleRevisionMissingRevisionAndRevisionReuseNeverDispatch() {
    try (var store = new GatewayStore(temp.resolve("forged"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic allocation");
      var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile(), second),
              (p, r, q, h) -> {
                calls.incrementAndGet();
                throw new AssertionError();
              });
      service.selectPolicy(
          admin.id(), UUID.randomUUID(), GatewayFixtures.profile().id(), 0, "synthetic first");
      var stolen = GatewayFixtures.body(UUID.randomUUID()).put("profileId", second.id());
      assertEquals(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          assertThrows(GatewayException.class, () -> service.grade(user.id(), stolen)).code());
      var legacy = GatewayFixtures.body(UUID.randomUUID());
      legacy.remove("policyRevision");
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(GatewayException.class, () -> service.grade(user.id(), legacy)).code());
      service.selectPolicy(admin.id(), UUID.randomUUID(), second.id(), 1, "synthetic second");
      service.selectPolicy(
          admin.id(), UUID.randomUUID(), GatewayFixtures.profile().id(), 2, "back to first");
      var stale = GatewayFixtures.body(UUID.randomUUID());
      assertEquals(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          assertThrows(GatewayException.class, () -> service.grade(user.id(), stale)).code());
      assertEquals(0, calls.get());
      assertEquals(2, store.account(user.id()).balance());
      assertEquals(1, store.ledger(admin.id(), user.id(), 100).size());
    }
  }

  @Test
  void policySwitchInsideEngineBeforeSanitizedCallbackRejectsWithZeroDebitAndNoDispatch() {
    try (var store = new GatewayStore(temp.resolve("before-dispatch"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 1, "synthetic allocation");
      var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
      var serviceRef = new AtomicReference<GatewayModelService>();
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile(), second),
              (p, r, q, h) -> {
                serviceRef
                    .get()
                    .selectPolicy(
                        admin.id(), UUID.randomUUID(), second.id(), 1, "before dispatch switch");
                try {
                  h.run();
                } catch (RuntimeException denied) {
                  return CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.NETWORK));
                }
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      serviceRef.set(service);
      service.selectPolicy(
          admin.id(), UUID.randomUUID(), GatewayFixtures.profile().id(), 0, "synthetic first");
      var body = GatewayFixtures.body(UUID.randomUUID());
      var reply = service.grade(user.id(), body);
      assertEquals(409, reply.status());
      assertEquals("MODEL_POLICY_CHANGED", reply.body().path("error").path("code").asText());
      assertEquals(0, reply.body().path("chargedCredits").asInt());
      assertFalse(reply.body().has("result"));
      assertEquals(0, calls.get());
      assertEquals(1, store.account(user.id()).balance());
      assertEquals(
          "NOT_DISPATCHED",
          store.request(user.id(), UUID.fromString(body.path("requestId").asText())).state());
      assertTrue(service.grade(user.id(), body).body().path("replayed").asBoolean());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void switchingWhileSupplierIsPendingDiscardsLateScoreButRetainsOneDebitAndReceipt()
      throws Exception {
    try (var store = new GatewayStore(temp.resolve("late"), Clock.systemUTC());
        var worker = Executors.newSingleThreadExecutor()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic allocation");
      var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
      var entered = new CountDownLatch(1);
      var response = new CompletableFuture<GradingResponse>();
      var request = new AtomicReference<GradingRequest>();
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile(), second),
              (p, r, q, h) -> {
                h.run();
                calls.incrementAndGet();
                request.set(q);
                entered.countDown();
                return response;
              });
      service.selectPolicy(
          admin.id(), UUID.randomUUID(), GatewayFixtures.profile().id(), 0, "synthetic first");
      var body = GatewayFixtures.body(UUID.randomUUID());
      var running = worker.submit(() -> service.grade(user.id(), body));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        service.selectPolicy(admin.id(), UUID.randomUUID(), second.id(), 1, "in-flight switch");
        response.complete(GatewayFixtures.response(request.get()));
        var reply = running.get(5, TimeUnit.SECONDS);
        assertEquals(409, reply.status());
        assertEquals("MODEL_POLICY_CHANGED", reply.body().path("error").path("code").asText());
        assertEquals(1, reply.body().path("chargedCredits").asInt());
        assertFalse(reply.body().has("result"));
        var replay = service.grade(user.id(), body);
        assertEquals(409, replay.status());
        assertTrue(replay.body().path("replayed").asBoolean());
        assertEquals(1, replay.body().path("chargedCredits").asInt());
        assertEquals(1, calls.get());
        assertEquals(1, store.account(user.id()).balance());
      } finally {
        response.cancel(true);
      }
    }
  }

  @Test
  void terminalReceiptReplaysUnderOriginalPolicyWithoutRelabelOrAnotherDebit() {
    try (var store = new GatewayStore(temp.resolve("terminal"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic allocation");
      var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile(), second),
              (p, r, q, h) -> {
                h.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      service.selectPolicy(
          admin.id(), UUID.randomUUID(), GatewayFixtures.profile().id(), 0, "synthetic first");
      var body = GatewayFixtures.body(UUID.randomUUID());
      var first = service.grade(user.id(), body);
      assertEquals(200, first.status());
      service.selectPolicy(admin.id(), UUID.randomUUID(), second.id(), 1, "synthetic second");
      var replay = service.grade(user.id(), body);
      assertEquals(200, replay.status());
      assertEquals(first.body().path("result"), replay.body().path("result"));
      assertEquals(first.body().path("completedAt"), replay.body().path("completedAt"));
      assertEquals(1, replay.body().path("chargedCredits").asInt());
      assertTrue(replay.body().path("replayed").asBoolean());
      var relabel = body.deepCopy().put("profileId", second.id()).put("policyRevision", 2);
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(GatewayException.class, () -> service.grade(user.id(), relabel)).code());
      assertEquals(1, calls.get());
      assertEquals(1, store.account(user.id()).balance());
    }
  }

  @Test
  void readOnlyAdminStatusDoesNotCreateDatabaseAndDetectsBootstrap() throws Exception {
    Path database = temp.resolve("new-data").resolve("gateway");
    assertFalse(GatewayStore.adminPresent(database));
    assertFalse(Files.exists(database.getParent()));
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      store.register("alice", GatewayFixtures.password());
    }
    assertFalse(GatewayStore.adminPresent(database));
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      store.bootstrapAdmin("operator", GatewayFixtures.password());
    }
    assertTrue(GatewayStore.adminPresent(database));
  }

  @Test
  void httpHealthAndAdminPolicyRoutesEnforceRoleAndExposeOnlyAssignedModel() throws Exception {
    try (var store = new GatewayStore(temp.resolve("http-policy"), Clock.systemUTC());
        var http = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic allocation");
      var second = GatewayFixtures.profile("synthetic-v2", "synthetic-second", true);
      var unverified = GatewayFixtures.profile("unverified", "synthetic-candidate", false);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile(), second, unverified),
              (p, r, q, h) -> {
                h.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      try (var server = new GatewayServer(0, store, service, Clock.systemUTC())) {
        server.start();
        String operator = store.login("operator", GatewayFixtures.password()).token();
        String alice = store.login("alice", GatewayFixtures.password()).token();
        var health = call(http, server, "/health", null, null);
        assertEquals(200, health.status());
        assertEquals(2, health.body().size());
        assertEquals("deskgrade-gateway", health.body().path("service").asText());
        assertEquals(2, health.body().path("apiVersion").asInt());
        var initial = call(http, server, "/v1/model-policy", alice, null).body();
        assertEquals(5, initial.size());
        assertEquals(0, initial.path("revision").asLong());
        assertTrue(initial.path("profileId").isNull());
        assertTrue(initial.path("modelId").isNull());
        assertTrue(initial.path("fallbackProfileId").isNull());
        assertTrue(initial.path("fallbackModelId").isNull());
        assertEquals(0, call(http, server, "/v1/models", alice, null).body().path("models").size());
        var selection = GatewayJson.MAPPER.createObjectNode();
        selection.put("requestId", UUID.randomUUID().toString());
        selection.put("profileId", GatewayFixtures.profile().id());
        selection.put("expectedRevision", 0);
        selection.put("reason", "synthetic HTTP selection");
        for (String route :
            List.of("/v1/admin/models", "/v1/admin/model-policy", "/v1/admin/model-policy/audit")) {
          assertEquals(401, call(http, server, route, null, null).status());
          assertEquals(403, call(http, server, route, alice, null).status());
        }
        assertEquals(403, call(http, server, "/v1/admin/model-policy", alice, selection).status());
        assertEquals(
            3, call(http, server, "/v1/admin/models", operator, null).body().path("models").size());
        var selected = call(http, server, "/v1/admin/model-policy", operator, selection);
        assertEquals(200, selected.status());
        assertFalse(selected.body().path("replayed").asBoolean());
        assertEquals(
            GatewayFixtures.profile().id(),
            selected.body().path("policy").path("profileId").asText());
        assertEquals(
            GatewayFixtures.profile().modelId(),
            selected.body().path("policy").path("modelId").asText());
        assertEquals(1, selected.body().path("policy").path("revision").asLong());
        assertTrue(
            call(http, server, "/v1/admin/model-policy", operator, selection)
                .body()
                .path("replayed")
                .asBoolean());
        var publicPolicy = call(http, server, "/v1/model-policy", alice, null).body();
        assertEquals("service_primary", publicPolicy.path("profileId").asText());
        assertEquals("online-grading-primary", publicPolicy.path("modelId").asText());
        assertEquals("service_fallback", publicPolicy.path("fallbackProfileId").asText());
        assertEquals("online-grading-fallback", publicPolicy.path("fallbackModelId").asText());
        assertNotEquals(1, publicPolicy.path("revision").asLong());
        assertFalse(publicPolicy.toString().contains(GatewayFixtures.profile().id()));
        assertFalse(publicPolicy.toString().contains(GatewayFixtures.profile().modelId()));
        assertFalse(publicPolicy.toString().contains(second.id()));
        assertFalse(publicPolicy.toString().contains(second.modelId()));
        var directory = call(http, server, "/v1/models", alice, null).body();
        assertEquals(java.util.Set.of("schemaVersion", "models"), fieldNames(directory));
        assertEquals(2, directory.path("models").size());
        assertEquals("service_primary", directory.path("models").get(0).path("profileId").asText());
        assertEquals(
            "service_fallback", directory.path("models").get(1).path("profileId").asText());
        assertEquals(
            "online-grading-primary", directory.path("models").get(0).path("modelId").asText());
        assertEquals(
            "online-grading-fallback", directory.path("models").get(1).path("modelId").asText());
        assertEquals(
            publicPolicy.path("revision").asLong(),
            directory.path("models").get(0).path("revision").asLong());
        assertEquals(600_000, directory.path("models").get(0).path("requestTimeoutMs").asLong());
        assertEquals("PRIMARY", directory.path("models").get(0).path("role").asText());
        assertEquals("FALLBACK", directory.path("models").get(1).path("role").asText());
        assertFalse(directory.toString().contains(GatewayFixtures.profile().id()));
        assertFalse(directory.toString().contains(GatewayFixtures.profile().modelId()));
        assertFalse(directory.toString().contains(second.id()));
        assertFalse(directory.toString().contains(second.modelId()));
        for (var publicModel : directory.path("models")) {
          assertEquals(
              java.util.Set.of(
                  "profileId",
                  "revision",
                  "displayName",
                  "modelId",
                  "requestTimeoutMs",
                  "verified",
                  "role"),
              fieldNames(publicModel));
          assertFalse(publicModel.has("endpoint"));
          assertFalse(publicModel.has("credentialRef"));
          assertFalse(publicModel.has("parameters"));
          assertFalse(publicModel.has("allowedParameters"));
          assertFalse(publicModel.has("verification"));
        }
        var stolen = GatewayFixtures.body(UUID.randomUUID()).put("profileId", second.id());
        var rejected = call(http, server, "/v1/grade", alice, stolen);
        assertEquals(409, rejected.status());
        assertEquals("MODEL_POLICY_CHANGED", rejected.body().path("error").path("code").asText());
        assertEquals(0, rejected.body().path("chargedCredits").asInt());
        assertEquals(0, calls.get());
        assertEquals(2, store.account(user.id()).balance());
        assertEquals(
            1,
            call(http, server, "/v1/admin/model-policy/audit", operator, null)
                .body()
                .path("entries")
                .size());
      }
    }
  }

  private record Wire(int status, JsonNode body) {}

  private static java.util.Set<String> fieldNames(JsonNode node) {
    var names = new java.util.HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    return java.util.Set.copyOf(names);
  }

  private static Wire call(
      HttpClient client, GatewayServer server, String path, String token, ObjectNode body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(10));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Wire(response.statusCode(), GatewayJson.MAPPER.readTree(response.body()));
  }
}
