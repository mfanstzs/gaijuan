package app.deskgrade.core.error;

import java.util.Objects;

/** Public failures expose a stable code and a fixed safe message, never an external cause. */
public final class DeskGradeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final ErrorCode code;

  public DeskGradeException(ErrorCode code) {
    super(Objects.requireNonNull(code, "code").safeMessage());
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
