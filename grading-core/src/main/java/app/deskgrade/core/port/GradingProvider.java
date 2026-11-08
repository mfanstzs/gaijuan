package app.deskgrade.core.port;

import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import java.util.concurrent.CompletionStage;

/** Asynchronous, read-only grading boundary. Implementations receive a java.time.Clock. */
public interface GradingProvider {
  CompletionStage<GradingResponse> grade(
      GradingRequest request, CancellationToken cancellationToken);
}
