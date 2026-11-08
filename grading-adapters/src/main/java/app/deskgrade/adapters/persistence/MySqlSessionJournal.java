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
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Server-owned MySQL ledger. One authenticated owner and dedicated connection per request. */
public final class MySqlSessionJournal implements PersistentSessionJournal {
  private final Connection connection;
  private final GradingRecords.Owner owner;
  private final String ownerScope;
  private final String clientScope;
  private final ObjectMapper json = new ObjectMapper();

  public MySqlSessionJournal(
      Connection connection, GradingRecords.Owner owner, String clientScope) {
    this.connection = Objects.requireNonNull(connection);
    this.owner = Objects.requireNonNull(owner);
    if (owner.gateway().isEmpty()) throw failure();
    this.ownerScope = H2RecordReader.ownerKey(owner);
    if (clientScope == null || !clientScope.matches("[a-fA-F0-9]{64}")) throw failure();
    this.clientScope = clientScope.toLowerCase(Locale.ROOT);
  }

  /** Run once at server startup before exposing the journal endpoint. */
  public static void initializeSchema(Connection connection) {
    try (var input = MySqlSessionJournal.class.getResourceAsStream("/db/mysql/V001__journal.sql")) {
      if (input == null) throw failure();
      String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      for (String sql : ddl.split(";")) {
        if (!sql.isBlank())
          try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
          }
      }
      ManualBatchDisposition.initializeCaptureIndex(connection, true);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT IGNORE INTO journal_config_document(id,kind,revision,json,created_at) VALUES('storage-probe','INTERNAL',1,'{}',?)")) {
        statement.setTimestamp(1, Timestamp.from(Instant.now()), utc());
        statement.executeUpdate();
      }
    } catch (Exception problem) {
      throw failure();
    }
  }

  private static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  private void ownRun(UUID id) {
    owned("SELECT 1 FROM grading_run WHERE run_id=? AND owner_scope=? AND client_scope=?", id);
  }

  private void ownAttempt(UUID id) {
    owned(
        "SELECT 1 FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE a.attempt_id=? AND r.owner_scope=? AND r.client_scope=?",
        id);
  }

  private void ownCall(UUID id) {
    owned(
        "SELECT 1 FROM model_call c JOIN grading_run r ON c.run_id=r.run_id WHERE c.call_id=? AND r.owner_scope=? AND r.client_scope=?",
        id);
  }

  private void owned(String query, UUID id) {
    if (id == null) throw failure();
    try (PreparedStatement statement = prepare(query, id, ownerScope, clientScope);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw failure();
    } catch (SQLException problem) {
      throw failure();
    }
  }

  private void ownReadRun(UUID id) {
    try (PreparedStatement statement =
            prepare("SELECT 1 FROM grading_run WHERE run_id=? AND owner_scope=?", id, ownerScope);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw failure();
    } catch (SQLException problem) {
      throw failure();
    }
  }

  private void ownSettings(SessionSettings settings) {
    if (!ownerScope.equals(H2RecordReader.ownerFromSnapshot(settings.configurationJson())))
      throw failure();
  }

  private void ownReader(GradingRecords.Owner requested) {
    if (!owner.equals(requested)) throw failure();
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
                "SELECT COUNT(*) FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE r.target_identity=? AND r.owner_scope=? AND "
                    + ManualBatchDisposition.UNRESOLVED
                    + " AND "
                    + ManualBatchDisposition.outstanding("r"),
                targetIdentity,
                ownerScope);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1) > 0;
    } catch (SQLException failure) {
      throw failure();
    }
  }

  @Override
  public synchronized List<UnresolvedSubmission> unresolvedSubmissions(
      GradingRecords.Owner requested, String targetIdentity) {
    ownReader(requested);
    try {
      return ManualBatchDisposition.unresolved(connection, true, owner, targetIdentity);
    } catch (SQLException problem) {
      throw failure();
    }
  }

  @Override
  public synchronized void abandonUnresolvedRun(
      GradingRecords.Owner requested,
      UUID runId,
      String targetIdentity,
      UUID decisionId,
      Instant now) {
    ownReader(requested);
    ownReadRun(runId);
    transaction(
        () ->
            ManualBatchDisposition.abandon(
                connection, true, owner, runId, targetIdentity, decisionId, now));
  }

  @Override
  public synchronized void beginRun(
      UUID runId, String target, SessionSettings settings, Instant now) {
    ownSettings(settings);
    transaction(
        () -> {
          execute(
              "INSERT INTO grading_run(run_id,target_identity,config_snapshot,state,pause_reason,started_at,updated_at,owner_scope,client_scope) VALUES(?,?,?,?,?,?,?,?,?)",
              runId,
              target,
              settings.configurationJson(),
              SessionState.IDLE.name(),
              PauseReason.NONE.name(),
              now,
              now,
              ownerScope,
              clientScope);
          saveConfiguration(runId, settings, now);
        });
  }

  private void saveConfiguration(UUID runId, SessionSettings settings, Instant now)
      throws SQLException {
    ownRun(runId);
    ManualBatchDisposition.requireActiveRun(connection, true, runId);
    ownSettings(settings);
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
    ownRun(runId);
    if (attemptId != null) ownAttempt(attemptId);
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, true, runId);
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
    ownRun(runId);
    ownAttempt(attemptId);
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, true, runId);
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

  /** Serializes only the ledger decision; the lock never spans provider or desktop work. */
  private void rejectDuplicateManualCapture(UUID runId, UUID attemptId, boolean excludeCurrent)
      throws SQLException {
    String target, question, answerId, hash;
    try (PreparedStatement statement =
            prepare(
                "SELECT r.target_identity,a.question_id,a.answer_id,a.capture_sha256 FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE a.attempt_id=? AND a.run_id=? AND a.owner_scope=? AND r.owner_scope=? AND r.client_scope=?",
                attemptId,
                runId,
                ownerScope,
                ownerScope,
                clientScope);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw new SQLException("Attempt does not belong to current run owner");
      target = row.getString("target_identity");
      if (!target.startsWith("manual-calibration:")) return;
      question = row.getString("question_id");
      answerId = row.getString("answer_id");
      hash = row.getString("capture_sha256");
    }
    ManualBatchDisposition.rejectAbandonedCapture(connection, true, ownerScope, question, hash);
    try (PreparedStatement lock =
            prepare("SELECT id FROM config_document WHERE id='storage-probe' FOR UPDATE");
        ResultSet row = lock.executeQuery()) {
      if (!row.next()) throw new SQLException("Storage guard missing");
    }
    // A plain SELECT could retain the pre-lock RR snapshot and miss another client's commit.
    String sql =
        "SELECT a.attempt_id FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id WHERE a.owner_scope=? AND r.owner_scope=? AND r.target_identity=? AND a.question_id=? AND a.answer_id=? AND a.capture_sha256=? AND (a.submit_prepared=TRUE OR a.confirmed_at IS NOT NULL)"
            + (excludeCurrent ? " AND a.attempt_id<>?" : "")
            + " LIMIT 1 FOR UPDATE";
    Object[] values =
        excludeCurrent
            ? new Object[] {ownerScope, ownerScope, target, question, answerId, hash, attemptId}
            : new Object[] {ownerScope, ownerScope, target, question, answerId, hash};
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
    ownCall(callId);
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
    ownAttempt(attemptId);
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
            || !response.requestId().equals(nullableUuid(row.getString("response_request_id")))
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
    ownAttempt(attemptId);
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
    ownReader(owner);
    return new MySqlRecordReader(connection).queryRecords(owner, query);
  }

  @Override
  public synchronized String recordJson(GradingRecords.Owner owner, UUID attemptId) {
    ownReader(owner);
    return new MySqlRecordReader(connection).recordJson(owner, attemptId);
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
    ownRun(runId);
    ownAttempt(attemptId);
    transaction(
        () -> {
          ManualBatchDisposition.requireActiveRun(connection, true, runId);
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
              "UPDATE grading_attempt SET state='SUBMISSION_UNKNOWN' WHERE submit_prepared=TRUE AND confirmed_at IS NULL AND run_id IN (SELECT r.run_id FROM grading_run r WHERE owner_scope=? AND client_scope=? AND "
                  + ManualBatchDisposition.outstanding("r")
                  + ")",
              ownerScope,
              clientScope);
          execute(
              "UPDATE grading_run SET state='SUBMISSION_UNKNOWN',pause_reason='SUBMISSION_UNCERTAIN',updated_at=? WHERE run_id IN (SELECT run_id FROM grading_attempt WHERE submit_prepared=TRUE AND confirmed_at IS NULL) AND owner_scope=? AND client_scope=? AND "
                  + ManualBatchDisposition.outstanding("grading_run"),
              now,
              ownerScope,
              clientScope);
          execute(
              "UPDATE grading_run SET state='PAUSED',pause_reason='RECOVERED',updated_at=? WHERE state NOT IN ('SUBMISSION_UNKNOWN','COMPLETED','STOPPED') AND owner_scope=? AND client_scope=? AND "
                  + ManualBatchDisposition.outstanding("grading_run"),
              now,
              ownerScope,
              clientScope);
        });
    return runs();
  }

  @Override
  public synchronized List<RunRecord> runs() {
    List<RunRecord> result = new ArrayList<>();
    try (PreparedStatement statement =
            prepare(
                "SELECT r.*,(SELECT COUNT(*) FROM model_call c WHERE c.run_id=r.run_id) AS calls,(SELECT COUNT(*) FROM grading_attempt a WHERE a.run_id=r.run_id AND a.confirmed_at IS NOT NULL) AS confirmed FROM grading_run r WHERE r.owner_scope=? ORDER BY started_at DESC LIMIT 1000",
                ownerScope);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        result.add(
            new RunRecord(
                UUID.fromString(rows.getString("run_id")),
                rows.getString("target_identity"),
                SessionState.valueOf(rows.getString("state")),
                PauseReason.valueOf(rows.getString("pause_reason")),
                rows.getInt("calls"),
                rows.getInt("confirmed"),
                rows.getTimestamp("started_at", utc()).toInstant()));
      }
      return List.copyOf(result);
    } catch (SQLException failure) {
      throw failure();
    }
  }

  @Override
  public synchronized List<AttemptRecord> attempts(UUID runId) {
    ownReadRun(runId);
    List<AttemptRecord> result = new ArrayList<>();
    try (PreparedStatement statement =
            prepare("SELECT * FROM grading_attempt WHERE run_id=? ORDER BY ordinal", runId);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        result.add(
            new AttemptRecord(
                UUID.fromString(rows.getString("attempt_id")),
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
    return new MySqlRecordReader(connection).recordJson(owner, attemptId);
  }

  private PreparedStatement prepare(String sql, Object... values) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(tableSql(sql));
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      if (value instanceof Instant instant)
        statement.setTimestamp(i + 1, Timestamp.from(instant), utc());
      else statement.setObject(i + 1, value instanceof UUID id ? id.toString() : value);
    }
    return statement;
  }

  static String tableSql(String sql) {
    for (String table :
        List.of("config_document", "grading_run", "grading_attempt", "model_call", "run_event"))
      sql = sql.replaceAll("\\b" + table + "\\b", "journal_" + table);
    return sql;
  }

  private static UUID nullableUuid(String id) {
    return id == null ? null : UUID.fromString(id);
  }

  private int execute(String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = prepare(sql, values)) {
      return statement.executeUpdate();
    }
  }

  private void transaction(SqlWork work) {
    try {
      connection.setAutoCommit(false);
      ManualBatchDisposition.lock(connection, true);
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
          ManualBatchDisposition.requireActiveAttempt(connection, true, attemptId);
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
