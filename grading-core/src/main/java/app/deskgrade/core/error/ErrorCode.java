package app.deskgrade.core.error;

/** Bounded error vocabulary. Messages must never contain external response bodies or secrets. */
public enum ErrorCode {
  INVALID_ARGUMENT("The supplied value is invalid."),
  CANCELLED("The operation was cancelled."),
  CONFIGURATION_REQUIRED("Configuration is required before this operation."),
  PROVIDER_FAILURE("The grading provider failed."),
  STORAGE_FAILURE("The run could not be stored or loaded."),
  DUPLICATE_SUBMISSION("This answer image already has a submission intent or recorded progress."),
  PLATFORM_FAILURE("The platform operation could not be verified."),
  SECRET_STORE_FAILURE("The credential operation failed.");

  private final String safeMessage;

  ErrorCode(String safeMessage) {
    this.safeMessage = safeMessage;
  }

  public String safeMessage() {
    return safeMessage;
  }
}
