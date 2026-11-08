package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * One connection with serialized transactions. WRITE_DELAY=0 persists intentions before GUI input.
 */
public final class H2SessionJournal implements PersistentSessionJournal {
  private static final List<String> MIGRATIONS =
      List.of("/db/migration/V001__session_ledger.sql", "/db/migration/V002__record_audit.sql");
  private final Connection connection;
  private final Path databasePath;
  private final ObjectMapper json = new ObjectMapper();

  public H2SessionJournal(Path databasePath) {
    Connection opened = null;
    try {
      Path absolute = databasePath.toAbsolutePath().normalize();
      Files.createDirectories(absolute.getParent());
      if (absolute.toString().contains(";")) {
        throw new IllegalArgumentException("Invalid database path");
      }
      this.databasePath = absolute;
      opened =
          DriverManager.getConnection(
              "jdbc:h2:file:" + absolute + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
      connection = opened;
      migrate();
    } catch (Exception failure) {
      if (opened != null) {
        try {
          opened.close();
        } catch (SQLException ignored) {
        }
      }
      throw failure();
    }
  }

  private void migrate() throws Exception {
    Set<String> tables = new HashSet<>();
    try (ResultSet found =
        connection.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
      while (found.next()) tables.add(found.getString("TABLE_NAME"));
    }
    int version = 0;
    if (!tables.isEmpty()) {
      if (!tables.contains("CONFIG_DOCUMENT")) throw failure();
      try (PreparedStatement statement =
              prepare("SELECT kind,revision,json FROM config_document WHERE id='schema-version'");
          ResultSet row = statement.executeQuery()) {
        if (!row.next() || !"MIGRATION".equals(row.getString("kind"))) throw failure();
        long stored = row.getLong("revision");
        if (stored < 1
            || stored > MIGRATIONS.size()
            || !Long.toString(stored).equals(row.getString("json"))) throw failure();
        version = (int) stored;
      }
    }
    if (version > 0 && version < MIGRATIONS.size()) backupBeforeUpgrade(version);
    for (int next = version + 1; next <= MIGRATIONS.size(); next++) {
      try (var stream = getClass().getResourceAsStream(MIGRATIONS.get(next - 1))) {
        if (stream == null) throw failure();
        String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
          if (!statement.isBlank()) {
            try (Statement command = connection.createStatement()) {
              command.execute(statement);
            }
          }
        }
      }
      if (next == 1) {
        execute(
            "INSERT INTO config_document VALUES(?,?,?,?,?)",
            "schema-version",
            "MIGRATION",
            1L,
            "1",
            Instant.now());
        execute(
            "INSERT INTO config_document VALUES(?,?,?,?,?)",
            "storage-probe",
            "INTERNAL",
            1L,
            "{}",
            Instant.now());
      } else {
        if (next == 2) {
          try (PreparedStatement query =
                  prepare("SELECT attempt_id,config_snapshot FROM grading_attempt");
              ResultSet rows = query.executeQuery()) {
            while (rows.next())
              execute(
                  "UPDATE grading_attempt SET owner_scope=? WHERE attempt_id=?",
                  H2RecordReader.ownerFromSnapshot(rows.getString("config_snapshot")),
                  rows.getObject("attempt_id", UUID.class));
          }
        }
        requireOne(
            execute(
                "UPDATE config_document SET revision=?,json=? WHERE id='schema-version' AND revision=?",
                (long) next,
                Integer.toString(next),
                (long) next - 1));
      }
    }
    for (String table :
        List.of("config_document", "grading_run", "grading_attempt", "model_call", "run_event")) {
      try (Statement statement = connection.createStatement()) {
        statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0").close();
      }
    }
    ManualBatchDisposition.initializeCaptureIndex(connection, false);
    assertWritable();
  }

