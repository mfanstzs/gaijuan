package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ModelCallTrace;
import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * H2-only authorization/ledger tests; provider tests own actual reconnect delays and eligibility.
 */
@Timeout(20)
class GatewayConnectRetryTest {
  @TempDir Path directory;

  @Test
  void lastPaidCreditAllowsTwoAuthorizedRetriesAndSuccessWithoutSnapshotMutation()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("last-credit"))) {
      var sends = new AtomicInteger();
      var engine =
          traced(
              (request, trace) -> {
                sends.incrementAndGet();
                assertEquals(0, fixture.store.account(fixture.user).balance());
                String original =
                    fixture
                        .store
                        .modelCallDetail(fixture.user, request.requestId(), "GRADING")
                        .requestJson();
                for (int attempt = 2; attempt <= 3; attempt++) {
                  trace.connectRetryScheduled(attempt, Duration.ofSeconds(20));
                  trace.authorizeConnectRetry(attempt);
                  sends.incrementAndGet();
                  assertEquals(0, fixture.store.account(fixture.user).balance());
                  assertEquals(
                      original,
                      fixture
                          .store
                          .modelCallDetail(fixture.user, request.requestId(), "GRADING")
                          .requestJson());
                }
                var response = GatewayFixtures.response(request);
                trace.outputReceived(GatewayJson.MAPPER.valueToTree(response.result()).toString());
                return CompletableFuture.completedFuture(response);
              });
      var service = fixture.service(engine);
      UUID id = UUID.randomUUID();
      var body = GatewayFixtures.body(id);
      var result = service.grade(fixture.user, body, fixture.token);
      assertEquals(200, result.status());
      assertEquals("SUCCEEDED", fixture.store.request(fixture.user, id).state());
      assertEquals(1, result.body().path("chargedCredits").asInt());
      assertEquals(0, fixture.store.account(fixture.user).balance());
      assertEquals(1, debits(fixture));
      assertEquals(3, sends.get());
      assertNull(fixture.store.modelFailure(fixture.user, id, "GRADING"));
      assertTrue(
          service.grade(fixture.user, body, fixture.token).body().path("replayed").asBoolean());
      assertEquals(3, sends.get());
      var changed = body.deepCopy();
      changed.withObject("rubric").put("rawRubric", "Different synthetic rule");
      assertCode(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          () -> service.grade(fixture.user, changed, fixture.token));
      assertEquals(1, debits(fixture));
      assertCode(
          GatewayException.Code.REQUEST_IN_PROGRESS,
          () -> fixture.store.beforeDispatch(fixture.user, id, fixture.token));
    }
  }

  @Test
  void exhaustedRetriesKeepOneUnknownReceiptAndExactFinalDiagnostic() {
    try (var fixture = new Fixture(directory.resolve("exhausted"))) {
      var sends = new AtomicInteger();
      var service =
          fixture.service(
              traced(
                  (request, trace) -> {
                    sends.incrementAndGet();
                    for (int attempt = 2; attempt <= 3; attempt++) {
                      trace.connectRetryScheduled(attempt, Duration.ofSeconds(20));
                      trace.authorizeConnectRetry(attempt);
                      sends.incrementAndGet();
                    }
                    return CompletableFuture.failedFuture(
                        new ProviderException(
                            ProviderException.Failure.TIMEOUT,
                            null,
                            ProviderException.Diagnostic.MODEL_CONNECT_TIMEOUT));
                  }));
      UUID id = UUID.randomUUID();
      var body = GatewayFixtures.body(id);
      var reply = service.grade(fixture.user, body, fixture.token);
      assertEquals(409, reply.status());
      assertEquals("DISPATCH_UNKNOWN", reply.body().path("error").path("code").asText());
      assertEquals(
          "MODEL_CONNECT_TIMEOUT", reply.body().path("error").path("diagnosticCode").asText());
      assertEquals("UNKNOWN", fixture.store.request(fixture.user, id).state());
      assertEquals(1, reply.body().path("chargedCredits").asInt());
      assertEquals(0, fixture.store.account(fixture.user).balance());
      assertEquals(1, debits(fixture));
      assertEquals(3, sends.get());
      service.grade(fixture.user, body, fixture.token);
      assertEquals(3, sends.get());
    }
  }

  @Test
  void logoutOrPolicyChangeDuringBackoffPreventsAnotherSendWithoutRecharging() {
    for (boolean logout : List.of(true, false)) {
      try (var fixture = new Fixture(directory.resolve(logout ? "logout" : "policy"))) {
        var sends = new AtomicInteger();
        var service =
            fixture.service(
                traced(
                    (request, trace) -> {
                      sends.incrementAndGet();
                      trace.connectRetryScheduled(2, Duration.ofSeconds(20));
                      if (logout) fixture.store.logout(fixture.token);
                      else
                        fixture.store.selectModelPolicy(
                            fixture.admin,
                            UUID.randomUUID(),
                            fixture.profile.id(),
                            1,
                            "Synthetic policy change during retry wait",
                            ignored -> fixture.profile.modelId());
                      assertCode(
                          logout
                              ? GatewayException.Code.UNAUTHENTICATED
                              : GatewayException.Code.MODEL_POLICY_CHANGED,
                          () -> trace.authorizeConnectRetry(2));
                      // The provider sanitizes callback failures, while the server-owned rejection
                      // retains its
                      // exact authorization code instead of misclassifying it as a network outage.
                      return CompletableFuture.failedFuture(
                          new ProviderException(ProviderException.Failure.INTERNAL_ERROR));
                    }));
        var id = UUID.randomUUID();
        var reply = service.grade(fixture.user, GatewayFixtures.body(id), fixture.token);
        assertEquals(logout ? 401 : 409, reply.status());
        assertEquals(
            logout ? "AUTH_REQUIRED" : "MODEL_POLICY_CHANGED",
            reply.body().path("error").path("code").asText());
        assertEquals(
            logout ? "GATEWAY_UNAUTHENTICATED" : "GATEWAY_MODEL_POLICY_CHANGED",
            reply.body().path("error").path("diagnosticCode").asText());
        assertEquals(1, sends.get());
        assertEquals(0, fixture.store.account(fixture.user).balance());
        assertEquals(1, debits(fixture));
      }
    }
  }

  @Test
  void sameIdDuringBackoffReplaysAndCannotCreateAnAdditionalRetryOwner() throws Exception {
    try (var fixture = new Fixture(directory.resolve("concurrent"));
        var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var pending = new CompletableFuture<GradingResponse>();
      var calls = new AtomicInteger();
      var capturedRequest = new java.util.concurrent.atomic.AtomicReference<GradingRequest>();
      var service =
          fixture.service(
              traced(
                  (request, trace) -> {
                    calls.incrementAndGet();
                    capturedRequest.set(request);
                    trace.connectRetryScheduled(2, Duration.ofSeconds(20));
                    entered.countDown();
                    return pending;
                  }));
      var body = GatewayFixtures.body(UUID.randomUUID());
      var first = worker.submit(() -> service.grade(fixture.user, body, fixture.token));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var replay = service.grade(fixture.user, body, fixture.token);
      assertEquals("REQUEST_IN_PROGRESS", replay.body().path("error").path("code").asText());
      assertTrue(replay.body().path("replayed").asBoolean());
      assertEquals(1, replay.body().path("chargedCredits").asInt());
      assertEquals(1, calls.get());
      pending.complete(GatewayFixtures.response(capturedRequest.get()));
      assertEquals(200, first.get(5, TimeUnit.SECONDS).status());
      assertEquals(1, debits(fixture));
    }
  }

  @Test
  void retryAuthorizationRejectsUnpaidWrongOwnerMismatchAndTerminalReceipts() {
    try (var fixture = new Fixture(directory.resolve("authorization"))) {
      var other = fixture.store.register("bob", GatewayFixtures.password()).id();
      UUID id = UUID.randomUUID();
      fixture.store.prepare(
          fixture.user, id, "a".repeat(64), fixture.profile.modelId(), fixture.profile.id(), 1L);
      assertCode(
          GatewayException.Code.REQUEST_IN_PROGRESS, () -> authorize(fixture, fixture.user, id));
      fixture.store.beforeDispatch(fixture.user, id, fixture.token);
      assertCode(GatewayException.Code.NOT_FOUND, () -> authorize(fixture, other, id));
      assertCode(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          () ->
              fixture.store.authorizeConnectRetry(
                  fixture.user,
                  id,
                  fixture.token,
                  "different-profile",
                  fixture.profile.modelId(),
                  fixture.profile.id(),
                  fixture.profile.modelId(),
                  1));
      var receipt = fixture.store.request(fixture.user, id);
      String snapshot = fixture.store.modelCallDetail(fixture.user, id, "GRADING").requestJson();
      authorize(fixture, fixture.user, id);
      assertEquals(receipt, fixture.store.request(fixture.user, id));
      assertEquals(
          snapshot, fixture.store.modelCallDetail(fixture.user, id, "GRADING").requestJson());
      assertEquals(0, fixture.store.account(fixture.user).balance());
      assertEquals(1, debits(fixture));
      fixture.store.finish(fixture.user, id, null, "DISPATCH_UNKNOWN", true);
      assertCode(
          GatewayException.Code.REQUEST_IN_PROGRESS, () -> authorize(fixture, fixture.user, id));
    }
  }

  @Test
  void restartNeverResumesTheRemainingRetriesOfAnAlreadyChargedReceipt() {
    Path path = directory.resolve("restart");
    UUID user, id = UUID.randomUUID();
    try (var fixture = new Fixture(path)) {
      user = fixture.user;
      fixture.store.prepare(
          user, id, "a".repeat(64), fixture.profile.modelId(), fixture.profile.id(), 1L);
      fixture.store.beforeDispatch(user, id, fixture.token);
      authorize(fixture, user, id);
      assertEquals(1, debits(fixture));
    }
    try (var store = new GatewayStore(path, Clock.systemUTC())) {
      assertEquals("UNKNOWN", store.request(user, id).state());
      assertEquals(0, store.account(user).balance());
      var profile = GatewayFixtures.profile();
      assertCode(
          GatewayException.Code.REQUEST_IN_PROGRESS,
          () ->
              store.authorizeConnectRetry(
                  user,
                  id,
                  null,
                  profile.id(),
                  profile.modelId(),
                  profile.id(),
                  profile.modelId(),
                  1));
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, rubric, request, hook) -> {
                calls.incrementAndGet();
                throw new AssertionError("Receipt lookup must never resume retries");
              });
      assertEquals("DISPATCH_UNKNOWN", models.status(user, id).path("state").asText());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void retryAuditIsBoundedOrderedAndContainsOnlyFixedFields() throws Exception {
    var rows = new ArrayList<String>();
    var audit = new GatewayConnectRetryAudit(UUID.randomUUID(), rows::add);
    var authorizations = new AtomicInteger();
    assertCode(
        GatewayException.Code.INVALID_REQUEST, () -> audit.scheduled(2, Duration.ofSeconds(20)));
    audit.initialDispatchAuthorized();
    assertCode(
        GatewayException.Code.INVALID_REQUEST,
        () -> audit.authorize(2, authorizations::incrementAndGet));
    assertCode(
        GatewayException.Code.INVALID_REQUEST, () -> audit.scheduled(3, Duration.ofSeconds(20)));
    assertCode(
        GatewayException.Code.INVALID_REQUEST, () -> audit.scheduled(2, Duration.ofSeconds(19)));
    for (int attempt = 2; attempt <= 3; attempt++) {
      audit.scheduled(attempt, Duration.ofSeconds(20));
      audit.authorize(attempt, authorizations::incrementAndGet);
    }
    assertCode(
        GatewayException.Code.INVALID_REQUEST, () -> audit.scheduled(4, Duration.ofSeconds(20)));
    assertCode(
        GatewayException.Code.INVALID_REQUEST,
        () -> audit.authorize(3, authorizations::incrementAndGet));
    audit.completed("UNKNOWN");
    audit.finish();
    audit.finish();
    assertCode(
        GatewayException.Code.INVALID_REQUEST, () -> audit.scheduled(2, Duration.ofSeconds(20)));
    assertCode(
        GatewayException.Code.INVALID_REQUEST,
        () -> audit.authorize(2, authorizations::incrementAndGet));
    assertEquals(2, authorizations.get());
    assertEquals(5, rows.size());
    for (String row : rows) {
      var json = GatewayJson.MAPPER.readTree(row);
      var fields = new java.util.HashSet<String>();
      json.fieldNames().forEachRemaining(fields::add);
      assertEquals(
          java.util.Set.of(
              "event",
              "requestId",
              "phase",
              "attempt",
              "authorizedAttempts",
              "scheduledRetries",
              "delayMs",
              "outcome"),
          fields);
      assertTrue(json.path("attempt").asInt() <= 3);
    }
    var summary = GatewayJson.MAPPER.readTree(rows.getLast());
    assertEquals("FINAL", summary.path("phase").asText());
    assertEquals(3, summary.path("authorizedAttempts").asInt());
    assertEquals("UNKNOWN", summary.path("outcome").asText());
  }

  private static void authorize(Fixture fixture, UUID owner, UUID id) {
    fixture.store.authorizeConnectRetry(
        owner,
        id,
        owner.equals(fixture.user) ? fixture.token : null,
        fixture.profile.id(),
        fixture.profile.modelId(),
        fixture.profile.id(),
        fixture.profile.modelId(),
        1);
  }

  private static long debits(Fixture fixture) {
    return fixture.store.ledger(fixture.admin, fixture.user, 100).stream()
        .filter(entry -> entry.kind().equals("MODEL_DISPATCH"))
        .count();
  }

  private static void assertCode(GatewayException.Code code, Runnable action) {
    assertEquals(code, assertThrows(GatewayException.class, action::run).code());
  }

  private static GatewayModelService.Engine traced(
      BiFunction<GradingRequest, ModelCallTrace, CompletionStage<GradingResponse>> operation) {
    return new GatewayModelService.Engine() {
      @Override
      public CompletionStage<GradingResponse> grade(
          ProviderProfile profile, Rubric rubric, GradingRequest request, Runnable before) {
        throw new AssertionError("The service must use its trace-aware engine");
      }

      @Override
      public CompletionStage<GradingResponse> grade(
          ProviderProfile profile,
          Rubric rubric,
          GradingRequest request,
          Runnable before,
          ModelCallTrace trace) {
        var snapshot = GatewayJson.MAPPER.createObjectNode().put("model", profile.modelId());
        snapshot
            .putArray("messages")
            .addObject()
            .put("role", "user")
            .put("content", "Synthetic grading request");
        trace.requestPrepared(snapshot.toString());
        before.run();
        return operation.apply(request, trace);
      }
    };
  }

  private static final class Fixture implements AutoCloseable {
    final GatewayStore store;
    final ProviderProfile profile = GatewayFixtures.profile();
    final UUID admin, user;
    final String token;

    Fixture(Path path) {
      store = new GatewayStore(path, Clock.systemUTC());
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      GatewayFixtures.activate(store, admin, profile);
      user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 1, "Synthetic one-request retry budget");
      token = store.login("alice", GatewayFixtures.password()).token();
    }

    GatewayModelService service(GatewayModelService.Engine engine) {
      return new GatewayModelService(store, List.of(profile), engine);
    }

    @Override
    public void close() {
      store.close();
    }
  }
}
