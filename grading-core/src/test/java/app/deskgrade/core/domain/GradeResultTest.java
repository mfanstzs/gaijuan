package app.deskgrade.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GradeResultTest {
  @Test
  void unablePreservesMissingScoreInsteadOfConvertingItToZero() {
    GradeResult result =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            null,
            List.of(
                new GradeResult.CriterionResult(
                    "meaning", null, null, "Synthetic unreadable answer")),
            List.of("Synthetic fixture could not be read"));

    assertEquals(GradeResult.Status.UNABLE, result.status());
    assertNull(result.criteria().getFirst().awarded());
  }

  @Test
  void unableCannotCarryEvenAZeroAward() {
    DeskGradeException error =
        assertThrows(
            DeskGradeException.class,
            () ->
                new GradeResult(
                    GradeResult.Status.UNABLE,
                    GradeResult.Readability.UNREADABLE,
                    null,
                    List.of(
                        new GradeResult.CriterionResult(
                            "meaning", BigDecimal.ZERO, null, "Unreadable")),
                    List.of("Unable to grade")));

    assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
  }

  @Test
  void scoredCannotCarryAMissingAward() {
    assertThrows(
        DeskGradeException.class,
        () ->
            new GradeResult(
                GradeResult.Status.SCORED,
                GradeResult.Readability.CLEAR,
                "Synthetic answer",
                List.of(
                    new GradeResult.CriterionResult(
                        "meaning", null, "Synthetic evidence", "Reason")),
                List.of()));
  }

  @Test
  void decimalAwardsRetainExactPrecisionAndListsAreImmutable() {
    BigDecimal award = new BigDecimal("0.10");
    var mutableCriteria =
        new ArrayList<>(
            List.of(
                new GradeResult.CriterionResult(
                    "meaning", award, "Synthetic evidence", "Synthetic reason")));
    GradeResult result =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "Synthetic answer",
            mutableCriteria,
            List.of());
    mutableCriteria.clear();

    assertEquals(new BigDecimal("0.10"), result.criteria().getFirst().awarded());
    assertEquals(
        new BigDecimal("0.30"), result.criteria().getFirst().awarded().add(new BigDecimal("0.20")));
    assertThrows(UnsupportedOperationException.class, () -> result.criteria().clear());
  }
}
