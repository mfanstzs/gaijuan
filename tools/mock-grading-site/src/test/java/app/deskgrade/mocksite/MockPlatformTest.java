package app.deskgrade.mocksite;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MockPlatformTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);

  private MockPlatform platform(SubmissionMode mode) {
    return new MockPlatform(20, mode, CLOCK);
  }

  @ParameterizedTest
  @EnumSource(SubmissionMode.class)
  void fiveHundredSyntheticAnswersHaveOneIndependentLedgerEntryEach(SubmissionMode mode) {
    MockPlatform platform = new MockPlatform(500, mode, CLOCK);
    for (int sequence = 1; sequence <= 500; sequence++) {
      var before = platform.state();
      assertEquals(sequence, before.answerSequence());
      assertEquals(1, before.pageState());
      String score = Integer.toString(sequence % 5);
      var reply = platform.submit(before.answerId(), score);
      assertEquals(200, reply.status());
      assertEquals(sequence, reply.state().ackAnswerSequence());
      assertEquals(sequence, reply.state().confirmedCount());
      if (mode == SubmissionMode.SUBMIT_THEN_NEXT) {
        assertEquals(2, reply.state().pageState());
        assertEquals(200, platform.next().status());
      }
    }
    assertEquals(3, platform.state().pageState());
    assertEquals(0, platform.state().answerSequence());
    var ledger = platform.ledger();
    assertEquals(500, ledger.size());
    for (int i = 0; i < ledger.size(); i++) {
      var entry = ledger.get(i);
      assertEquals(MockPlatform.answerId(i + 1), entry.answerId());
      assertEquals(new BigDecimal((i + 1) % 5), new BigDecimal(entry.score()));
      assertTrue(entry.accepted());
      assertFalse(entry.duplicate());
      assertEquals(1, entry.receivedCountForAnswer());
      assertEquals(CLOCK.instant().toString(), entry.receivedAt());
    }
  }

  @Test
  void duplicateAndStaleSubmissionsRemainVisibleAndCannotOverwriteTheScore() {
    var platform = platform(SubmissionMode.SUBMIT_AND_ADVANCE);
    assertEquals(200, platform.submit("SYN-0001", "3").status());
    assertEquals(409, platform.submit("SYN-0001", "4").status());
    assertEquals(409, platform.submit("SYN-0019", "2").status());
    assertEquals(3, platform.ledger().size());
    var duplicate = platform.ledger().get(1);
    assertTrue(duplicate.duplicate());
    assertFalse(duplicate.accepted());
    assertEquals(2, duplicate.receivedCountForAnswer());
    assertEquals("4", duplicate.score());
    assertEquals("3", platform.ledger().getFirst().score());
    assertEquals(1, platform.state().confirmedCount());
  }

  @Test
  void receiptLossCommitsButDoesNotPublishSuccessUntilExplicitlyCleared() {
    var platform = platform(SubmissionMode.SUBMIT_AND_ADVANCE);
    platform.configure(new Faults(0, false, false, false, true, false, false, false, false));
    var reply = platform.submit("SYN-0001", "4");
    assertTrue(reply.dropReceipt());
    assertEquals(1, platform.state().answerSequence());
    assertEquals(0, platform.state().ackAnswerSequence());
    assertEquals(0, platform.state().confirmedCount());
    assertTrue(platform.ledger().getFirst().accepted());
    assertEquals(409, platform.submit("SYN-0001", "4").status());
    platform.configure(Faults.NONE);
    assertEquals(2, platform.state().answerSequence());
    assertEquals(1, platform.state().ackAnswerSequence());
    assertEquals(1, platform.state().confirmedCount());
  }

  @Test
  void invalidScoresAreNotCoercedRoundedOrDefaulted() {
    var platform = platform(SubmissionMode.SUBMIT_AND_ADVANCE);
    for (String score : new String[] {"", "abc", "NaN", "-1", "5", "1.5"}) {
      assertEquals(422, platform.submit("SYN-0001", score).status());
    }
    assertEquals(0, platform.state().confirmedCount());
    assertEquals(6, platform.ledger().size());
    assertEquals(200, platform.submit("SYN-0001", "3.00").status());
  }

  @Test
  void faultOutcomesAreDeterministicAndClearingDoesNotCreateSubmissions() {
    var platform = platform(SubmissionMode.SUBMIT_THEN_NEXT);
    platform.configure(new Faults(0, false, false, true, false, false, false, false, false));
    assertEquals(422, platform.submit("SYN-0001", "4").status());
    platform.configure(new Faults(0, false, false, false, false, true, false, false, false));
    assertEquals(503, platform.submit("SYN-0001", "4").status());
    platform.configure(new Faults(0, false, false, false, false, false, false, true, false));
    assertEquals(5, platform.state().pageState());
    assertEquals(401, platform.submit("SYN-0001", "4").status());
    platform.configure(new Faults(0, false, false, false, false, false, false, false, true));
    assertEquals(6, platform.state().pageState());
    assertEquals(409, platform.submit("SYN-0001", "4").status());
    assertEquals(409, platform.next().status());
    platform.configure(new Faults(0, false, false, false, false, false, true, false, false));
    assertEquals(2, platform.state().questionNo());
    platform.configure(Faults.NONE);
    assertEquals(0, platform.state().confirmedCount());
    assertEquals(1, platform.state().questionNo());
    assertEquals(200, platform.submit("SYN-0001", "4").status());
  }

  @Test
  void onlyExplicitlyConfirmedSeparateModeMayNavigate() {
    var platform = platform(SubmissionMode.SUBMIT_THEN_NEXT);
    assertEquals(409, platform.next().status());
    platform.submit("SYN-0001", "0");
    assertEquals(200, platform.next().status());
    assertEquals(409, platform.next().status());
    assertEquals(2, platform.state().answerSequence());
    assertEquals(3, platform.forceAdvance().answerSequence());
    assertEquals(1, platform.ledger().size());
  }

  @Test
  void restartReturnsToFirstAnswerAndClearsScoresAndFaults() {
    var platform = platform(SubmissionMode.SUBMIT_AND_ADVANCE);
    assertEquals(200, platform.submit("SYN-0001", "3").status());
    platform.configure(new Faults(0, true, false, false, false, false, false, false, false));

    var restarted = platform.restart();

    assertEquals(1, restarted.answerSequence());
    assertEquals("SYN-0001", restarted.answerId());
    assertEquals(0, restarted.confirmedCount());
    assertEquals(0, restarted.faultFlags());
    assertTrue(platform.ledger().isEmpty());
    assertTrue(platform.acceptedScores().isEmpty());
  }

  @Test
  void invalidFixtureConfigurationFailsImmediately() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MockPlatform(0, SubmissionMode.SUBMIT_AND_ADVANCE, CLOCK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Faults(30001, false, false, false, false, false, false, false, false));
    assertThrows(
        IllegalArgumentException.class, () -> SubmissionMode.parse("automatic-mystery-mode"));
  }
}
