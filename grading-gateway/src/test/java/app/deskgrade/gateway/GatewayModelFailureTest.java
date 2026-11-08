package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayModelFailureTest {
  @Test
  void providerDiagnosticsRoundTripWithoutThrowableOrFreeformData() {
    var source = new ProviderException(ProviderException.Failure.HTTP_ERROR, 418);
    var failure = GatewayModelFailure.provider(source, true);
    var json = failure.toJson();
    assertEquals("AFTER_DISPATCH", json.path("phase").asText());
    assertEquals("HTTP_ERROR", json.path("failureKind").asText());
    assertEquals("MODEL_HTTP_ERROR", json.path("diagnosticCode").asText());
    assertEquals(418, json.path("upstreamStatus").asInt());
    assertEquals(failure, GatewayModelFailure.read(json));
    assertEquals(5, json.size());
    assertNull(GatewayModelFailure.read(null));
    assertNull(GatewayModelFailure.read(GatewayJson.MAPPER.nullNode()));
  }

  @Test
  void arbitraryMessagesFieldsClassesCodesAndStatusAreRejected() {
    var valid =
        GatewayModelFailure.provider(
                new ProviderException(ProviderException.Failure.NETWORK), false)
            .toJson();
    for (String field : List.of("diagnosticCode", "failureKind", "phase")) {
      var changed =
          valid.deepCopy().put(field, "https://private.invalid/?api_key=synthetic-secret");
      assertThrows(IllegalArgumentException.class, () -> GatewayModelFailure.read(changed));
    }
    for (int status : List.of(0, 99, 600, Integer.MAX_VALUE)) {
      var changed = valid.deepCopy().put("upstreamStatus", status);
      assertThrows(IllegalArgumentException.class, () -> GatewayModelFailure.read(changed));
    }
    var extra = valid.deepCopy().put("exception", "java.net.SocketException with secret details");
    assertThrows(IllegalArgumentException.class, () -> GatewayModelFailure.read(extra));
    var missing = valid.deepCopy();
    missing.remove("upstreamStatus");
    assertThrows(IllegalArgumentException.class, () -> GatewayModelFailure.read(missing));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayModelFailure.read(valid.deepCopy().put("schemaVersion", "1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayModelFailure.read(valid.deepCopy().put("schemaVersion", 4_294_967_297L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayModelFailure.read(valid.deepCopy().put("upstreamStatus", 429.5)));
  }

  @Test
  void localUnexpectedExceptionNeverRetainsItsMessageOrCause() {
    var source =
        new IllegalStateException(
            "private-service-url synthetic-token student-answer",
            new RuntimeException("private cause"));
    var failure = GatewayModelFailure.local(source, true, false);
    assertEquals("GATEWAY_UNEXPECTED_ERROR", failure.diagnosticCode());
    assertFalse(failure.toString().contains("private"));
    assertFalse(failure.toJson().toString().contains("student-answer"));
    assertEquals(
        "GATEWAY_STORAGE_FAILURE",
        GatewayModelFailure.local(
                new GatewayException(GatewayException.Code.STORAGE_FAILURE), false, false)
            .diagnosticCode());
  }
}
