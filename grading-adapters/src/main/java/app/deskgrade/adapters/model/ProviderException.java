package app.deskgrade.adapters.model;

import app.deskgrade.core.application.session.SafeFailureCode;
import app.deskgrade.core.domain.model.ModelCallException;
import java.util.Objects;

/** Fixed messages only: never retain request bodies, images, credentials or upstream exceptions. */
public final class ProviderException extends ModelCallException implements SafeFailureCode {
  private static final long serialVersionUID = 1L;
  private final Failure failure;
  private final Diagnostic diagnostic;

  public ProviderException(Failure failure) {
    this(failure, null);
  }

  public ProviderException(Failure failure, Integer httpStatus) {
    this(failure, httpStatus, Diagnostic.valueOf("MODEL_" + failure.name()));
  }

  public ProviderException(Failure failure, Integer httpStatus, Diagnostic diagnostic) {
    super(Kind.valueOf(failure.name()), httpStatus);
    if (Objects.requireNonNull(diagnostic).failure != failure)
      throw new IllegalArgumentException("Diagnostic does not match failure kind");
    this.failure = failure;
    this.diagnostic = diagnostic;
  }

  public Failure failure() {
    return failure;
  }

  public Diagnostic diagnostic() {
    return diagnostic;
  }

  @Override
  public String diagnosticCode() {
    return diagnostic.name();
  }

  /** A closed vocabulary only: no host names, addresses, exception messages or class names. */
  public enum Diagnostic {
    MODEL_UNVERIFIED_CONFIGURATION(Failure.UNVERIFIED_CONFIGURATION),
    MODEL_MISSING_CREDENTIAL(Failure.MISSING_CREDENTIAL),
    MODEL_INVALID_IMAGE(Failure.INVALID_IMAGE),
    MODEL_AUTHENTICATION(Failure.AUTHENTICATION),
    MODEL_RATE_LIMIT(Failure.RATE_LIMIT),
    MODEL_SERVER_ERROR(Failure.SERVER_ERROR),
    MODEL_HTTP_ERROR(Failure.HTTP_ERROR),
    MODEL_TIMEOUT(Failure.TIMEOUT),
    MODEL_CONNECT_TIMEOUT(Failure.TIMEOUT),
    MODEL_REQUEST_TIMEOUT(Failure.TIMEOUT),
    MODEL_SOCKET_TIMEOUT(Failure.TIMEOUT),
    MODEL_CANCELLED(Failure.CANCELLED),
    MODEL_NETWORK(Failure.NETWORK),
    MODEL_DNS_FAILURE(Failure.NETWORK),
    MODEL_TLS_HANDSHAKE_FAILED(Failure.NETWORK),
    MODEL_TLS_FAILURE(Failure.NETWORK),
    MODEL_NETWORK_UNREACHABLE(Failure.NETWORK),
    MODEL_CONNECTION_REFUSED(Failure.NETWORK),
    MODEL_CONNECT_FAILED(Failure.NETWORK),
    MODEL_CONNECTION_RESET(Failure.NETWORK),
    MODEL_CONNECTION_CLOSED(Failure.NETWORK),
    MODEL_SOCKET_FAILURE(Failure.NETWORK),
    MODEL_IO_FAILURE(Failure.NETWORK),
    MODEL_INTERNAL_ERROR(Failure.INTERNAL_ERROR),
    MODEL_REFUSED(Failure.REFUSED),
    MODEL_EMPTY_RESPONSE(Failure.EMPTY_RESPONSE),
    MODEL_TRUNCATED(Failure.TRUNCATED),
    MODEL_INVALID_JSON(Failure.INVALID_JSON),
    MODEL_INVALID_RESULT(Failure.INVALID_RESULT),
    MODEL_RESPONSE_TOO_LARGE(Failure.RESPONSE_TOO_LARGE);

    private final Failure failure;

    Diagnostic(Failure failure) {
      this.failure = failure;
    }

    ProviderException exception() {
      return new ProviderException(failure, null, this);
    }
  }

  public enum Failure {
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
