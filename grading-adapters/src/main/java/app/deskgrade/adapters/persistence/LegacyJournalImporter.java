package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.GradingRecords;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Offline migration from a backed-up V2 H2 journal. Unknown ownership is retained in the source.
 */
public final class LegacyJournalImporter {
  private LegacyJournalImporter() {}

  public record Report(
      int importedRuns,
      int skippedRuns,
      int importedAttempts,
      int importedCalls,
      List<String> quarantinedRunIds) {
    public Report {
      quarantinedRunIds = List.copyOf(quarantinedRunIds);
    }
  }

  /** Existing destination records are never overwritten; per-run checkpoints make re-entry safe. */
  public static Report importV2(
      Connection legacy, Connection target, Set<String> existingAccountIds) {
    Objects.requireNonNull(existingAccountIds);
    int imported = 0, skipped = 0, attempts = 0, calls = 0;
    List<String> quarantined = new ArrayList<>();
    try {
      if (!target.getAutoCommit()) throw new SQLException("Migration needs a dedicated connection");
      List<Map<String, Object>> runs = read(legacy, "SELECT * FROM grading_run ORDER BY run_id");
      for (var run : runs) {
        String runId = (String) run.get("run_id");
        var attemptRows =
            read(legacy, "SELECT * FROM grading_attempt WHERE run_id=? ORDER BY ordinal", runId);
        GradingRecords.Owner owner = owner((String) run.get("config_snapshot"));
        boolean known = owner != null && existingAccountIds.contains(owner.accountId());
        String scope = known ? H2RecordReader.ownerKey(owner) : "";
        for (var attempt : attemptRows) {
          known &=
              scope.equals(
                  H2RecordReader.ownerFromSnapshot((String) attempt.get("config_snapshot")));
          known &= scope.equals(attempt.get("owner_scope"));
        }
        if (!known) {
          quarantined.add(runId);
          continue;
        }
        var callRows =
            read(legacy, "SELECT * FROM model_call WHERE run_id=? ORDER BY call_id", runId);
        var eventRows =
            read(legacy, "SELECT * FROM run_event WHERE run_id=? ORDER BY event_id", runId);
        var configRows =
            read(
                legacy,
                "SELECT * FROM config_document WHERE id LIKE ? OR id=? ORDER BY id",
                runId + ":%",
                "manual-abandon:" + runId);
        String digest = digest(List.of(run, attemptRows, callRows, eventRows, configRows));
        var marker =
            read(target, "SELECT source_digest FROM journal_legacy_import WHERE run_id=?", runId);
        if (!marker.isEmpty()) {
          if (!digest.equals(marker.getFirst().get("source_digest")))
            throw new SQLException("Changed legacy run conflicts with checkpoint");
          skipped++;
          continue;
        }
        if (!read(target, "SELECT run_id FROM journal_grading_run WHERE run_id=?", runId).isEmpty())
          throw new SQLException("Legacy run ID conflicts with destination");
        target.setAutoCommit(false);
        try {
          run.put("owner_scope", scope);
          insert(target, "grading_run", run);
          for (var row : configRows) insert(target, "config_document", row);
          for (var row : attemptRows) insert(target, "grading_attempt", row);
          for (var row : callRows) insert(target, "model_call", row);
          for (var row : eventRows) insert(target, "run_event", row);
          Map<String, Object> checkpoint = new LinkedHashMap<>();
          checkpoint.put("run_id", runId);
          checkpoint.put("source_digest", digest);
          checkpoint.put("imported_at", Instant.now());
          insert(target, "legacy_import", checkpoint);
          target.commit();
        } catch (Exception failure) {
          target.rollback();
          throw failure;
        } finally {
          target.setAutoCommit(true);
        }
        imported++;
        attempts += attemptRows.size();
        calls += callRows.size();
      }
      return new Report(imported, skipped, attempts, calls, quarantined);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Legacy journal migration failed; original database retained", failure);
    }
  }

  private static GradingRecords.Owner owner(String configuration) {
    try {
      JsonNode access = JournalJson.MAPPER.readTree(configuration).path("access");
      if (!"COMMERCIAL".equals(access.path("mode").asText())) return null;
      return new GradingRecords.Owner(
          access.path("gateway").asText(), access.path("accountId").asText());
    } catch (Exception invalid) {
      return null;
    }
  }

  private static String digest(Object value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(
                    JournalJson.MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
  }

  private static List<Map<String, Object>> read(
      Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) query.setObject(i + 1, parameters[i]);
      try (ResultSet result = query.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (result.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
            String name = result.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT);
            Object value = result.getObject(column);
            if (value instanceof UUID uuid) value = uuid.toString();
            else if (value instanceof OffsetDateTime time) value = time.toInstant();
            else if (value instanceof Timestamp time) value = time.toInstant();
            else if (value instanceof Clob) value = result.getString(column);
            row.put(name, value);
          }
          rows.add(row);
        }
        return rows;
      }
    }
  }

  private static void insert(Connection connection, String table, Map<String, Object> row)
      throws SQLException {
    if (!Set.of(
            "grading_run",
            "config_document",
            "grading_attempt",
            "model_call",
            "run_event",
            "legacy_import")
        .contains(table)) throw new SQLException("Invalid migration table");
    // Column names come only from JDBC metadata, never from submitted record content.
    for (String column : row.keySet())
      if (!column.matches("[a-z][a-z0-9_]*")) throw new SQLException("Invalid migration column");
    String sql =
        "INSERT INTO journal_"
            + table
            + "("
            + String.join(",", row.keySet())
            + ") VALUES("
            + String.join(",", Collections.nCopies(row.size(), "?"))
            + ")";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      int index = 1;
      for (Object value : row.values()) {
        if (value instanceof Instant time)
          insert.setTimestamp(
              index++, Timestamp.from(time), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        else insert.setObject(index++, value);
      }
      insert.executeUpdate();
    }
  }
}
