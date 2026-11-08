package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Called only after Bearer authentication; ownership comes from the server, never the request. */
public final class JournalCommandDispatcher {
  private JournalCommandDispatcher() {}

  public static JsonNode execute(
      PersistentSessionJournal journal, GradingRecords.Owner owner, JsonNode request) {
    Objects.requireNonNull(journal);
    Objects.requireNonNull(owner);
    exact(request, "schemaVersion", "operation", "arguments");
    if (!request.path("schemaVersion").isInt() || request.path("schemaVersion").intValue() != 1)
      throw invalid();
    String operation = string(request, "operation", 50);
    JsonNode args = request.get("arguments");
    if (args == null || !args.isObject()) throw invalid();
    Object result = null;
    switch (operation) {
      case "assertWritable" -> {
        exact(args);
        journal.assertWritable();
      }
      case "hasUnknownSubmission" -> {
        exact(args, "targetIdentity");
        result = journal.hasUnknownSubmission(string(args, "targetIdentity", 500));
      }
      case "unresolvedSubmissions" -> {
        exact(args, "targetIdentity");
        result = journal.unresolvedSubmissions(owner, string(args, "targetIdentity", 500));
      }
      case "abandonUnresolvedRun" -> {
        exact(args, "runId", "targetIdentity", "decisionId");
        journal.abandonUnresolvedRun(
            owner,
            value(args, "runId", UUID.class),
            string(args, "targetIdentity", 500),
            value(args, "decisionId", UUID.class),
            Instant.now());
      }
      case "beginRun" -> {
        exact(args, "runId", "targetIdentity", "settings", "now");
        journal.beginRun(
            value(args, "runId", UUID.class),
            string(args, "targetIdentity", 500),
            value(args, "settings", SessionSettings.class),
            value(args, "now", Instant.class));
      }
      case "recordConfiguration" -> {
        exact(args, "runId", "settings", "now");
        journal.recordConfiguration(
            value(args, "runId", UUID.class),
            value(args, "settings", SessionSettings.class),
            value(args, "now", Instant.class));
      }
      case "beginAttempt" -> {
        exact(
            args,
            "runId",
            "attemptId",
            "ordinal",
            "view",
            "epoch",
            "evidencePath",
            "sha256",
            "capturedAt",
            "settings");
        String sha = string(args, "sha256", 64);
        if (!sha.matches("[a-fA-F0-9]{64}")) throw invalid();
        if (!MetadataOnlyEvidenceStore.NO_IMAGE_STORED.equals(string(args, "evidencePath", 2000)))
          throw invalid();
        int ordinal = value(args, "ordinal", Integer.class);
        long epoch = value(args, "epoch", Long.class);
        if (ordinal < 1 || epoch < 0) throw invalid();
        var capture =
            new SessionPlatform.Capture(
                new byte[] {0}, sha, value(args, "capturedAt", Instant.class));
        journal.beginAttempt(
            value(args, "runId", UUID.class),
            value(args, "attemptId", UUID.class),
            ordinal,
            view(args),
            epoch,
            MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
            capture,
            value(args, "settings", SessionSettings.class));
      }
      case "transition" -> {
        exact(args, "runId", "attemptId", "state", "reason", "now");
        journal.transition(
            nullable(args, "runId", UUID.class),
            nullable(args, "attemptId", UUID.class),
            value(args, "state", SessionState.class),
            value(args, "reason", PauseReason.class),
            value(args, "now", Instant.class));
      }
      case "beginModelCall" -> {
        exact(args, "callId", "runId", "attemptId", "profileId", "now");
        journal.beginModelCall(
            value(args, "callId", UUID.class),
            value(args, "runId", UUID.class),
            value(args, "attemptId", UUID.class),
            string(args, "profileId", 200),
            value(args, "now", Instant.class));
      }
      case "completeModelCall" -> {
        exact(args, "callId", "response", "safeCode", "now");
        journal.completeModelCall(
            value(args, "callId", UUID.class),
            nullable(args, "response", GradingResponse.class),
            nullable(args, "safeCode", String.class),
            value(args, "now", Instant.class));
      }
      case "completeModelCallMetrics" -> {
        exact(args, "callId", "source", "usage", "elapsed", "safeCode", "now");
        journal.completeModelCallMetrics(
            value(args, "callId", UUID.class),
            nullable(args, "source", GradingResponse.Source.class),
            nullable(args, "usage", GradingResponse.Usage.class),
            nullable(args, "elapsed", Duration.class),
            nullable(args, "safeCode", String.class),
            value(args, "now", Instant.class));
      }
      case "saveGrade" -> {
        exact(args, "attemptId", "response", "total");
        journal.saveGrade(
            value(args, "attemptId", UUID.class),
            value(args, "response", GradingResponse.class),
            nullable(args, "total", BigDecimal.class));
      }
      case "recordScoreReadback" -> {
        exact(args, "attemptId", "observedScore", "now");
        journal.recordScoreReadback(
            value(args, "attemptId", UUID.class),
            value(args, "observedScore", BigDecimal.class),
            value(args, "now", Instant.class));
      }
      case "confirmSubmission" -> {
        if (args.has("confirmationKind")) {
          exact(args, "runId", "attemptId", "now", "confirmationKind");
          journal.confirmSubmission(
              value(args, "runId", UUID.class),
              value(args, "attemptId", UUID.class),
              value(args, "now", Instant.class),
              value(args, "confirmationKind", SessionPlatform.ConfirmationKind.class));
        } else {
          exact(args, "runId", "attemptId", "now");
          journal.confirmSubmission(
              value(args, "runId", UUID.class),
              value(args, "attemptId", UUID.class),
              value(args, "now", Instant.class));
        }
      }
      case "recoverInterrupted" -> {
        exact(args, "now");
        result = journal.recoverInterrupted(value(args, "now", Instant.class));
      }
      case "runs" -> {
        exact(args);
        result = journal.runs();
      }
      case "attempts" -> {
        exact(args, "runId");
        result = journal.attempts(value(args, "runId", UUID.class));
      }
      case "queryRecords" -> {
        exact(args, "query");
        result = journal.queryRecords(owner, value(args, "query", GradingRecords.Query.class));
      }
      case "recordJson" -> {
        exact(args, "attemptId");
        result = journal.recordJson(owner, value(args, "attemptId", UUID.class));
      }
      default -> throw invalid();
    }
    var response = JournalJson.MAPPER.createObjectNode();
    response.set("result", JournalJson.MAPPER.valueToTree(result));
    return response;
  }

