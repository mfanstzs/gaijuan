package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** M0 record of grading observations; it does not assert any platform submission. */
public record RunSnapshot(UUID runId, Instant recordedAt, List<GradingResponse> responses) {
  public RunSnapshot {
    if (runId == null
        || recordedAt == null
        || responses == null
        || responses.stream().anyMatch(value -> value == null)) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    responses = List.copyOf(responses);
  }
}
