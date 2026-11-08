package app.deskgrade.core.application.session;

public enum PauseReason {
  NONE(""),
  USER_PAUSED("用户暂停"),
  USER_STOPPED("用户停止"),
  CONFIG_CHANGED("配置已修改，请重新检查当前答卷"),
  TARGET_CHANGED("目标窗口、页面或显示环境已改变"),
  PLATFORM_UNSUPPORTED("平台判据尚未验证，不能自动提交"),
  HOTKEY_UNAVAILABLE("全局停止热键不可用"),
  STORAGE_UNAVAILABLE("本地记录不可写"),
  QUESTION_CHANGED("平台题号与当前规则不一致"),
  PAGE_TIMEOUT("等待平台就绪超时"),
  MODEL_FAILED("模型评分失败"),
  INVALID_RESULT("评分结果无法通过规则校验"),
  FALLBACK_UNAVAILABLE("主模型无法可靠评分，服务器没有可用的备用模型，任务已停止"),
  FALLBACK_FAILED("备用模型仍无法给出合法评分，任务已停止"),
  SCORE_MISMATCH("分数读回与已验证分数不一致"),
  DUPLICATE_SCREEN("当前答题画面已有提交记录，请核对并切换下一份后重新绑定"),
  SUBMISSION_UNCERTAIN("平台提交结果未知，禁止自动重试"),
  NEXT_UNCERTAIN("未确认下一份答卷"),
  LIMIT_REACHED("已达到本次运行上限"),
  CREDITS_EXHAUSTED("账户次数已用完，本轮任务已停止；增加次数后可重新开始"),
  COST_UNKNOWN("费用未知，无法继续按费用上限运行"),
  RECOVERED("上次运行已中断，重新核验后再开始"),
  LOCAL_FAILURE("发生本地错误，已关闭输入权限");
  private final String description;

  PauseReason(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }
}
