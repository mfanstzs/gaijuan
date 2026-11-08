package app.deskgrade.core.application.session;

import java.util.Objects;

/**
 * Bounded local-only metadata. Contains no images, text, hashes, screen coordinates or website
 * data. This record must never be passed to the session journal or a remote transport.
 */
public record ScreenChangeDiagnostic(
    Checkpoint checkpoint,
    long changedPixels,
    long totalPixels,
    ChangeScope changeScope,
    TripleConsistency tripleConsistency,
    long millisSinceFill) {
  public static final long MAX_TOTAL_PIXELS = 16_000_000;
  public static final long MAX_MILLIS_SINCE_FILL = 86_400_000;

  public ScreenChangeDiagnostic {
    Objects.requireNonNull(checkpoint);
    Objects.requireNonNull(changeScope);
    Objects.requireNonNull(tripleConsistency);
    if (millisSinceFill < -1 || millisSinceFill > MAX_MILLIS_SINCE_FILL)
      throw new IllegalArgumentException("Invalid local diagnostic elapsed time");
    boolean unknown = changedPixels == -1 && totalPixels == -1;
    if (changeScope == ChangeScope.UNAVAILABLE || changeScope == ChangeScope.DIMENSIONS_CHANGED) {
      if (!unknown) throw new IllegalArgumentException("Unavailable pixel counts must be unknown");
    } else {
      if (totalPixels < 1
          || totalPixels > MAX_TOTAL_PIXELS
          || changedPixels < 0
          || changedPixels > totalPixels)
        throw new IllegalArgumentException("Invalid local diagnostic pixel counts");
      if ((changeScope == ChangeScope.NO_PIXEL_CHANGE) != (changedPixels == 0))
        throw new IllegalArgumentException("Pixel counts do not match change scope");
    }
  }

  public ScreenChangeDiagnostic withCheckpoint(Checkpoint value) {
    return new ScreenChangeDiagnostic(
        value, changedPixels, totalPixels, changeScope, tripleConsistency, millisSinceFill);
  }

  public enum ChangeScope {
    EDGE_ONLY,
    LOCALIZED,
    BROAD,
    NO_PIXEL_CHANGE,
    DIMENSIONS_CHANGED,
    UNAVAILABLE
  }

  public enum TripleConsistency {
    COHERENT,
    MIXED,
    UNKNOWN
  }

  public enum Checkpoint {
    UNSPECIFIED,
    OBSERVE,
    CAPTURE_EVIDENCE,
    BEFORE_FILL,
    FILL_ACTION,
    AFTER_FILL_CHECK,
    BEFORE_READBACK,
    READBACK_ACTION,
    AFTER_READBACK_CHECK,
    AFTER_READBACK_VERIFY,
    BEFORE_SUBMIT_CAPTURE,
    BEFORE_SUBMIT_VERIFY,
    BEFORE_SUBMIT_READBACK,
    BEFORE_SUBMIT_ACK,
    SUBMIT_ACTION,
    BEFORE_NEXT,
    NEXT_ACTION
  }
}
