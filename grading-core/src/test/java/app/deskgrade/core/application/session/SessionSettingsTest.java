package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionSettingsTest {
  @Test
  void rejectsSubMillisecondBusyPollingAndUnboundedConfigurationSnapshots() {
    assertThrows(
        IllegalArgumentException.class, () -> settings("{}", Duration.ofNanos(999_999), "model_1"));
    assertDoesNotThrow(() -> settings("{}", Duration.ofMillis(1), "model_1"));

    String exact = "a".repeat(SessionSettings.MAX_CONFIGURATION_BYTES);
    assertDoesNotThrow(() -> settings(exact, Duration.ofMillis(1), "model_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> settings(exact + "a", Duration.ofMillis(1), "model_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> settings("\u4e2d".repeat(349_526), Duration.ofMillis(1), "model_1"));
  }

  @Test
  void rejectsProfileIdentifiersThatCannotBelongToAProviderProfile() {
    assertThrows(
        IllegalArgumentException.class,
        () -> settings("{}", Duration.ofMillis(1), "../forged-profile"));
    assertThrows(
        IllegalArgumentException.class,
        () -> settings("{}", Duration.ofMillis(1), "x".repeat(101)));
  }

  private static SessionSettings settings(String configuration, Duration poll, String profile) {
    var question =
        new QuestionDefinition(
            "q1",
            1,
            "question",
            "answer",
            BigDecimal.ONE,
            List.of(new ScoringCriterion("c1", "criterion", List.of(BigDecimal.ONE))));
    return new SessionSettings(
        question, profile, 1, 1, configuration, 0, 0, 0, null, poll, Duration.ofSeconds(1));
  }
}
