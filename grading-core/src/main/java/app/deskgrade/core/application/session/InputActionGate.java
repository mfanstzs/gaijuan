package app.deskgrade.core.application.session;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;

/** Revocation is immediate and independent of queued controller work or HTTP cancellation. */
public final class InputActionGate {
  private long epoch;
  private boolean open;
  private Thread executorThread;

  public synchronized long open() {
    epoch++;
    open = true;
    return epoch;
  }

  public synchronized long openIfUnchanged(long expectedEpoch) {
    if (epoch != expectedEpoch) {
      return -1;
    }
    return open();
  }

  public synchronized void revokeIfOpen() {
    if (open) {
      revoke();
    }
  }

  public synchronized long revoke() {
    open = false;
    return ++epoch;
  }

  public synchronized long epoch() {
    return epoch;
  }

  public synchronized boolean accepts(long expected) {
    return open && epoch == expected;
  }

  public synchronized void bindExecutorThread() {
    if (executorThread != null && executorThread != Thread.currentThread()) {
      throw new IllegalStateException("Only one desktop action executor is allowed");
    }
    executorThread = Thread.currentThread();
  }

  public ActionPermission permission(long expectedEpoch, Runnable verifyTargetAndJournal) {
    return () -> {
      synchronized (this) {
        if (!accepts(expectedEpoch) || Thread.currentThread() != executorThread) {
          throw new DeskGradeException(ErrorCode.CANCELLED);
        }
      }
      verifyTargetAndJournal.run();
      synchronized (this) {
        if (!accepts(expectedEpoch)) {
          throw new DeskGradeException(ErrorCode.CANCELLED);
        }
      }
    };
  }
}
