package app.deskgrade.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Adds server-owned exchange facts after the journal has enforced the current account scope. */
final class GatewayCallRecordEnricher {
  private static final int MAX_REPLY_BYTES = 8 * 1024 * 1024;
  private static final int MAX_CALLS = 50;
  private static final Set<String> INTERNAL_FIELDS =
      Set.of(
          "model",
          "modelid",
          "profileid",
          "serverprofileid",
          "fallbackprofileid",
          "fallbackmodelid",
          "credentialref",
          "endpoint",
          "gateway",
          "serviceaddress",
          "accountid",
          "ownerkey",
          "access",
          "profile",
          "binding",
          "platform",
          "modelsnapshot",
          "mode",
          "maxcalls",
          "targetidentity",
          "calibrationid",
          "calibrationrevision",
          "savedat",
          "hwnd",
          "pid",
          "processstartedat",
          "identity",
          "title",
          "windowclass",
          "windowbounds",
          "clientbounds",
          "display",
          "dpi",
          "layout",
          "answerregion",
          "scoreregion",
          "submitregion",
          "submissionackregion",
          "nextregion",
          "calibratedregion");

  private GatewayCallRecordEnricher() {}

  static JsonNode enrich(GatewayStore store, UUID userId, JsonNode command, JsonNode reply) {
    if (!"recordJson".equals(command.path("operation").asText())
        || !reply.path("result").isTextual()) return reply;
    var detail =
        (ObjectNode)
            GatewayJson.parse(reply.path("result").asText().getBytes(StandardCharsets.UTF_8));
    redact(detail);
    var calls = detail.putArray("serverCalls");
    var seen = new HashSet<UUID>();
    for (var local : detail.path("modelCalls")) {
      UUID id;
      try {
        id = UUID.fromString(local.path("requestId").asText());
      } catch (IllegalArgumentException malformed) {
        continue;
      }
      if (!seen.add(id)) continue;
      if (seen.size() > MAX_CALLS) {
        detail.put("serverCallsTruncated", true);
        break;
      }
      var entry = GatewayJson.MAPPER.createObjectNode();
      entry.put("requestId", id.toString());
      entry.put("kind", "GRADING");
      try {
        var stored = store.modelCallDetail(userId, id, "GRADING");
        entry.set("request", customerSafe(objectOrNull(stored.requestJson())));
        entry.set("response", customerSafe(objectOrNull(stored.responseJson())));
        entry.put("modelOutput", stored.modelOutput());
        entry.put("state", stored.call().state());
      } catch (GatewayException missing) {
        if (missing.code() != GatewayException.Code.NOT_FOUND
            && missing.code() != GatewayException.Code.REQUEST_UNKNOWN) throw missing;
        entry.put("traceUnavailable", "NO_SERVER_RECORD");
      }
      calls.add(entry);
      if (replyWith(reply, detail).toString().getBytes(StandardCharsets.UTF_8).length
          > MAX_REPLY_BYTES) {
        calls.remove(calls.size() - 1);
        calls
            .addObject()
            .put("requestId", id.toString())
            .put("kind", "GRADING")
            .put("traceUnavailable", "DETAIL_SIZE_LIMIT");
        detail.put("serverCallsTruncated", true);
        break;
      }
    }
    var result = replyWith(reply, detail);
    while (replyBytes(result) > MAX_REPLY_BYTES && !calls.isEmpty()) {
      calls.remove(calls.size() - 1);
      detail.put("serverCallsTruncated", true);
      result = replyWith(reply, detail);
    }
    if (replyBytes(result) > MAX_REPLY_BYTES) {
      // Keep the response bounded even when the original journal detail alone is oversized.
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
    return result;
  }

  private static int replyBytes(JsonNode reply) {
    return reply.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private static JsonNode objectOrNull(String json) {
    return json == null
        ? GatewayJson.MAPPER.nullNode()
        : GatewayJson.parse(json.getBytes(StandardCharsets.UTF_8));
  }

  /** Preserve grading evidence while removing provider, deployment and desktop internals. */
  private static JsonNode customerSafe(JsonNode source) {
    JsonNode copy = source.deepCopy();
    redact(copy);
    return copy;
  }

  private static void redact(JsonNode node) {
    if (node instanceof ObjectNode object) {
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        if (INTERNAL_FIELDS.contains(name.toLowerCase(Locale.ROOT))) object.remove(name);
        else redact(object.path(name));
      }
    } else if (node instanceof ArrayNode array) {
      for (JsonNode item : array) redact(item);
    }
  }

  private static ObjectNode replyWith(JsonNode reply, ObjectNode detail) {
    var enriched = (ObjectNode) reply.deepCopy();
    enriched.put("result", detail.toString());
    return enriched;
  }
}
