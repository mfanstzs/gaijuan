package app.deskgrade.adapters.model;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.GradingProvider;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit synthetic fixture provider. It performs no network request and is never an API fallback.
 * The caller supplies and owns the executor, clock, and fixed synthetic grading data.
 */
public final class MockGradingProvider implements GradingProvider {
  private static final Logger LOG = LoggerFactory.getLogger(MockGradingProvider.class);

  private final GradeResult syntheticResult;
  private final Clock clock;
  private final Executor executor;

  public MockGradingProvider(GradeResult syntheticResult, Clock clock, Executor executor) {
    if (syntheticResult == null || clock == null || executor == null) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    this.syntheticResult = syntheticResult;
    this.clock = clock;
    this.executor = executor;
  }

  @Override
  public CompletionStage<GradingResponse> grade(
      GradingRequest request, CancellationToken cancellationToken) {
    if (request == null || cancellationToken == null) {
      return CompletableFuture.failedFuture(new DeskGradeException(ErrorCode.INVALID_ARGUMENT));
    }
    if (cancellationToken.isCancellationRequested()) {
      return CompletableFuture.failedFuture(new DeskGradeException(ErrorCode.CANCELLED));
    }
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            cancellationToken.throwIfCancellationRequested();
            GradingResponse response =
                new GradingResponse(
                    request.requestId(),
                    GradingResponse.Source.MOCK,
                    syntheticResult,
                    clock.instant());
            cancellationToken.throwIfCancellationRequested();
            LOG.atInfo()
                .addKeyValue("event", "grading_completed")
                .addKeyValue("source", "MOCK")
                .addKeyValue("request_id", request.requestId())
                .log("Synthetic grading fixture completed");
            return response;
          },
          executor);
    } catch (RejectedExecutionException ignored) {
      return CompletableFuture.failedFuture(new DeskGradeException(ErrorCode.PROVIDER_FAILURE));
    }
  }
}
