package app.deskgrade.adapters.windows.mock;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.SessionPlatform;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MockActionGuardTest {
  @Test
  void changedScoreAfterReadbackAndMissingFillCannotSubmit() {
    MockActionGuard guard = new MockActionGuard();
    var expected = view("1", 1, SessionPlatform.Page.READY, null, 0, null);
    var scored = view("1", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.valueOf(4));
    assertThrows(IllegalStateException.class, () -> guard.claimSubmit(expected, scored));
    guard.recordFilled(expected, BigDecimal.valueOf(4));
    assertThrows(
        IllegalStateException.class,
        () ->
            guard.claimSubmit(
                expected, view("1", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.ONE)));
    guard.claimSubmit(expected, scored);
    assertThrows(
        IllegalStateException.class,
        () ->
            guard.verifyScore(
                expected, view("1", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.ONE)));
    assertThrows(IllegalStateException.class, () -> guard.claimSubmit(expected, scored));
  }

  @Test
  void changedAnswerAcknowledgementOrCountCannotReuseFill() {
    MockActionGuard guard = new MockActionGuard();
    var expected = view("1", 1, SessionPlatform.Page.READY, null, 0, null);
    guard.recordFilled(expected, BigDecimal.ONE);
    assertThrows(
        IllegalStateException.class,
        () ->
            guard.claimSubmit(
                expected, view("2", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.ONE)));
    assertThrows(
        IllegalStateException.class,
        () ->
            guard.claimSubmit(
                expected, view("1", 1, SessionPlatform.Page.READY, "9", 1, BigDecimal.ONE)));
    guard.beginFill();
    assertThrows(
        IllegalStateException.class,
        () ->
            guard.claimSubmit(
                expected, view("1", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.ONE)));
  }

  @Test
  void nextRequiresThisSubmissionExactAcknowledgementAndSingleClaim() {
    MockActionGuard guard = new MockActionGuard();
    var expected = view("1", 1, SessionPlatform.Page.READY, null, 0, null);
    var submitted = view("1", 2, SessionPlatform.Page.SUBMITTED, "1", 1, BigDecimal.ONE);
    assertThrows(IllegalStateException.class, () -> guard.claimNext(submitted, submitted));
    guard.recordFilled(expected, BigDecimal.ONE);
    guard.claimSubmit(expected, view("1", 1, SessionPlatform.Page.READY, null, 0, BigDecimal.ONE));
    var wrongAck = view("1", 2, SessionPlatform.Page.SUBMITTED, "2", 1, BigDecimal.ONE);
    assertThrows(IllegalStateException.class, () -> guard.claimNext(wrongAck, wrongAck));
    var wrongCount = view("1", 2, SessionPlatform.Page.SUBMITTED, "1", 2, BigDecimal.ONE);
    assertThrows(IllegalStateException.class, () -> guard.claimNext(wrongCount, wrongCount));
    guard.claimNext(submitted, submitted);
    guard.verifyNext(submitted, submitted);
    assertThrows(IllegalStateException.class, () -> guard.claimNext(submitted, submitted));
  }

  private SessionPlatform.View view(
      String id,
      long revision,
      SessionPlatform.Page page,
      String ack,
      int count,
      BigDecimal score) {
    return new SessionPlatform.View(id, "1", revision, page, ack, count, score, true);
  }
}
