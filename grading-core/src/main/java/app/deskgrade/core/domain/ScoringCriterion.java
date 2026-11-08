package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;

/** Initial rubric value; cross-field rubric validation is introduced in M3. */
public record ScoringCriterion(String id, String description, List<BigDecimal> allowedScores) {
  public ScoringCriterion {
    if (id == null
        || id.isBlank()
        || description == null
        || description.isBlank()
        || allowedScores == null
        || allowedScores.isEmpty()
        || allowedScores.stream().anyMatch(value -> value == null || value.signum() < 0)) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    allowedScores = List.copyOf(allowedScores);
  }
}
