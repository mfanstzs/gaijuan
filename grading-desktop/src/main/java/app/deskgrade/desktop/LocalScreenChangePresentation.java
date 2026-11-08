package app.deskgrade.desktop;

import app.deskgrade.core.application.session.ScreenChangeDiagnostic;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit local log/UI projection; never supplies an object to journal or model transports. */
final class LocalScreenChangePresentation {
  private LocalScreenChangePresentation() {}

  static Map<String, Object> localFields(ScreenChangeDiagnostic detail) {
    if (detail == null) return Map.of();
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", 1);
    result.put("checkpoint", detail.checkpoint().name());
    result.put("changedPixels", detail.changedPixels());
    result.put("totalPixels", detail.totalPixels());
    result.put("changeScope", detail.changeScope().name());
    result.put("tripleConsistency", detail.tripleConsistency().name());
    result.put("millisSinceFill", detail.millisSinceFill());
    return Map.copyOf(result);
  }

  static String suffix(ScreenChangeDiagnostic detail) {
    if (detail == null) return "";
    String checkpoint =
        switch (detail.checkpoint()) {
          case UNSPECIFIED -> "检查步骤待定位";
          case OBSERVE -> "页面观察";
          case CAPTURE_EVIDENCE -> "评分图片复核";
          case BEFORE_FILL -> "填分前检查";
          case FILL_ACTION -> "填分操作";
          case AFTER_FILL_CHECK -> "填分后即时检查";
          case BEFORE_READBACK -> "读分前检查";
          case READBACK_ACTION -> "复制读分";
          case AFTER_READBACK_CHECK -> "读分后内部检查";
          case AFTER_READBACK_VERIFY -> "读分后复核";
          case BEFORE_SUBMIT_CAPTURE -> "提交前答卷复核";
          case BEFORE_SUBMIT_VERIFY -> "提交前权限检查";
          case BEFORE_SUBMIT_READBACK -> "提交前再次读分";
          case BEFORE_SUBMIT_ACK -> "提交前提示区基线";
          case SUBMIT_ACTION -> "提交动作";
          case BEFORE_NEXT -> "下一份前检查";
          case NEXT_ACTION -> "下一份动作";
        };
    String scope =
        switch (detail.changeScope()) {
          case EDGE_ONLY -> "边缘变化";
          case LOCALIZED -> "局部变化";
          case BROAD -> "较大范围变化";
          case NO_PIXEL_CHANGE -> "像素相同但图像编码不同";
          case DIMENSIONS_CHANGED -> "图片尺寸变化";
          case UNAVAILABLE -> "差异未计算（限额或格式原因）";
        };
    String consistency =
        switch (detail.tripleConsistency()) {
          case COHERENT -> "三帧一致";
          case MIXED -> "三帧不一致";
          case UNKNOWN -> "三帧情况未知";
        };
    String pixels =
        detail.changedPixels() >= 0
            ? "，变化 " + detail.changedPixels() + "/" + detail.totalPixels() + " 像素"
            : "";
    String elapsed = detail.millisSinceFill() >= 0 ? "，距填分 " + detail.millisSinceFill() + "ms" : "";
    return " 本机诊断：" + checkpoint + "，" + scope + pixels + "，" + consistency + elapsed + "。";
  }
}
