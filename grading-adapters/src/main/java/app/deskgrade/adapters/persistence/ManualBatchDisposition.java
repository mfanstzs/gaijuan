package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionJournal.RunRecord;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Shared SQL for explicit human disposition; callers hold the short ledger transaction lock. */
final class ManualBatchDisposition {
  static final String KIND = "MANUAL_BATCH_ABANDONMENT";
  static final String EVENT = "MANUAL_BATCH_ABANDONED";
  static final String REASON = "USER_CONFIRMED_NEW_BATCH";
  static final String UNRESOLVED =
      "a.confirmed_at IS NULL AND (a.submit_prepared=TRUE OR a.state IN ('SUBMIT_SENT','WAIT_ACK','SUBMISSION_UNKNOWN'))";

  private ManualBatchDisposition() {}

  static void initializeCaptureIndex(Connection connection, boolean mysql) throws SQLException {
    String table = mysql ? "journal_grading_attempt" : "grading_attempt";
    String index = mysql ? "journal_attempt_owner_capture" : "attempt_owner_capture_idx";
    if (mysql) {
      try (var indexes =
          connection
              .getMetaData()
              .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
        while (indexes.next()) if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
      }
    }
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE INDEX "
              + (mysql ? "" : "IF NOT EXISTS ")
              + index
              + " ON "
              + table
              + "(owner_scope,question_id,capture_sha256,run_id)");
    }
  }

  static String outstanding(String runAlias) {
    return "NOT EXISTS(SELECT 1 FROM config_document disposition WHERE disposition.id=CONCAT('manual-abandon:',"
        + runAlias
        + ".run_id) AND disposition.kind='"
        + KIND
        + "')";
  }

  static void lock(Connection connection, boolean mysql) throws SQLException {
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT id FROM config_document WHERE id='storage-probe' FOR UPDATE");
        var row = statement.executeQuery()) {
      if (!row.next()) throw new SQLException("Storage guard missing");
    }
  }

  static void requireActiveRun(Connection connection, boolean mysql, UUID runId)
      throws SQLException {
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT id FROM config_document WHERE id=? FOR UPDATE",
                markerId(runId));
        var row = statement.executeQuery()) {
      if (row.next()) throw new SQLException("Manually ended batch cannot perform another action");
    }
  }

  static void requireActiveAttempt(Connection connection, boolean mysql, UUID attemptId)
      throws SQLException {
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT run_id FROM grading_attempt WHERE attempt_id=?",
                attemptId);
        var row = statement.executeQuery()) {
      if (!row.next()) throw new SQLException("Attempt missing");
      requireActiveRun(connection, mysql, UUID.fromString(row.getString(1)));
    }
  }

  static List<PersistentSessionJournal.UnresolvedSubmission> unresolved(
      Connection connection, boolean mysql, GradingRecords.Owner owner, String target)
      throws SQLException {
    validate(owner, target);
    String ownerKey = H2RecordReader.ownerKey(owner);
    String sql =
        "SELECT r.*,a.attempt_id,a.answer_id,a.question_id,a.score,"
            + "(SELECT COUNT(*) FROM model_call c WHERE c.run_id=r.run_id) AS calls,"
            + "(SELECT COUNT(*) FROM grading_attempt confirmed WHERE confirmed.run_id=r.run_id AND confirmed.confirmed_at IS NOT NULL) AS confirmed"
            + " FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id"
            + " WHERE a.owner_scope=? AND r.target_identity=? AND "
            + UNRESOLVED
            + " AND "
            + outstanding("r")
            + (mysql ? " AND r.owner_scope=?" : "")
            + " ORDER BY r.started_at,a.captured_at,a.attempt_id LIMIT 100";
    List<PersistentSessionJournal.UnresolvedSubmission> result = new ArrayList<>();
    try (var statement =
            prepare(
                connection,
                mysql,
                sql,
                mysql
                    ? new Object[] {ownerKey, target, ownerKey}
                    : new Object[] {ownerKey, target});
        var row = statement.executeQuery()) {
      while (row.next()) {
        if (!ownerKey.equals(H2RecordReader.ownerFromSnapshot(row.getString("config_snapshot"))))
          throw new SQLException("Run owner snapshot mismatch");
        result.add(
            new PersistentSessionJournal.UnresolvedSubmission(
                new RunRecord(
                    UUID.fromString(row.getString("run_id")),
                    row.getString("target_identity"),
                    SessionState.valueOf(row.getString("state")),
                    PauseReason.valueOf(row.getString("pause_reason")),
                    row.getInt("calls"),
                    row.getInt("confirmed"),
                    row.getTimestamp("started_at", utc()).toInstant()),
                UUID.fromString(row.getString("attempt_id")),
                row.getString("answer_id"),
                row.getString("question_id"),
                row.getBigDecimal("score")));
      }
    }
    return List.copyOf(result);
  }

  static void abandon(
      Connection connection,
      boolean mysql,
      GradingRecords.Owner owner,
      UUID runId,
      String target,
      UUID decisionId,
      Instant now)
      throws SQLException {
    validate(owner, target);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(now);
    String ownerKey = H2RecordReader.ownerKey(owner);
    String previousState, previousReason;
    try (var statement =
            prepare(
                connection, mysql, "SELECT * FROM grading_run WHERE run_id=? FOR UPDATE", runId);
        var row = statement.executeQuery()) {
      if (!row.next()
          || !target.equals(row.getString("target_identity"))
          || !ownerKey.equals(H2RecordReader.ownerFromSnapshot(row.getString("config_snapshot")))
          || (mysql && !ownerKey.equals(row.getString("owner_scope"))))
        throw new SQLException("Batch does not belong to the authenticated owner and target");
      previousState = row.getString("state");
      previousReason = row.getString("pause_reason");
    }
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT kind,json FROM config_document WHERE id=? FOR UPDATE",
                markerId(runId));
        var row = statement.executeQuery()) {
      if (row.next()) {
        try {
          var marker = JournalJson.MAPPER.readTree(row.getString("json"));
          if (!KIND.equals(row.getString("kind"))
              || !decisionId.toString().equals(marker.path("decisionId").asText())
              || !ownerKey.equals(marker.path("ownerScope").asText())
              || !target.equals(marker.path("targetIdentity").asText()))
            throw new SQLException("Manual disposition is immutable");
          return;
        } catch (java.io.IOException invalid) {
          throw new SQLException("Manual disposition is invalid");
        }
      }
    }
    if (!Set.of("SUBMISSION_UNKNOWN", "STOPPED", "PAUSED", "FAILED").contains(previousState))
      throw new SQLException("Stop the old batch before reviewing its unresolved submissions");
    var attempts = new ArrayList<UUID>();
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT a.attempt_id,a.owner_scope FROM grading_attempt a WHERE a.run_id=? AND "
                    + UNRESOLVED
                    + " ORDER BY a.ordinal FOR UPDATE",
                runId);
        var row = statement.executeQuery()) {
      while (row.next()) {
        if (!ownerKey.equals(row.getString("owner_scope")))
          throw new SQLException("Attempt owner mismatch");
        attempts.add(UUID.fromString(row.getString("attempt_id")));
      }
    }
    if (attempts.isEmpty()) throw new SQLException("Batch has no unresolved submission");
    var marker =
        JournalJson.MAPPER
            .createObjectNode()
            .put("schemaVersion", 1)
            .put("decisionId", decisionId.toString())
            .put("runId", runId.toString())
            .put("ownerScope", ownerKey)
            .put("targetIdentity", target)
            .put("previousRunState", previousState)
            .put("previousPauseReason", previousReason)
            .put("reason", REASON)
            .put("at", now.toString());
    marker.set("decidedBy", JournalJson.MAPPER.valueToTree(owner));
    marker.set("attemptIds", JournalJson.MAPPER.valueToTree(attempts));
    execute(
        connection,
        mysql,
        "INSERT INTO config_document VALUES(?,?,?,?,?)",
        markerId(runId),
        KIND,
        1L,
        marker.toString(),
        now);
    for (UUID attempt : attempts)
      execute(
          connection,
          mysql,
          "INSERT INTO run_event VALUES(?,?,?,?,?,?)",
          UUID.randomUUID(),
          runId,
          attempt,
          EVENT,
          REASON,
          now);
    if (execute(
            connection,
            mysql,
            "UPDATE grading_run SET state='STOPPED',pause_reason='USER_STOPPED',updated_at=? WHERE run_id=?",
            now,
            runId)
        != 1) throw new SQLException("Batch disposition did not update one run");
  }

  /**
   * A changed calibration or legacy student identity cannot replay an explicitly abandoned image.
   */
  static void rejectAbandonedCapture(
      Connection connection,
      boolean mysql,
      String ownerScope,
      String questionId,
      String captureHash)
      throws SQLException {
    try (var statement =
            prepare(
                connection,
                mysql,
                "SELECT a.attempt_id FROM grading_attempt a JOIN grading_run r ON a.run_id=r.run_id"
                    + " JOIN config_document disposition ON disposition.id=CONCAT('manual-abandon:',r.run_id)"
                    + " WHERE a.owner_scope=? AND a.question_id=? AND a.capture_sha256=?"
                    + " AND r.target_identity LIKE 'manual-calibration:%' AND disposition.kind=?"
                    + " AND "
                    + UNRESOLVED
                    + " LIMIT 1 FOR UPDATE",
                ownerScope,
                questionId,
                captureHash,
                KIND);
        var row = statement.executeQuery()) {
      if (row.next()) throw new DeskGradeException(ErrorCode.DUPLICATE_SUBMISSION);
    }
  }

  private static String markerId(UUID runId) {
    return "manual-abandon:" + Objects.requireNonNull(runId);
  }

  private static void validate(GradingRecords.Owner owner, String target) {
    Objects.requireNonNull(owner);
    if (target == null || target.isBlank() || target.length() > 500)
      throw new IllegalArgumentException();
  }

  private static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  private static PreparedStatement prepare(
      Connection connection, boolean mysql, String sql, Object... values) throws SQLException {
    var statement = connection.prepareStatement(mysql ? MySqlSessionJournal.tableSql(sql) : sql);
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      if (value instanceof Instant instant)
        statement.setTimestamp(i + 1, Timestamp.from(instant), utc());
      else
        statement.setObject(i + 1, value instanceof UUID id ? (mysql ? id.toString() : id) : value);
    }
    return statement;
  }

  private static int execute(Connection connection, boolean mysql, String sql, Object... values)
      throws SQLException {
    try (var statement = prepare(connection, mysql, sql, values)) {
      return statement.executeUpdate();
    }
  }
}
