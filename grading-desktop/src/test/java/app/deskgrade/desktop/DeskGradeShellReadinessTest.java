package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.application.session.PauseReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskGradeShellReadinessTest {
  @Test
  void duplicateScreenStillRequiresManualReviewAndNewBinding() {
    assertEquals(
        "当前答题画面已有提交记录，请核对并切换下一份后重新绑定", DeskGradeShell.pauseMessage(PauseReason.DUPLICATE_SCREEN));
    assertEquals(
        PauseReason.SCORE_MISMATCH.description(),
        DeskGradeShell.pauseMessage(PauseReason.SCORE_MISMATCH));
  }

  @Test
  void answerOnlyBindingCannotAuthorizeScoreAndSubmitCoordinates() {
    var blockers =
        DeskGradeShell.startBlockers(
            new DeskGradeShell.StartReadiness(
                true, true, false, false, false, true, true, true, true, true, true, true, false,
                false, false, true));
    assertEquals(List.of("当前仅为只读绑定；请完成本次全部必需区域框选并绑定"), blockers);
  }

  @Test
  void localReadinessExplainsRequiredConfigurationWithoutAccountsOrPaidQuota() {
    var blockers =
        DeskGradeShell.startBlockers(
            new DeskGradeShell.StartReadiness(
                true, true, true, true, true, false, false, false, true, true, false, false, false,
                false, false, false));
    assertTrue(blockers.contains("请在“模型与 Key”配置并指定阅卷主模型"));
    assertTrue(blockers.contains("阅卷主模型尚未缓存 Key"));
    assertTrue(blockers.contains("评分规则未保存、已修改或无效"));
    assertTrue(blockers.contains("全局暂停/停止热键未注册或已被其他程序占用"));
    assertTrue(blockers.contains("本次阅卷数量必须为 1–10000 的整数"));
    assertFalse(String.join(" ", blockers).matches("(?s).*(登录|注册账号|余额|剩余次数|管理员).*"));
  }

  @Test
  void localStartRequiresNoAccountOrCreditBalance() {
    assertTrue(
        DeskGradeShell.startBlockers(
                new DeskGradeShell.StartReadiness(
                    true, true, true, true, true, true, true, true, true, true, true, true, false,
                    false, false, true))
            .isEmpty());
  }

  @Test
  void localModeStillBlocksUnknownSubmissionAndActiveTasks() {
    var blockers =
        DeskGradeShell.startBlockers(
            new DeskGradeShell.StartReadiness(
                true, true, true, true, true, true, true, true, true, true, true, true, false, true,
                true, true));
    assertEquals(List.of("阅卷任务正在运行", "存在提交结果未知状态，请点击“处理旧未决提交”核对并结束旧批次"), blockers);
  }

  @Test
  void openingLocalDataIsAnExplicitBlockerNotAnAuthenticationGate() {
    var blockers =
        DeskGradeShell.startBlockers(
            new DeskGradeShell.StartReadiness(
                false, true, true, true, true, true, true, true, true, true, true, true, false,
                false, false, true));
    assertEquals(List.of("本地数据尚未就绪"), blockers);
  }

  @Test
  void fallbackCanBeDisabledButAnEnabledFallbackMustHaveItsOwnVerifiedKey() {
    assertTrue(DeskGradeShell.fallbackBlockers(false, false, false, false).isEmpty());
    assertTrue(DeskGradeShell.fallbackBlockers(true, true, true, true).isEmpty());
    assertEquals(
        List.of("备用模型图像能力尚未验证；请验证或停用备用模型", "备用模型尚未缓存 Key；请配置或停用备用模型"),
        DeskGradeShell.fallbackBlockers(true, true, false, false));
  }
}
