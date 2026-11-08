package app.deskgrade.core.application.session;

import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.error.DeskGradeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Privacy-safe failure context. Arbitrary exception messages and stack traces are never retained.
 */
public record FailureDiagnostic(
    SessionState phase, List<String> codes, ScreenChangeDiagnostic screenChange) {
  private static final int MAX_CODES = 8;

  public FailureDiagnostic(SessionState phase, List<String> codes) {
    this(phase, codes, null);
  }

  public FailureDiagnostic {
    Objects.requireNonNull(phase);
    codes = List.copyOf(codes);
    if (codes.isEmpty() || codes.size() > MAX_CODES)
      throw new IllegalArgumentException("Invalid diagnostic code count");
    for (String code : codes) {
      if (!validCode(code)) throw new IllegalArgumentException("Invalid diagnostic code");
    }
  }

  public static FailureDiagnostic from(SessionState phase, Throwable failure) {
    var codes = new ArrayList<String>();
    ScreenChangeDiagnostic screenChange = null;
    Throwable current = failure;
    // Bound both traversal and output, including circular or unusually deep cause chains.
    for (int depth = 0; current != null && depth < MAX_CODES; depth++) {
      String code = null;
      if (current instanceof SafeFailureCode safe) code = safe.diagnosticCode();
      else if (current instanceof DeskGradeException known) code = known.code().name();
      else if (current instanceof ModelCallException known) code = "MODEL_" + known.kind().name();
      if (validCode(code) && !codes.contains(code)) codes.add(code);
      if (screenChange == null && current instanceof SafeScreenChangeFailure safe) {
        try {
          screenChange = safe.screenChangeDiagnostic();
        } catch (RuntimeException | LinkageError ignored) {
          // Optional metadata must not obscure the original failure or prevent action revocation.
        }
      }
      Throwable next = current.getCause();
      if (next == current) break;
      current = next;
    }
    if (codes.isEmpty()) codes.add("LOCAL_OPERATION_FAILED");
    return new FailureDiagnostic(phase, codes, screenChange);
  }

  private static boolean validCode(String code) {
    return code != null && code.matches("[A-Z0-9_:-]{1,120}");
  }
}
