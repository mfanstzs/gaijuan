package app.deskgrade.adapters.runtime;

import java.util.Objects;

/** Attempts every resource in order. Failure details are propagated, never printed by cleanup. */
public final class ResourceCleanup {
  private ResourceCleanup() {}

  /**
   * Null entries are absent resources; the first failure retains subsequent failures as suppressed.
   */
  public static void closeAll(AutoCloseable... resources) throws Exception {
    Objects.requireNonNull(resources);
    Throwable first = null;
    boolean interrupted = false;
    for (AutoCloseable resource : resources) {
      if (resource == null) continue;
      try {
        resource.close();
      } catch (Throwable failure) {
        interrupted |= failure instanceof InterruptedException;
        if (first == null) first = failure;
        else if (first != failure) first.addSuppressed(failure);
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    if (first instanceof Exception exception) throw exception;
    if (first instanceof Error error) throw error;
  }
}
