package app.deskgrade.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.error.DeskGradeException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GradingResponseTest {
  @Test
  void costBoundariesCollapseHostileZeroScalesAndRejectHugeExponents() {
    var usage = new GradingResponse.Usage(1L, 1L, new BigDecimal("0E-99999999"));
    assertEquals(BigDecimal.ZERO, usage.estimatedCost());

    assertThrows(
        DeskGradeException.class,
        () -> new GradingResponse.Usage(1L, 1L, new BigDecimal("1E-99999999")));
    assertThrows(
        DeskGradeException.class,
        () -> new GradingResponse.Usage(1L, 1L, new BigDecimal("1E+99999999")));
  }

  @Test
  void providerPricesCannotCreateUnboundedCostArithmetic() {
    var safe = new ProviderProfile.Pricing("CNY", new BigDecimal("0E-99999999"), null);
    assertEquals(BigDecimal.ZERO, safe.inputPerMillion());
    assertThrows(
        DeskGradeException.class,
        () -> new ProviderProfile.Pricing("CNY", new BigDecimal("1E-99999999"), BigDecimal.ONE));
  }
}
