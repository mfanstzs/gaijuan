package app.deskgrade.adapters.commercial;

import static app.deskgrade.adapters.commercial.CommercialJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict wire codec for the ordinary-user model directory; it has no supplier-secret fields. */
public final class PublicModelProfileJsonCodec {
  private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "models");
  private static final Set<String> MODEL_FIELDS =
      Set.of(
          "profileId",
          "revision",
          "displayName",
          "modelId",
          "requestTimeoutMs",
          "verified",
          "role");

  public ObjectNode write(List<PublicModelProfile> profiles) {
    if (profiles == null || profiles.size() > 2) throw new IllegalArgumentException();
    var root = MAPPER.createObjectNode();
    root.put("schemaVersion", 1);
    var models = root.putArray("models");
    var identities = new HashSet<String>();
    for (var profile : profiles) {
      if (profile == null || !identities.add(profile.profileId()))
        throw new IllegalArgumentException();
      var model = models.addObject();
      model.put("profileId", profile.profileId());
      model.put("revision", profile.revision());
      model.put("displayName", profile.displayName());
      model.put("modelId", profile.modelId());
      model.put("requestTimeoutMs", profile.requestTimeout().toMillis());
      model.put("verified", profile.verified());
      model.put("role", profile.role().name());
    }
    return root;
  }

  public List<PublicModelProfile> read(JsonNode root) {
    try {
      fields(root, ROOT_FIELDS);
      if (!root.get("schemaVersion").isIntegralNumber()
          || root.get("schemaVersion").intValue() != 1
          || !root.get("models").isArray()
          || root.get("models").size() > 2) throw invalid();
      var result = new ArrayList<PublicModelProfile>();
      var identities = new HashSet<String>();
      for (var model : root.get("models")) {
        fields(model, MODEL_FIELDS);
        String profileId = identifier(model.get("profileId"));
        long revision = positive(model.get("revision"));
        String displayName = text(model.get("displayName"), 1, 200);
        String modelId = modelId(model.get("modelId"));
        long requestTimeoutMs = bounded(model.get("requestTimeoutMs"), 100, 600_000);
        boolean verified = bool(model.get("verified"));
        PublicModelProfile.Role role;
        try {
          role = PublicModelProfile.Role.valueOf(text(model.get("role"), 1, 20));
        } catch (IllegalArgumentException unknown) {
          throw invalid();
        }
        if (!identities.add(profileId)) throw invalid();
        result.add(
            new PublicModelProfile(
                profileId,
                revision,
                displayName,
                modelId,
                Duration.ofMillis(requestTimeoutMs),
                verified,
                role));
      }
      return List.copyOf(result);
    } catch (GatewayException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static String identifier(JsonNode node) {
    String value = text(node, 1, 100);
    if (!value.matches("[A-Za-z0-9_-]{1,100}")) throw invalid();
    return value;
  }

  private static String modelId(JsonNode node) {
    String value = text(node, 1, 200);
    if (!value.matches("[A-Za-z0-9_.:/-]{1,200}")) throw invalid();
    return value;
  }

  private static long positive(JsonNode node) {
    long value = nonnegative(node);
    if (value < 1) throw invalid();
    return value;
  }

  private static long bounded(JsonNode node, long minimum, long maximum) {
    long value = nonnegative(node);
    if (value < minimum || value > maximum) throw invalid();
    return value;
  }
}
