package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RecordsViewStatusTest {
  @Test
  void manualProgressNeverAppearsAsPlatformConfirmationOrAsAnUnattemptedSubmission() {
    String clicked = RecordsView.submissionLabel("SUBMIT_CLICKED");
    String advanced = RecordsView.submissionLabel("VISUALLY_ADVANCED");
    String acknowledged = RecordsView.submissionLabel("CONFIRMED");
    String notAttempted = RecordsView.submissionLabel("NOT_PREPARED");
    assertEquals("已点击提交", clicked);
    assertEquals("已观察页面推进", advanced);
    assertNotEquals(acknowledged, clicked);
    assertNotEquals(acknowledged, advanced);
    assertNotEquals(notAttempted, clicked);
    assertNotEquals(notAttempted, advanced);
  }

  @Test
  void unknownOrFutureStatusesAreNeverRepresentedAsNoSubmissionAttempt() {
    assertEquals("结果未知", RecordsView.submissionLabel("UNKNOWN"));
    assertEquals("状态未识别", RecordsView.submissionLabel("FUTURE_STATUS"));
    assertEquals("状态未识别", RecordsView.submissionLabel(null));
  }
}
