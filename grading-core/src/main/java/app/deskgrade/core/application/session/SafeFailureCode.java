package app.deskgrade.core.application.session;

/**
 * A failure whose diagnostic is a bounded application-defined code, never user or provider text.
 */
public interface SafeFailureCode {
  String diagnosticCode();
}
