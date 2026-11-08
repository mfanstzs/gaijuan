package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPerformanceLogTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneOffset.ofHours(8));
  @TempDir Path directory;

  @Test
  void oneTraceIsOneReadableLocalLineWithExclusivePhasesAndOverlappingNestedMetrics()
      throws Exception {
    Path logs = directory.resolve("performance");
    var summary = failedSummary();
    try (var log = new LocalPerformanceLog(logs, CLOCK, LocalGradingLog.MAX_FILE_BYTES)) {
      log.append(summary);
    }
    var rows = events(logs);
    assertEquals(1, rows.size());
    var event = rows.getFirst();
    assertEquals("ATTEMPT_TIMING_SUMMARY", event.path("event").asText());
    assertEquals("LOCAL_ONLY", event.path("storage").asText());
    assertEquals("MONOTONIC", event.path("timingClock").asText());
    assertEquals("milliseconds", event.path("durationUnit").asText());
    assertTrue(event.path("phaseExclusive").asBoolean());
    assertTrue(event.path("nestedMetricsOverlap").asBoolean());
    assertEquals(0, new BigDecimal("2345.679").compareTo(event.path("totalMs").decimalValue()));
    assertEquals(summary.traceId().toString(), event.path("traceId").asText());
    assertTrue(event.path("attemptId").isNull());
    assertTrue(event.path("untilAcknowledgedMs").isNull());
    assertEquals("STOPPED", event.path("finalState").asText());
    assertEquals("MODEL_FAILED", event.path("pauseReason").asText());
    assertEquals("WAIT_READY", event.path("phases").get(0).path("state").asText());
    assertEquals(2, event.path("metrics").get(0).path("count").asInt());
    assertEquals(1, event.path("metrics").get(0).path("failures").asInt());
    assertEquals("FAILED", event.path("modelCalls").get(0).path("outcome").asText());
    assertEquals(
        0,
        new BigDecimal("1.235")
            .compareTo(event.path("modelCalls").get(0).path("callbackQueueMs").decimalValue()));
    for (String forbidden :
        List.of(
            "Authorization",
            "credentialRef",
            "data:image",
            "studentText",
            "referenceAnswer",
            "endpoint",
            "http://",
            "https://",
            "png")) assertFalse(event.toString().contains(forbidden), forbidden);
    assertTrue(
        Files.readString(logs.resolve("performance-2026-10-21-000001.jsonl"))
            .contains("\"totalMs\":2345.679"));
  }

  @Test
  void reopeningAndRotationPreserveEveryEarlierTrace() throws Exception {
    Path logs = directory.resolve("performance");
    try (var log = new LocalPerformanceLog(logs, CLOCK, LocalGradingLog.MAX_FILE_BYTES)) {
      log.append(failedSummary());
    }
    Path first = logs.resolve("performance-2026-10-21-000001.jsonl");
    byte[] preserved = Files.readAllBytes(first);
    try (var reopened = new LocalPerformanceLog(logs, CLOCK, preserved.length + 1L)) {
      reopened.append(failedSummary());
    }
    assertArrayEquals(preserved, Files.readAllBytes(first));
    assertTrue(Files.exists(logs.resolve("performance-2026-10-21-000002.jsonl")));
    assertEquals(2, events(logs).size());
  }

  @Test
  void largestBoundedSummaryStillFitsTheSingleEventLimit() throws Exception {
    var base = failedSummary();
    var phases = new ArrayList<AttemptPerformance.PhaseTiming>();
    for (var phase : SessionState.values())
      phases.add(new AttemptPerformance.PhaseTiming(phase, Integer.MAX_VALUE, Long.MAX_VALUE));
    var metrics = new ArrayList<AttemptPerformance.MetricTiming>();
    for (var metric : AttemptPerformance.Metric.values())
      metrics.add(
          new AttemptPerformance.MetricTiming(
              metric, Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE));
    var calls = new ArrayList<AttemptPerformance.ModelTiming>();
    for (int i = 0; i < AttemptPerformance.MAX_MODEL_CALLS; i++)
      calls.add(
          new AttemptPerformance.ModelTiming(
              UUID.randomUUID(),
              AttemptPerformance.ModelRole.FALLBACK,
              Long.MAX_VALUE,
              Long.MAX_VALUE,
              AttemptPerformance.ModelOutcome.IN_FLIGHT));
    var codes =
        java.util.Collections.nCopies(AttemptPerformance.MAX_DIAGNOSTIC_CODES, "X".repeat(120));
    var full =
        new AttemptPerformance(
            base.runId(),
            base.traceId(),
            UUID.randomUUID(),
            Integer.MAX_VALUE,
            base.startedAt(),
            base.completedAt(),
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            SessionState.SUBMISSION_UNKNOWN,
            PauseReason.SUBMISSION_UNCERTAIN,
            phases,
            metrics,
            calls,
            codes);
    Path logs = directory.resolve("performance");
    try (var log = new LocalPerformanceLog(logs, CLOCK, LocalGradingLog.MAX_FILE_BYTES)) {
      log.append(full);
    }
    assertTrue(
        Files.size(logs.resolve("performance-2026-10-21-000001.jsonl"))
            <= LocalGradingLog.MAX_EVENT_BYTES);
    assertEquals("SUBMISSION_UNKNOWN", events(logs).getFirst().path("finalState").asText());
  }

  @Test
  void optionalPerformanceFailureCannotPoisonTheGradeLedgerOrRepeatSubmission() throws Exception {
    Path records = directory.resolve("records"), performance = directory.resolve("performance");
    Files.writeString(performance, "synthetic blocking file");
    try (var journal =
        new LocalLoggingSessionJournal(
            new H2SessionJournal(directory.resolve("database/ledger")), records)) {
      assertTrue(journal.performanceEnabled());
      var config = settings("{\"access\":{\"mode\":\"LOCAL\"}}", 1);
      journal.beginRun(RUN, "synthetic-local-performance", config, NOW);
      journal.beginAttempt(
          RUN,
          ATTEMPT,
          1,
          new SessionPlatform.View(
              "synthetic-answer", "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
          1,
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
          new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), NOW),
          config);
      var response = response(new GradingResponse.Usage(10L, 2L, null));
      journal.beginModelCall(response.requestId(), RUN, ATTEMPT, "synthetic-profile", NOW);
      journal.completeModelCall(response.requestId(), response, null, NOW.plusSeconds(1));
      journal.saveGrade(ATTEMPT, response, BigDecimal.ONE);
      journal.recordScoreReadback(ATTEMPT, BigDecimal.ONE, NOW.plusSeconds(1));
      journal.transition(
          RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW.plusSeconds(1));
      journal.transition(
          RUN, ATTEMPT, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW.plusSeconds(1));
      journal.confirmSubmission(
          RUN, ATTEMPT, NOW.plusSeconds(2), SessionPlatform.ConfirmationKind.SCREEN_TRANSITION);
      assertDoesNotThrow(() -> journal.recordPerformance(failedSummary()));
      assertDoesNotThrow(journal::assertWritable);
      assertTrue(journal.attempts(RUN).getFirst().confirmed());
      assertEquals(1, journal.runs().getFirst().modelCalls());
      assertEquals(1, journal.runs().getFirst().confirmedSubmissions());
      Files.move(performance, directory.resolve("preserved-blocking-file"));
      assertDoesNotThrow(() -> journal.recordPerformance(failedSummary()));
      assertEquals(1, events(performance).size());
      assertEquals(1, journal.runs().getFirst().modelCalls());
      assertEquals(1, journal.runs().getFirst().confirmedSubmissions());
    }
  }

  @Test
  void performanceStorageIsLazyAndDisabledForOrdinaryAuthoritativeJournal() throws Exception {
    Path performance = directory.resolve("performance");
    try (var journal =
        new LocalLoggingSessionJournal(
            new H2SessionJournal(directory.resolve("database/ledger")),
            directory.resolve("records"))) {
      assertTrue(journal.performanceEnabled());
      assertFalse(Files.exists(performance));
    }
    try (var journal = new H2SessionJournal(directory.resolve("database/ledger"))) {
      assertFalse(journal.performanceEnabled());
    }
    assertFalse(Files.exists(performance));
  }

  private static AttemptPerformance failedSummary() {
    return new AttemptPerformance(
        RUN,
        UUID.randomUUID(),
        null,
        1,
        NOW,
        NOW.plusSeconds(3),
        2_345_678_901L,
        null,
        SessionState.STOPPED,
        PauseReason.MODEL_FAILED,
        List.of(
            new AttemptPerformance.PhaseTiming(SessionState.WAIT_READY, 1, 300_000_000L),
            new AttemptPerformance.PhaseTiming(SessionState.GRADING, 1, 2_000_000_000L)),
        List.of(
            new AttemptPerformance.MetricTiming(
                AttemptPerformance.Metric.OBSERVE, 2, 1_100_000L, 1),
            new AttemptPerformance.MetricTiming(
                AttemptPerformance.Metric.CAPTURE_RAW, 1, 700_000L, 0)),
        List.of(
            new AttemptPerformance.ModelTiming(
                UUID.randomUUID(),
                AttemptPerformance.ModelRole.PRIMARY,
                2_000_000_000L,
                1_234_567L,
                AttemptPerformance.ModelOutcome.FAILED)),
        List.of("MODEL_CONNECT_TIMEOUT"));
  }

  private static List<JsonNode> events(Path logs) throws Exception {
    var result = new ArrayList<JsonNode>();
    try (var files = Files.list(logs)) {
      for (Path file : files.sorted().toList())
        for (String line : Files.readAllLines(file))
          if (!line.isBlank()) result.add(JSON.readTree(line));
    }
    return result;
  }
}
