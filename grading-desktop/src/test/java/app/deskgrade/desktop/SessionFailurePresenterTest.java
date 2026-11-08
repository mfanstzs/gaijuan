package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionFailurePresenterTest {
  @Test
  void focusFailureIsNotReportedAsAnswerChange() {
    String message = message("REGION_0_UNSAFE:FOCUS");
    assertTrue(message.contains("失去焦点"));
    assertFalse(message.contains("画面与评分截图不一致"));
    assertTrue(message.contains("填分已停止"));
  }

  @Test
  void answerAndClipboardFailuresExplainDifferentRemedies() {
    assertTrue(message("ANSWER_CHANGED_DURING_CAPTURE").contains("评分截图不一致"));
    assertTrue(message("CLIPBOARD_COPY_TIMEOUT").contains("剪贴板"));
    assertTrue(message("HELD_KEY_VK:17").contains("仍被按住"));
  }

  @Test
  void unknownSubmissionNeverBecomesOrdinaryRetryAdvice() {
    var status =
        new SessionController.Status(
            null,
            SessionState.SUBMISSION_UNKNOWN,
            PauseReason.SUBMISSION_UNCERTAIN,
            1,
            0,
            null,
            new FailureDiagnostic(SessionState.SUBMIT_SENT, List.of("MANUAL_SUBMIT")));
    String message = SessionFailurePresenter.message(status);
    assertTrue(message.contains("平台提交结果未知"));
    assertTrue(message.contains("禁止自动重试"));
    assertTrue(message.contains("人工核对是否已保存"));
    assertTrue(message.contains("MANUAL_SUBMIT"));
    assertFalse(message.contains("本题未填分或提交"));
    assertFalse(message.contains("重新开始"));
  }

  @Test
  void unknownSubmissionShowsWaitingFailureWithoutClaimingItWasNotSubmitted() {
    var status =
        new SessionController.Status(
            null,
            SessionState.SUBMISSION_UNKNOWN,
            PauseReason.SUBMISSION_UNCERTAIN,
            1,
            0,
            null,
            new FailureDiagnostic(SessionState.WAIT_ACK, List.of("ANSWER_CHANGED_DURING_CAPTURE")));
    String message = SessionFailurePresenter.message(status);
    assertTrue(message.contains("答题画面未稳定"));
    assertTrue(message.contains("ANSWER_CHANGED_DURING_CAPTURE"));
    assertTrue(message.contains("勿重复提交"));
    assertFalse(message.contains("本题未填分或提交"));
  }

  @Test
  void modelTransportDiagnosticUsesLocalProviderAndOwnKeyGuidance() {
    assertTrue(modelMessage("MODEL_CONNECT_TIMEOUT").contains("本机连接模型服务超时"));
    assertTrue(modelMessage("GATEWAY_NETWORK").contains("本地版不再使用"));
    assertTrue(modelMessage("MODEL_AUTHENTICATION").contains("本机保存的 Key"));
    assertFalse(modelMessage("MODEL_AUTHENTICATION").contains("管理员"));
    assertTrue(modelMessage("MODEL_TLS_HANDSHAKE_FAILED").contains("安全连接失败"));
    assertTrue(modelMessage("MODEL_RATE_LIMIT").contains("限流"));
    assertTrue(modelMessage("MODEL_INTERNAL_ERROR").contains("内部处理异常"));
    assertTrue(modelMessage("GATEWAY_DISPATCH_UNKNOWN").contains("不会自动重发"));
    assertFalse(modelMessage("GATEWAY_DISPATCH_UNKNOWN").contains("重新绑定"));
  }

  private String modelMessage(String code) {
    return SessionFailurePresenter.message(
        new SessionController.Status(
            null,
            SessionState.STOPPED,
            PauseReason.MODEL_FAILED,
            1,
            0,
            null,
            new FailureDiagnostic(SessionState.GRADING, List.of(code))));
  }

  private String message(String code) {
    return SessionFailurePresenter.message(
        new SessionController.Status(
            null,
            SessionState.STOPPED,
            PauseReason.LOCAL_FAILURE,
            1,
            0,
            null,
            new FailureDiagnostic(SessionState.FILLING, List.of(code))));
  }
}
