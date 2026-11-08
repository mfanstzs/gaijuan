package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;

/** Immutable question configuration, not yet a business-validated rubric. */
public record QuestionDefinition(
    String id,
    long revision,
    String prompt,
    String standardAnswer,
    BigDecimal maximumScore,
    List<ScoringCriterion> criteria) {
  public QuestionDefinition {
    if (id == null
        || id.isBlank()
        || revision < 1
        || prompt == null
        || prompt.isBlank()
        || standardAnswer == null
        || maximumScore == null
        || maximumScore.signum() < 0
        || criteria == null
        || criteria.isEmpty()
        || criteria.stream().anyMatch(value -> value == null)) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    criteria = List.copyOf(criteria);
  }
}
