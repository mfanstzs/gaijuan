package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class H2TestFixtures {
  static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID ATTEMPT = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
  static final String TARGET = "synthetic-window-only";

  static SessionSettings settings(String json, long modelRevision) {
    var question =
        new QuestionDefinition(
            "q",
            1,
            "合成原文",
            "合成标准",
            BigDecimal.ONE,
            List.of(new ScoringCriterion("p", "合成评分点", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    return new SessionSettings(
        question,
        "synthetic-profile",
        modelRevision,
        1,
        json,
        20,
        25,
        0,
        null,
        Duration.ofMillis(1),
        Duration.ofSeconds(1));
  }

  static SessionSettings settings() {
    return settings("{\"source\":\"MOCK\",\"revision\":1}", 1);
  }

  static void begin(H2SessionJournal journal) {
    journal.beginRun(RUN, TARGET, settings(), NOW);
    beginAttempt(journal, RUN, ATTEMPT, 1, settings());
  }

  static void beginAttempt(
      H2SessionJournal journal, UUID run, UUID attempt, int ordinal, SessionSettings settings) {
    journal.beginAttempt(
        run,
        attempt,
        ordinal,
        new SessionPlatform.View(
            "synthetic-answer-" + ordinal,
            "q",
            1,
            SessionPlatform.Page.READY,
            null,
            0,
            null,
            false),
        1,
        "synthetic-evidence.png",
        new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), NOW),
        settings);
  }

  static GradingResponse response(GradingResponse.Usage usage) {
    return new GradingResponse(
        UUID.randomUUID(),
        GradingResponse.Source.MOCK,
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "合成答案",
            List.of(new GradeResult.CriterionResult("p", BigDecimal.ONE, "合成证据", "合成依据")),
            List.of()),
        NOW,
        usage,
        Duration.ofMillis(10));
  }
}
