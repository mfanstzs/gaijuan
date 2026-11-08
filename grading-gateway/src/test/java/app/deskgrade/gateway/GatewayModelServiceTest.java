package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayModelServiceTest {
  @TempDir Path temp;

  @Test
  void successReplaysOnceAndDifferentBodyConflicts() {
    try (var store = new GatewayStore(temp.resolve("success"), Clock.systemUTC())) {
      var user = funded(store, 2);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var body = GatewayFixtures.body(UUID.randomUUID());
      var first = service.grade(user, body);
      var replay = service.grade(user, body);
      assertEquals(200, first.status());
      assertEquals(1, first.body().get("chargedCredits").intValue());
      assertFalse(first.body().get("replayed").booleanValue());
      assertTrue(replay.body().get("replayed").booleanValue());
      assertEquals(1, calls.get());
      assertEquals(1, store.account(user).balance());
      var changed = body.deepCopy();
      changed.withObject("rubric").put("rawRubric", "different synthetic rule");
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(GatewayException.class, () -> service.grade(user, changed)).code());
      assertThrows(
          GatewayException.class,
          () ->
              service.status(
                  UUID.randomUUID(), UUID.fromString(body.get("requestId").textValue())));
    }
  }

  @Test
  void oversizedRubricRuleIsRejectedBeforePersistenceOrSupplierDispatch() {
    try (var store = new GatewayStore(temp.resolve("rubric-cost"), Clock.systemUTC())) {
      var user = funded(store, 1);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                calls.incrementAndGet();
                throw new AssertionError("oversized request reached supplier");
              });
      UUID requestId = UUID.randomUUID();
      var body = GatewayFixtures.body(requestId);
      ((ObjectNode) body.path("rubric").path("criteria").get(0))
          .put("rule", "x".repeat(GatewayModelService.MAX_CRITERION_RULE_CHARS + 1));
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(GatewayException.class, () -> service.grade(user, body)).code());
      assertEquals(0, calls.get());
      assertNull(store.findRequest(user, requestId));
    }
  }

  @Test
  void supplierFailureAfterDispatchRemainsChargedAndIsNotRetried() {
    try (var store = new GatewayStore(temp.resolve("failed"), Clock.systemUTC())) {
      var user = funded(store, 3);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new ProviderException(ProviderException.Failure.AUTHENTICATION));
              });
      var body = GatewayFixtures.body(UUID.randomUUID());
      var reply = service.grade(user, body);
      assertEquals(502, reply.status());
      assertEquals("MODEL_FAILED", reply.body().path("error").path("code").asText());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      service.grade(user, body);
      assertEquals(1, calls.get());
      assertEquals(2, store.account(user).balance());
    }
  }

  @Test
  void safeProviderDiagnosticsPersistForUnknownAndConfirmedFailuresAndReplayWithoutRetry() {
    try (var store = new GatewayStore(temp.resolve("failure-details"), Clock.systemUTC())) {
      var user = funded(store, 20);
      record Case(
          ProviderException.Failure kind,
          ProviderException.Diagnostic code,
          Integer status,
          String expectedState,
          String expectedError) {}
      var cases =
          List.of(
              new Case(
                  ProviderException.Failure.NETWORK,
                  ProviderException.Diagnostic.MODEL_DNS_FAILURE,
                  null,
                  "UNKNOWN",
                  "DISPATCH_UNKNOWN"),
              new Case(
                  ProviderException.Failure.TIMEOUT,
                  ProviderException.Diagnostic.MODEL_CONNECT_TIMEOUT,
                  null,
                  "UNKNOWN",
                  "DISPATCH_UNKNOWN"),
              new Case(
                  ProviderException.Failure.CANCELLED,
                  ProviderException.Diagnostic.MODEL_CANCELLED,
                  null,
                  "UNKNOWN",
                  "DISPATCH_UNKNOWN"),
              new Case(
                  ProviderException.Failure.INTERNAL_ERROR,
                  ProviderException.Diagnostic.MODEL_INTERNAL_ERROR,
                  null,
                  "UNKNOWN",
                  "DISPATCH_UNKNOWN"),
              new Case(
                  ProviderException.Failure.AUTHENTICATION,
                  ProviderException.Diagnostic.MODEL_AUTHENTICATION,
                  401,
                  "FAILED",
                  "MODEL_FAILED"),
              new Case(
                  ProviderException.Failure.RATE_LIMIT,
                  ProviderException.Diagnostic.MODEL_RATE_LIMIT,
                  429,
                  "FAILED",
                  "MODEL_FAILED"),
              new Case(
                  ProviderException.Failure.SERVER_ERROR,
                  ProviderException.Diagnostic.MODEL_SERVER_ERROR,
                  503,
                  "FAILED",
                  "MODEL_FAILED"),
              new Case(
                  ProviderException.Failure.INVALID_JSON,
                  ProviderException.Diagnostic.MODEL_INVALID_JSON,
                  null,
                  "FAILED",
                  "MODEL_RESULT_INVALID"));
      var calls = new AtomicInteger();
      for (var testCase : cases) {
        var service =
            new GatewayModelService(
                store,
                List.of(GatewayFixtures.profile()),
                (p, r, q, hook) -> {
                  hook.run();
                  calls.incrementAndGet();
                  return CompletableFuture.failedFuture(
                      new ProviderException(testCase.kind(), testCase.status(), testCase.code()));
                });
        UUID id = UUID.randomUUID();
        var body = GatewayFixtures.body(id);
        var reply = service.grade(user, body);
        assertEquals(testCase.expectedError(), reply.body().path("error").path("code").asText());
        assertEquals(
            testCase.code().name(), reply.body().path("error").path("diagnosticCode").asText());
        assertFalse(reply.body().path("error").has("upstreamStatus"));
        assertEquals(testCase.expectedState(), store.request(user, id).state());
        assertEquals(1, reply.body().path("chargedCredits").asInt());
        assertEquals(
            testCase.code().name(), service.status(user, id).path("diagnosticCode").asText());
        var saved = store.modelFailure(user, id, "GRADING");
        assertEquals(testCase.status(), saved.upstreamStatus());
        assertEquals(GatewayModelFailure.Phase.AFTER_DISPATCH, saved.phase());
        var replay = service.grade(user, body);
        assertTrue(replay.body().path("replayed").asBoolean());
        assertEquals(
            testCase.code().name(), replay.body().path("error").path("diagnosticCode").asText());
      }
      assertEquals(cases.size(), calls.get());
      assertEquals(20 - cases.size(), store.account(user).balance());
    }
  }

  @Test
  void failureBeforeDispatchRetainsPhaseAndUnexpectedLocalFailureDoesNotLeakExceptionText() {
    try (var store = new GatewayStore(temp.resolve("local-failure-details"), Clock.systemUTC())) {
      var user = funded(store, 2);
      UUID beforeId = UUID.randomUUID(), afterId = UUID.randomUUID();
      var unavailable =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) ->
                  CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.MISSING_CREDENTIAL)));
      var before = unavailable.grade(user, GatewayFixtures.body(beforeId));
      assertEquals(
          "MODEL_MISSING_CREDENTIAL", before.body().path("error").path("diagnosticCode").asText());
      assertEquals(0, before.body().path("chargedCredits").asInt());
      assertEquals(
          GatewayModelFailure.Phase.BEFORE_DISPATCH,
          store.modelFailure(user, beforeId, "GRADING").phase());
      var failed =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                return CompletableFuture.failedFuture(
                    new IllegalStateException("SYNTHETIC_PRIVATE_URL_PASSWORD_STUDENT_TEXT"));
              });
      var after = failed.grade(user, GatewayFixtures.body(afterId));
      assertEquals("DISPATCH_UNKNOWN", after.body().path("error").path("code").asText());
      assertEquals(
          "GATEWAY_UNEXPECTED_ERROR", after.body().path("error").path("diagnosticCode").asText());
      assertFalse(after.body().toString().contains("SYNTHETIC_PRIVATE"));
      assertFalse(
          store
              .modelCallDetail(user, afterId, "GRADING")
              .requestJson()
              .contains("SYNTHETIC_PRIVATE"));
      assertEquals(1, store.account(user).balance());
    }
  }

  @Test
  void failureDiagnosticPersistenceFailureNeverReturnsAUsableScoreOrClaimsNoCharge() {
    Path database = temp.resolve("corrupt-failure-snapshot");
    UUID id = UUID.randomUUID(), user;
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      user = funded(store, 1);
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                try (var connection =
                        java.sql.DriverManager.getConnection("jdbc:h2:file:" + database, "sa", "");
                    var update =
                        connection.prepareStatement(
                            "UPDATE grade_requests SET request_json=? WHERE user_id=? AND request_id=?")) {
                  update.setString(1, "{malformed stored snapshot");
                  update.setString(2, user.toString());
                  update.setString(3, id.toString());
                  assertEquals(1, update.executeUpdate());
                } catch (java.sql.SQLException failure) {
                  throw new AssertionError(failure);
                }
                return CompletableFuture.failedFuture(
                    new ProviderException(ProviderException.Failure.NETWORK));
              });
      var reply = service.grade(user, GatewayFixtures.body(id));
      assertEquals(503, reply.status());
      assertEquals("STORE_UNAVAILABLE", reply.body().path("error").path("code").asText());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      assertFalse(reply.body().has("result"));
      assertEquals("DISPATCHING", store.request(user, id).state());
      assertEquals(0, store.account(user).balance());
    }
    try (var reopened = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals("UNKNOWN", reopened.request(user, id).state());
      assertTrue(reopened.request(user, id).charged());
      assertEquals(0, reopened.account(user).balance());
    }
  }

  @Test
  void missingKeyAndForgottenCallbackCannotProduceFreeResults() {
    try (var store = new GatewayStore(temp.resolve("before"), Clock.systemUTC())) {
      var user = funded(store, 2);
      var unavailable =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) ->
                  CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.MISSING_CREDENTIAL)));
      assertEquals(
          0,
          unavailable
              .grade(user, GatewayFixtures.body(UUID.randomUUID()))
              .body()
              .path("chargedCredits")
              .asInt());
      var forgotten =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> CompletableFuture.completedFuture(GatewayFixtures.response(q)));
      var reply = forgotten.grade(user, GatewayFixtures.body(UUID.randomUUID()));
      assertNotEquals(200, reply.status());
      assertFalse(reply.body().has("result"));
      assertEquals(2, store.account(user).balance());
    }
  }

  @Test
  void sanitizedCallbackRejectionRetainsInsufficientCreditsCode() {
    try (var store = new GatewayStore(temp.resolve("zero"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var user = store.register("alice", GatewayFixtures.password()).id();
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                try {
                  hook.run();
                } catch (RuntimeException denied) {
                  return CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.NETWORK));
                }
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var reply = service.grade(user, GatewayFixtures.body(UUID.randomUUID()));
      assertEquals(402, reply.status());
      assertEquals("INSUFFICIENT_CREDITS", reply.body().path("error").path("code").asText());
      assertEquals(0, reply.body().path("chargedCredits").asInt());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void sameRequestConcurrentCallerCannotDispatchAgain() throws Exception {
    try (var store = new GatewayStore(temp.resolve("duplicate"), Clock.systemUTC());
        var workers = Executors.newFixedThreadPool(2)) {
      var user = funded(store, 2);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                entered.countDown();
                return CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException();
                      } catch (InterruptedException e) {
                        throw new IllegalStateException();
                      }
                      return GatewayFixtures.response(q);
                    });
              });
      var body = GatewayFixtures.body(UUID.randomUUID());
      var first = workers.submit(() -> service.grade(user, body));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var second = service.grade(user, body);
        assertEquals(409, second.status());
        assertEquals("REQUEST_IN_PROGRESS", second.body().path("error").path("code").asText());
        assertEquals(1, calls.get());
      } finally {
        release.countDown();
      }
      assertEquals(200, first.get(4, TimeUnit.SECONDS).status());
      assertEquals(1, store.account(user).balance());
    }
  }

  @Test
  void malformedImageAndInjectedSupplierFieldsFailBeforeDispatch() {
    try (var store = new GatewayStore(temp.resolve("invalid"), Clock.systemUTC())) {
      var user = funded(store, 1);
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                calls.incrementAndGet();
                throw new AssertionError();
              });
      var image = GatewayFixtures.body(UUID.randomUUID());
      image.put("imageBase64", "AAAA");
      assertThrows(GatewayException.class, () -> service.grade(user, image));
      var injected = GatewayFixtures.body(UUID.randomUUID());
      injected.put("endpoint", "http://127.0.0.1:1");
      assertThrows(GatewayException.class, () -> service.grade(user, injected));
      assertEquals(0, calls.get());
      assertEquals(1, store.account(user).balance());
    }
  }

  @Test
  void logoutBetweenPreparationAndDispatchPreventsChargeAndSupplierCall() {
    try (var store = new GatewayStore(temp.resolve("logout"), Clock.systemUTC())) {
      var user = funded(store, 1);
      var login = store.login("alice", GatewayFixtures.password());
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                store.logout(login.token());
                try {
                  hook.run();
                } catch (RuntimeException denied) {
                  return CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.NETWORK));
                }
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var reply = service.grade(user, GatewayFixtures.body(UUID.randomUUID()), login.token());
      assertEquals(401, reply.status());
      assertEquals("AUTH_REQUIRED", reply.body().path("error").path("code").asText());
      assertEquals(0, reply.body().path("chargedCredits").asInt());
      assertEquals(0, calls.get());
      assertEquals(1, store.account(user).balance());
    }
  }

  @Test
  void storageLostAfterCommittedDispatchNeverReportsUncharged() {
    Path database = temp.resolve("lost-store");
    UUID user, id = UUID.randomUUID();
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      user = funded(store, 1);
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                store.close();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var reply = service.grade(user, GatewayFixtures.body(id));
      assertEquals(503, reply.status());
      assertEquals("STORE_UNAVAILABLE", reply.body().path("error").path("code").asText());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      assertFalse(reply.body().has("result"));
    }
    try (var reopened = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals(0, reopened.account(user).balance());
      assertEquals("UNKNOWN", reopened.request(user, id).state());
      assertTrue(reopened.request(user, id).charged());
    }
  }

  @Test
  void alreadyChargedResultReplaysEvenWhenNoModelsAreCurrentlyAvailable() {
    try (var store = new GatewayStore(temp.resolve("offline-replay"), Clock.systemUTC())) {
      var user = funded(store, 1);
      var live =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      var body = GatewayFixtures.body(UUID.randomUUID());
      assertEquals(200, live.grade(user, body).status());
      var unavailable =
          new GatewayModelService(
              store,
              List.of(),
              (p, r, q, hook) -> {
                throw new AssertionError("Must not dispatch");
              });
      var reply = unavailable.grade(user, body);
      assertEquals(200, reply.status());
      assertTrue(reply.body().path("replayed").asBoolean());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      assertEquals(0, store.account(user).balance());
    }
  }

  private static UUID funded(GatewayStore store, long credits) {
    var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
    GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
    var user = store.register("alice", GatewayFixtures.password());
    store.adjust(admin.id(), UUID.randomUUID(), user.id(), credits, "synthetic test allocation");
    return user.id();
  }
}
