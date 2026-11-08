package app.deskgrade.core.application.grading;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.error.DeskGradeException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class GradeValidatorTest {
  private final QuestionDefinition question =
      new QuestionDefinition(
          "translation",
          1,
          "合成原文",
          "合成参考答案",
          new BigDecimal("4"),
          List.of(rule("meaning", "0", "2"), rule("shi", "0", "1"), rule("yi", "0", "1")));

  private static ScoringCriterion rule(String id, String... allowed) {
    return new ScoringCriterion(
        id, "按语义独立判断", java.util.Arrays.stream(allowed).map(BigDecimal::new).toList());
  }

  private static GradeResult.CriterionResult c(String id, String score) {
    return new GradeResult.CriterionResult(
        id, score == null ? null : new BigDecimal(score), "证据", "依据");
  }

  private static GradeResult scored(GradeResult.CriterionResult... criteria) {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "答案",
        List.of(criteria),
        List.of());
  }

  @Test
  void meaningZeroDoesNotCascadeIntoOtherIndependentPoints() {
    assertEquals(
        0,
        new BigDecimal("2")
            .compareTo(
                GradeValidator.validate(
                        question, scored(c("yi", "1"), c("meaning", "0"), c("shi", "1.0")))
                    .totalScore()));
  }

  @Test
  void forbiddenHalfOrIntermediatePointFailsWithoutRounding() {
    for (String invalid : List.of("1", "0.5", "2.000000000000000000001", "0E-99999999")) {
      assertThrows(
          DeskGradeException.class,
          () ->
              GradeValidator.validate(
                  question, scored(c("meaning", invalid), c("shi", "1"), c("yi", "1"))));
    }
  }

  @Test
  void missingExtraDuplicateAndUnknownCriteriaFail() {
    assertThrows(
        DeskGradeException.class,
        () -> GradeValidator.validate(question, scored(c("meaning", "2"), c("shi", "1"))));
    assertThrows(
        DeskGradeException.class,
        () ->
            GradeValidator.validate(
                question, scored(c("meaning", "2"), c("shi", "1"), c("yi", "1"), c("extra", "1"))));
    assertThrows(
        DeskGradeException.class,
        () ->
            GradeValidator.validate(
                question, scored(c("meaning", "2"), c("shi", "1"), c("shi", "1"))));
    assertThrows(
        DeskGradeException.class,
        () ->
            GradeValidator.validate(
                question, scored(c("meaning", "2"), c("shi", "1"), c("unknown", "1"))));
  }

  @Test
  void unableHasNoSubmittableTotal() {
    var unable =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            null,
            List.of(c("meaning", null), c("shi", null), c("yi", null)),
            List.of("无法辨认关键字"));
    assertNull(GradeValidator.validate(question, unable).totalScore());
  }

  @Test
  void clearCompleteBlankHasAnExactZeroOnlyWhenEveryCriterionAllowsZero() {
    var blank =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "",
            List.of(
                new GradeResult.CriterionResult("meaning", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("shi", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("yi", BigDecimal.ZERO, null, "未作答")),
            List.of());

    assertEquals(BigDecimal.ZERO, GradeValidator.validate(question, blank).totalScore());

    var noZeroQuestion =
        new QuestionDefinition(
            "no-zero", 1, "原文", "参考", BigDecimal.ONE, List.of(rule("required", "1")));
    var inventedZero =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "",
            List.of(new GradeResult.CriterionResult("required", BigDecimal.ZERO, null, "未作答")),
            List.of());
    assertThrows(
        DeskGradeException.class, () -> GradeValidator.validate(noZeroQuestion, inventedZero));

    var ambiguousBlank =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.PARTIAL,
            "",
            List.of(
                new GradeResult.CriterionResult("meaning", new BigDecimal("2"), null, "不应补分"),
                new GradeResult.CriterionResult("shi", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("yi", BigDecimal.ZERO, null, "未作答")),
            List.of());
    assertThrows(DeskGradeException.class, () -> GradeValidator.validate(question, ambiguousBlank));

    var whitespaceInsteadOfDeclaredBlank =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            " \n",
            List.of(
                new GradeResult.CriterionResult("meaning", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("shi", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("yi", BigDecimal.ZERO, null, "未作答")),
            List.of());
    assertThrows(
        DeskGradeException.class,
        () -> GradeValidator.validate(question, whitespaceInsteadOfDeclaredBlank));
  }

  @Test
  void declaredBlankRejectsStudentEvidenceWithoutChangingScores() {
    for (String evidence : List.of("学生写有答案", "  可见一个否定词\n", "0")) {
      var contradictoryBlank =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              "",
              List.of(
                  new GradeResult.CriterionResult("meaning", BigDecimal.ZERO, null, "未作答"),
                  new GradeResult.CriterionResult("shi", BigDecimal.ZERO, evidence, "未作答"),
                  new GradeResult.CriterionResult("yi", BigDecimal.ZERO, null, "未作答")),
              List.of());

      assertThrows(
          DeskGradeException.class, () -> GradeValidator.validate(question, contradictoryBlank));
      assertEquals(BigDecimal.ZERO, contradictoryBlank.criteria().get(1).awarded());
      assertEquals(evidence, contradictoryBlank.criteria().get(1).evidence());
    }
  }

  @Test
  void declaredBlankStillAcceptsAbsentOrEmptyEvidence() {
    var blank =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "",
            List.of(
                new GradeResult.CriterionResult("meaning", BigDecimal.ZERO, null, "未作答"),
                new GradeResult.CriterionResult("shi", BigDecimal.ZERO, "", "未作答"),
                new GradeResult.CriterionResult("yi", BigDecimal.ZERO, " \n\t", "未作答")),
            List.of());

    assertEquals(BigDecimal.ZERO, GradeValidator.validate(question, blank).totalScore());
  }

  @Test
  void nonblankWrongAnswerMayHaveEvidenceAndAnExactZero() {
    var wrongAnswer = scored(c("meaning", "0"), c("shi", "0"), c("yi", "0"));

    assertEquals(BigDecimal.ZERO, GradeValidator.validate(question, wrongAnswer).totalScore());
    assertEquals("证据", wrongAnswer.criteria().getFirst().evidence());
  }

  @Test
  void decimalSumIsExactAndRuleGridIsValidated() {
    var decimal =
        new QuestionDefinition(
            "decimal",
            1,
            "原文",
            "参考",
            new BigDecimal("0.3"),
            List.of(rule("a", "0", "0.1"), rule("b", "0", "0.2")));
    assertEquals(
        new BigDecimal("0.3"),
        GradeValidator.validate(decimal, scored(c("a", "0.1"), c("b", "0.2"))).totalScore());
    assertThrows(
        DeskGradeException.class,
        () -> new Rubric(decimal, "原始规则", new BigDecimal("0.2"), false, false));
  }

  @Test
  void duplicateRuleScoresAcrossDifferentScalesAndWrongMaximumFail() {
    var duplicate =
        new QuestionDefinition(
            "q", 1, "原文", "参考", BigDecimal.ONE, List.of(rule("a", "0", "1", "1.0")));
    assertThrows(DeskGradeException.class, () -> GradeValidator.validateQuestion(duplicate));
    var wrong =
        new QuestionDefinition("q", 1, "原文", "参考", BigDecimal.TEN, List.of(rule("a", "0", "1")));
    assertThrows(DeskGradeException.class, () -> GradeValidator.validateQuestion(wrong));
  }

  @Test
  void missingSourceIsSupportedWithoutLegacyAcceptance() {
    var missing =
        new QuestionDefinition(
            "q", 1, "[原文缺失]", "参考", BigDecimal.ONE, List.of(rule("a", "0", "1")));
    assertDoesNotThrow(
        () -> new Rubric(missing, "规则", BigDecimal.ONE, true, false).validateReady());
    assertDoesNotThrow(() -> new Rubric(missing, "规则", BigDecimal.ONE, true, true).validateReady());
  }
}
