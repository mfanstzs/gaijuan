package app.deskgrade.desktop;

import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-secret starting templates only. Fresh installations must supply and verify their own key. */
final class LocalModelDefaults {
  private LocalModelDefaults() {}

  static DesktopWorkspace.LocalModelSelection selection() {
    return new DesktopWorkspace.LocalModelSelection(
        "gateway_qwen38_flash", "gateway_qwen38_max", "local_qwen38_rubric");
  }

  static List<ProviderProfile> profiles() {
    return List.of(
        profile(
            "gateway_qwen38_flash",
            "千问评分模型",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "qwen3.8-flash",
            "qwen_validation"),
        profile(
            "gateway_qwen38_max",
            "千问备用评分模型",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "qwen3.8-max",
            "qwen_validation"),
        profile(
            "local_qwen38_rubric",
            "千问评分规则生成模型",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "qwen3.8-max",
            "qwen_validation"));
  }

  private static ProviderProfile profile(
      String id, String name, String endpoint, String model, String credential) {
    return new ProviderProfile(
        id,
        1,
        name,
        URI.create(endpoint),
        model,
        credential,
        ProviderProfile.StructuredMode.JSON_OBJECT,
        Map.of(),
        Set.of(),
        Duration.ofSeconds(10),
        Duration.ofSeconds(120),
        null,
        null);
  }
}
