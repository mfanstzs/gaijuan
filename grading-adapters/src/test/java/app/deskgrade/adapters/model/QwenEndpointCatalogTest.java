package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class QwenEndpointCatalogTest {
  @Test
  void qwenAiUsesItsOwnDocumentedExactEndpointAndEditableVisionExamples() {
    assertEquals(
        URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
        QwenEndpointCatalog.qwenAiEndpoint());
    assertEquals(
        List.of("qwen3.8-flash", "qwen3.8-max"),
        QwenEndpointCatalog.qwenAiVisionModels().stream()
            .map(QwenEndpointCatalog.ModelCandidate::modelId)
            .toList());
    var draft =
        QwenEndpointCatalog.unverifiedProfile(
            "a",
            2,
            "千问 AI 平台",
            QwenEndpointCatalog.qwenAiEndpoint(),
            "user-exact-new-model-id",
            "local-secret-ref");
    assertFalse(draft.isVerified());
    assertNull(draft.verification());
    assertNull(draft.pricing());
    assertTrue(draft.parameters().isEmpty());
    assertTrue(draft.allowedParameters().isEmpty());
    assertEquals("user-exact-new-model-id", draft.modelId());
  }

  @Test
  void everyBailianRegionIsExplicitAndWorkspaceCannotInjectAnotherHostOrPath() {
    for (var region : QwenEndpointCatalog.Region.values()) {
      URI endpoint = QwenEndpointCatalog.bailianWorkspaceEndpoint(region, "llm-synthetic-123");
      assertEquals(
          "llm-synthetic-123." + region.regionId() + ".maas.aliyuncs.com", endpoint.getHost());
      assertEquals("/compatible-mode/v1/chat/completions", endpoint.getPath());
    }
    for (String invalid :
        new String[] {
          "",
          " ",
          "x.y",
          "x@evil.test",
          "https://host",
          "x/path",
          "../x",
          "x?y",
          "-x",
          "x-",
          "A",
          "a".repeat(64)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              QwenEndpointCatalog.bailianWorkspaceEndpoint(
                  QwenEndpointCatalog.Region.BEIJING, invalid));
    }
  }

  @Test
  void customServiceDraftRemainsPossibleAndCannotInheritProof() {
    var custom =
        QwenEndpointCatalog.unverifiedProfile(
            "custom",
            1,
            "用户自定义服务",
            URI.create("https://api.example.test/v1/chat/completions"),
            "exact/model",
            "ref");
    assertEquals("api.example.test", custom.endpoint().getHost());
    assertFalse(custom.isVerified());
  }
}
