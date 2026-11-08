package app.deskgrade.core.domain.model;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;

/** Immutable versioned source, reference, original rules and exact score grid. */
public record Rubric(
    QuestionDefinition question,
    String rawRubric,
    BigDecimal scoreStep,
    boolean requiresSourceText,
    boolean missingSourceAccepted) {
  public Rubric {
    if (question == null
        || rawRubric == null
        || rawRubric.isBlank()
        || rawRubric.length() > 64000
        || scoreStep == null
        || scoreStep.signum() <= 0
        || scoreStep.scale() > 8) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    GradeValidator.validateQuestion(question);
    for (var criterion : question.criteria()) {
      for (var value : criterion.allowedScores()) {
        if (value.remainder(scoreStep).signum() != 0) {
          throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
        }
      }
    }
  }

  public void validateReady() {
    // DeskGrade grades from the student's answer image, reference answer and fixed rubric.  A
    // question-source image/text is optional in the supported workflow, including for older saved
    // rubrics that still carry the legacy requiresSourceText flag.  Keeping that legacy flag in the
    // wire format preserves backwards compatibility, but it must not block an otherwise complete
    // grading configuration.
    if (question.standardAnswer().isBlank()) {
      throw new DeskGradeException(ErrorCode.CONFIGURATION_REQUIRED);
    }
  }
}
