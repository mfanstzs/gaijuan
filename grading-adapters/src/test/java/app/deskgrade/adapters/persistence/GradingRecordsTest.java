package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.error.DeskGradeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradingRecordsTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final GradingRecords.Owner ALICE =
      new GradingRecords.Owner("http://127.0.0.1:8787", "synthetic-alice");
  private static final GradingRecords.Owner BOB =
      new GradingRecords.Owner("http://127.0.0.1:8787", "synthetic-bob");

  @Test
  void opaqueCommercialOwnerScopeRestoresOwnershipWithoutPersistingAccountDetails() {
    String scope = "a".repeat(64);
    String snapshot = "{\"access\":{\"mode\":\"COMMERCIAL\",\"ownerScope\":\"" + scope + "\"}}";

    assertEquals(scope, H2RecordReader.ownerFromSnapshot(snapshot));
    assertEquals(scope, MySqlRecordReader.ownerFromSnapshot(snapshot));
    assertEquals("", H2RecordReader.ownerFromSnapshot(snapshot.replace(scope, "not-a-hash")));
    assertEquals("", MySqlRecordReader.ownerFromSnapshot(snapshot.replace(scope, "not-a-hash")));
  }

  private H2SessionJournal journal() {
    return new H2SessionJournal(directory.resolve("ledger"));
  }

  private SessionSettings owned(GradingRecords.Owner owner) throws Exception {
    var rubric = new Rubric(settings().question(), "按合成标准评分；不猜测缺失答案", BigDecimal.ONE, false, false);
    String snapshot =
        JSON.writeValueAsString(
            Map.of(
                "mode",
                "READ_ONLY_TRIAL",
                "access",
                Map.of(
                    "mode",
                    "COMMERCIAL",
                    "gateway",
                    owner.gateway(),
                    "accountId",
                    owner.accountId()),
                "rubric",
                new RubricJsonCodec().write(rubric),
                "gradingInstructions",
                "保留完整学生转写和逐项依据",
                "profile",
                Map.of(
                    "serverProfileId",
                    "synthetic-profile",
                    "modelId",
                    "SYNTHETIC_MODEL",
                    "revision",
                    1)));
    return settings(snapshot, 1);
  }

  private record Attempt(UUID run, UUID id, UUID request) {}

  private Attempt begin(
      H2SessionJournal journal, GradingRecords.Owner owner, String answer, java.time.Instant at)
      throws Exception {
    var result = new Attempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var settings = owned(owner);
    journal.beginRun(result.run(), "synthetic-target", settings, at);
    journal.beginAttempt(
        result.run(),
        result.id(),
        1,
        new SessionPlatform.View(answer, "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
        1,
        MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
        new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), at),
        settings);
    journal.beginModelCall(result.request(), result.run(), result.id(), "synthetic-profile", at);
    return result;
  }

  private GradingResponse returned(UUID id, GradeResult result) {
    return new GradingResponse(
        id, GradingResponse.Source.MOCK, result, NOW, null, java.time.Duration.ofMillis(25));
  }

  private GradeResult scored() {
    return response(null).result();
  }

  private JsonNode audit(H2SessionJournal journal, Attempt attempt) throws Exception {
    return JSON.readTree(journal.recordJson(ALICE, attempt.id()));
  }

  @Test
  void fixedJsonPreservesStudentRulesModelAndSeparatesReadbackFromSubmission() throws Exception {
    try (var journal = journal()) {
      var attempt = begin(journal, ALICE, "synthetic-1", NOW);
      var returned = returned(attempt.request(), scored());
      journal.completeModelCall(attempt.request(), returned, null, NOW);
      journal.saveGrade(attempt.id(), returned, BigDecimal.ONE);
      assertThrows(
          DeskGradeException.class,
          () -> journal.recordScoreReadback(attempt.id(), BigDecimal.ZERO, NOW));
      journal.transition(attempt.run(), attempt.id(), SessionState.FILLING, PauseReason.NONE, NOW);
      journal.recordScoreReadback(attempt.id(), BigDecimal.ONE, NOW);
      var record = audit(journal, attempt);
      assertEquals(2, record.path("schemaVersion").asInt());
      assertEquals("合成答案", record.at("/modelCalls/0/modelResult/studentText").asText());
      assertEquals("合成依据", record.at("/modelCalls/0/modelResult/criteria/0/reason").asText());
      assertEquals("合成证据", record.at("/modelCalls/0/modelResult/criteria/0/evidence").asText());
      assertEquals("合成标准", record.path("standardAnswer").asText());
      assertEquals("按合成标准评分；不猜测缺失答案", record.at("/rubricSnapshot/rawRubric").asText());
      assertEquals("保留完整学生转写和逐项依据", record.at("/configuration/gradingInstructions").asText());
      assertEquals("SYNTHETIC_MODEL", record.at("/modelSnapshot/modelId").asText());
      assertEquals(1, record.path("javaValidatedTotal").asInt());
      assertEquals("READBACK_MATCHED", record.at("/input/status").asText());
      assertEquals("NOT_PREPARED", record.at("/submission/status").asText());
      assertTrue(record.at("/modelCalls/0/estimatedCost").isNull());
      assertTrue(record.at("/modelCalls/0/acceptedAsAttemptGrade").asBoolean());
      assertEquals("NOT_STORED", record.path("imagePersistence").asText());
      assertFalse(record.has("evidencePath"));
      journal.transition(
          attempt.run(), attempt.id(), SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
      journal.transition(
          attempt.run(), attempt.id(), SessionState.SUBMIT_SENT, PauseReason.NONE, NOW);
      journal.recoverInterrupted(NOW);
      assertEquals("UNKNOWN", audit(journal, attempt).at("/submission/status").asText());
      journal.confirmSubmission(attempt.run(), attempt.id(), NOW);
      assertEquals("CONFIRMED", audit(journal, attempt).at("/submission/status").asText());
    }
  }

  @Test
  void lateReturnedJsonIsPreservedWithoutAcceptingAPlatformScore() throws Exception {
    UUID run, attempt;
    try (var journal = journal()) {
      var a = begin(journal, ALICE, "late", NOW);
      run = a.run();
      attempt = a.id();
      journal.transition(a.run(), a.id(), SessionState.PAUSED, PauseReason.USER_PAUSED, NOW);
      journal.completeModelCall(a.request(), returned(a.request(), scored()), null, NOW);
    }
    try (var reopened = journal()) {
      var record = JSON.readTree(reopened.recordJson(ALICE, attempt));
      assertEquals(run.toString(), record.path("runId").asText());
      assertTrue(record.path("javaValidatedTotal").isNull());
      assertEquals("VALIDATED", record.at("/modelCalls/0/validationStatus").asText());
      assertEquals(1, record.at("/modelCalls/0/javaValidatedTotal").asInt());
      assertFalse(record.at("/modelCalls/0/acceptedAsAttemptGrade").asBoolean());
      assertEquals("NOT_PREPARED", record.at("/submission/status").asText());
    }
  }

  @Test
  void unableAndInvalidBusinessResultsRemainRecordedWithoutZeroScore() throws Exception {
    try (var journal = journal()) {
      var unable = begin(journal, ALICE, "unable", NOW);
      var body =
          new GradeResult(
              GradeResult.Status.UNABLE,
              GradeResult.Readability.UNREADABLE,
              null,
              List.of(new GradeResult.CriterionResult("p", null, null, "无法识别")),
              List.of("图中文字不清楚"));
      var response = returned(unable.request(), body);
      journal.completeModelCall(unable.request(), response, null, NOW);
      assertThrows(
          DeskGradeException.class,
          () -> journal.saveGrade(unable.id(), response, BigDecimal.ZERO));
      journal.saveGrade(unable.id(), response, null);
      assertTrue(audit(journal, unable).path("javaValidatedTotal").isNull());
      assertEquals("UNABLE", audit(journal, unable).at("/modelCalls/0/validationStatus").asText());
      var invalid = begin(journal, ALICE, "invalid", NOW);
      var wrong =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              "原样保留的合成文字",
              List.of(
                  new GradeResult.CriterionResult("wrong-criterion", BigDecimal.ONE, "依据", "原因")),
              List.of());
      var wrongResponse = returned(invalid.request(), wrong);
      journal.completeModelCall(invalid.request(), wrongResponse, null, NOW);
      assertThrows(
          DeskGradeException.class,
          () -> journal.saveGrade(invalid.id(), wrongResponse, BigDecimal.ONE));
      var record = audit(journal, invalid);
      assertEquals("INVALID_RESULT", record.at("/modelCalls/0/validationStatus").asText());
      assertEquals("原样保留的合成文字", record.at("/modelCalls/0/modelResult/studentText").asText());
      assertTrue(record.path("javaValidatedTotal").isNull());
    }
  }

  @Test
  void acceptedGradeMustExactlyMatchCompletedCallForSameAttemptAndJavaSum() throws Exception {
    try (var journal = journal()) {
      var first = begin(journal, ALICE, "first", NOW);
      var second = begin(journal, ALICE, "second", NOW);
      var returned = returned(first.request(), scored());
      assertThrows(
          DeskGradeException.class, () -> journal.saveGrade(first.id(), returned, BigDecimal.ONE));
      journal.completeModelCall(first.request(), returned, null, NOW);
      assertThrows(
          DeskGradeException.class, () -> journal.saveGrade(second.id(), returned, BigDecimal.ONE));
      assertThrows(
          DeskGradeException.class, () -> journal.saveGrade(first.id(), returned, BigDecimal.ZERO));
      var changed =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              "被替换的转写",
              scored().criteria(),
              List.of());
      assertThrows(
          DeskGradeException.class,
          () -> journal.saveGrade(first.id(), returned(first.request(), changed), BigDecimal.ONE));
      journal.saveGrade(first.id(), returned, BigDecimal.ONE);
      var mismatch = returned(UUID.randomUUID(), scored());
      journal.completeModelCall(second.request(), mismatch, null, NOW);
      assertEquals(
          "REQUEST_ID_MISMATCH",
          audit(journal, second).at("/modelCalls/0/validationStatus").asText());
      assertTrue(audit(journal, second).at("/modelCalls/0/javaValidatedTotal").isNull());
    }
  }

  @Test
  void malformedHttpResponsesStoreOnlySafeFailureWithoutInventedModelResult() throws Exception {
    try (var journal = journal()) {
      var a = begin(journal, ALICE, "failure", NOW);
      journal.completeModelCall(a.request(), null, "MODEL_INVALID_JSON", NOW);
      var result = audit(journal, a);
      assertEquals("MODEL_INVALID_JSON", result.at("/modelCalls/0/errorCode").asText());
      assertTrue(result.at("/modelCalls/0/modelResult").isNull());
      assertTrue(result.path("javaValidatedTotal").isNull());
    }
  }

  @Test
  void databasePaginationFiltersAndDetailCannotCrossAccountsOrServices() throws Exception {
    try (var journal = journal()) {
      var a = begin(journal, ALICE, "literal_%", NOW);
      begin(journal, ALICE, "second", NOW.plusSeconds(1));
      begin(journal, ALICE, "third", NOW.plusSeconds(2));
      var b = begin(journal, BOB, "bob", NOW);
      var otherService = new GradingRecords.Owner("http://127.0.0.1:8788", ALICE.accountId());
      var elsewhere = begin(journal, otherService, "elsewhere", NOW);
      var first =
          journal.queryRecords(
              ALICE, new GradingRecords.Query(null, "", "", null, null, null, 0, 2));
      var second =
          journal.queryRecords(
              ALICE, new GradingRecords.Query(null, "", "", null, null, null, 1, 2));
      assertEquals(3, first.total());
      assertEquals(2, first.rows().size());
      assertEquals(1, second.rows().size());
      assertTrue(first.hasNext());
      assertFalse(second.hasNext());
      Set<UUID> ids = new HashSet<>();
      first.rows().forEach(r -> assertTrue(ids.add(r.attemptId())));
      second.rows().forEach(r -> assertTrue(ids.add(r.attemptId())));
      assertThrows(DeskGradeException.class, () -> journal.recordJson(ALICE, b.id()));
      assertThrows(DeskGradeException.class, () -> journal.recordJson(ALICE, elsewhere.id()));
      var literal =
          journal.queryRecords(
              ALICE, new GradingRecords.Query(null, "_%", "", null, null, null, 0, 25));
      assertEquals(1, literal.total());
      assertEquals(a.id(), literal.rows().getFirst().attemptId());
      assertEquals(
          1,
          journal
              .queryRecords(
                  ALICE,
                  new GradingRecords.Query(
                      null,
                      "",
                      "q",
                      SessionState.CAPTURING,
                      NOW.plusSeconds(1),
                      NOW.plusSeconds(2),
                      0,
                      25))
              .total());
      assertThrows(
          NullPointerException.class,
          () -> journal.queryRecords(null, GradingRecords.Query.firstPage()));
    }
  }

  @Test
  void jsonExportUsesAllFilteredPagesOnlyCurrentOwnerAndNoImageFile() throws Exception {
    try (var journal = journal()) {
      for (int i = 0; i < 4; i++) begin(journal, ALICE, "export-" + i, NOW.plusSeconds(i));
      begin(journal, BOB, "private-bob-record", NOW);
      Path target = directory.resolve("records.json");
      journal.exportJson(
          target, ALICE, new GradingRecords.Query(null, "export-", "", null, null, null, 1, 2));
      JsonNode exported = JSON.readTree(Files.readString(target));
      assertEquals(4, exported.path("records").size());
      assertFalse(exported.path("imageBytesIncluded").asBoolean());
      assertFalse(Files.readString(target).contains("private-bob-record"));
      for (var record : exported.path("records")) {
        assertEquals("NOT_STORED", record.path("imagePersistence").asText());
        assertFalse(record.has("evidencePath"));
      }
    }
  }

  @Test
  void metadataStoreNeverCreatesOrDeletesCaptureFilesAndRejectsHashMismatch() throws Exception {
    Path existing = directory.resolve("old-answer.png");
    byte[] legacy = {4, 5, 6};
    Files.write(existing, legacy);
    byte[] png = {1, 2, 3};
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
    var store = new MetadataOnlyEvidenceStore();
    assertEquals(
        "NO_IMAGE_STORED",
        store.save(
            UUID.randomUUID(), UUID.randomUUID(), new SessionPlatform.Capture(png, hash, NOW)));
    assertThrows(
        DeskGradeException.class,
        () ->
            store.save(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new SessionPlatform.Capture(png, "0".repeat(64), NOW)));
    assertArrayEquals(legacy, Files.readAllBytes(existing));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(existing), files.toList());
    }
  }

  @Test
  void auditRetainsExactAllowedDecimalsInsteadOfRoundingThroughDouble() throws Exception {
    BigDecimal exact = new BigDecimal("99999999.99999998");
    var question =
        new app.deskgrade.core.domain.QuestionDefinition(
            "q",
            1,
            "合成精度题",
            "标准答案",
            exact,
            List.of(
                new app.deskgrade.core.domain.ScoringCriterion(
                    "p", "精确允许值", List.of(BigDecimal.ZERO, exact))));
    var prior = owned(ALICE);
    var snapshot =
        (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(prior.configurationJson());
    snapshot.put(
        "rubric",
        new RubricJsonCodec()
            .write(new Rubric(question, "按精确允许值评分", new BigDecimal("0.00000001"), false, false)));
    var settings =
        new SessionSettings(
            question,
            prior.modelProfileId(),
            1,
            1,
            snapshot.toString(),
            1,
            1,
            0,
            null,
            prior.pollInterval(),
            prior.pageTimeout());
    try (var journal = journal()) {
      UUID run = UUID.randomUUID(), attempt = UUID.randomUUID(), call = UUID.randomUUID();
      journal.beginRun(run, "synthetic-decimal", settings, NOW);
      journal.beginAttempt(
          run,
          attempt,
          1,
          new SessionPlatform.View(
              "decimal", "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
          1,
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
          new SessionPlatform.Capture(new byte[] {1}, "0".repeat(64), NOW),
          settings);
      var grade =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              "合成转写",
              List.of(new GradeResult.CriterionResult("p", exact, "精确依据", "精确分值")),
              List.of());
      var returned = returned(call, grade);
      journal.beginModelCall(call, run, attempt, "synthetic-profile", NOW);
      journal.completeModelCall(call, returned, null, NOW);
      journal.saveGrade(attempt, returned, exact);
      var record =
          new ObjectMapper()
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              .readTree(journal.recordJson(ALICE, attempt));
      assertEquals(
          0,
          exact.compareTo(
              record.at("/modelCalls/0/modelResult/criteria/0/awarded").decimalValue()));
      assertEquals(
          0,
          exact.compareTo(
              record.at("/questionSnapshot/criteria/0/allowedScores/1").decimalValue()));
      assertEquals(0, exact.compareTo(record.path("javaValidatedTotal").decimalValue()));
    }
  }

  @Test
  void versionOneDatabaseGetsBackupAndKeepsLegacyRecordsWithoutAdoptingCurrentAccount()
      throws Exception {
    Path database = directory.resolve("ledger");
    Path legacyImage = directory.resolve("legacy-synthetic.png");
    Files.write(legacyImage, new byte[] {9, 8, 7});
    UUID ownedLegacy = UUID.randomUUID();
    try (var connection =
        DriverManager.getConnection(
            "jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "")) {
      String sql =
          new String(
              Objects.requireNonNull(
                      getClass().getResourceAsStream("/db/migration/V001__session_ledger.sql"))
                  .readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      for (String statement : sql.split(";"))
        if (!statement.isBlank()) connection.createStatement().execute(statement);
      connection
          .createStatement()
          .execute(
              "INSERT INTO config_document VALUES('schema-version','MIGRATION',1,'1',CURRENT_TIMESTAMP)");
      connection
          .createStatement()
          .execute(
              "INSERT INTO config_document VALUES('storage-probe','INTERNAL',1,'{}',CURRENT_TIMESTAMP)");
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO grading_run VALUES(?,?,?,'PAUSED','RECOVERED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
        statement.setObject(1, RUN);
        statement.setString(2, "legacy");
        statement.setString(3, "{}");
        statement.executeUpdate();
      }
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO grading_attempt(attempt_id,run_id,ordinal,answer_id,question_id,page_revision,action_epoch,rule_revision,model_revision,platform_revision,config_snapshot,evidence_path,capture_sha256,captured_at,state) VALUES(?,?,?,?,'q',1,1,1,1,1,?,?,?,CURRENT_TIMESTAMP,'PAUSED')")) {
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
          statement.setObject(1, ordinal == 1 ? ATTEMPT : ownedLegacy);
          statement.setObject(2, RUN);
          statement.setInt(3, ordinal);
          statement.setString(4, "legacy-" + ordinal);
          statement.setString(5, ordinal == 1 ? "{}" : owned(ALICE).configurationJson());
          statement.setString(6, legacyImage.toString());
          statement.setString(7, "0".repeat(64));
          statement.executeUpdate();
        }
      }
    }
    try (var upgraded = journal()) {
      assertEquals(1, upgraded.runs().size());
      assertEquals(2, upgraded.attempts(RUN).size());
      var page = upgraded.queryRecords(ALICE, GradingRecords.Query.firstPage());
      assertEquals(1, page.total());
      assertEquals(ownedLegacy, page.rows().getFirst().attemptId());
      assertThrows(DeskGradeException.class, () -> upgraded.recordJson(ALICE, ATTEMPT));
      var legacy = JSON.readTree(upgraded.recordJson(ALICE, ownedLegacy));
      assertEquals("LEGACY_REFERENCE_PRESERVED", legacy.path("imagePersistence").asText());
      assertTrue(legacy.path("questionSnapshot").isNull());
      assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(legacyImage));
    }
    try (var paths = Files.list(directory)) {
      assertTrue(
          paths.anyMatch(
              p ->
                  p.getFileName().toString().startsWith("ledger.before-v1-")
                      && p.toString().endsWith(".zip")));
    }
  }
}
