package app.deskgrade.core.application.session;

/** Check immediately before each OS input event. It never authorizes replay or retries. */
@FunctionalInterface
public interface ActionPermission {
  void verify();

  /**
   * Coalesces a caller's equivalent target check while retaining revocation checks on both sides.
   */
  default void verifyForTarget(Runnable targetCheck) {
    verify();
    targetCheck.run();
    verify();
  }
}
