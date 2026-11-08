package app.deskgrade.adapters.model;

import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Documentation-derived choices, never credential detection or proof of account capability. */
public final class QwenEndpointCatalog {
  public static final LocalDate DOCUMENTATION_CHECKED_ON = LocalDate.of(2026, 9, 10);
  public static final URI QWEN_AI_DOCUMENTATION =
      URI.create(
          "https://platform.qianwenai.com/docs/developer-guides/getting-started/first-api-call");
  public static final URI VISION_DOCUMENTATION =
      URI.create(
          "https://platform.qianwenai.com/docs/developer-guides/getting-started/vision-models");
  public static final URI BAILIAN_REGIONS_DOCUMENTATION =
      URI.create("https://help.aliyun.com/zh/model-studio/regions");

  private QwenEndpointCatalog() {}

  /** Explicitly selected 千问 AI 平台 (qianwenai.com), as documented by that platform. */
  public static URI qwenAiEndpoint() {
    return URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
  }

  /** Editable examples, with no inference about regional availability, price or verification. */
  public static List<ModelCandidate> qwenAiVisionModels() {
    return List.of(candidate("qwen3.8-flash"), candidate("qwen3.8-max"));
  }

  private static ModelCandidate candidate(String id) {
    return new ModelCandidate(id, id, VISION_DOCUMENTATION, DOCUMENTATION_CHECKED_ON);
  }

  /** This is a separate Alibaba Cloud Bailian option; never chosen by inspecting an API Key. */
  public static URI bailianWorkspaceEndpoint(Region region, String workspaceId) {
    Objects.requireNonNull(region, "Region is required");
    if (workspaceId == null || !workspaceId.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw new IllegalArgumentException(
          "Workspace ID must be one exact DNS label from the console.");
    }
    return URI.create(
        "https://"
            + workspaceId
            + "."
            + region.regionId()
            + ".maas.aliyuncs.com/compatible-mode/v1/chat/completions");
  }

  /** Optional draft factory. It enables no optional request parameter and carries no proof. */
  public static ProviderProfile unverifiedProfile(
      String id,
      long revision,
      String displayName,
      URI endpoint,
      String exactModelId,
      String credentialRef) {
    return new ProviderProfile(
        id,
        revision,
        displayName,
        endpoint,
        exactModelId,
        credentialRef,
        ProviderProfile.StructuredMode.NONE,
        Map.of(),
        Set.of(),
        Duration.ofSeconds(10),
        Duration.ofSeconds(120),
        null,
        null);
  }

  public record ModelCandidate(
      String modelId, String displayName, URI documentation, LocalDate checkedOn) {}

  public enum Region {
    BEIJING("cn-beijing", "华北2（北京）"),
    SINGAPORE("ap-southeast-1", "新加坡"),
    HONG_KONG("cn-hongkong", "中国香港"),
    FRANKFURT("eu-central-1", "德国（法兰克福）"),
    TOKYO("ap-northeast-1", "日本（东京）"),
    VIRGINIA("us-east-1", "美国（弗吉尼亚）");

    private final String regionId;
    private final String displayName;

    Region(String regionId, String displayName) {
      this.regionId = regionId;
      this.displayName = displayName;
    }

    public String regionId() {
      return regionId;
    }

    public String displayName() {
      return displayName;
    }
  }
}
