package app.deskgrade.gateway;

import app.deskgrade.adapters.model.ProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** An allowlisted diagnostic only; never retain a Throwable, upstream body, URL or credential. */
record GatewayModelFailure(
    Phase phase, String failureKind, String diagnosticCode, Integer upstreamStatus) {
  enum Phase {
    BEFORE_DISPATCH,
    AFTER_DISPATCH
  }

  private static final Set<String> LOCAL_KINDS =
      Set.of(
          "GATEWAY_REJECTION",
          "GATEWAY_TIMEOUT",
          "GATEWAY_INTERRUPTED",
          "INVALID_MODEL_RESULT",
          "UNEXPECTED_ERROR");
  private static final Set<String> LOCAL_CODES =
      Set.of(
          "GATEWAY_WAIT_TIMEOUT",
          "GATEWAY_INTERRUPTED",
          "GATEWAY_RESULT_INVALID",
          "GATEWAY_UNEXPECTED_ERROR");

  GatewayModelFailure {
    if (phase == null
        || !validKind(failureKind)
        || !validCode(diagnosticCode)
        || upstreamStatus != null && (upstreamStatus < 100 || upstreamStatus > 599))
      throw new IllegalArgumentException("Invalid failure diagnostic");
  }

  static GatewayModelFailure provider(ProviderException failure, boolean dispatched) {
    return new GatewayModelFailure(
        phase(dispatched),
        failure.failure().name(),
        failure.diagnosticCode(),
        failure.httpStatus());
  }

  static GatewayModelFailure local(Throwable failure, boolean dispatched, boolean invalidResult) {
    if (failure instanceof GatewayException gateway)
      return new GatewayModelFailure(
          phase(dispatched), "GATEWAY_REJECTION", "GATEWAY_" + gateway.code().name(), null);
    if (failure instanceof java.util.concurrent.TimeoutException)
      return new GatewayModelFailure(
          phase(dispatched), "GATEWAY_TIMEOUT", "GATEWAY_WAIT_TIMEOUT", null);
    if (failure instanceof InterruptedException)
      return new GatewayModelFailure(
          phase(dispatched), "GATEWAY_INTERRUPTED", "GATEWAY_INTERRUPTED", null);
    return new GatewayModelFailure(
        phase(dispatched),
        invalidResult ? "INVALID_MODEL_RESULT" : "UNEXPECTED_ERROR",
        invalidResult ? "GATEWAY_RESULT_INVALID" : "GATEWAY_UNEXPECTED_ERROR",
        null);
  }

  private static Phase phase(boolean dispatched) {
    return dispatched ? Phase.AFTER_DISPATCH : Phase.BEFORE_DISPATCH;
  }

  ObjectNode toJson() {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("schemaVersion", 1);
    node.put("phase", phase.name());
    node.put("failureKind", failureKind);
    node.put("diagnosticCode", diagnosticCode);
    if (upstreamStatus == null) node.putNull("upstreamStatus");
    else node.put("upstreamStatus", upstreamStatus);
    return node;
  }

  /** Missing legacy diagnostics return null; malformed stored data is never rendered or trusted. */
  static GatewayModelFailure read(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return null;
    if (!node.isObject()
        || node.size() != 5
        || !node.path("schemaVersion").isIntegralNumber()
        || !node.path("schemaVersion").canConvertToInt()
        || node.path("schemaVersion").intValue() != 1
        || !node.path("phase").isTextual()
        || !node.path("failureKind").isTextual()
        || !node.path("diagnosticCode").isTextual()
        || !(node.path("upstreamStatus").isNull()
            || node.path("upstreamStatus").isIntegralNumber()
                && node.path("upstreamStatus").canConvertToInt()))
      throw new IllegalArgumentException("Invalid failure diagnostic");
    return new GatewayModelFailure(
        Phase.valueOf(node.path("phase").textValue()),
        node.path("failureKind").textValue(),
        node.path("diagnosticCode").textValue(),
        node.path("upstreamStatus").isNull() ? null : node.path("upstreamStatus").intValue());
  }

  private static boolean validKind(String kind) {
    if (kind == null) return false;
    if (LOCAL_KINDS.contains(kind)) return true;
    try {
      ProviderException.Failure.valueOf(kind);
      return true;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return false;
    }
  }

  private static boolean validCode(String code) {
    if (code == null) return false;
    if (LOCAL_CODES.contains(code)) return true;
    try {
      ProviderException.Diagnostic.valueOf(code);
      return true;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      if (!code.startsWith("GATEWAY_")) return false;
      try {
        GatewayException.Code.valueOf(code.substring("GATEWAY_".length()));
        return true;
      } catch (IllegalArgumentException rejected) {
        return false;
      }
    }
  }
}
