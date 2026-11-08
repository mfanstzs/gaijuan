package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The source is assigned by the adapter, never read from model-generated content. */
public record GradingResponse(
    UUID requestId,
    Source source,
    GradeResult result,
    Instant completedAt,
    Usage usage,
    Duration elapsed) {
  public GradingResponse(UUID requestId, Source source, GradeResult result, Instant completedAt) {
    this(requestId, source, result, completedAt, null, null);
  }

  public GradingResponse {
    if (requestId == null || source == null || result == null || completedAt == null) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
  }

  public enum Source {
    MOCK,
    LIVE
  }

  /** Unknown usage and prices remain null; these numbers never authorize input. */
  public record Usage(Long inputTokens, Long outputTokens, BigDecimal estimatedCost) {
    public Usage {
      BigDecimal normalizedCost = estimatedCost == null ? null : estimatedCost.stripTrailingZeros();
      if ((inputTokens != null && inputTokens < 0)
          || (outputTokens != null && outputTokens < 0)
          || (normalizedCost != null
              && (normalizedCost.signum() < 0
                  || normalizedCost.scale() > 18
                  || normalizedCost.precision() - normalizedCost.scale() > 20))) {
        throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
      }
      if (estimatedCost != null
          && (estimatedCost.scale() > 18
              || estimatedCost.precision() - estimatedCost.scale() > 20)) {
        estimatedCost = normalizedCost;
      }
    }
  }
}
