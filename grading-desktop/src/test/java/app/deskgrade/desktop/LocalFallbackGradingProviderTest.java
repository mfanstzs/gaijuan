package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.*;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.GradingProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalFallbackGradingProviderTest {
  private static final QuestionDefinition QUESTION =
      new QuestionDefinition(
          "local-question",
          1,
          "[原文缺失]",
          "标准答案",
          BigDecimal.ONE,
          List.of(
              new ScoringCriterion("P1", "表达标准答案含义得1分", List.of(BigDecimal.ZERO, BigDecimal.ONE))));

  private static GradingRequest request(UUID id) {
    return new GradingRequest(id, QUESTION, new byte[] {1, 2, 3});
  }

  private static GradingResponse response(GradingRequest request, boolean unable) {
    var result =
        unable
            ? new GradeResult(
                GradeResult.Status.UNABLE,
                GradeResult.Readability.UNREADABLE,
                null,
                List.of(new GradeResult.CriterionResult("P1", null, null, "无法可靠读取")),
                List.of("无法可靠读取"))
            : new GradeResult(
                GradeResult.Status.SCORED,
                GradeResult.Readability.CLEAR,
                "标准答案",
                List.of(new GradeResult.CriterionResult("P1", BigDecimal.ONE, "标准答案", "意思正确")),
                List.of());
    return new GradingResponse(
        request.requestId(), GradingResponse.Source.LIVE, result, Instant.EPOCH);
  }

  @Test
  void alternateModelUsesSameAnswerOnlyOnceAfterUnable() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
    GradingProvider primary =
        (request, token) -> {
          primaryCalls.incrementAndGet();
          return CompletableFuture.completedFuture(response(request, true));
        };
    GradingProvider alternate =
        (request, token) -> {
          fallbackCalls.incrementAndGet();
          return CompletableFuture.completedFuture(response(request, false));
        };
    try (var provider = new LocalFallbackGradingProvider(primary, alternate, "alternate")) {
      UUID id = UUID.randomUUID();
      provider.grade(request(id), CancellationToken.none()).toCompletableFuture().join();
      UUID alternateId = FallbackGradingProvider.fallbackRequestId(id);
      assertThrows(
          CompletionException.class,
          () ->
              provider
                  .gradeFallback(
                      id,
                      new GradingRequest(alternateId, QUESTION, new byte[] {9, 8, 7}),
                      CancellationToken.none())
                  .toCompletableFuture()
                  .join());
      assertThrows(
          CompletionException.class,
          () ->
              provider
                  .gradeFallback(id, request(UUID.randomUUID()), CancellationToken.none())
                  .toCompletableFuture()
                  .join());
      assertEquals(0, fallbackCalls.get());
      assertEquals(
          alternateId,
          provider
              .gradeFallback(id, request(alternateId), CancellationToken.none())
              .toCompletableFuture()
              .join()
              .requestId());
      assertThrows(
          CompletionException.class,
          () ->
              provider
                  .gradeFallback(id, request(alternateId), CancellationToken.none())
                  .toCompletableFuture()
                  .join());
      assertEquals(1, primaryCalls.get());
      assertEquals(1, fallbackCalls.get());
    }
  }

  @Test
  void networkFailureOrValidGradeNeverAuthorizeAlternateModel() throws Exception {
    for (boolean networkFailure : List.of(true, false)) {
      AtomicInteger fallbackCalls = new AtomicInteger();
      GradingProvider primary =
          (request, token) ->
              networkFailure
                  ? CompletableFuture.failedFuture(
                      new ModelCallException(ModelCallException.Kind.TIMEOUT, null))
                  : CompletableFuture.completedFuture(response(request, false));
      GradingProvider alternate =
          (request, token) -> {
            fallbackCalls.incrementAndGet();
            return CompletableFuture.completedFuture(response(request, false));
          };
      try (var provider = new LocalFallbackGradingProvider(primary, alternate, "alternate")) {
        UUID id = UUID.randomUUID();
        try {
          provider.grade(request(id), CancellationToken.none()).toCompletableFuture().join();
        } catch (CompletionException expected) {
          assertTrue(networkFailure);
        }
        assertThrows(
            CompletionException.class,
            () ->
                provider
                    .gradeFallback(
                        id,
                        request(FallbackGradingProvider.fallbackRequestId(id)),
                        CancellationToken.none())
                    .toCompletableFuture()
                    .join());
        assertEquals(0, fallbackCalls.get());
      }
    }
  }

  @Test
  void cancellationAndMissingAlternateDoNotSendMoreCalls() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
    GradingProvider primary =
        (request, token) -> {
          primaryCalls.incrementAndGet();
          return CompletableFuture.completedFuture(response(request, true));
        };
    GradingProvider alternate =
        (request, token) -> {
          fallbackCalls.incrementAndGet();
          return CompletableFuture.completedFuture(response(request, false));
        };
    try (var provider = new LocalFallbackGradingProvider(primary, alternate, "alternate")) {
      UUID id = UUID.randomUUID();
      assertThrows(
          CompletionException.class,
          () -> provider.grade(request(id), () -> true).toCompletableFuture().join());
      assertEquals(0, primaryCalls.get());
      provider.grade(request(id), CancellationToken.none()).toCompletableFuture().join();
      assertThrows(
          CompletionException.class,
          () ->
              provider
                  .gradeFallback(
                      id, request(FallbackGradingProvider.fallbackRequestId(id)), () -> true)
                  .toCompletableFuture()
                  .join());
      assertEquals(0, fallbackCalls.get());
    }
    try (var provider = new LocalFallbackGradingProvider(primary, null, null)) {
      UUID id = UUID.randomUUID();
      provider.grade(request(id), CancellationToken.none()).toCompletableFuture().join();
      assertTrue(provider.fallbackProfileId().isEmpty());
      assertThrows(
          CompletionException.class,
          () ->
              provider
                  .gradeFallback(
                      id,
                      request(FallbackGradingProvider.fallbackRequestId(id)),
                      CancellationToken.none())
                  .toCompletableFuture()
                  .join());
    }
  }
}
