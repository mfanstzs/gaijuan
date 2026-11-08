package app.deskgrade.adapters.windows;

import app.deskgrade.core.application.session.SafeFailureCode;
import app.deskgrade.core.application.session.SafeScreenChangeFailure;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.error.DeskGradeException;

/** Bounded diagnostics containing only application-defined codes, never copied control contents. */
public final class SafeInputException extends IllegalStateException
    implements SafeFailureCode, SafeScreenChangeFailure {
  private final String diagnosticCode;
  private final ScreenChangeDiagnostic screenChangeDiagnostic;

  private SafeInputException(
      String code, Throwable cause, ScreenChangeDiagnostic screenChangeDiagnostic) {
    super("桌面操作已停止 [" + checked(code) + "]", cause);
    diagnosticCode = code;
    this.screenChangeDiagnostic = screenChangeDiagnostic;
  }

  @Override
  public String diagnosticCode() {
    return diagnosticCode;
  }

  @Override
  public ScreenChangeDiagnostic screenChangeDiagnostic() {
    return screenChangeDiagnostic;
  }

  public static SafeInputException code(String code) {
    return new SafeInputException(code, null, null);
  }

  public static SafeInputException screenChange(
      String code, ScreenChangeDiagnostic screenChangeDiagnostic) {
    if (screenChangeDiagnostic == null) throw new IllegalArgumentException("Missing diagnostic");
    return new SafeInputException(code, null, screenChangeDiagnostic);
  }

  public static SafeInputException stage(String code, Throwable failure) {
    ScreenChangeDiagnostic screenChange = screenChange(failure);
    Throwable safe = safeCause(failure, screenChange);
    return new SafeInputException(code, safe, screenChange);
  }

  private static Throwable safeCause(
      Throwable failure, ScreenChangeDiagnostic screenChangeDiagnostic) {
    if (failure instanceof SafeInputException) return failure;
    String code = null;
    if (failure instanceof SafeFailureCode safe) code = safe.diagnosticCode();
    else if (failure instanceof DeskGradeException known) code = known.code().name();
    else if (failure instanceof ModelCallException known) code = "MODEL_" + known.kind().name();
    if (code != null && code.matches("[A-Z0-9_:-]{1,120}"))
      return new SafeInputException(code, null, screenChangeDiagnostic);
    return new IllegalStateException("Exception type: " + failure.getClass().getName());
  }

  private static ScreenChangeDiagnostic screenChange(Throwable failure) {
    if (!(failure instanceof SafeScreenChangeFailure screen)) return null;
    try {
      return screen.screenChangeDiagnostic();
    } catch (RuntimeException | LinkageError unavailable) {
      return null;
    }
  }

  private static String checked(String code) {
    if (code == null || !code.matches("[A-Z0-9_:-]{1,120}"))
      throw new IllegalArgumentException("Invalid input diagnostic code");
    return code;
  }
}
