package app.deskgrade.core.application.session;

/** Supplies validated, local-only difference metadata without exposing the compared images. */
public interface SafeScreenChangeFailure {
  ScreenChangeDiagnostic screenChangeDiagnostic();
}
