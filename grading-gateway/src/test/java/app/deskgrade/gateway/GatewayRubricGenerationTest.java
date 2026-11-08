package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.ScoringCriterion;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayRubricGenerationTest {
  @TempDir Path temp;

  @Test
  void successDebitsOnceReplaysAcrossPolicyChangesAndConflictingInputIsRejected() {
    Path database = temp.resolve("rubric-success");
    UUID admin;
    UUID user;
    var first = GatewayFixtures.profile();
    var second = GatewayFixtures.profile("rubric-v2", "synthetic-rubric-v2", true);
    var calls = new AtomicInteger();
    ObjectNode body = generationBody(UUID.randomUUID(), "历史", "标准答案", "4");
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 3, "rubric generation budget");
      var service = service(store, List.of(first, second), calls, validRubric());
      var selected =
          service.selectRubricPolicy(
              admin, UUID.randomUUID(), first.id(), 0, "select rubric generator");
      assertEquals(1, selected.policy().revision());
      assertEquals(new GatewayStore.ModelPolicy(0, null, null), store.modelPolicy());

      var created = service.generateRubric(user, body, null);
      assertEquals(200, created.status());
      assertEquals(
          java.util.Set.of(
              "requestId",
              "scoreStep",
              "criteria",
              "completedAt",
              "usage",
              "creditsRemaining",
              "chargedCredits",
              "replayed"),
          fieldNames(created.body()));
      assertEquals(1, created.body().path("chargedCredits").asInt());
      assertEquals(2, created.body().path("creditsRemaining").asInt());
      assertFalse(created.body().path("replayed").asBoolean());
      assertEquals("P1", created.body().path("criteria").get(0).path("id").asText());

      service.selectRubricPolicy(
          admin, UUID.randomUUID(), second.id(), 1, "replace rubric generator");
      var replay = service.generateRubric(user, body, null);
      assertEquals(200, replay.status());
      assertTrue(replay.body().path("replayed").asBoolean());
      assertEquals(1, calls.get());
      assertEquals(2, store.account(user).balance());
      assertEquals(
          1,
          store.ledger(admin, user, 100).stream()
              .filter(entry -> entry.kind().equals("RUBRIC_GENERATION"))
              .count());

      ObjectNode changed = body.deepCopy().put("referenceAnswer", "different answer");
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(GatewayException.class, () -> service.generateRubric(user, changed, null))
              .code());
    }
    try (var reopened = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals(2, reopened.rubricModelPolicy().revision());
      assertEquals(second.id(), reopened.rubricModelPolicy().profileId());
      assertEquals(2, reopened.account(user).balance());
    }
  }

  @Test
  void invalidSupplierResultStaysChargedWhileFailureBeforeDispatchDoesNotDebit() {
    try (var store = new GatewayStore(temp.resolve("rubric-failures"), Clock.systemUTC())) {
      var profile = GatewayFixtures.profile();
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      var user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 2, "rubric generation budget");
      var calls = new AtomicInteger();
      var invalid =
          new GatewayModelService.GeneratedRubric(
              BigDecimal.ONE,
              List.of(
                  new ScoringCriterion(
                      "P1", "only three points", List.of(BigDecimal.ZERO, new BigDecimal("3")))),
              null,
              Duration.ZERO);
      var service = service(store, List.of(profile), calls, invalid);
      service.selectRubricPolicy(
          admin, UUID.randomUUID(), profile.id(), 0, "select rubric generator");
      ObjectNode invalidBody = generationBody(UUID.randomUUID(), "历史", "标准答案", "4");

      var rejected = service.generateRubric(user, invalidBody, null);
      assertEquals(502, rejected.status());
      assertEquals("MODEL_RESULT_INVALID", rejected.body().path("error").path("code").asText());
      assertEquals(1, rejected.body().path("chargedCredits").asInt());
      assertEquals(1, store.account(user).balance());
      assertEquals(1, calls.get());
      assertTrue(
          service.generateRubric(user, invalidBody, null).body().path("replayed").asBoolean());
      assertEquals(1, calls.get());

      var unavailable =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                throw new AssertionError("grading engine must not run");
              },
              (p, subject, reference, maximum, hook) ->
                  CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.MISSING_CREDENTIAL)));
      ObjectNode unavailableBody = generationBody(UUID.randomUUID(), "语文", "另一个答案", "4");
      var notDispatched = unavailable.generateRubric(user, unavailableBody, null);
      assertEquals(503, notDispatched.status());
      assertEquals("MODEL_UNAVAILABLE", notDispatched.body().path("error").path("code").asText());
      assertEquals(0, notDispatched.body().path("chargedCredits").asInt());
      assertEquals(1, store.account(user).balance());
      assertEquals(
          "NOT_DISPATCHED",
          store
              .rubricGenerationRequest(
                  user, UUID.fromString(unavailableBody.path("requestId").asText()))
              .state());
    }
  }

  @Test
  void rubricFailureDiagnosticsPersistSeparatelyAndReplayWithExistingDispatchOutcome() {
    try (var store =
        new GatewayStore(temp.resolve("rubric-failure-diagnostic"), Clock.systemUTC())) {
      var profile = GatewayFixtures.profile();
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      var user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 3, "synthetic rubric diagnostics");
      var calls = new AtomicInteger();
      for (var failure :
          List.of(
              new ProviderException(
                  ProviderException.Failure.TIMEOUT,
                  null,
                  ProviderException.Diagnostic.MODEL_REQUEST_TIMEOUT),
              new ProviderException(ProviderException.Failure.INTERNAL_ERROR),
              new ProviderException(ProviderException.Failure.AUTHENTICATION, 401))) {
        var service =
            new GatewayModelService(
                store,
                List.of(profile),
                (p, r, q, hook) -> {
                  throw new AssertionError();
                },
                (p, subject, reference, maximum, hook) -> {
                  hook.run();
                  calls.incrementAndGet();
                  return CompletableFuture.failedFuture(failure);
                });
        if (store.rubricModelPolicy().revision() == 0)
          service.selectRubricPolicy(
              admin, UUID.randomUUID(), profile.id(), 0, "synthetic generator diagnostic policy");
        UUID id = UUID.randomUUID();
        var body = generationBody(id, "语文", "标准答案", "4");
        var reply = service.generateRubric(user, body, null);
        assertEquals(
            failure.diagnosticCode(), reply.body().path("error").path("diagnosticCode").asText());
        assertEquals(
            failure.failure() == ProviderException.Failure.AUTHENTICATION
                ? "MODEL_FAILED"
                : "DISPATCH_UNKNOWN",
            reply.body().path("error").path("code").asText());
        assertEquals(1, reply.body().path("chargedCredits").asInt());
        assertEquals(
            failure.httpStatus(),
            store.modelFailure(user, id, "RUBRIC_GENERATION").upstreamStatus());
        assertEquals(
            failure.diagnosticCode(),
            service
                .generateRubric(user, body, null)
                .body()
                .path("error")
                .path("diagnosticCode")
                .asText());
      }
      assertEquals(3, calls.get());
      assertEquals(0, store.account(user).balance());
    }
  }

  @Test
  void inputsAndGeneratedContractAreRejectedAtTheirTrustBoundaries() {
    try (var store = new GatewayStore(temp.resolve("rubric-validation"), Clock.systemUTC())) {
      var profile = GatewayFixtures.profile();
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      var user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 1, "rubric generation budget");
      var calls = new AtomicInteger();
      var service = service(store, List.of(profile), calls, validRubric());
      service.selectRubricPolicy(
          admin, UUID.randomUUID(), profile.id(), 0, "select rubric generator");
      ObjectNode multilineSubject = generationBody(UUID.randomUUID(), "历史\n语文", "答案", "4");
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(
                  GatewayException.class,
                  () -> service.generateRubric(user, multilineSubject, null))
              .code());
      ObjectNode tooLarge = generationBody(UUID.randomUUID(), "历史", "答案", "101");
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(GatewayException.class, () -> service.generateRubric(user, tooLarge, null))
              .code());
      assertEquals(0, calls.get());
      assertEquals(1, store.account(user).balance());
    }
  }

  private static GatewayModelService service(
      GatewayStore store,
      List<app.deskgrade.core.domain.model.ProviderProfile> profiles,
      AtomicInteger calls,
      GatewayModelService.GeneratedRubric generated) {
    return new GatewayModelService(
        store,
        profiles,
        (p, r, q, hook) -> {
          throw new AssertionError("grading engine must not run");
        },
        (p, subject, reference, maximum, hook) -> {
          hook.run();
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(generated);
        });
  }

  private static GatewayModelService.GeneratedRubric validRubric() {
    return new GatewayModelService.GeneratedRubric(
        BigDecimal.ONE,
        List.of(
            new ScoringCriterion("P1", "表达第一个要点得相应分数", List.of(BigDecimal.ZERO, BigDecimal.ONE)),
            new ScoringCriterion(
                "P2",
                "表达其余要点得相应分数",
                List.of(
                    BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("3")))),
        null,
        Duration.ofMillis(20));
  }

  private static ObjectNode generationBody(
      UUID requestId, String subject, String referenceAnswer, String maxScore) {
    return GatewayJson.MAPPER
        .createObjectNode()
        .put("requestId", requestId.toString())
        .put("subject", subject)
        .put("referenceAnswer", referenceAnswer)
        .put("maxScore", new BigDecimal(maxScore));
  }

  private static java.util.Set<String> fieldNames(ObjectNode node) {
    var fields = new HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
