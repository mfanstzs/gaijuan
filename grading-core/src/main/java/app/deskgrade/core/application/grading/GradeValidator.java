package app.deskgrade.core.application.grading;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Local completeness and value validation. Model totals are never consumed. */
public final class GradeValidator {
  private GradeValidator() {}

  public static void validateQuestion(QuestionDefinition question) {
    if (question == null
        || question.criteria().size() > 100
        || question.maximumScore().signum() <= 0
        || question.maximumScore().precision() > 16
        || question.maximumScore().scale() > 8) fail();
    Set<String> ids = new HashSet<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (var criterion : question.criteria()) {
      if (criterion.id().length() > 100
          || !ids.add(criterion.id())
          || criterion.allowedScores().size() > 1000) fail();
      var values = new TreeSet<BigDecimal>();
      for (var value : criterion.allowedScores()) {
        if (value.precision() > 16 || value.scale() > 8 || value.scale() < -8 || !values.add(value))
          fail();
      }
      sum = sum.add(values.last());
    }
    if (sum.compareTo(question.maximumScore()) != 0) fail();
  }

  public static ValidatedGrade validate(QuestionDefinition question, GradeResult result) {
    validateQuestion(question);
    if (result == null
        || result.criteria().size() != question.criteria().size()
        || result.criteria().size() > 100
        || result.issues().size() > 20
        || length(result.studentText()) > 16000) fail();
    boolean declaredBlank =
        result.status() == GradeResult.Status.SCORED && result.studentText().isEmpty();
    if (result.status() == GradeResult.Status.SCORED
        && result.studentText().isBlank()
        && (!declaredBlank || result.readability() != GradeResult.Readability.CLEAR)) fail();
    for (var issue : result.issues()) if (length(issue) > 500) fail();
    Set<String> seen = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var value : result.criteria()) {
      if (!seen.add(value.criterionId())
          || length(value.criterionId()) > 100
          || length(value.evidence()) > 1000
          || length(value.reason()) > 500
          || (value.awarded() != null
              && (value.awarded().precision() > 16
                  || value.awarded().scale() > 8
                  || value.awarded().scale() < -8))) fail();
      var rule =
          question.criteria().stream().filter(c -> c.id().equals(value.criterionId())).findFirst();
      if (rule.isEmpty()) fail();
      if (result.status() == GradeResult.Status.SCORED) {
        if (value.awarded() == null
            || rule.orElseThrow().allowedScores().stream()
                .noneMatch(score -> score.compareTo(value.awarded()) == 0)) fail();
        if (declaredBlank
            && (value.awarded().compareTo(BigDecimal.ZERO) != 0
                || (value.evidence() != null && !value.evidence().isBlank()))) fail();
        total = total.add(value.awarded());
      } else if (value.awarded() != null) fail();
    }
    if (total.compareTo(question.maximumScore()) > 0) fail();
    return new ValidatedGrade(result, result.status() == GradeResult.Status.SCORED ? total : null);
  }

  private static int length(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }

  private static void fail() {
    throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
  }

  public record ValidatedGrade(GradeResult result, BigDecimal totalScore) {}
}
