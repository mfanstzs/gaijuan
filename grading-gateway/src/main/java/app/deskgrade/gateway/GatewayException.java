package app.deskgrade.gateway;

/**
 * Public errors are finite codes. Never attach passwords, tokens, SQL, bodies or supplier errors.
 */
public final class GatewayException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    USERNAME_UNAVAILABLE,
    UNAUTHENTICATED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    RATE_LIMITED,
    MODEL_CAPACITY_EXHAUSTED,
    NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    INSUFFICIENT_QUOTA,
    REQUEST_IN_PROGRESS,
    GRADE_INGRESS_BUSY,
    ACCOUNT_GRADE_IN_PROGRESS,
    REQUEST_UNKNOWN,
    MODEL_UNAVAILABLE,
    MODEL_POLICY_CHANGED,
    FALLBACK_NOT_ELIGIBLE,
    SERVICE_UNAVAILABLE,
    STORAGE_FAILURE,
    DUPLICATE_SUBMISSION,
    INTERNAL_ERROR
  }

  private final Code code;
  private final long retryAfterSeconds;

  public GatewayException(Code code) {
    this(code, 0);
  }

  GatewayException(Code code, long retryAfterSeconds) {
    super(code.name(), null, false, false);
    this.code = code;
    this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
  }

  public Code code() {
    return code;
  }

  /** Whole seconds suitable for Retry-After. Zero means that no precise delay is known. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