  private static void exact(JsonNode node, String... fields) {
    if (node == null || !node.isObject()) throw invalid();
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(fields))) throw invalid();
  }

  private static SessionPlatform.View view(JsonNode args) {
    JsonNode encoded = args.get("view");
    if (encoded == null || !encoded.isObject()) throw invalid();
    if (encoded.has("confirmationKind")) return value(args, "view", SessionPlatform.View.class);
    // The original wire contract predates provenance; old protocol clients implied platform ACK.
    var compatible = ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).deepCopy();
    compatible.put("confirmationKind", SessionPlatform.ConfirmationKind.PLATFORM_ACK.name());
    try {
      return JournalJson.MAPPER.treeToValue(compatible, SessionPlatform.View.class);
    } catch (Exception problem) {
      throw invalid();
    }
  }

  private static String string(JsonNode node, String field, int max) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().isEmpty()
        || value.textValue().length() > max) throw invalid();
    return value.textValue();
  }

  private static <T> T value(JsonNode node, String field, Class<T> type) {
    T result = nullable(node, field, type);
    if (result == null) throw invalid();
    return result;
  }

  private static <T> T nullable(JsonNode node, String field, Class<T> type) {
    try {
      return JournalJson.MAPPER.treeToValue(node.get(field), type);
    } catch (Exception problem) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid journal command");
  }
}
