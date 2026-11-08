package app.deskgrade.mocksite;

/** Each received submission, including rejected and duplicate requests. */
public record LedgerEntry(
    int receivedSequence,
    String answerId,
    String score,
    int receivedCountForAnswer,
    boolean accepted,
    boolean duplicate,
    String outcome,
    String receivedAt) {}
