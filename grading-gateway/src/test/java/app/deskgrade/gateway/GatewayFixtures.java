package app.deskgrade.gateway;

import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

final class GatewayFixtures {
  static char[] password() {
    return "synthetic test password 42".toCharArray();
  }

  static ProviderProfile profile() {
    return profile("synthetic-v1", "synthetic-model", true);
  }

  static ProviderProfile profile(String id, String model, boolean verified) {
    var p =
        new ProviderProfile(
            id,
            1,
            "Synthetic server model",
            URI.create("http://127.0.0.1:1/v1/chat/completions"),
            model,
            "synthetic-credential",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            Map.of(),
            Set.of(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            null,
            null);
    return !verified
        ? p
        : p.withVerification(
            new ProviderProfile.Verification(p.configDigest(), Instant.EPOCH, true, true));
  }

  static void activate(GatewayStore store, UUID admin, ProviderProfile profile) {
    store.bindProfile(profile.id(), profile.configDigest());
    store.selectModelPolicy(
        admin,
        UUID.randomUUID(),
        profile.id(),
        store.modelPolicy().revision(),
        "synthetic test policy",
        id -> profile.isVerified() && id.equals(profile.id()) ? profile.modelId() : null);
  }

  static Rubric rubric() {
    return new Rubric(
        new QuestionDefinition(
            "q1",
            1,
            "1+1=?",
            "2",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "c1", "Correct answer earns 1", List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "Synthetic fixed grading rule",
        BigDecimal.ONE,
        true,
        false);
  }

  static GradingResponse response(GradingRequest request) {
    return new GradingResponse(
        request.requestId(),
        GradingResponse.Source.MOCK,
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "2",
            List.of(
                new GradeResult.CriterionResult(
                    "c1", BigDecimal.ONE, "2", "Matches synthetic reference")),
            List.of()),
        Instant.now(),
        null,
        Duration.ofMillis(1));
  }

  static ObjectNode body(UUID id) {
    try {
      var image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, 12, 12);
      g.setColor(Color.BLACK);
      g.drawLine(1, 1, 10, 10);
      g.dispose();
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      var node = GatewayJson.MAPPER.createObjectNode();
      node.put("requestId", id.toString());
      node.put("profileId", profile().id());
      node.put("policyRevision", 1);
      node.set(
          "rubric",
          GatewayJson.parse(
              new RubricJsonCodec()
                  .write(rubric())
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      node.put("imageBase64", Base64.getEncoder().encodeToString(out.toByteArray()));
      return node;
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static ObjectNode publicBody(UUID id) {
    return body(id).put("profileId", "service_primary").put("policyRevision", 4_000_000_000_001L);
  }

  private GatewayFixtures() {}
}
