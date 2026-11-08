package app.deskgrade.adapters.commercial;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.model.ModelCallException;
import java.util.Set;
import java.util.UUID;

/**
 * Safe client/server categories only. No constructor accepts upstream text, credentials or causes.
 */
public final class GatewayException extends ModelCallException
    implements app.deskgrade.core.application.session.SafeFailureCode {
  private static final long serialVersionUID = 1L;
  private final Code code;
  private final UUID requestId;
  private final Long creditsRemaining;
  private final Integer chargedCredits;
  private final boolean replayed;
  private final String upstreamDiagnosticCode;
  private static final Set<String> SERVER_DIAGNOSTICS =
      Set.of(
          "GATEWAY_WAIT_TIMEOUT",
          "GATEWAY_INTERRUPTED",
          "GATEWAY_RESULT_INVALID",
          "GATEWAY_UNEXPECTED_ERROR",
          "GATEWAY_USERNAME_UNAVAILABLE",
          "GATEWAY_UNAUTHENTICATED",
          "GATEWAY_MODEL_CAPACITY_EXHAUSTED",
          "GATEWAY_INSUFFICIENT_QUOTA",
          "GATEWAY_GRADE_INGRESS_BUSY",
          "GATEWAY_ACCOUNT_GRADE_IN_PROGRESS",
          "GATEWAY_REQUEST_UNKNOWN",
          "GATEWAY_SERVICE_UNAVAILABLE",
          "GATEWAY_STORAGE_FAILURE");

  public GatewayException(Code code) {
    this(code, null, null, null, null, false);
  }

  GatewayException(
      Code code,
      Integer status,
      UUID requestId,
      Long creditsRemaining,
      Integer chargedCredits,
      boolean replayed) {
    this(code, status, requestId, creditsRemaining, chargedCredits, replayed, null);
  }

  GatewayException(
      Code code,
      Integer status,
      UUID requestId,
      Long creditsRemaining,
      Integer chargedCredits,
      boolean replayed,
      String upstreamDiagnosticCode) {
    super(kind(code), status);
    if (upstreamDiagnosticCode != null && !validDiagnosticCode(upstreamDiagnosticCode))
      throw new IllegalArgumentException("Invalid gateway diagnostic code");
    this.code = java.util.Objects.requireNonNull(code);
    this.requestId = requestId;
    this.creditsRemaining = creditsRemaining;
    this.chargedCredits = chargedCredits;
    this.replayed = replayed;
    this.upstreamDiagnosticCode = upstreamDiagnosticCode;
  }

  public Code code() {
    return code;
  }

  @Override
  public String diagnosticCode() {
    return upstreamDiagnosticCode == null ? "GATEWAY_" + code.name() : upstreamDiagnosticCode;
  }

  static boolean validDiagnosticCode(String value) {
    if (value == null) return false;
    try {
      ProviderException.Diagnostic.valueOf(value);
      return true;
    } catch (IllegalArgumentException unknownProviderCode) {
      if (SERVER_DIAGNOSTICS.contains(value)) return true;
      if (!value.startsWith("GATEWAY_")) return false;
      try {
        Code.valueOf(value.substring("GATEWAY_".length()));
        return true;
      } catch (IllegalArgumentException unknownGatewayCode) {
        return false;
      }
    }
  }

  public UUID requestId() {
    return requestId;
  }

  public Long creditsRemaining() {
    return creditsRemaining;
  }

  public Integer chargedCredits() {
    return chargedCredits;
  }

  public boolean replayed() {
    return replayed;
  }

  @Override
  public boolean fallbackEligible() {
    // INVALID_RESPONSE also uses the broad INVALID_RESULT kind, but can describe policy/catalog
    // metadata corruption before any model dispatch. Only this server receipt code proves that a
    // charged primary model result passed through the gateway's eligibility classifier.
    return code == Code.MODEL_RESULT_INVALID;
  }

  @Override
  public String getMessage() {
    return "Gateway request failed: " + code.name();
  }

  private static Kind kind(Code code) {
    return switch (code) {
      case AUTH_REQUIRED, INVALID_CREDENTIALS, ACCOUNT_DISABLED -> Kind.AUTHENTICATION;
      case RATE_LIMITED -> Kind.RATE_LIMIT;
      case TIMEOUT -> Kind.TIMEOUT;
      case NETWORK -> Kind.NETWORK;
      case CANCELLED, SESSION_CHANGED, CLIENT_CLOSED -> Kind.CANCELLED;
      case INVALID_RESPONSE, MODEL_RESULT_INVALID -> Kind.INVALID_RESULT;
      case INTERNAL_ERROR, STORE_UNAVAILABLE -> Kind.SERVER_ERROR;
      default -> Kind.HTTP_ERROR;
    };
  }

  public enum Code {
    AUTH_REQUIRED,
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    RATE_LIMITED,
    INVALID_REQUEST,
    MODEL_UNAVAILABLE,
    MODEL_POLICY_CHANGED,
    FALLBACK_NOT_ELIGIBLE,
    INSUFFICIENT_CREDITS,
    IDEMPOTENCY_CONFLICT,
    REQUEST_IN_PROGRESS,
    FORBIDDEN,
    NOT_FOUND,
    DISPATCH_UNKNOWN,
    MODEL_FAILED,
    MODEL_RESULT_INVALID,
    STORE_UNAVAILABLE,
    DUPLICATE_SUBMISSION,
    INTERNAL_ERROR,
    INVALID_RESPONSE,
    TIMEOUT,
    NETWORK,
    CANCELLED,
    SESSION_CHANGED,
    CLIENT_CLOSED
  }
}
