package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Used under the journal's connection lock. Never opens a capture or any other external file. */
final class H2RecordReader implements GradingRecords {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final String CONFIRMATION_EVENT_SQL =
      "(SELECT e.event_type FROM run_event e WHERE e.attempt_id=a.attempt_id AND e.event_type IN ('SUBMISSION_CONFIRMED','SCREEN_TRANSITION_OBSERVED','SUBMIT_CLICKED') ORDER BY e.happened_at DESC,e.event_id DESC LIMIT 1) AS confirmation_event";
  private final Connection connection;

  H2RecordReader(Connection connection) {
    this.connection = connection;
  }

  static String ownerKey(Owner owner) {
    Objects.requireNonNull(owner);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (owner.gateway() + "\n" + owner.accountId())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw failure();
    }
  }

  static String ownerFromSnapshot(String snapshot) {
    try {
      JsonNode node = JSON.readTree(snapshot);
      JsonNode access = node.path("access");
      if ("LOCAL".equals(access.path("mode").asText())) return ownerKey(Owner.local());
      if ("COMMERCIAL".equals(access.path("mode").asText())) {
        String opaqueScope = access.path("ownerScope").asText();
        if (opaqueScope.matches("[0-9a-f]{64}")) return opaqueScope;
        return ownerKey(
            new Owner(access.path("gateway").asText(), access.path("accountId").asText()));
      }
      if ("BYOK".equals(access.path("mode").asText())
          || "MOCK".equals(node.path("mode").asText())
          || "MOCK".equals(node.path("source").asText())) return ownerKey(Owner.localDeveloper());
      // Missing legacy ownership is never assigned to the account that happens to log in later.
      return "";
    } catch (Exception ignored) {
      return "";
    }
  }

  @Override
  public Page queryRecords(Owner owner, Query query) {
    Objects.requireNonNull(query);
    StringBuilder where = new StringBuilder(" WHERE a.owner_scope=?");
    List<Object> parameters = new ArrayList<>(List.of(ownerKey(owner)));
    if (query.runId() != null) {
      where.append(" AND a.run_id=?");
      parameters.add(query.runId());
    }
    if (!query.answerId().isEmpty()) {
      where.append(" AND a.answer_id LIKE ? ESCAPE '!'");
      parameters.add(contains(query.answerId()));
    }
    if (!query.questionId().isEmpty()) {
      where.append(" AND a.question_id LIKE ? ESCAPE '!'");
      parameters.add(contains(query.questionId()));
    }
    if (query.state() != null) {
      where.append(" AND a.state=?");
      parameters.add(query.state().name());
    }
    if (query.fromInclusive() != null) {
      where.append(" AND a.captured_at>=?");
      parameters.add(query.fromInclusive());
    }
    if (query.toExclusive() != null) {
      where.append(" AND a.captured_at<?");
      parameters.add(query.toExclusive());
    }
    try {
      long total;
      try (PreparedStatement statement =
              prepare("SELECT COUNT(*) FROM grading_attempt a" + where, parameters);
          ResultSet row = statement.executeQuery()) {
        row.next();
        total = row.getLong(1);
      }
      parameters.add(query.pageSize());
      parameters.add((long) query.page() * query.pageSize());
      List<Summary> rows = new ArrayList<>();
      try (PreparedStatement statement =
              prepare(
                  "SELECT a.*,"
                      + CONFIRMATION_EVENT_SQL
                      + ",(SELECT COUNT(*) FROM model_call c WHERE c.attempt_id=a.attempt_id) AS calls FROM grading_attempt a"
                      + where
                      + " ORDER BY a.captured_at DESC,a.attempt_id DESC LIMIT ? OFFSET ?",
                  parameters);
          ResultSet row = statement.executeQuery()) {
        while (row.next())
          rows.add(
              new Summary(
                  row.getObject("attempt_id", UUID.class),
                  row.getObject("run_id", UUID.class),
                  row.getString("answer_id"),
                  row.getString("question_id"),
                  SessionState.valueOf(row.getString("state")),
                  row.getBigDecimal("score"),
                  submissionStatus(row),
                  instant(row, "captured_at"),
                  row.getInt("calls")));
      }
      return new Page(rows, total, query.page(), query.pageSize());
    } catch (SQLException e) {
      throw failure();
    }
  }

  @Override
  public String recordJson(Owner owner, UUID attemptId) {
    return read(ownerKey(owner), attemptId);
  }

  /** Compatibility entry used only by the legacy internal journal API, never by account UI. */
  String internalRecordJson(UUID attemptId) {
    return read(null, attemptId);
  }

  private String read(String scope, UUID attemptId) {
    List<Object> params = new ArrayList<>(List.of(Objects.requireNonNull(attemptId)));
    if (scope != null) params.add(scope);
    try (PreparedStatement statement =
            prepare(
                "SELECT a.*,"
                    + CONFIRMATION_EVENT_SQL
                    + " FROM grading_attempt a WHERE attempt_id=?"
                    + (scope == null ? "" : " AND owner_scope=?"),
                params);
        ResultSet row = statement.executeQuery()) {
      if (!row.next()) throw failure();
      ObjectNode audit = JSON.createObjectNode();
      audit.put("schemaVersion", 2);
      audit.put("attemptId", attemptId.toString());
      audit.put("captureId", attemptId.toString());
      audit.put("runId", row.getObject("run_id", UUID.class).toString());
      audit.put("answerId", row.getString("answer_id"));
      audit.put("questionId", row.getString("question_id"));
      audit.put("state", row.getString("state"));
      audit.put("score", row.getBigDecimal("score"));
      audit.put("javaValidatedTotal", row.getBigDecimal("score"));
      audit.put("submissionPrepared", row.getBoolean("submit_prepared"));
      audit.put("submitSent", row.getBoolean("submit_sent"));
      audit.put("confirmedAt", time(row, "confirmed_at"));
      audit.put("captureSha256", row.getString("capture_sha256"));
      audit.put("capturedAt", time(row, "captured_at"));
      audit.put(
          "imagePersistence",
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED.equals(row.getString("evidence_path"))
              ? "NOT_STORED"
              : "LEGACY_REFERENCE_PRESERVED");
      audit.put("actionEpoch", row.getLong("action_epoch"));
      audit.put("ruleRevision", row.getLong("rule_revision"));
      audit.put("modelRevision", row.getLong("model_revision"));
      audit.put("platformRevision", row.getLong("platform_revision"));
      JsonNode configuration = parse(row.getString("config_snapshot"));
      audit.set("configuration", configuration);
      JsonNode question = parse(row.getString("question_snapshot"));
      audit.set("questionSnapshot", question);
      audit.set(
          "standardAnswer",
          question.path("standardAnswer").isMissingNode()
              ? JSON.nullNode()
              : question.path("standardAnswer"));
      audit.set("rubricSnapshot", embedded(configuration.get("rubric")));
      audit.set("modelSnapshot", embedded(configuration.get("profile")));
      audit.set("gradingResult", parse(row.getString("result_json")));
      UUID acceptedRequest = row.getObject("accepted_request_id", UUID.class);
      audit.put("acceptedRequestId", acceptedRequest == null ? null : acceptedRequest.toString());
      ObjectNode input = audit.putObject("input");
      input.put("javaTotal", row.getBigDecimal("score"));
      input.put("readbackScore", row.getBigDecimal("score_readback"));
      input.put("readbackAt", time(row, "score_readback_at"));
      input.put(
          "status",
          row.getObject("score_readback_at") == null
              ? "NO_MATCHED_READBACK_RECORDED"
              : "READBACK_MATCHED");
      boolean screenTransition = manualCoordinates(row);
      String confirmationKind = confirmationKind(row);
      ObjectNode submission = audit.putObject("submission");
      submission.put("status", submissionStatus(row));
      submission.put("confirmationKind", confirmationKind);
      submission.put("platformConfirmed", "PLATFORM_ACK".equals(confirmationKind));
      submission.put("intentPrepared", row.getBoolean("submit_prepared"));
      submission.put("dispatchPrepared", row.getBoolean("submit_sent"));
      submission.put("confirmedAt", time(row, "confirmed_at"));
      submission.put(
          "note",
          screenTransition
              ? "Manual-coordinate progress records dispatched input or an observed screen transition only. confirmedAt is the progress time and does not establish platform acceptance."
              : "Dispatch preparation is not proof the platform received a submission; only the platform acknowledgement is authoritative.");
      var calls = audit.putArray("modelCalls");
      try (PreparedStatement callsQuery =
              prepare(
                  "SELECT * FROM model_call WHERE attempt_id=? ORDER BY started_at,call_id",
                  List.of(attemptId));
          ResultSet call = callsQuery.executeQuery()) {
        while (call.next()) {
          ObjectNode entry = calls.addObject();
          UUID callId = call.getObject("call_id", UUID.class);
          UUID responseId = call.getObject("response_request_id", UUID.class);
          entry.put("callId", callId.toString());
          entry.put("requestId", callId.toString());
          entry.put("responseRequestId", responseId == null ? null : responseId.toString());
          entry.put("requestIdMatched", responseId == null ? null : callId.equals(responseId));
          entry.put("profileId", call.getString("profile_id"));
          entry.put("source", call.getString("source"));
          entry.put("startedAt", time(call, "started_at"));
          entry.put("completedAt", time(call, "completed_at"));
          entry.put("responseCompletedAt", time(call, "response_completed_at"));
          entry.put("inputTokens", call.getObject("input_tokens", Long.class));
          entry.put("outputTokens", call.getObject("output_tokens", Long.class));
          entry.put("estimatedCost", call.getBigDecimal("estimated_cost"));
          entry.put("elapsedMs", call.getObject("elapsed_ms", Long.class));
          entry.put("errorCode", call.getString("error_code"));
          entry.put("validationStatus", call.getString("validation_status"));
          entry.put("javaValidatedTotal", call.getBigDecimal("java_total"));
          entry.set("modelResult", parse(call.getString("result_json")));
          entry.put(
              "acceptedAsAttemptGrade",
              callId.equals(acceptedRequest)
                  && callId.equals(responseId)
                  && row.getBigDecimal("score") != null);
        }
      }
      var events = audit.putArray("events");
      try (PreparedStatement eventQuery =
              prepare(
                  "SELECT * FROM run_event WHERE attempt_id=? ORDER BY happened_at,event_id",
                  List.of(attemptId));
          ResultSet event = eventQuery.executeQuery()) {
        while (event.next()) {
          ObjectNode item = events.addObject();
          String eventType = event.getString("event_type");
          item.put(
              "state",
              screenTransition && "SUBMISSION_CONFIRMED".equals(eventType)
                  ? "SCREEN_TRANSITION_OBSERVED"
                  : eventType);
          item.put("reason", event.getString("reason"));
          item.put("at", time(event, "happened_at"));
        }
      }
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit);
    } catch (Exception e) {
      throw failure();
    }
  }

  private static String submissionStatus(ResultSet row) throws SQLException {
    String kind = confirmationKind(row);
    if (kind == null) return row.getBoolean("submit_prepared") ? "UNKNOWN" : "NOT_PREPARED";
    return switch (kind) {
      case "SCREEN_TRANSITION" -> "VISUALLY_ADVANCED";
      case "INPUT_DISPATCHED" -> "SUBMIT_CLICKED";
      default -> "CONFIRMED";
    };
  }

  private static String confirmationKind(ResultSet row) throws SQLException {
    if (row.getObject("confirmed_at") == null) return null;
    String eventType = row.getString("confirmation_event");
    if ("SCREEN_TRANSITION_OBSERVED".equals(eventType)) return "SCREEN_TRANSITION";
    if ("SUBMIT_CLICKED".equals(eventType)) return "INPUT_DISPATCHED";
    // Legacy manual progress has no precise event kind; never promote it to a platform ACK.
    return manualCoordinates(row) ? "SCREEN_TRANSITION" : "PLATFORM_ACK";
  }

  private static boolean manualCoordinates(ResultSet row) throws SQLException {
    try {
      return "MANUAL_COORDINATES"
          .equals(parse(row.getString("config_snapshot")).path("platform").path("mode").asText());
    } catch (java.io.IOException invalid) {
      throw new SQLException("Invalid record configuration");
    }
  }

  private static String contains(String text) {
    return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static String time(ResultSet row, String column) throws SQLException {
    Instant value = instant(row, column);
    return value == null ? null : value.toString();
  }

  private static JsonNode parse(String value) throws java.io.IOException {
    return value == null ? JSON.nullNode() : JSON.readTree(value);
  }

  private static JsonNode embedded(JsonNode value) throws java.io.IOException {
    if (value == null) return JSON.nullNode();
    if (value.isTextual()
        && (value.textValue().stripLeading().startsWith("{")
            || value.textValue().stripLeading().startsWith("[")))
      return JSON.readTree(value.textValue());
    return value;
  }

  private PreparedStatement prepare(String sql, List<Object> parameters) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    for (int i = 0; i < parameters.size(); i++) {
      Object value = parameters.get(i);
      statement.setObject(
          i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
    }
    return statement;
  }

  private static DeskGradeException failure() {
    return new DeskGradeException(ErrorCode.STORAGE_FAILURE);
  }
}
