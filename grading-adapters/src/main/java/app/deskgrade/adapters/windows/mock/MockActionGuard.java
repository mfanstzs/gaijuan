package app.deskgrade.adapters.windows.mock;

import app.deskgrade.core.application.session.SessionPlatform;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Additional per-adapter input binding; the controller and durable journal still own authorization.
 */
final class MockActionGuard {
  private record Key(String answer, String question, long revision) {}

  private final Set<Key> sentSubmissions = new HashSet<>();
  private SessionPlatform.View filled;
  private BigDecimal score;
  private SessionPlatform.View lastSent;
  private boolean nextClaimed;

  void beginFill() {
    filled = null;
    score = null;
  }

  void recordFilled(SessionPlatform.View view, BigDecimal value) {
    if (view.page() != SessionPlatform.Page.READY || value == null || value.signum() < 0)
      throw invalid();
    filled = view;
    score = value;
  }

  void claimSubmit(SessionPlatform.View expected, SessionPlatform.View current) {
    verifyScore(expected, current);
    if (!sentSubmissions.add(key(expected))) throw invalid();
    lastSent = expected;
    nextClaimed = false;
  }

  void verifyScore(SessionPlatform.View expected, SessionPlatform.View current) {
    if (filled == null
        || expected.page() != SessionPlatform.Page.READY
        || !samePage(filled, expected)
        || !samePage(expected, current)
        || current.displayedScore() == null
        || score.compareTo(current.displayedScore()) != 0) throw invalid();
  }

  void claimNext(SessionPlatform.View expected, SessionPlatform.View current) {
    verifyNext(expected, current);
    if (nextClaimed) throw invalid();
    nextClaimed = true;
  }

  void verifyNext(SessionPlatform.View expected, SessionPlatform.View current) {
    if (lastSent == null
        || expected.page() != SessionPlatform.Page.SUBMITTED
        || !expected.separateNext()
        || !samePage(expected, current)
        || !Objects.equals(lastSent.answerId(), expected.answerId())
        || !lastSent.questionId().equals(expected.questionId())
        || !Objects.equals(expected.answerId(), expected.acknowledgedAnswerId())
        || expected.confirmedCount() != lastSent.confirmedCount() + 1) throw invalid();
  }

  static boolean samePage(SessionPlatform.View expected, SessionPlatform.View current) {
    return Objects.equals(current.answerId(), expected.answerId())
        && current.questionId().equals(expected.questionId())
        && current.pageRevision() == expected.pageRevision()
        && current.page() == expected.page()
        && current.separateNext() == expected.separateNext()
        && current.confirmedCount() == expected.confirmedCount()
        && Objects.equals(current.acknowledgedAnswerId(), expected.acknowledgedAnswerId());
  }

  private static Key key(SessionPlatform.View view) {
    return new Key(view.answerId(), view.questionId(), view.pageRevision());
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Synthetic score or submission binding is no longer valid.");
  }
}
