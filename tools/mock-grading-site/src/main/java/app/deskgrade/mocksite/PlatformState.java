package app.deskgrade.mocksite;

/** The state published to and visibly rendered by the page, distinct from the test ledger. */
public record PlatformState(
    String answerId,
    int answerSequence,
    int questionNo,
    int pageState,
    int mode,
    int ackAnswerSequence,
    int confirmedCount,
    int totalCount,
    int pageRevision,
    int faultFlags,
    String imageUrl,
    String message) {}
