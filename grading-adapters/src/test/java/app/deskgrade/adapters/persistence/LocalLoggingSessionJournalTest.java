package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLoggingSessionJournalTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PRIVATE_TEXT = "PRIVATE-STUDENT-TEXT-NOT-FOR-JSONL";
  @TempDir Path directory;

  @Test
  void localLedgerKeepsFullResultWhileJsonlContainsOnlyTypedMetadata() throws Exception {
    Path database = directory.resolve("database/ledger");
    Path logs = directory.resolve("records");
    UUID call = UUID.randomUUID();
    try (var journal = new LocalLoggingSessionJournal(new H2SessionJournal(database), logs)) {
      journal.registerProfile(profile());
      journal.assertWritable();
      beginLocal(journal);
      journal.beginModelCall(call, RUN, ATTEMPT, profile().id(), NOW);
      var response = response(call);
      journal.completeModelCall(call, response, null, NOW.plusMillis(123));
      journal.saveGrade(ATTEMPT, response, BigDecimal.ONE);
      journal.recordScoreReadback(ATTEMPT, BigDecimal.ONE, NOW.plusMillis(130));
      journal.transition(
          RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW.plusSeconds(1));
      journal.transition(
          RUN, ATTEMPT, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW.plusSeconds(2));
      journal.confirmSubmission(
          RUN, ATTEMPT, NOW.plusSeconds(3), SessionPlatform.ConfirmationKind.SCREEN_TRANSITION);

      assertEquals(
          1,
          journal
              .queryRecords(GradingRecords.Owner.local(), GradingRecords.Query.firstPage())
              .total());
      assertEquals(
          0,
          journal
              .queryRecords(GradingRecords.Owner.localDeveloper(), GradingRecords.Query.firstPage())
              .total());
      String audit = journal.recordJson(GradingRecords.Owner.local(), ATTEMPT);
      assertTrue(audit.contains(PRIVATE_TEXT));
      assertTrue(journal.attempts(RUN).getFirst().confirmed());
    }

    List<JsonNode> events = events(logs);
    var completed = named(events, "MODEL_CALL_COMPLETED");
    assertEquals(RUN.toString(), completed.path("runId").asText());
    assertEquals(ATTEMPT.toString(), completed.path("attemptId").asText());
    assertEquals(call.toString(), completed.path("callId").asText());
    assertEquals(23, completed.path("inputTokens").asInt());
    assertEquals(7, completed.path("outputTokens").asInt());
    assertEquals(123, completed.path("elapsedMs").asInt());
    assertEquals("SCORED", completed.path("gradingStatus").asText());
    assertEquals("qwen3.8-flash", named(events, "MODEL_CALL_STARTED").path("model").asText());
    assertEquals(1, named(events, "GRADE_VALIDATED").path("javaValidatedTotal").asInt());
    assertEquals(
        "SCREEN_TRANSITION",
        named(events, "PLATFORM_PROGRESS_RECORDED").path("confirmationKind").asText());
    String all = events.toString();
    for (String forbidden :
        List.of(
            PRIVATE_TEXT,
            "Authorization",
            "credentialRef",
            "data:image",
            "private-target",
            "private-answer",
            "private-evidence",
            "private-endpoint.invalid")) assertFalse(all.contains(forbidden), forbidden);
    assertTrue(events.stream().allMatch(e -> "LOCAL_ONLY".equals(e.path("storage").asText())));

    try (var reopened = new H2SessionJournal(database)) {
      assertTrue(reopened.attempts(RUN).getFirst().confirmed());
      assertEquals(
          1,
          reopened
              .queryRecords(GradingRecords.Owner.local(), GradingRecords.Query.firstPage())
              .total());
    }
  }

  @Test
  void failureCodesAreClosedVocabularyAndUnknownTokensAreNeverLogged() throws Exception {
    Path logs = directory.resolve("records");
    try (var journal =
        new LocalLoggingSessionJournal(new H2SessionJournal(directory.resolve("ledger")), logs)) {
      beginLocal(journal);
      UUID first = UUID.randomUUID();
      journal.beginModelCall(first, RUN, ATTEMPT, profile().id(), NOW);
      journal.completeModelCall(first, null, "MODEL_CONNECT_TIMEOUT", NOW.plusSeconds(10));
      UUID second = UUID.randomUUID();
      journal.beginModelCall(second, RUN, ATTEMPT, profile().id(), NOW.plusSeconds(11));
      journal.completeModelCall(second, null, "SECRET_CANARY", NOW.plusSeconds(12));
    }
    List<JsonNode> completed =
        events(logs).stream()
            .filter(n -> "MODEL_CALL_COMPLETED".equals(n.path("event").asText()))
            .toList();
    assertEquals("MODEL_CONNECT_TIMEOUT", completed.getFirst().path("safeErrorCode").asText());
    assertEquals(10000, completed.getFirst().path("elapsedMs").asInt());
    assertEquals("FAILED", completed.getFirst().path("outcome").asText());
    assertTrue(completed.getFirst().path("inputTokens").isNull());
    assertEquals("UNRECOGNIZED_FAILURE", completed.getLast().path("safeErrorCode").asText());
    assertFalse(completed.toString().contains("SECRET_CANARY"));
  }

  @Test
  void rubricGenerationAndCapabilityCallsUseTheSameLocalLogWithoutInventingAnswers()
      throws Exception {
    Path logs = directory.resolve("records");
    try (var journal =
        new LocalLoggingSessionJournal(new H2SessionJournal(directory.resolve("ledger")), logs)) {
      UUID call = UUID.randomUUID();
      journal.beginUtilityModelCall(
          call, LocalLoggingSessionJournal.UtilityPurpose.RUBRIC_GENERATION, profile(), NOW);
      journal.recordUtilityModelCall(
          call,
          LocalLoggingSessionJournal.UtilityPurpose.RUBRIC_GENERATION,
          profile(),
          new GradingResponse.Usage(12L, 8L, null),
          Duration.ofMillis(42),
          null,
          NOW.plusMillis(42));
      assertTrue(journal.runs().isEmpty());
    }
    List<JsonNode> events = events(logs);
    assertEquals(2, events.size());
    assertEquals("RUBRIC_GENERATION", events.getFirst().path("purpose").asText());
    assertEquals("qwen3.8-flash", events.getLast().path("model").asText());
    assertEquals(12, events.getLast().path("inputTokens").asInt());
    assertFalse(events.getLast().has("score"));
  }

  @Test
  void failedJsonlAppendIsNotIgnoredEvenAfterAuthoritativeLedgerCommitted() throws Exception {
    Path database = directory.resolve("ledger");
    Path logs = directory.resolve("records");
    try (var journal = new LocalLoggingSessionJournal(new H2SessionJournal(database), logs)) {
      beginLocal(journal);
      // A missing log directory makes the mandatory append fail; the committed ledger survives.
      Path moved = directory.resolve("preserved-records");
      Files.move(logs, moved);
      var failure =
          assertThrows(
              DeskGradeException.class,
              () ->
                  journal.transition(
                      RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW));
      assertEquals(ErrorCode.STORAGE_FAILURE, failure.code());
      assertTrue(journal.attempts(RUN).getFirst().submissionPrepared());
      assertFalse(journal.attempts(RUN).getFirst().confirmed());
      assertThrows(DeskGradeException.class, journal::assertWritable);
    }
    try (var reopened = new H2SessionJournal(database)) {
      assertTrue(reopened.hasUnknownSubmission("private-target"));
    }
  }

  @Test
  void explicitLocalOwnerDoesNotClaimCommercialOrLegacyDeveloperRecords() {
    assertNotEquals(
        H2RecordReader.ownerKey(GradingRecords.Owner.local()),
        H2RecordReader.ownerKey(GradingRecords.Owner.localDeveloper()));
    assertEquals(
        H2RecordReader.ownerKey(GradingRecords.Owner.local()),
        H2RecordReader.ownerFromSnapshot("{\"access\":{\"mode\":\"LOCAL\"}}"));
    assertEquals(
        H2RecordReader.ownerKey(GradingRecords.Owner.localDeveloper()),
        H2RecordReader.ownerFromSnapshot("{\"access\":{\"mode\":\"BYOK\"}}"));
    assertEquals("", H2RecordReader.ownerFromSnapshot("{}"));
    assertThrows(IllegalArgumentException.class, () -> new GradingRecords.Owner("", "other"));
  }

  private static void beginLocal(LocalLoggingSessionJournal journal) {
    var settings = settings("{\"access\":{\"mode\":\"LOCAL\"}}", 1);
    journal.beginRun(RUN, "private-target", settings, NOW);
    journal.beginAttempt(
        RUN,
        ATTEMPT,
        1,
        new SessionPlatform.View(
            "private-answer", "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
        1,
        "private-evidence",
        new SessionPlatform.Capture(new byte[] {1, 2, 3}, "0".repeat(64), NOW),
        settings);
  }

  private static ProviderProfile profile() {
    return new ProviderProfile(
        "synthetic-profile",
        1,
        "Synthetic",
        URI.create("https://private-endpoint.invalid/chat/completions"),
        "qwen3.8-flash",
        "private-reference",
        ProviderProfile.StructuredMode.JSON_OBJECT,
        Map.of(),
        Set.of(),
        Duration.ofSeconds(10),
        Duration.ofSeconds(120),
        null,
        null);
  }

  private static GradingResponse response(UUID call) {
    return new GradingResponse(
        call,
        GradingResponse.Source.LIVE,
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            PRIVATE_TEXT,
            List.of(
                new GradeResult.CriterionResult("p", BigDecimal.ONE, PRIVATE_TEXT, PRIVATE_TEXT)),
            List.of()),
        NOW,
        new GradingResponse.Usage(23L, 7L, null),
        Duration.ofMillis(123));
  }

  private static List<JsonNode> events(Path logs) throws Exception {
    List<JsonNode> result = new ArrayList<>();
    try (var files = Files.list(logs)) {
      for (Path file : files.sorted().toList()) {
        for (String line : Files.readAllLines(file))
          if (!line.isBlank()) result.add(JSON.readTree(line));
      }
    }
    return result;
  }

  private static JsonNode named(List<JsonNode> events, String name) {
    return events.stream()
        .filter(e -> name.equals(e.path("event").asText()))
        .findFirst()
        .orElseThrow();
  }
}
