package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalScreenChangePresentationTest {
  @Test
  void localLogProjectionIsAnExactAllowlistOfNumbersAndEnumNames() {
    var fields = LocalScreenChangePresentation.localFields(detail());
    assertEquals(
        Set.of(
            "schemaVersion",
            "checkpoint",
            "changedPixels",
            "totalPixels",
            "changeScope",
            "tripleConsistency",
            "millisSinceFill"),
        fields.keySet());
    assertEquals("BEFORE_READBACK", fields.get("checkpoint"));
    assertEquals(12L, fields.get("changedPixels"));
    assertEquals(205L, fields.get("millisSinceFill"));
    assertTrue(fields.values().stream().allMatch(v -> v instanceof Number || v instanceof String));
    assertThrows(UnsupportedOperationException.class, () -> fields.put("image", new byte[] {1}));
    assertTrue(LocalScreenChangePresentation.localFields(null).isEmpty());
  }

  @Test
  void exactCheckpointAndCountsAreVisibleWithoutDeclaringActualStudentReplacement() {
    String message =
        SessionFailurePresenter.message(
            new SessionController.Status(
                null,
                SessionState.STOPPED,
                PauseReason.LOCAL_FAILURE,
                1,
                0,
                null,
                new FailureDiagnostic(
                    SessionState.VERIFYING_SCORE,
                    List.of("MANUAL_OBSERVE", "ANSWER_CHANGED_WITHOUT_ACTION"),
                    detail())));
    assertTrue(message.contains("读分前检查"));
    assertTrue(message.contains("边缘变化"));
    assertTrue(message.contains("12/10000 像素"));
    assertTrue(message.contains("三帧一致"));
    assertTrue(message.contains("205ms"));
    assertFalse(message.contains("已经换卷"));
  }

  @Test
  void unknownSubmissionStillRequiresHumanReviewWhenPixelDetailsArePresent() {
    String message =
        SessionFailurePresenter.message(
            new SessionController.Status(
                null,
                SessionState.SUBMISSION_UNKNOWN,
                PauseReason.SUBMISSION_UNCERTAIN,
                1,
                0,
                null,
                new FailureDiagnostic(
                    SessionState.SUBMIT_SENT, List.of("MANUAL_SUBMIT"), detail())));
    assertTrue(message.contains("禁止自动重试"));
    assertTrue(message.contains("人工核对是否已保存"));
    assertTrue(message.contains("本机诊断"));
    assertFalse(message.contains("本题未填分或提交"));
  }

  @Test
  void unknownAndUnchangedPixelsAreNotInventedAsAUsefulDifference() {
    assertEquals("", LocalScreenChangePresentation.suffix(null));
    var unavailable =
        new ScreenChangeDiagnostic(
            ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY,
            -1,
            -1,
            ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE,
            ScreenChangeDiagnostic.TripleConsistency.UNKNOWN,
            -1);
    String suffix = LocalScreenChangePresentation.suffix(unavailable);
    assertTrue(suffix.contains("读分后复核"));
    assertTrue(suffix.contains("差异未计算"));
    assertFalse(suffix.contains("-1"));
    var samePixels =
        new ScreenChangeDiagnostic(
            ScreenChangeDiagnostic.Checkpoint.AFTER_FILL_CHECK,
            0,
            100,
            ScreenChangeDiagnostic.ChangeScope.NO_PIXEL_CHANGE,
            ScreenChangeDiagnostic.TripleConsistency.COHERENT,
            0);
    assertTrue(LocalScreenChangePresentation.suffix(samePixels).contains("像素相同但图像编码不同"));
  }

  private ScreenChangeDiagnostic detail() {
    return new ScreenChangeDiagnostic(
        ScreenChangeDiagnostic.Checkpoint.BEFORE_READBACK,
        12,
        10000,
        ScreenChangeDiagnostic.ChangeScope.EDGE_ONLY,
        ScreenChangeDiagnostic.TripleConsistency.COHERENT,
        205);
  }
}
