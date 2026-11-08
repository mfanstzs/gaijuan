package app.deskgrade.core.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.deskgrade.core.error.DeskGradeException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GradingRequestTest {
  @Test
  void requestDefensivelyCopiesTheAnswerImageAndRubricLists() {
    byte[] image = {1, 2, 3};
    var allowedScores = new ArrayList<>(List.of(BigDecimal.ZERO, new BigDecimal("0.5")));
    ScoringCriterion criterion =
        new ScoringCriterion("meaning", "Synthetic criterion", allowedScores);
    var criteria = new ArrayList<>(List.of(criterion));
    QuestionDefinition question =
        new QuestionDefinition(
            "synthetic-question",
            1,
            "Synthetic prompt",
            "Synthetic answer",
            new BigDecimal("0.5"),
            criteria);
    GradingRequest request = new GradingRequest(UUID.randomUUID(), question, image);

    image[0] = 9;
    request.answerImage()[1] = 9;
    allowedScores.clear();
    criteria.clear();

    assertArrayEquals(new byte[] {1, 2, 3}, request.answerImage());
    assertEquals(1, question.criteria().size());
    assertEquals(List.of(BigDecimal.ZERO, new BigDecimal("0.5")), criterion.allowedScores());
    assertThrows(UnsupportedOperationException.class, () -> question.criteria().clear());
    assertThrows(UnsupportedOperationException.class, () -> criterion.allowedScores().clear());
  }

  @Test
  void emptyImageIsRejectedBeforeItCanProduceAResult() {
    QuestionDefinition question =
        new QuestionDefinition(
            "synthetic-question",
            1,
            "Synthetic prompt",
            "Synthetic answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "meaning", "Synthetic criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));

    assertThrows(
        DeskGradeException.class,
        () -> new GradingRequest(UUID.randomUUID(), question, new byte[0]));
  }
}
