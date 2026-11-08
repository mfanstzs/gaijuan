package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.EvidenceStore;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControllerFallbackTest {
  @TempDir Path directory;

  @Test
  void successfulFallbackDoesNotLeaveThePrimaryFailureInFinalStatus() throws Exception {
    var platform = new FakePlatform(1);
    var provider =
        provider(
            request -> {
              throw new app.deskgrade.adapters.commercial.GatewayException(
                  app.deskgrade.adapters.commercial.GatewayException.Code.MODEL_RESULT_INVALID);
            },
            (primary, request) -> scored(request));
    try (var journal = new H2SessionJournal(directory.resolve("diagnostic-cleared"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      assertEquals(2, controller.status().modelCalls());
      assertNull(controller.status().failureDiagnostic());
    }
  }

  @Test
  void networkFailureHasPreciseDiagnosticAndNeverCallsFallback() throws Exception {
    var platform = new FakePlatform(1);
    var fallbackCalls = new AtomicInteger();
    var provider =
        provider(
            request -> {
              throw new app.deskgrade.adapters.model.ProviderException(
                  app.deskgrade.adapters.model.ProviderException.Failure.TIMEOUT,
                  null,
                  app.deskgrade.adapters.model.ProviderException.Diagnostic.MODEL_REQUEST_TIMEOUT);
            },
            (primary, request) -> {
              fallbackCalls.incrementAndGet();
              return scored(request);
            });
    try (var journal = new H2SessionJournal(directory.resolve("network-no-retry"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.MODEL_FAILED, controller.status().reason());
      assertEquals(
          List.of("MODEL_REQUEST_TIMEOUT"), controller.status().failureDiagnostic().codes());
      assertEquals(SessionState.GRADING, controller.status().failureDiagnostic().phase());
      assertEquals(1, controller.status().modelCalls());
      assertEquals(0, fallbackCalls.get());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void fallbackFailureShowsItsOwnDiagnosticRatherThanThePrimaryFailure() throws Exception {
    var platform = new FakePlatform(1);
    var provider =
        provider(
            request -> {
              throw new app.deskgrade.adapters.commercial.GatewayException(
                  app.deskgrade.adapters.commercial.GatewayException.Code.MODEL_RESULT_INVALID);
            },
            (primary, request) -> {
              throw new app.deskgrade.adapters.model.ProviderException(
                  app.deskgrade.adapters.model.ProviderException.Failure.NETWORK,
                  null,
                  app.deskgrade.adapters.model.ProviderException.Diagnostic.MODEL_DNS_FAILURE);
            });
    try (var journal = new H2SessionJournal(directory.resolve("fallback-diagnostic"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.FALLBACK_FAILED, controller.status().reason());
      assertEquals(List.of("MODEL_DNS_FAILURE"), controller.status().failureDiagnostic().codes());
      assertEquals(2, controller.status().modelCalls());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void unablePrimaryUsesDifferentBoundProfileAndPersistsTwoModelCalls() throws Exception {
    var platform = new FakePlatform(1);
    var primaryId = new AtomicReference<UUID>();
    var fallbackId = new AtomicReference<UUID>();
    FallbackGradingProvider provider =
        provider(
            request -> {
              primaryId.set(request.requestId());
              return unable(request);
            },
            (primary, request) -> {
              fallbackId.set(request.requestId());
              return scored(request);
            });
    try (var journal = new H2SessionJournal(directory.resolve("success"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      assertEquals(2, controller.status().modelCalls());
      assertEquals(1, controller.status().confirmedSubmissions());
      assertEquals(FallbackGradingProvider.fallbackRequestId(primaryId.get()), fallbackId.get());
      String audit =
          journal.attemptAuditJson(
              journal.attempts(controller.status().runId()).getFirst().attemptId());
      assertTrue(audit.contains("\"profileId\" : \"mock-A\""), audit);
      assertTrue(audit.contains("\"profileId\" : \"mock-B\""), audit);
      assertEquals(List.of(new java.math.BigDecimal("4")), platform.ledger.get(1));
    }
  }

  @Test
  void identityChangeAfterPrimaryStillUsesFrozenImageButStopsBeforeScoreInput() throws Exception {
    var platform = new FakePlatform(1);
    var fallbackCalls = new AtomicInteger();
    var primaryImage = new AtomicReference<byte[]>();
    FallbackGradingProvider provider =
        provider(
            request -> {
              primaryImage.set(request.answerImage());
              platform.identityEpoch++;
              return unable(request);
            },
            (primary, request) -> {
              fallbackCalls.incrementAndGet();
              assertArrayEquals(primaryImage.get(), request.answerImage());
              return scored(request);
            });
    try (var journal = new H2SessionJournal(directory.resolve("identity"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.TARGET_CHANGED, controller.status().reason());
      assertEquals(2, controller.status().modelCalls());
      assertEquals(1, fallbackCalls.get());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void strictPrimaryFailureUsesAlternateOnceAndSecondUnableStopsWithoutInput() throws Exception {
    var platform = new FakePlatform(1);
    var fallbackCalls = new AtomicInteger();
    var primaryImage = new AtomicReference<byte[]>();
    FallbackGradingProvider provider =
        new FallbackGradingProvider() {
          @Override
          public Optional<String> fallbackProfileId() {
            return Optional.of("mock-B");
          }

          @Override
          public CompletionStage<GradingResponse> grade(
              GradingRequest request, CancellationToken cancellationToken) {
            primaryImage.set(request.answerImage());
            return CompletableFuture.failedFuture(
                new app.deskgrade.adapters.commercial.GatewayException(
                    app.deskgrade.adapters.commercial.GatewayException.Code.MODEL_RESULT_INVALID));
          }

          @Override
          public CompletionStage<GradingResponse> gradeFallback(
              UUID primaryRequestId, GradingRequest request, CancellationToken cancellationToken) {
            fallbackCalls.incrementAndGet();
            assertArrayEquals(primaryImage.get(), request.answerImage());
            return CompletableFuture.completedFuture(unable(request));
          }
        };
    try (var journal = new H2SessionJournal(directory.resolve("failed"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.FALLBACK_FAILED, controller.status().reason());
      assertEquals(2, controller.status().modelCalls());
      assertEquals(1, fallbackCalls.get());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void invalidPolicyOrCatalogResponseStopsWithoutTryingTheFallbackModel() throws Exception {
    var platform = new FakePlatform(1);
    var fallbackCalls = new AtomicInteger();
    FallbackGradingProvider provider =
        new FallbackGradingProvider() {
          @Override
          public Optional<String> fallbackProfileId() {
            return Optional.of("mock-B");
          }

          @Override
          public CompletionStage<GradingResponse> grade(
              GradingRequest request, CancellationToken cancellationToken) {
            return CompletableFuture.failedFuture(
                new app.deskgrade.adapters.commercial.GatewayException(
                    app.deskgrade.adapters.commercial.GatewayException.Code.INVALID_RESPONSE));
          }

          @Override
          public CompletionStage<GradingResponse> gradeFallback(
              UUID primaryRequestId, GradingRequest request, CancellationToken cancellationToken) {
            fallbackCalls.incrementAndGet();
            return CompletableFuture.completedFuture(scored(request));
          }
        };
    try (var journal = new H2SessionJournal(directory.resolve("policy-invalid"));
        var controller = controller(platform, journal, provider)) {
      controller.start();
      await(controller, SessionState.STOPPED);
      assertEquals(PauseReason.MODEL_FAILED, controller.status().reason());
      controller.resume().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, controller.status().modelCalls());
      assertEquals(0, fallbackCalls.get());
      assertEquals(0, platform.inputs.get());
    }
  }

  private SessionController controller(
      FakePlatform platform, H2SessionJournal journal, FallbackGradingProvider provider) {
    EvidenceStore evidence = (run, attempt, capture) -> "synthetic.png";
    return new SessionController(
        platform,
        journal,
        evidence,
        provider,
        result -> GradeValidator.validate(settings(1).question(), result).totalScore(),
        settings(1),
        Clock.systemUTC(),
        () -> true,
        ignored -> {});
  }

  private static FallbackGradingProvider provider(
      java.util.function.Function<GradingRequest, GradingResponse> primary,
      java.util.function.BiFunction<UUID, GradingRequest, GradingResponse> fallback) {
    return new FallbackGradingProvider() {
      @Override
      public Optional<String> fallbackProfileId() {
        return Optional.of("mock-B");
      }

      @Override
      public CompletionStage<GradingResponse> grade(
          GradingRequest request, CancellationToken cancellationToken) {
        return CompletableFuture.completedFuture(primary.apply(request));
      }

      @Override
      public CompletionStage<GradingResponse> gradeFallback(
          UUID primaryRequestId, GradingRequest request, CancellationToken cancellationToken) {
        assertEquals(
            FallbackGradingProvider.fallbackRequestId(primaryRequestId), request.requestId());
        return CompletableFuture.completedFuture(fallback.apply(primaryRequestId, request));
      }
    };
  }

  private static GradingResponse scored(GradingRequest request) {
    return new GradingResponse(
        request.requestId(), GradingResponse.Source.MOCK, RESULT, Instant.now());
  }

  private static GradingResponse unable(GradingRequest request) {
    return new GradingResponse(
        request.requestId(),
        GradingResponse.Source.MOCK,
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.PARTIAL,
            null,
            List.of(new GradeResult.CriterionResult("p", null, null, "无法可靠辨认")),
            List.of("图片不完整")),
        Instant.now(),
        null,
        Duration.ofMillis(1));
  }
}
