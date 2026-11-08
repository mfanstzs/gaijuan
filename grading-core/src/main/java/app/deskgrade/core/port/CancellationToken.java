package app.deskgrade.core.port;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;

/**
 * Cooperative cancellation; cancellation is not an assurance that an external action was undone.
 */
@FunctionalInterface
public interface CancellationToken {
  boolean isCancellationRequested();

  default void throwIfCancellationRequested() {
    if (isCancellationRequested()) {
      throw new DeskGradeException(ErrorCode.CANCELLED);
    }
  }

  static CancellationToken none() {
    return () -> false;
  }
}
