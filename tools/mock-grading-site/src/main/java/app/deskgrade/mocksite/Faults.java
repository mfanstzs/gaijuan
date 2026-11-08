package app.deskgrade.mocksite;

/** Persistent, explicit fault controls. No randomness is used by the simulator. */
public record Faults(
    int delayMillis,
    boolean blankImage,
    boolean identicalImage,
    boolean rejectScore,
    boolean dropReceipt,
    boolean submitFailure,
    boolean questionChanged,
    boolean loginExpired,
    boolean popup) {
  public static final Faults NONE =
      new Faults(0, false, false, false, false, false, false, false, false);

  public Faults {
    if (delayMillis < 0 || delayMillis > 30000) {
      throw new IllegalArgumentException("delayMillis must be 0..30000");
    }
  }

  public int flags() {
    return (delayMillis > 0 ? 1 : 0)
        | (blankImage ? 2 : 0)
        | (identicalImage ? 4 : 0)
        | (rejectScore ? 8 : 0)
        | (dropReceipt ? 16 : 0)
        | (submitFailure ? 32 : 0)
        | (questionChanged ? 64 : 0)
        | (loginExpired ? 128 : 0)
        | (popup ? 256 : 0);
  }
}