  private void backupBeforeUpgrade(int version) throws Exception {
    Path destination =
        databasePath.resolveSibling(
            databasePath.getFileName() + ".before-v" + version + "-" + UUID.randomUUID() + ".zip");
    try (Statement statement = connection.createStatement()) {
      statement.execute("BACKUP TO '" + destination.toString().replace("'", "''") + "'");
    }
    try (var channel =
        java.nio.channels.FileChannel.open(destination, java.nio.file.StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  @Override
  public synchronized void assertWritable() {
    transaction(
        () ->
            requireOne(
                execute(
                    "UPDATE config_document SET revision=revision+1 WHERE id='storage-probe'")));
  }

  @Override
  public synchronized boolean hasUnknownSubmission(String targetIdentity) {
    try (PreparedStatement statement =
            prepare(
                "SELECT COUNT(*) FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE r.target_identity=? AND "
                    + ManualBatchDisposition.UNRESOLVED
                    + " AND "
                    + ManualBatchDisposition.outstanding("r"),
                targetIdentity);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1) > 0;
    } catch (SQLException failure) {
      throw failure();
    }
  }

  @Override
  public synchronized List<UnresolvedSubmission> unresolvedSubmissions(
      GradingRecords.Owner owner, String targetIdentity) {
    try {
      return ManualBatchDisposition.unresolved(connection, false, owner, targetIdentity);
    } catch (SQLException problem) {
      throw failure();
    }
  }

  @Override
  public synchronized void abandonUnresolvedRun(
      GradingRecords.Owner owner, UUID runId, String targetIdentity, UUID decisionId, Instant now) {
    transaction(
        () ->
            ManualBatchDisposition.abandon(
                connection, false, owner, runId, targetIdentity, decisionId, now));
  }

  @Override
  public synchronized void beginRun(
      UUID runId, String target, SessionSettings settings, Instant now) {
    transaction(
        () -> {
          execute(
              "INSERT INTO grading_run VALUES(?,?,?,?,?,?,?)",
              runId,
              target,
              settings.configurationJson(),
              SessionState.IDLE.name(),
              PauseReason.NONE.name(),
              now,
              now);
          saveConfiguration(runId, settings, now);
        });
  }

  private void saveConfiguration(UUID runId, SessionSettings settings, Instant now)
      throws SQLException {
    ManualBatchDisposition.requireActiveRun(connection, false, runId);
    String id =
        runId
            + ":"
            + settings.question().revision()
            + ":"
            + settings.modelRevision()
            + ":"
            + settings.platformRevision();
    try (PreparedStatement statement = prepare("SELECT json FROM config_document WHERE id=?", id);
        ResultSet row = statement.executeQuery()) {
      if (row.next()) {
        if (!settings.configurationJson().equals(row.getString("json")))
          throw new SQLException("Configuration revision is immutable");
        return;
      }
    }
    try (PreparedStatement statement =
            prepare("SELECT run_id FROM grading_run WHERE run_id=?", runId);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw new SQLException("Run missing");
    }
    execute(
        "INSERT INTO config_document VALUES(?,?,?,?,?)",
        id,
        "RUN_CONFIGURATION",
        settings.modelRevision(),
        settings.configurationJson(),
        now);
  }

  @Override
  public synchronized void recordConfiguration(UUID runId, SessionSettings settings, Instant now) {
    transaction(() -> saveConfiguration(runId, settings, now));
  }

  @Override
  public synchronized void beginAttempt(
      UUID runId,
      UUID attemptId,
      int ordinal,
      SessionPlatform.View view,
      long epoch,
      String evidencePath,
      SessionPlatform.Capture capture,
      SessionSettings settings) {
    transaction(
        () -> {
          saveConfiguration(runId, settings, capture.capturedAt());
          execute(
              "INSERT INTO grading_attempt(attempt_id,run_id,ordinal,answer_id,question_id,page_revision,action_epoch,rule_revision,model_revision,platform_revision,config_snapshot,evidence_path,capture_sha256,captured_at,state,owner_scope,question_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              attemptId,
              runId,
              ordinal,
              view.answerId(),
              view.questionId(),
              view.pageRevision(),
              epoch,
              settings.question().revision(),
              settings.modelRevision(),
              settings.platformRevision(),
              settings.configurationJson(),
              evidencePath,
              capture.sha256(),
              capture.capturedAt(),
              SessionState.CAPTURING.name(),
              H2RecordReader.ownerFromSnapshot(settings.configurationJson()),
              json.valueToTree(settings.question()).toString());
        });
  }

  @Override
  public synchronized void transition(
      UUID runId, UUID attemptId, SessionState state, PauseReason reason, Instant now) {
    if (runId == null) {
      return;
    }
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, false, runId);
          if (attemptId != null && state == SessionState.SUBMIT_PREPARED)
            rejectDuplicateManualCapture(runId, attemptId, true);
          requireOne(
              execute(
                  "UPDATE grading_run SET state=?,pause_reason=?,updated_at=? WHERE run_id=?",
                  state.name(),
                  reason.name(),
                  now,
                  runId));
          if (attemptId != null) {
            if (state == SessionState.SUBMIT_PREPARED) {
              if (execute(
                      "UPDATE grading_attempt SET submit_prepared=TRUE WHERE attempt_id=? AND run_id=? AND submit_prepared=FALSE AND confirmed_at IS NULL",
                      attemptId,
                      runId)
                  != 1) {
                throw new SQLException("Intent already exists");
              }
            }
            if (state == SessionState.SUBMIT_SENT) {
              if (execute(
                      "UPDATE grading_attempt SET submit_sent=TRUE WHERE attempt_id=? AND run_id=? AND submit_prepared=TRUE AND submit_sent=FALSE AND confirmed_at IS NULL",
                      attemptId,
                      runId)
                  != 1) {
                throw new SQLException("Submit already sent");
              }
            }
            requireOne(
                execute(
                    "UPDATE grading_attempt SET state=? WHERE attempt_id=? AND run_id=?",
                    state.name(),
                    attemptId,
                    runId));
          }
          execute(
              "INSERT INTO run_event VALUES(?,?,?,?,?,?)",
              UUID.randomUUID(),
              runId,
              attemptId,
              state.name(),
              reason.name(),
              now);
        });
  }

  @Override
  public synchronized void beginModelCall(
      UUID callId, UUID runId, UUID attemptId, String profileId, Instant now) {
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, false, runId);
          rejectDuplicateManualCapture(runId, attemptId, false);
          requireOne(
              execute(
                  "INSERT INTO model_call(call_id,run_id,attempt_id,profile_id,started_at) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM grading_attempt WHERE attempt_id=? AND run_id=?)",
                  callId,
                  runId,
                  attemptId,
                  profileId,
                  now,
                  attemptId,
                  runId));
        });
  }

  /** Serializes only the local ledger decision; the lock never spans provider or desktop work. */
  private void rejectDuplicateManualCapture(UUID runId, UUID attemptId, boolean excludeCurrent)
      throws SQLException {
    String target, owner, question, answerId, hash;
    try (PreparedStatement statement =
            prepare(
                "SELECT r.target_identity,a.owner_scope,a.question_id,a.answer_id,a.capture_sha256 FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE a.attempt_id=? AND a.run_id=?",
                attemptId,
                runId);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw new SQLException("Attempt does not belong to run");
      target = row.getString("target_identity");
      if (!target.startsWith("manual-calibration:")) return;
      owner = row.getString("owner_scope");
      question = row.getString("question_id");
      answerId = row.getString("answer_id");
      hash = row.getString("capture_sha256");
    }
    ManualBatchDisposition.rejectAbandonedCapture(connection, false, owner, question, hash);
    try (PreparedStatement lock =
            prepare("SELECT id FROM config_document WHERE id='storage-probe' FOR UPDATE");
        ResultSet row = lock.executeQuery()) {
      if (!row.next()) throw new SQLException("Storage guard missing");
    }
    // This is a locking/current read after the shared guard, including on MySQL REPEATABLE READ.
    String sql =
        "SELECT a.attempt_id FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE a.owner_scope=? AND r.target_identity=? AND a.question_id=? AND a.answer_id=? AND a.capture_sha256=? AND (a.submit_prepared=TRUE OR a.confirmed_at IS NOT NULL)"
            + (excludeCurrent ? " AND a.attempt_id<>?" : "")
            + " LIMIT 1 FOR UPDATE";
    Object[] values =
        excludeCurrent
            ? new Object[] {owner, target, question, answerId, hash, attemptId}
            : new Object[] {owner, target, question, answerId, hash};
    try (PreparedStatement statement = prepare(sql, values);
        ResultSet row = statement.executeQuery()) {
      if (row.next()) throw new DeskGradeException(ErrorCode.DUPLICATE_SUBMISSION);
    }
  }

  @Override
  public synchronized void completeModelCall(
      UUID callId, GradingResponse response, String safeCode, Instant now) {
    GradingResponse.Usage usage = response == null ? null : response.usage();
    finishModelCall(
        callId,
        response == null ? null : response.source(),
        usage,
        response == null ? null : response.elapsed(),
        safeCode,
        now,
        response);
  }

  /** A capability probe has metering but no GradeResult; do not invent a student score. */
  public synchronized void completeModelCallMetrics(
      UUID callId,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      java.time.Duration elapsed,
      String safeCode,
      Instant now) {
    finishModelCall(callId, source, usage, elapsed, safeCode, now, null);
  }

  private void finishModelCall(
      UUID callId,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      java.time.Duration elapsed,
      String safeCode,
      Instant now,
      GradingResponse response) {
    if (usage != null) requireExactDecimal(usage.estimatedCost(), 38, 18);
    if (safeCode != null && !safeCode.matches("[A-Z0-9_]{1,100}")) throw failure();
    if (elapsed != null && elapsed.isNegative()) throw failure();
    Long elapsedMillis;
    try {
      elapsedMillis = elapsed == null ? null : elapsed.toMillis();
    } catch (ArithmeticException e) {
      throw failure();
    }
    transaction(
        () -> {
          requireOne(
              execute(
                  "UPDATE model_call SET completed_at=?,source=?,input_tokens=?,output_tokens=?,estimated_cost=?,elapsed_ms=?,error_code=? WHERE call_id=? AND completed_at IS NULL",
                  now,
                  source == null ? null : source.name(),
                  usage == null ? null : usage.inputTokens(),
                  usage == null ? null : usage.outputTokens(),
                  usage == null ? null : usage.estimatedCost(),
                  elapsedMillis,
                  safeCode,
                  callId));
          if (response != null) {
            String validation = "UNVALIDATED_LEGACY";
            BigDecimal javaTotal = null;
            try (PreparedStatement statement =
                    prepare(
                        "SELECT a.question_snapshot FROM model_call c JOIN grading_attempt a ON c.attempt_id=a.attempt_id WHERE c.call_id=?",
                        callId);
                ResultSet row = statement.executeQuery()) {
              requireOne(row.next() ? 1 : 0);
              if (!callId.equals(response.requestId())) validation = "REQUEST_ID_MISMATCH";
              else if (row.getString(1) != null) {
                try {
                  QuestionDefinition question =
                      json.readValue(row.getString(1), QuestionDefinition.class);
                  javaTotal = GradeValidator.validate(question, response.result()).totalScore();
                  requireExactDecimal(javaTotal, 32, 8);
                  validation = javaTotal == null ? "UNABLE" : "VALIDATED";
                } catch (Exception invalid) {
                  validation = "INVALID_RESULT";
                  javaTotal = null;
                }
              }
            }
            requireOne(
                execute(
                    "UPDATE model_call SET response_request_id=?,response_completed_at=?,result_json=?,validation_status=?,java_total=? WHERE call_id=?",
                    response.requestId(),
                    response.completedAt(),
                    json.valueToTree(response.result()).toString(),
                    validation,
                    javaTotal,
                    callId));
          }
        });
  }

  @Override
  public synchronized void saveGrade(UUID attemptId, GradingResponse response, BigDecimal total) {
    try {
      requireExactDecimal(total, 32, 8);
      try (PreparedStatement statement =
              prepare(
                  "SELECT question_snapshot FROM grading_attempt WHERE attempt_id=?", attemptId);
          ResultSet row = statement.executeQuery()) {
        if (!row.next() || row.getString(1) == null) throw failure();
        BigDecimal calculated =
            GradeValidator.validate(
                    json.readValue(row.getString(1), QuestionDefinition.class), response.result())
                .totalScore();
        if ((calculated == null) != (total == null)
            || (calculated != null && calculated.compareTo(total) != 0)) throw failure();
      }
      try (PreparedStatement statement =
              prepare(
                  "SELECT response_request_id,result_json,source,validation_status FROM model_call WHERE call_id=? AND attempt_id=? AND completed_at IS NOT NULL AND error_code IS NULL",
                  response.requestId(),
                  attemptId);
          ResultSet row = statement.executeQuery()) {
        if (!row.next()
            || !response.requestId().equals(row.getObject("response_request_id", UUID.class))
            || !response.source().name().equals(row.getString("source"))
            || !("VALIDATED".equals(row.getString("validation_status"))
                || "UNABLE".equals(row.getString("validation_status")))
            || !row.getString("result_json").equals(json.valueToTree(response.result()).toString()))
          throw failure();
      }
      String body =
          json.writeValueAsString(
              Map.of("source", response.source().name(), "result", response.result()));
      transactionForAttempt(
          attemptId,
          () ->
              requireOne(
                  execute(
                      "UPDATE grading_attempt SET score=?,result_json=?,accepted_request_id=? WHERE attempt_id=? AND submit_prepared=FALSE",
                      total,
                      body,
                      response.requestId(),
                      attemptId)));
    } catch (Exception failure) {
      throw failure();
    }
  }

  @Override
  public synchronized void recordScoreReadback(
      UUID attemptId, BigDecimal observedScore, Instant now) {
    if (observedScore == null || now == null) throw failure();
    requireExactDecimal(observedScore, 32, 8);
    transactionForAttempt(
        attemptId,
        () ->
            requireOne(
                execute(
                    "UPDATE grading_attempt SET score_readback=?,score_readback_at=? WHERE attempt_id=? AND score=? AND submit_prepared=FALSE",
                    observedScore,
                    now,
                    attemptId,
                    observedScore)));
  }

  @Override
  public synchronized GradingRecords.Page queryRecords(
      GradingRecords.Owner owner, GradingRecords.Query query) {
    return new H2RecordReader(connection).queryRecords(owner, query);
  }

  @Override
  public synchronized String recordJson(GradingRecords.Owner owner, UUID attemptId) {
    return new H2RecordReader(connection).recordJson(owner, attemptId);
  }

  public synchronized void exportJson(
      Path destination, GradingRecords.Owner owner, GradingRecords.Query query) {
    RecordsJsonExporter.write(destination, this, owner, query);
  }

  @Override
  public synchronized void confirmSubmission(UUID runId, UUID attemptId, Instant now) {
    confirmSubmission(runId, attemptId, now, SessionPlatform.ConfirmationKind.PLATFORM_ACK);
  }

  @Override
  public synchronized void confirmSubmission(
      UUID runId, UUID attemptId, Instant now, SessionPlatform.ConfirmationKind confirmationKind) {
    String eventType =
        switch (Objects.requireNonNull(confirmationKind)) {
          case PLATFORM_ACK -> "SUBMISSION_CONFIRMED";
          case SCREEN_TRANSITION -> "SCREEN_TRANSITION_OBSERVED";
          case INPUT_DISPATCHED -> "SUBMIT_CLICKED";
        };
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, false, runId);
          if (execute(
                  "UPDATE grading_attempt SET confirmed_at=? WHERE attempt_id=? AND run_id=? AND submit_prepared=TRUE AND confirmed_at IS NULL",
                  now,
                  attemptId,
                  runId)
              != 1) {
            throw new SQLException("Confirmation requires one unconfirmed intent");
          }
          execute(
              "INSERT INTO run_event VALUES(?,?,?,?,?,?)",
              UUID.randomUUID(),
              runId,
              attemptId,
              eventType,
              PauseReason.NONE.name(),
              now);
        });
  }

  @Override
  public synchronized List<RunRecord> recoverInterrupted(Instant now) {
    transaction(
        () -> {
          execute(
              "UPDATE grading_attempt SET state='SUBMISSION_UNKNOWN' WHERE submit_prepared=TRUE AND confirmed_at IS NULL AND run_id IN (SELECT r.run_id FROM grading_run r WHERE "
                  + ManualBatchDisposition.outstanding("r")
                  + ")");
          execute(
              "UPDATE grading_run SET state='SUBMISSION_UNKNOWN',pause_reason='SUBMISSION_UNCERTAIN',updated_at=? WHERE run_id IN (SELECT run_id FROM grading_attempt WHERE submit_prepared=TRUE AND confirmed_at IS NULL) AND "
                  + ManualBatchDisposition.outstanding("grading_run"),
              now);
          execute(
              "UPDATE grading_run SET state='PAUSED',pause_reason='RECOVERED',updated_at=? WHERE state NOT IN ('SUBMISSION_UNKNOWN','COMPLETED','STOPPED') AND "
                  + ManualBatchDisposition.outstanding("grading_run"),
              now);
        });
    return runs();
  }

  /** Explicit allow-list for evidence retention; unresolved intentions never qualify. */
  public synchronized Set<UUID> resolvedEvidenceRunsBefore(Instant cutoff) {
    Set<UUID> result = new HashSet<>();
    try (PreparedStatement statement =
            prepare(
                "SELECT run_id FROM grading_run r WHERE state IN ('COMPLETED','STOPPED') AND updated_at<? AND NOT EXISTS(SELECT 1 FROM grading_attempt a WHERE a.run_id=r.run_id AND a.submit_prepared=TRUE AND a.confirmed_at IS NULL)",
                cutoff);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) result.add(rows.getObject(1, UUID.class));
      return Set.copyOf(result);
    } catch (SQLException e) {
      throw failure();
    }
  }

  @Override
  public synchronized List<RunRecord> runs() {
    List<RunRecord> result = new ArrayList<>();
    try (PreparedStatement statement =
            prepare(
                "SELECT r.*,(SELECT COUNT(*) FROM model_call c WHERE c.run_id=r.run_id) AS calls,(SELECT COUNT(*) FROM grading_attempt a WHERE a.run_id=r.run_id AND a.confirmed_at IS NOT NULL) AS confirmed FROM grading_run r ORDER BY started_at DESC LIMIT 1000");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        result.add(
            new RunRecord(
                rows.getObject("run_id", UUID.class),
                rows.getString("target_identity"),
                SessionState.valueOf(rows.getString("state")),
                PauseReason.valueOf(rows.getString("pause_reason")),
                rows.getInt("calls"),
                rows.getInt("confirmed"),
                rows.getObject("started_at", OffsetDateTime.class).toInstant()));
      }
      return List.copyOf(result);
    } catch (SQLException failure) {
      throw failure();
    }
  }

  @Override
  public synchronized List<AttemptRecord> attempts(UUID runId) {
    List<AttemptRecord> result = new ArrayList<>();
    try (PreparedStatement statement =
            prepare("SELECT * FROM grading_attempt WHERE run_id=? ORDER BY ordinal", runId);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        result.add(
            new AttemptRecord(
                rows.getObject("attempt_id", UUID.class),
                runId,
                rows.getString("answer_id"),
                rows.getString("question_id"),
                SessionState.valueOf(rows.getString("state")),
                rows.getBigDecimal("score"),
                rows.getBoolean("submit_prepared"),
                rows.getObject("confirmed_at") != null,
                rows.getString("evidence_path"),
                rows.getString("result_json")));
      }
      return List.copyOf(result);
    } catch (SQLException failure) {
      throw failure();
    }
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException failure) {
      throw failure();
    }
  }

  /** Legacy internal reader; account UI must call recordJson with an explicit owner. */
  public synchronized String attemptAuditJson(UUID attemptId) {
    return new H2RecordReader(connection).internalRecordJson(attemptId);
  }

  private PreparedStatement prepare(String sql, Object... values) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      statement.setObject(
          i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
    }
    return statement;
  }

  private int execute(String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = prepare(sql, values)) {
      return statement.executeUpdate();
    }
  }

  private void transaction(SqlWork work) {
    try {
      connection.setAutoCommit(false);
      ManualBatchDisposition.lock(connection, false);
      work.run();
      connection.commit();
    } catch (SQLException | RuntimeException failure) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      if (failure instanceof DeskGradeException known
          && known.code() == ErrorCode.DUPLICATE_SUBMISSION) throw known;
      throw failure();
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException ignored) {
      }
    }
  }

  private void transactionForAttempt(UUID attemptId, SqlWork work) {
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveAttempt(connection, false, attemptId);
          work.run();
        });
  }

  private static DeskGradeException failure() {
    return new DeskGradeException(ErrorCode.STORAGE_FAILURE);
  }

  private static void requireOne(int rows) throws SQLException {
    if (rows != 1) throw new SQLException("Expected one ledger row");
  }

  private static void requireExactDecimal(BigDecimal value, int precision, int scale) {
    if (value == null) return;
    var normalized = value.stripTrailingZeros();
    if (normalized.signum() < 0
        || normalized.scale() > scale
        || normalized.precision() - normalized.scale() > precision - scale) throw failure();
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }
}
