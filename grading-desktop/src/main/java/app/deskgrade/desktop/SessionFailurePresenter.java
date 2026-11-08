package app.deskgrade.desktop;

import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;

/** End-user guidance from bounded application codes, never arbitrary exception text. */
final class SessionFailurePresenter {
  private SessionFailurePresenter() {}

  static String message(SessionController.Status status) {
    var diagnostic = status.failureDiagnostic();
    return baseMessage(status)
        + LocalScreenChangePresentation.suffix(
            diagnostic == null ? null : diagnostic.screenChange());
  }

  private static String baseMessage(SessionController.Status status) {
    var diagnostic = status.failureDiagnostic();
    if (diagnostic != null && status.reason() == PauseReason.SUBMISSION_UNCERTAIN)
      return unknownSubmissionMessage(diagnostic);
    if (diagnostic != null
        && (status.reason() == PauseReason.MODEL_FAILED
            || status.reason() == PauseReason.FALLBACK_FAILED
            || status.reason() == PauseReason.FALLBACK_UNAVAILABLE))
      return modelMessage(diagnostic.codes());
    if (diagnostic == null || status.reason() != PauseReason.LOCAL_FAILURE)
      return DeskGradeShell.pauseMessage(status.reason());
    var codes = diagnostic.codes();
    String explanation;
    if (codes.stream().anyMatch(code -> code.endsWith(":FOCUS")))
      explanation = "阅卷网页失去焦点，请保持目标窗口在前台，勿切换窗口";
    else if (codes.stream()
        .anyMatch(code -> code.endsWith(":OCCLUDED") || code.equals("CONTROL_OBSCURED")))
      explanation = "框选区域被其他窗口或弹窗遮挡，请移开遮挡后重新绑定";
    else if (codes.stream().anyMatch(code -> code.startsWith("HELD_KEY_VK:")))
      explanation = "检测到鼠标按键或快捷键仍被按住，请松开后重新开始";
    else if (codes.stream()
        .anyMatch(code -> code.contains("ANSWER_CHANGED") || code.equals("EXPECTED_PAGE_MISMATCH")))
      explanation = "答题框内的画面与评分截图不一致，请核对是否换卷、滚动或有动态内容";
    else if (codes.stream().anyMatch(code -> code.startsWith("CLIPBOARD_")))
      explanation = "分数复制读回失败，请检查分数框可复制且剪贴板未被其他程序占用";
    else if (codes.stream()
        .anyMatch(code -> code.endsWith(":GEOMETRY") || code.endsWith(":DISPLAY")))
      explanation = "目标窗口位置、大小或显示缩放已改变，请重新框选";
    else if (codes.stream()
        .anyMatch(code -> code.startsWith("GATEWAY_") || code.equals("STORAGE_FAILURE")))
      explanation = "运行记录未能保存到本机，请检查磁盘空间和目录权限，勿重复提交旧答卷";
    else explanation = "本地操作未能完成，请保留日志并重新绑定后再开始";
    String phase =
        switch (diagnostic.phase()) {
          case BEFORE_INPUT -> "填分前检查";
          case FILLING -> "填分";
          case VERIFYING_SCORE -> "分数读回";
          case SUBMIT_PREPARED, SUBMIT_SENT, WAIT_ACK -> "提交确认";
          default -> "页面操作";
        };
    return phase + "已停止：" + explanation + "。（诊断码：" + String.join(" / ", codes) + "）";
  }

  private static String unknownSubmissionMessage(
      app.deskgrade.core.application.session.FailureDiagnostic diagnostic) {
    var codes = diagnostic.codes();
    String explanation;
    if (codes.stream().anyMatch(code -> code.endsWith(":FOCUS"))) explanation = "提交确认时目标网页失去焦点";
    else if (codes.stream().anyMatch(code -> code.endsWith(":OCCLUDED")))
      explanation = "提交确认时目标区域被遮挡";
    else if (codes.contains("ANSWER_CHANGED_DURING_CAPTURE")) explanation = "提交确认时答题画面未稳定";
    else if (codes.contains("SUBMISSION_ACK_REGION_UNSTABLE")) explanation = "提交确认提示未稳定";
    else explanation = "提交操作或确认检查未能完成";
    return "平台提交结果未知："
        + explanation
        + "。已停止且禁止自动重试，请在阅卷网站人工核对是否已保存，勿重复提交。（诊断码："
        + String.join(" / ", codes)
        + "）";
  }

  private static String modelMessage(java.util.List<String> codes) {
    String explanation;
    if (codes.contains("MODEL_DNS_FAILURE")) explanation = "本机无法解析模型服务域名";
    else if (codes.contains("MODEL_CONNECT_TIMEOUT")) explanation = "本机连接模型服务超时，派发结果可能未知";
    else if (codes.contains("MODEL_REQUEST_TIMEOUT")
        || codes.contains("MODEL_SOCKET_TIMEOUT")
        || codes.contains("MODEL_TIMEOUT")) explanation = "模型请求等待超时，派发结果可能未知";
    else if (codes.contains("MODEL_TLS_HANDSHAKE_FAILED") || codes.contains("MODEL_TLS_FAILURE"))
      explanation = "本机与模型服务的安全连接失败";
    else if (codes.contains("MODEL_CONNECTION_REFUSED")
        || codes.contains("MODEL_CONNECT_FAILED")
        || codes.contains("MODEL_NETWORK_UNREACHABLE")) explanation = "本机无法连接模型服务";
    else if (codes.contains("MODEL_CONNECTION_RESET")
        || codes.contains("MODEL_CONNECTION_CLOSED")
        || codes.contains("MODEL_IO_FAILURE")
        || codes.contains("MODEL_NETWORK")
        || codes.contains("MODEL_SOCKET_FAILURE")) explanation = "模型连接中断，未取得可确认的评分结果";
    else if (codes.contains("MODEL_AUTHENTICATION"))
      explanation = "模型服务鉴权失败，请检查本机保存的 Key、接口地址和供应商权限（不是阅卷网站的登录失败）";
    else if (codes.contains("MODEL_RATE_LIMIT")) explanation = "模型服务限流，本题已停止";
    else if (codes.contains("MODEL_SERVER_ERROR")) explanation = "模型服务暂时异常，本题已停止";
    else if (codes.contains("MODEL_INTERNAL_ERROR") || codes.contains("GATEWAY_INTERNAL_ERROR"))
      explanation = "评分内部处理异常，请查看本机诊断记录";
    else if (codes.contains("GATEWAY_TIMEOUT")) explanation = "旧云端请求等待超时；本地版不再使用 DeskGrade 评分服务器";
    else if (codes.contains("GATEWAY_NETWORK")) explanation = "旧云端连接中断；本地版不再使用 DeskGrade 评分服务器";
    else if (codes.contains("GATEWAY_DISPATCH_UNKNOWN")) explanation = "请求已派发，但尚无可确认的评分结果";
    else explanation = "评分请求失败，请在记录中查看本次诊断";
    return explanation + "。本题未填分或提交，不会自动重发。（诊断码：" + String.join(" / ", codes) + "）";
  }
}
