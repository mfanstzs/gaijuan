package app.deskgrade.adapters.windows;

import java.math.BigDecimal;
import java.util.UUID;

/** Bounded copy/read/restore protocol. It never accepts pre-existing clipboard text as readback. */
final class ClipboardNumberReadback {
  interface ClipboardAccess {
    Object snapshot();

    long sequence();

    void setText(String text);

    String text();

    boolean ownedByTarget();

    void restore(Object snapshot);
  }

  @FunctionalInterface
  interface Wait {
    void pause() throws InterruptedException;
  }

  static BigDecimal read(ClipboardAccess clipboard, Runnable copy, Runnable verify, Wait wait) {
    Object original = null;
    long restoreSequence = -1;
    try {
      verify.run();
      long before = clipboard.sequence();
      original = clipboard.snapshot();
      if (clipboard.sequence() != before) throw failure("CLIPBOARD_SNAPSHOT_CHANGED");
      String sentinel = "DeskGrade-readback-" + UUID.randomUUID();
      clipboard.setText(sentinel);
      long sentinelSequence = clipboard.sequence();
      if (!sentinel.equals(clipboard.text()) || clipboard.sequence() != sentinelSequence)
        throw failure("CLIPBOARD_SENTINEL_CHANGED");
      restoreSequence = sentinelSequence;
      verify.run();
      copy.run();
      for (int attempt = 0; attempt < 30; attempt++) {
        verify.run();
        long changed = clipboard.sequence();
        if (changed != sentinelSequence) {
          // Other applications may update the shared clipboard without taking foreground focus.
          if (!clipboard.ownedByTarget()) {
            restoreSequence = -1;
            throw failure("CLIPBOARD_FOREIGN_OWNER");
          }
          restoreSequence = changed;
          String value = clipboard.text();
          if (clipboard.sequence() != changed || !clipboard.ownedByTarget()) {
            restoreSequence = -1;
            throw failure("CLIPBOARD_CHANGED_DURING_READ");
          }
          BigDecimal score = parse(value);
          verify.run();
          return score;
        }
        wait.pause();
      }
      throw failure("CLIPBOARD_COPY_TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure("CLIPBOARD_INTERRUPTED");
    } catch (RuntimeException | LinkageError problem) {
      if (problem instanceof SafeInputException safe) throw safe;
      throw SafeInputException.stage("CLIPBOARD_PROTOCOL", problem);
    } finally {
      // Never replace data copied by another application after our own readback finished.
      try {
        if (restoreSequence >= 0 && clipboard.sequence() == restoreSequence)
          clipboard.restore(original);
      } catch (RuntimeException restoreFailed) {
        throw SafeInputException.stage("CLIPBOARD_RESTORE", restoreFailed);
      }
    }
  }

  static BigDecimal parse(String value) {
    if (value == null || value.length() > 64) throw failure("CLIPBOARD_NON_NUMERIC");
    String trimmed = value.strip();
    if (trimmed.length() > 24 || !trimmed.matches("[0-9]+(?:\\.[0-9]+)?"))
      throw failure("CLIPBOARD_NON_NUMERIC");
    return new BigDecimal(trimmed);
  }

  private static SafeInputException failure(String code) {
    return SafeInputException.code(code);
  }
}
