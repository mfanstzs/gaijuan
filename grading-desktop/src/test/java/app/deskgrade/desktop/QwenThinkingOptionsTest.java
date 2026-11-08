package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QwenThinkingOptionsTest {
  private static final URI OFFICIAL = URI.create(LocalModelSetup.Vendor.QWEN.endpoint);

  @Test
  void keepingTheExistingDefaultNeverMaterializesAnyProviderParameter() {
    var result =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-flash", Map.of(), Set.of(), QwenThinkingOptions.Mode.KEEP);
    assertEquals(Map.of(), result.values());
    assertEquals(Set.of(), result.allowed());
    assertTrue(
        QwenThinkingOptions.summary(OFFICIAL, "qwen3.8-flash", result.values()).contains("供应商默认"));
  }

  @Test
  void onlyTheConfirmedOfficialEndpointAndExactModelNamesAreSupported() {
    for (String model : Set.of("qwen3.8-flash", "qwen3.8-max", "qwen3.8-max-0902"))
      assertTrue(QwenThinkingOptions.supported(OFFICIAL, model));
    for (String endpoint :
        Set.of(
            "https://api.openai.com/v1/chat/completions",
            "https://example.invalid/compatible-mode/v1/chat/completions",
            "https://dashscope.aliyuncs.com.evil.invalid/compatible-mode/v1/chat/completions",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions?alternate=1"))
      assertFalse(QwenThinkingOptions.supported(endpoint, "qwen3.8-flash"));
    for (String model :
        Set.of("qwen3.8-flash-preview", "qwen3.7-flash", "gpt-6-luna", "QWEN3.8-FLASH", ""))
      assertFalse(QwenThinkingOptions.supported(OFFICIAL, model));
    assertFalse(QwenThinkingOptions.supported(OFFICIAL, null));
    assertFalse(QwenThinkingOptions.supported("invalid URI", "qwen3.8-flash"));
  }

  @Test
  void threeModesHaveExactBooleanAndEffortSemantics() {
    var off =
        QwenThinkingOptions.apply(
            OFFICIAL,
            "qwen3.8-flash",
            Map.of("enable_thinking", "true", "reasoning_effort", "xhigh"),
            Set.of("enable_thinking", "reasoning_effort"),
            QwenThinkingOptions.Mode.NONE);
    assertEquals(Map.of("enable_thinking", "false"), off.values());
    assertEquals(Set.of("enable_thinking"), off.allowed());
    var low =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-max", Map.of(), Set.of(), QwenThinkingOptions.Mode.LOW);
    assertEquals(Map.of("enable_thinking", "true", "reasoning_effort", "low"), low.values());
    var high =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-max-0902", Map.of(), Set.of(), QwenThinkingOptions.Mode.HIGH);
    assertEquals(Map.of("enable_thinking", "true", "reasoning_effort", "xhigh"), high.values());
    assertEquals(Set.of("enable_thinking", "reasoning_effort"), high.allowed());
    assertEquals("不思考", QwenThinkingOptions.summary(OFFICIAL, "qwen3.8-flash", off.values()));
    assertEquals("少思考", QwenThinkingOptions.summary(OFFICIAL, "qwen3.8-max", low.values()));
    assertEquals("多思考", QwenThinkingOptions.summary(OFFICIAL, "qwen3.8-max", high.values()));
  }

  @Test
  void existingOtherOptionsAndTheCurrentMediumModeAreNeverRewrittenOnKeep() {
    var values =
        Map.of(
            "temperature",
            "0.1",
            "max_tokens",
            "4000",
            "reasoning_effort",
            "medium",
            "enable_thinking",
            "true");
    var allowed = values.keySet();
    var kept =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-flash", values, allowed, QwenThinkingOptions.Mode.KEEP);
    assertEquals(values, kept.values());
    assertEquals(allowed, kept.allowed());
    assertTrue(QwenThinkingOptions.summary(OFFICIAL, "qwen3.8-flash", values).contains("中档"));
    var high =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-flash", values, allowed, QwenThinkingOptions.Mode.HIGH);
    assertEquals("0.1", high.values().get("temperature"));
    assertEquals("4000", high.values().get("max_tokens"));
    assertEquals("xhigh", high.values().get("reasoning_effort"));
    assertEquals("medium", values.get("reasoning_effort"));
  }

  @Test
  void unsupportedTargetsCanBeKeptButExplicitModesCannotLeakQwenOptions() {
    var openai = URI.create(LocalModelSetup.Vendor.OPENAI.endpoint);
    var original = Map.of("temperature", "0.1");
    assertEquals(
        original,
        QwenThinkingOptions.apply(
                openai, "gpt-6-luna", original, original.keySet(), QwenThinkingOptions.Mode.KEEP)
            .values());
    assertEquals("", QwenThinkingOptions.summary(openai, "gpt-6-luna", original));
    for (var mode :
        Set.of(
            QwenThinkingOptions.Mode.NONE,
            QwenThinkingOptions.Mode.LOW,
            QwenThinkingOptions.Mode.HIGH)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> QwenThinkingOptions.apply(openai, "gpt-6-luna", original, original.keySet(), mode));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              QwenThinkingOptions.apply(
                  URI.create("https://example.invalid/v1/chat/completions"),
                  "qwen3.8-flash",
                  Map.of(),
                  Set.of(),
                  mode));
    }
  }

  @Test
  void preparedOptionsAreImmutableAndIndependentOfInputCollections() {
    var values = new HashMap<>(Map.of("temperature", "0.1"));
    var allowed = new HashSet<>(values.keySet());
    var result =
        QwenThinkingOptions.apply(
            OFFICIAL, "qwen3.8-flash", values, allowed, QwenThinkingOptions.Mode.LOW);
    values.clear();
    allowed.clear();
    assertEquals("0.1", result.values().get("temperature"));
    assertTrue(result.allowed().contains("temperature"));
    assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.allowed().clear());
  }
}
