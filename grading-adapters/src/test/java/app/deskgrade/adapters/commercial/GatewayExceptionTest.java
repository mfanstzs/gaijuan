package app.deskgrade.adapters.commercial;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayExceptionTest {
  @Test
  void legacyConstructorKeepsGatewayCategoryAsDiagnostic() {
    var failure = new GatewayException(GatewayException.Code.DISPATCH_UNKNOWN);
    assertEquals("GATEWAY_DISPATCH_UNKNOWN", failure.diagnosticCode());
    assertFalse(failure.fallbackEligible());
  }

  @Test
  void optionalUpstreamCodeIsFiniteAndDoesNotChangeAccountingOrFallbackEligibility() {
    UUID id = UUID.randomUUID();
    for (var code : ProviderException.Diagnostic.values()) {
      var failure =
          new GatewayException(
              GatewayException.Code.DISPATCH_UNKNOWN, 503, id, 9L, 1, false, code.name());
      assertEquals(code.name(), failure.diagnosticCode());
      assertEquals(id, failure.requestId());
      assertEquals(1, failure.chargedCredits());
      assertEquals(9L, failure.creditsRemaining());
      assertFalse(failure.fallbackEligible());
      assertNull(failure.getCause());
    }
    assertTrue(GatewayException.validDiagnosticCode("GATEWAY_WAIT_TIMEOUT"));
    assertTrue(GatewayException.validDiagnosticCode("GATEWAY_STORAGE_FAILURE"));
    assertFalse(GatewayException.validDiagnosticCode("MODEL_PRIVATE_STUDENT_DATA"));
    assertFalse(GatewayException.validDiagnosticCode("GATEWAY_FAKE_ERROR"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GatewayException(
                GatewayException.Code.MODEL_FAILED,
                503,
                id,
                9L,
                1,
                false,
                "secret-private-answer"));
  }
}
