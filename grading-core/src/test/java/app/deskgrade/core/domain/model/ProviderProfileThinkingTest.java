package app.deskgrade.core.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderProfileThinkingTest {
  private static final Set<String> DECLARED = Set.of("enable_thinking", "reasoning_effort");

  @ParameterizedTest
  @ValueSource(strings = {"low", "medium", "xhigh"})
  void acceptsOnlyExplicitDocumentedEffortValuesWithoutInventingDefaults(String effort) {
    var thinking = profile(Map.of("enable_thinking", "true", "reasoning_effort", effort));
    assertEquals(effort, thinking.parameters().get("reasoning_effort"));
    assertEquals("true", thinking.parameters().get("enable_thinking"));
    var providerDefault = profile(Map.of("reasoning_effort", effort));
    assertEquals(Map.of("reasoning_effort", effort), providerDefault.parameters());
    assertFalse(providerDefault.parameters().containsKey("enable_thinking"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"none", "minimal", "high", "max", "LOW", " xhigh", "xhigh ", "1", "true", ""})
  void rejectsUndocumentedAliasesAndNonEffortValues(String effort) {
    var error =
        assertThrows(
            DeskGradeException.class,
            () -> profile(Map.of("enable_thinking", "true", "reasoning_effort", effort)));
    assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"low", "medium", "xhigh"})
  void disabledThinkingCannotAlsoRequestAnEffort(String effort) {
    assertThrows(
        DeskGradeException.class,
        () -> profile(Map.of("enable_thinking", "false", "reasoning_effort", effort)));
    assertEquals(
        Map.of("enable_thinking", "false"),
        profile(Map.of("enable_thinking", "false")).parameters());
  }

  @Test
  void eachModeChangesBothVerificationDigestsAndNoModeIsEnabledByDefault() {
    var unchanged = profile(Map.of());
    var proof =
        new ProviderProfile.Verification(unchanged.configDigest(), Instant.EPOCH, true, true);
    assertTrue(unchanged.withVerification(proof).isVerified());
    assertTrue(unchanged.withVerification(proof).isStructuredVerified());
    assertTrue(unchanged.parameters().isEmpty());
    for (Map<String, String> mode :
        List.of(
            Map.of("enable_thinking", "false"),
            Map.of("enable_thinking", "true", "reasoning_effort", "low"),
            Map.of("enable_thinking", "true", "reasoning_effort", "medium"),
            Map.of("enable_thinking", "true", "reasoning_effort", "xhigh"))) {
      var changed = profile(mode).withVerification(proof);
      assertNotEquals(unchanged.configDigest(), changed.configDigest());
      assertFalse(changed.isVerified());
      assertFalse(changed.isStructuredVerified());
      var newProof =
          new ProviderProfile.Verification(changed.configDigest(), Instant.EPOCH, true, true);
      assertTrue(changed.withVerification(newProof).isVerified());
    }
  }

  @Test
  void unsupportedBudgetAndUndeclaredEffortRemainRejected() {
    assertThrows(DeskGradeException.class, () -> profile(Map.of("thinking_budget", "2048")));
    var base = profile(Map.of());
    assertThrows(
        DeskGradeException.class,
        () ->
            new ProviderProfile(
                base.id(),
                base.revision(),
                base.displayName(),
                base.endpoint(),
                base.modelId(),
                base.credentialRef(),
                base.structuredMode(),
                Map.of("reasoning_effort", "low"),
                Set.of("enable_thinking"),
                base.connectTimeout(),
                base.requestTimeout(),
                null,
                null));
  }

  private static ProviderProfile profile(Map<String, String> parameters) {
    return new ProviderProfile(
        "thinking_fixture",
        1,
        "合成思考参数",
        URI.create("http://127.0.0.1/chat/completions"),
        "qwen3.8-flash",
        "synthetic-ref",
        ProviderProfile.StructuredMode.JSON_OBJECT,
        parameters,
        DECLARED,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        null,
        null);
  }
}
