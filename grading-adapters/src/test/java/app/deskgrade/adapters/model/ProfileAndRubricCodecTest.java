package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProfileAndRubricCodecTest {
  static ProviderProfile profile(URI endpoint) {
    return new ProviderProfile(
        "synthetic",
        1,
        "测试档位",
        endpoint,
        "synthetic-fixture-model",
        "credential-ref",
        ProviderProfile.StructuredMode.JSON_OBJECT,
        Map.of(),
        Set.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        null,
        null);
  }

  @Test
  void multipleProfilesRoundtripNoSecretAndImportDoesNotTrustVerification() {
    var p = profile(URI.create("http://127.0.0.1:8123/chat/completions"));
    p =
        p.withVerification(
            new ProviderProfile.Verification(p.configDigest(), Instant.EPOCH, true, true));
    var second =
        new ProviderProfile(
            "second",
            2,
            "第二档位",
            p.endpoint(),
            "second-model",
            p.credentialRef(),
            p.structuredMode(),
            Map.of(),
            Set.of(),
            p.connectTimeout(),
            p.requestTimeout(),
            null,
            null);
    var codec = new ProfileJsonCodec();
    String json = codec.write(List.of(p, second));
    assertEquals(2, codec.read(json).size());
    assertFalse(codec.read(json).getFirst().isVerified());
    assertTrue(codec.readLocal(json).getFirst().isVerified());
    assertFalse(json.contains("Authorization"));
  }

  @Test
  void anyModelParameterOrRevisionChangeInvalidatesVerification() {
    var p = profile(URI.create("http://127.0.0.1:8123/chat/completions"));
    var proof = new ProviderProfile.Verification(p.configDigest(), Instant.EPOCH, true, true);
    assertTrue(p.withVerification(proof).isVerified());
    var changed =
        new ProviderProfile(
            p.id(),
            2,
            p.displayName(),
            p.endpoint(),
            p.modelId(),
            p.credentialRef(),
            p.structuredMode(),
            p.parameters(),
            p.allowedParameters(),
            p.connectTimeout(),
            p.requestTimeout(),
            null,
            proof);
    assertFalse(changed.isVerified());
  }

  @Test
  void documentedThinkingEffortRoundtripsWithoutLosingTypeOrTrustBoundaries() {
    var base = profile(URI.create("http://127.0.0.1:8123/chat/completions"));
    var codec = new ProfileJsonCodec();
    for (String effort : List.of("low", "medium", "xhigh")) {
      var profile =
          new ProviderProfile(
              base.id(),
              base.revision(),
              base.displayName(),
              base.endpoint(),
              "qwen3.8-flash",
              base.credentialRef(),
              base.structuredMode(),
              Map.of("enable_thinking", "true", "reasoning_effort", effort),
              Set.of("enable_thinking", "reasoning_effort"),
              base.connectTimeout(),
              base.requestTimeout(),
              null,
              null);
      var verified =
          profile.withVerification(
              new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, true, true));
      String document = codec.write(List.of(verified));
      var local = codec.readLocal(document).getFirst();
      assertEquals(verified, local);
      assertEquals(effort, local.parameters().get("reasoning_effort"));
      assertTrue(local.isVerified());
      assertFalse(codec.read(document).getFirst().isVerified());
    }
  }

  @Test
  void trustedLocalStoreAccepts256ProfilesButPortableImportsRemainLimitedTo20() {
    var base = profile(URI.create("http://127.0.0.1:8123/chat/completions"));
    var many =
        java.util.stream.IntStream.range(0, 257)
            .mapToObj(
                index ->
                    new ProviderProfile(
                        "bulk_" + index,
                        1,
                        base.displayName(),
                        base.endpoint(),
                        base.modelId(),
                        base.credentialRef(),
                        base.structuredMode(),
                        base.parameters(),
                        base.allowedParameters(),
                        base.connectTimeout(),
                        base.requestTimeout(),
                        null,
                        null))
            .toList();
    var codec = new ProfileJsonCodec();
    assertEquals(20, codec.read(codec.write(many.subList(0, 20))).size());
    assertThrows(ProviderException.class, () -> codec.read(codec.write(many.subList(0, 21))));
    assertEquals(256, codec.readLocal(codec.write(many.subList(0, 256))).size());
    assertThrows(ProviderException.class, () -> codec.readLocal(codec.write(many)));
  }

  @Test
  void bothLocalAndPortableReadersEnforceTheSameOneMebibyteDocumentBoundary() {
    var codec = new ProfileJsonCodec();
    String document =
        codec.write(List.of(profile(URI.create("http://127.0.0.1:8123/chat/completions"))));
    int bytes = document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    String exact = document + " ".repeat(ProfileJsonCodec.MAX_DOCUMENT_BYTES - bytes);
    assertEquals(1, codec.readLocal(exact).size());
    assertEquals(1, codec.read(exact).size());
    assertThrows(ProviderException.class, () -> codec.readLocal(exact + " "));
    assertThrows(ProviderException.class, () -> codec.read(exact + " "));
  }

  @Test
  void refusesUnsupportedParametersUnsafeEndpointAndCredentialsInUrl() {
    var p = profile(URI.create("http://127.0.0.1:8123/chat/completions"));
    assertThrows(
        RuntimeException.class,
        () ->
            new ProviderProfile(
                p.id(),
                1,
                p.displayName(),
                p.endpoint(),
                p.modelId(),
                p.credentialRef(),
                p.structuredMode(),
                Map.of("temperature", "0.2"),
                Set.of(),
                p.connectTimeout(),
                p.requestTimeout(),
                null,
                null));
    assertThrows(
        RuntimeException.class, () -> profile(URI.create("http://example.com/chat/completions")));
    assertThrows(
        RuntimeException.class,
        () -> profile(URI.create("https://secret@example.com/chat/completions")));
  }

  @Test
  void completeRubricRoundtripKeepsOriginalRules() {
    var r = new Rubric(GradeResultJsonCodecTest.QUESTION, "原始规则，不改写", BigDecimal.ONE, true, false);
    var codec = new RubricJsonCodec();
    assertEquals(r, codec.read(codec.write(r)));
  }

  @Test
  void translationExampleAllowsMissingSourceWithoutInventingItButStillRequiresGradingInputs()
      throws Exception {
    Path path = Path.of("../examples/translation-rubric.json");
    var codec = new RubricJsonCodec();
    var rubric = codec.read(Files.readString(path));
    assertEquals("[原文缺失]", rubric.question().prompt());
    assertFalse(rubric.requiresSourceText());
    assertTrue(rubric.missingSourceAccepted());
    assertDoesNotThrow(rubric::validateReady);
    var roundtrip = codec.read(codec.write(rubric));
    assertEquals("[原文缺失]", roundtrip.question().prompt());
    assertFalse(roundtrip.requiresSourceText());
    assertTrue(roundtrip.missingSourceAccepted());
    assertFalse(rubric.question().standardAnswer().isBlank());
    assertFalse(rubric.rawRubric().isBlank());
    assertFalse(rubric.question().criteria().isEmpty());
    assertEquals(
        List.of(BigDecimal.ZERO, new BigDecimal("2")),
        rubric.question().criteria().getFirst().allowedScores());

    var question = rubric.question();
    var withoutStandardAnswer =
        new QuestionDefinition(
            question.id(),
            question.revision(),
            question.prompt(),
            "",
            question.maximumScore(),
            question.criteria());
    var incomplete =
        new Rubric(
            withoutStandardAnswer,
            rubric.rawRubric(),
            rubric.scoreStep(),
            rubric.requiresSourceText(),
            rubric.missingSourceAccepted());
    var missingAnswer = assertThrows(DeskGradeException.class, incomplete::validateReady);
    assertEquals(ErrorCode.CONFIGURATION_REQUIRED, missingAnswer.code());

    var missingRules =
        assertThrows(
            DeskGradeException.class,
            () ->
                new Rubric(
                    question,
                    " ",
                    rubric.scoreStep(),
                    rubric.requiresSourceText(),
                    rubric.missingSourceAccepted()));
    assertEquals(ErrorCode.INVALID_ARGUMENT, missingRules.code());
  }

  @Test
  void shippedPromptStaysEqualToAuthoritativePrompt() throws Exception {
    String authoritative = Files.readString(Path.of("../prompts/GRADING_SYSTEM.md"));
    String shipped = Files.readString(Path.of("src/main/resources/model/GRADING_SYSTEM.md"));
    assertEquals(authoritative, shipped);
    assertTrue(shipped.contains("studentText=\"\""));
    assertTrue(shipped.contains("每个评分点的 allowedScores 都包含 0"));
    assertTrue(shipped.contains("纯白加载页"));
    assertTrue(shipped.contains("没有模型响应或请求失败"));
    assertTrue(shipped.contains("不要求答题框的全部外围线框都在截图内"));
    assertTrue(shipped.contains("均不得据此认定本题不完整"));
    assertTrue(shipped.contains("不得把部分可见或模糊的作答当成空白"));
  }
}
