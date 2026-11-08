package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Opt-in: two synthetic-image requests can incur fees. Default verify never discovers *IT. */
class LiveVisionApiIT {
  @Test
  void authorizedSelectedProfileReadsSyntheticImageAndGradesOneWrongAnswer() throws Exception {
    assertEquals(
        "true",
        System.getProperty("deskgrade.liveApi.allowPaid"),
        "Explicit paid API authorization is required.");
    assertTrue(
        System.getProperty("os.name", "").startsWith("Windows"),
        "The configured credential store requires the same Windows user.");
    Path profiles = Path.of(required("deskgrade.liveApi.profileFile"));
    String id = required("deskgrade.liveApi.profileId");
    Path secretDirectory = Path.of(required("deskgrade.liveApi.secretDirectory"));
    assertTrue(Files.isRegularFile(profiles));
    assertTrue(Files.size(profiles) <= 1048576);
    var profile =
        new ProfileJsonCodec()
            .read(Files.readString(profiles)).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow();
    assertEquals(
        "https",
        profile.endpoint().getScheme(),
        "Live API verification requires the selected HTTPS endpoint.");
    var question =
        new QuestionDefinition(
            "synthetic-live-7-plus-5",
            1,
            "计算 7 + 5。学生填写的答案在图片中。",
            "12",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "calculation",
                    "图片中学生填12得1分，否则得0分；不可把参考答案当学生答案。",
                    List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    var rubric =
        new Rubric(question, "本合成单题满分1分。学生计算答案完全等于12才得1分；错误答案得0分。", BigDecimal.ONE, true, false);
    var secrets = new DpapiSecretStore(secretDirectory);
    long timeout = profile.requestTimeout().toSeconds() + 10;
    try (var unverified =
        new OpenAICompatibleVisionProvider(profile, rubric, secrets, Clock.systemUTC())) {
      var proof =
          unverified
              .capabilityTestDetailed(CancellationToken.none())
              .toCompletableFuture()
              .get(timeout, TimeUnit.SECONDS);
      assertTrue(
          proof.verification().imagePassed(),
          "Selected profile did not read the random synthetic image correctly.");
      assertTrue(proof.verification().structuredOutputPassed());
      try (var verified =
          new OpenAICompatibleVisionProvider(
              profile.withVerification(proof.verification()), rubric, secrets, Clock.systemUTC())) {
        var result =
            verified
                .grade(
                    new GradingRequest(UUID.randomUUID(), question, wrongAnswerImage()),
                    CancellationToken.none())
                .toCompletableFuture()
                .get(timeout, TimeUnit.SECONDS);
        assertEquals(GradingResponse.Source.LIVE, result.source());
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                GradeValidator.validate(question, result.result()).totalScore()),
            "The deliberately wrong synthetic answer must not receive the reference answer's score.");
        // No input executor or platform adapter is constructed anywhere in this read-only test.
      }
    }
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    assertNotNull(value, "Missing local live-test setting: " + property);
    assertFalse(value.isBlank(), "Empty local live-test setting: " + property);
    return value;
  }

  private static byte[] wrongAnswerImage() throws Exception {
    var image = new BufferedImage(460, 140, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 460, 140);
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 54));
      graphics.drawString("7 + 5 = 11", 45, 95);
    } finally {
      graphics.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }
}
