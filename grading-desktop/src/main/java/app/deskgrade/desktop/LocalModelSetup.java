package app.deskgrade.desktop;

import app.deskgrade.adapters.model.DoubaoEndpointCatalog;
import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Pure wizard decisions. No credentials, persistence, or model requests. */
final class LocalModelSetup {
  enum Purpose {
    PRIMARY("阅卷主模型", "primary", true),
    FALLBACK("备用阅卷模型", "fallback", true),
    RUBRIC("评分规则生成", "rubric", false);

    final String title;
    final String key;
    final boolean vision;

    Purpose(String title, String key, boolean vision) {
      this.title = title;
      this.key = key;
      this.vision = vision;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  enum Vendor {
    QWEN("千问 / 阿里云", "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
    OPENAI("OpenAI", "openai", "https://api.openai.com/v1/chat/completions"),
    DOUBAO("豆包 / 火山引擎", "doubao", DoubaoEndpointCatalog.ENDPOINT.toString()),
    CUSTOM("其他兼容服务（高级）", "custom", "");

    final String title;
    final String key;
    final String endpoint;

    Vendor(String title, String key, String endpoint) {
      this.title = title;
      this.key = key;
      this.endpoint = endpoint;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private LocalModelSetup() {}

  static String modelLabel(String id) {
    if (id == null) return "";
    return switch (id) {
      case "qwen3.8-flash" -> "千问 3.8 Flash（速度优先）";
      case "qwen3.8-max" -> "千问 3.8 Max（质量优先）";
      case "qwen3.8-max-0902" -> "千问 3.8 Max（0902 快照）";
      case "gpt-6-luna" -> "GPT-6 Luna";
      case DoubaoEndpointCatalog.SEED_2_1_LITE -> "豆包 Seed 2.1 Lite（对比候选）";
      default -> id;
    };
  }

  static Vendor vendorOf(ProviderProfile profile) {
    if (profile == null) return Vendor.QWEN;
    for (Vendor vendor : Vendor.values())
      if (!vendor.endpoint.isEmpty() && vendor.endpoint.equals(profile.endpoint().toString()))
        return vendor;
    return Vendor.CUSTOM;
  }

  static boolean ready(ProviderProfile profile, Purpose purpose, boolean credentialPresent) {
    return profile != null
        && credentialPresent
        && (purpose.vision ? profile.isVerified() : profile.isStructuredVerified());
  }

  static String defaultModelId(Purpose purpose, Vendor vendor) {
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(vendor);
    return switch (vendor) {
      case QWEN -> purpose == Purpose.PRIMARY ? "qwen3.8-flash" : "qwen3.8-max";
      case OPENAI -> purpose == Purpose.RUBRIC ? "gpt-6-luna" : "";
      case DOUBAO -> DoubaoEndpointCatalog.SEED_2_1_LITE;
      case CUSTOM -> "";
    };
  }

  /**
   * Finds an existing editor candidate, not a new active role. Exact vendor endpoints only: a saved
   * Key/verification belongs to the complete saved profile and is never copied to a new endpoint.
   */
  static ProviderProfile savedVendorProfile(
      Collection<ProviderProfile> profiles,
      Purpose purpose,
      Vendor vendor,
      String preferredId,
      Predicate<ProviderProfile> credentialPresent) {
    Objects.requireNonNull(profiles);
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(vendor);
    Objects.requireNonNull(credentialPresent);
    if (vendor == Vendor.CUSTOM) return null;
    String defaultModel = defaultModelId(purpose, vendor);
    Comparator<ProviderProfile> order =
        Comparator.comparingInt((ProviderProfile p) -> p.id().equals(preferredId) ? 0 : 1)
            .thenComparingInt(p -> ready(p, purpose, credentialPresent.test(p)) ? 0 : 1)
            .thenComparingInt(p -> credentialPresent.test(p) ? 0 : 1)
            .thenComparingInt(p -> p.modelId().equals(defaultModel) ? 0 : 1)
            .thenComparing(ProviderProfile::id);
    return profiles.stream()
        .filter(p -> vendor.endpoint.equals(p.endpoint().toString()))
        .min(order)
        .orElse(null);
  }

  static ProviderProfile savedOrIsolatedDraft(
      ProviderProfile original,
      Purpose purpose,
      String name,
      URI endpoint,
      String model,
      ProviderProfile.StructuredMode format,
      boolean newCredential,
      UUID nonce) {
    return savedOrIsolatedDraft(
        original,
        purpose,
        name,
        endpoint,
        model,
        format,
        newCredential,
        nonce,
        QwenThinkingOptions.Mode.KEEP);
  }

  static ProviderProfile savedOrIsolatedDraft(
      ProviderProfile original,
      Purpose purpose,
      String name,
      URI endpoint,
      String model,
      ProviderProfile.StructuredMode format,
      boolean newCredential,
      UUID nonce,
      QwenThinkingOptions.Mode thinkingMode) {
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(nonce);
    boolean sameEndpoint = original != null && original.endpoint().equals(endpoint);
    boolean sameModel = sameEndpoint && original.modelId().equals(model);
    var parameters =
        QwenThinkingOptions.apply(
            endpoint,
            model,
            sameModel ? original.parameters() : Map.of(),
            sameModel ? original.allowedParameters() : Set.of(),
            thinkingMode);
    if (original != null
        && !newCredential
        && original.displayName().equals(name)
        && original.endpoint().equals(endpoint)
        && original.modelId().equals(model)
        && original.structuredMode() == format
        && original.parameters().equals(parameters.values())
        && original.allowedParameters().equals(parameters.allowed())) return original;
    String id = "draft_" + purpose.key + "_" + nonce.toString().replace("-", "").substring(0, 16);
    return new ProviderProfile(
        id,
        1,
        name,
        endpoint,
        model,
        sameEndpoint && !newCredential ? original.credentialRef() : "key_" + id,
        format,
        parameters.values(),
        parameters.allowed(),
        sameEndpoint ? original.connectTimeout() : Duration.ofSeconds(10),
        sameEndpoint ? original.requestTimeout() : Duration.ofSeconds(120),
        sameModel ? original.pricing() : null,
        null);
  }
}
