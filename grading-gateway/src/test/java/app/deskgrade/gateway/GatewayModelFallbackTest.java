package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.port.FallbackGradingProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayModelFallbackTest {
  @TempDir Path temp;

  @Test
  void unablePrimaryPermitsExactlyOneBoundAlternateWithIndependentDebitAndReceipt() {
    try (var store = new GatewayStore(temp.resolve("unable"), Clock.systemUTC())) {
      UUID user = funded(store, 3);
      var primary = GatewayFixtures.profile();
      var alternate = GatewayFixtures.profile("alternate-v1", "different-model", true);
      var dispatched = new ArrayList<String>();
      var service =
          new GatewayModelService(
              store,
              List.of(primary, alternate),
              (profile, rubric, request, beforeDispatch) -> {
                beforeDispatch.run();
                dispatched.add(profile.id());
                return CompletableFuture.completedFuture(
                    profile.id().equals(primary.id())
                        ? unable(request)
                        : GatewayFixtures.response(request));
              });

      UUID primaryId = UUID.randomUUID();
      var primaryBody = GatewayFixtures.body(primaryId);
      var primaryReply = service.grade(user, primaryBody);
      assertEquals(200, primaryReply.status());
      assertEquals("UNABLE", primaryReply.body().path("result").path("status").asText());

      UUID fallbackId = FallbackGradingProvider.fallbackRequestId(primaryId);
      var fallbackBody = primaryBody.deepCopy();
      fallbackBody.put("requestId", fallbackId.toString());
      fallbackBody.put("profileId", alternate.id());
      fallbackBody.put("fallbackOfRequestId", primaryId.toString());
      var fallbackReply = service.grade(user, fallbackBody);

      assertEquals(200, fallbackReply.status());
      assertEquals("SCORED", fallbackReply.body().path("result").path("status").asText());
      assertEquals(List.of(primary.id(), alternate.id()), dispatched);
      assertEquals(1, store.account(user).balance());
      assertTrue(store.request(user, primaryId).charged());
      assertTrue(store.request(user, fallbackId).charged());
      assertNotEquals(
          store.request(user, primaryId).modelId(), store.request(user, fallbackId).modelId());

      var replay = service.grade(user, fallbackBody);
      assertEquals(200, replay.status());
      assertTrue(replay.body().path("replayed").asBoolean());
      assertEquals(1, store.account(user).balance());
      assertEquals(2, dispatched.size());

      var forged = fallbackBody.deepCopy().put("requestId", UUID.randomUUID().toString());
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(GatewayException.class, () -> service.grade(user, forged)).code());
      assertEquals(1, store.account(user).balance());
    }
  }

  @Test
  void onlyUnableOrStrictInvalidPrimaryIsEligibleForAlternateDispatch() {
    try (var store = new GatewayStore(temp.resolve("eligibility"), Clock.systemUTC())) {
      UUID user = funded(store, 8);
      var primary = GatewayFixtures.profile();
      var alternate = GatewayFixtures.profile("alternate-v1", "different-model", true);
      var service =
          new GatewayModelService(
              store,
              List.of(primary, alternate),
              (profile, rubric, request, beforeDispatch) -> {
                beforeDispatch.run();
                if (profile.id().equals(primary.id())) {
                  if (request.question().prompt().equals("1+1=?"))
                    return CompletableFuture.completedFuture(GatewayFixtures.response(request));
                  if (request.question().prompt().equals("network-fixture"))
                    return CompletableFuture.failedFuture(
                        new ProviderException(ProviderException.Failure.NETWORK));
                  if (request.question().prompt().equals("request-mismatch-fixture")) {
                    var response = GatewayFixtures.response(request);
                    return CompletableFuture.completedFuture(
                        new GradingResponse(
                            UUID.randomUUID(),
                            response.source(),
                            response.result(),
                            response.completedAt(),
                            response.usage(),
                            response.elapsed()));
                  }
                  return CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.INVALID_RESULT));
                }
                return CompletableFuture.completedFuture(GatewayFixtures.response(request));
              });

      UUID scoredId = UUID.randomUUID();
      var scored = GatewayFixtures.body(scoredId);
      assertEquals(200, service.grade(user, scored).status());
      var forbiddenFallback = fallback(scored, scoredId, alternate.id());
      assertEquals(
          GatewayException.Code.FALLBACK_NOT_ELIGIBLE,
          assertThrows(GatewayException.class, () -> service.grade(user, forbiddenFallback))
              .code());
      assertEquals(7, store.account(user).balance());

      UUID invalidId = UUID.randomUUID();
      var invalid = GatewayFixtures.body(invalidId);
      invalid.withObject("rubric").put("sourceText", "strict-invalid-fixture");
      var invalidReply = service.grade(user, invalid);
      assertEquals(502, invalidReply.status());
      assertEquals("MODEL_RESULT_INVALID", invalidReply.body().path("error").path("code").asText());
      var allowedFallback = fallback(invalid, invalidId, alternate.id());
      assertEquals(200, service.grade(user, allowedFallback).status());
      assertEquals(5, store.account(user).balance());

      UUID mismatchId = UUID.randomUUID();
      var mismatch = GatewayFixtures.body(mismatchId);
      mismatch.withObject("rubric").put("sourceText", "request-mismatch-fixture");
      var mismatchReply = service.grade(user, mismatch);
      assertEquals(502, mismatchReply.status());
      assertEquals(
          "MODEL_RESULT_INVALID", mismatchReply.body().path("error").path("code").asText());
      assertEquals(
          200, service.grade(user, fallback(mismatch, mismatchId, alternate.id())).status());
      assertEquals(3, store.account(user).balance());

      UUID networkId = UUID.randomUUID();
      var network = GatewayFixtures.body(networkId);
      network.withObject("rubric").put("sourceText", "network-fixture");
      assertEquals(409, service.grade(user, network).status());
      var networkFallback = fallback(network, networkId, alternate.id());
      assertEquals(
          GatewayException.Code.FALLBACK_NOT_ELIGIBLE,
          assertThrows(GatewayException.class, () -> service.grade(user, networkFallback)).code());
      assertEquals(2, store.account(user).balance());
    }
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode fallback(
      com.fasterxml.jackson.databind.node.ObjectNode primary, UUID primaryId, String profileId) {
    var result = primary.deepCopy();
    result.put("requestId", FallbackGradingProvider.fallbackRequestId(primaryId).toString());
    result.put("profileId", profileId);
    result.put("fallbackOfRequestId", primaryId.toString());
    return result;
  }

  private static GradingResponse unable(GradingRequest request) {
    return new GradingResponse(
        request.requestId(),
        GradingResponse.Source.MOCK,
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.PARTIAL,
            null,
            List.of(
                new GradeResult.CriterionResult(
                    "c1", null, null, "The visible answer cannot be read reliably")),
            List.of("The answer image is incomplete")),
        Instant.EPOCH,
        null,
        Duration.ofMillis(1));
  }

  private static UUID funded(GatewayStore store, long credits) {
    var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
    GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
    var user = store.register("alice", GatewayFixtures.password());
    store.adjust(admin.id(), UUID.randomUUID(), user.id(), credits, "synthetic allocation");
    return user.id();
  }
}
