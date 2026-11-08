package app.deskgrade.core.domain.model;

/** Bounded diagnostics only; deliberately has no constructor accepting an upstream cause. */
public class ModelCallException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final Kind kind;
  private final Integer httpStatus;

  public ModelCallException(Kind kind, Integer httpStatus) {
    super("Model request failed: " + kind.name());
    this.kind = kind;
    this.httpStatus = httpStatus;
  }

  public Kind kind() {
    return kind;
  }

  public Integer httpStatus() {
    return httpStatus;
  }

  public boolean retryable() {
    return switch (kind) {
      case TIMEOUT,
              NETWORK,
              RATE_LIMIT,
              SERVER_ERROR,
              INVALID_JSON,
              INVALID_RESULT,
              EMPTY_RESPONSE,
              TRUNCATED ->
          true;
      default -> false;
    };
  }

  /**
   * Whether this failure proves that a dispatched model answer itself violated the grading contract
   * and can therefore qualify for a server-authorized alternate-model attempt. Transport/client
   * subclasses may narrow this classification when the same broad kind is used for non-model
   * metadata validation.
   */
  public boolean fallbackEligible() {
    return switch (kind) {
      case EMPTY_RESPONSE, TRUNCATED, INVALID_JSON, INVALID_RESULT -> true;
      default -> false;
    };
  }

  public enum Kind {
    UNVERIFIED_CONFIGURATION,
    MISSING_CREDENTIAL,
    INVALID_IMAGE,
    AUTHENTICATION,
    RATE_LIMIT,
    SERVER_ERROR,
    HTTP_ERROR,
    TIMEOUT,
    CANCELLED,
    NETWORK,
    INTERNAL_ERROR,
    REFUSED,
    EMPTY_RESPONSE,
    TRUNCATED,
    INVALID_JSON,
    INVALID_RESULT,
    RESPONSE_TOO_LARGE
  }
}
