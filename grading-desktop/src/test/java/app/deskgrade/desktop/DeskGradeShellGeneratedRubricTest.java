package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeskGradeShellGeneratedRubricTest {
  @Test
  void pendingReceiptOnlyMatchesTheGeneratedPairAndOriginatingAccount() {
    var generatedPoint =
        new ScoringCriterion("P1", "答出守方有利", List.of(BigDecimal.ZERO, BigDecimal.ONE));
    var pending =
        new DeskGradeShell.PendingGeneratedRubric(
            UUID.randomUUID(),
            42,
            " 历史材料题 ",
            " 标准答案 ",
            new BigDecimal("1.0"),
            new BigDecimal("1.0"),
            List.of(generatedPoint));
    var generatedCandidate =
        new Rubric(
            new QuestionDefinition(
                "Q1", 8, "[原文缺失]", "标准答案", BigDecimal.ONE, List.of(generatedPoint)),
            "P1 | 0,1 | 答出守方有利",
            BigDecimal.ONE,
            false,
            true);

    assertTrue(pending.matches(generatedCandidate, "历史材料题", 42));
    assertFalse(pending.matches(generatedCandidate, "语文材料题", 42));
    assertFalse(pending.matches(generatedCandidate, "历史材料题", 43));

    var editedPoint =
        new ScoringCriterion("P1", "人工改成另一条规则", List.of(BigDecimal.ZERO, BigDecimal.ONE));
    var editedCandidate =
        new Rubric(
            new QuestionDefinition("Q1", 9, "[原文缺失]", "标准答案", BigDecimal.ONE, List.of(editedPoint)),
            "P1 | 0,1 | 人工改成另一条规则",
            BigDecimal.ONE,
            false,
            true);
    assertFalse(pending.matches(editedCandidate, "历史材料题", 42));
  }
}
