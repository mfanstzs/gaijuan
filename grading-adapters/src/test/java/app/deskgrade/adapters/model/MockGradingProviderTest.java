package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.CancellationToken;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MockGradingProviderTest {
  private static final Instant NOW = Instant.parse("2026-09-10T01:02:03Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void explicitlyConstructedMockReturnsOnlyTheSuppliedFixtureAndInjectedTime() {
    GradeResult synthetic = syntheticResult();
    MockGradingProvider provider = new MockGradingProvider(synthetic, CLOCK, Runnable::run);
    GradingRequest request = request();

    GradingResponse response =
        provider.grade(request, CancellationToken.none()).toCompletableFuture().join();

    assertEquals(GradingResponse.Source.MOCK, response.source());
    assertSame(synthetic, response.result());
    assertEquals(request.requestId(), response.requestId());
    assertEquals(NOW, response.completedAt());
  }

  @Test
  void preCancelledRequestDoesNotScheduleGrading() {
    Queue<Runnable> work = new ArrayDeque<>();
    MockGradingProvider provider = new MockGradingProvider(syntheticResult(), CLOCK, work::add);

    CompletionException error =
        assertThrows(
            CompletionException.class,
            () -> provider.grade(request(), () -> true).toCompletableFuture().join());

    assertEquals(ErrorCode.CANCELLED, ((DeskGradeException) error.getCause()).code());
    assertEquals(0, work.size());
  }

  @Test
  void cancellationWhileQueuedDiscardsTheResponse() {
    Queue<Runnable> work = new ArrayDeque<>();
    AtomicBoolean cancelled = new AtomicBoolean();
    MockGradingProvider provider = new MockGradingProvider(syntheticResult(), CLOCK, work::add);

    var future = provider.grade(request(), cancelled::get).toCompletableFuture();
    assertFalse(future.isDone());
    cancelled.set(true);
    work.remove().run();

    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertEquals(ErrorCode.CANCELLED, ((DeskGradeException) error.getCause()).code());
  }

  @Test
  void executorFailureIsSanitizedAndDoesNotBecomeAFakeSuccess() {
    Executor rejected =
        command -> {
          throw new RejectedExecutionException("synthetic-secret-must-not-escape");
        };
    MockGradingProvider provider = new MockGradingProvider(syntheticResult(), CLOCK, rejected);

    CompletionException completion =
        assertThrows(
            CompletionException.class,
            () -> provider.grade(request(), CancellationToken.none()).toCompletableFuture().join());
    DeskGradeException failure = (DeskGradeException) completion.getCause();

    assertEquals(ErrorCode.PROVIDER_FAILURE, failure.code());
    assertEquals(ErrorCode.PROVIDER_FAILURE.safeMessage(), failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void unableFixtureRemainsUnableWithNoAward() {
    GradeResult unable =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            null,
            List.of(
                new GradeResult.CriterionResult(
                    "meaning", null, null, "Synthetic unreadable answer")),
            List.of("Synthetic unreadable fixture"));
    MockGradingProvider provider = new MockGradingProvider(unable, CLOCK, Runnable::run);

    GradingResponse response =
        provider.grade(request(), CancellationToken.none()).toCompletableFuture().join();

    assertEquals(GradingResponse.Source.MOCK, response.source());
    assertEquals(GradeResult.Status.UNABLE, response.result().status());
    assertNull(response.result().criteria().getFirst().awarded());
  }

  private static GradeResult syntheticResult() {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "Synthetic answer",
        List.of(
            new GradeResult.CriterionResult(
                "meaning", new BigDecimal("0.5"), "Synthetic evidence", "Synthetic reason")),
        List.of());
  }

  private static GradingRequest request() {
    QuestionDefinition question =
        new QuestionDefinition(
            "synthetic-question",
            1,
            "Synthetic prompt",
            "Synthetic answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "meaning",
                    "Synthetic criterion",
                    List.of(BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE))));
    return new GradingRequest(UUID.randomUUID(), question, new byte[] {1, 2, 3});
  }
}
