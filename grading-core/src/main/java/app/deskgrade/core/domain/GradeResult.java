package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider data only. This is not a validated score and provides no total or input permission.
 * Schema and rubric completeness/allowed-value validation belong to M3.
 */
public record GradeResult(
    Status status,
    Readability readability,
    String studentText,
    List<CriterionResult> criteria,
    List<String> issues) {
  public GradeResult {
    if (status == null
        || readability == null
        || criteria == null
        || criteria.isEmpty()
        || criteria.stream().anyMatch(value -> value == null)
        || issues == null
        || issues.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    if (status == Status.UNABLE
        && (issues.isEmpty() || criteria.stream().anyMatch(value -> value.awarded() != null))) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    if (status == Status.SCORED
        && (studentText == null
            || readability == Readability.UNREADABLE
            || !issues.isEmpty()
            || criteria.stream().anyMatch(value -> value.awarded() == null))) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    criteria = List.copyOf(criteria);
    issues = List.copyOf(issues);
  }

  public enum Status {
    SCORED,
    UNABLE
  }

  public enum Readability {
    CLEAR,
    PARTIAL,
    UNREADABLE
  }

  public record CriterionResult(
      String criterionId, BigDecimal awarded, String evidence, String reason) {
    public CriterionResult {
      if (criterionId == null
          || criterionId.isBlank()
          || (awarded != null && awarded.signum() < 0)
          || reason == null
          || reason.isBlank()) {
        throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
      }
    }
  }
}
