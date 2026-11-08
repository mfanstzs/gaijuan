package app.deskgrade.adapters.model;

import app.deskgrade.core.domain.model.ProviderProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Portable configurations contain references only. Imports must rerun capability verification. */
public final class ProfileJsonCodec {
  public static final int MAX_LOCAL_PROFILES = 256;
  public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

  public String write(List<ProviderProfile> profiles) {
    var root = StrictJson.MAPPER.createObjectNode();
    root.put("schemaVersion", 1);
    var list = root.putArray("profiles");
    for (var p : profiles) {
      var n = list.addObject();
      n.put("id", p.id());
      n.put("revision", p.revision());
      n.put("displayName", p.displayName());
      n.put("endpoint", p.endpoint().toString());
      n.put("modelId", p.modelId());
      n.put("credentialRef", p.credentialRef());
      n.put("structuredMode", p.structuredMode().name());
      var params = n.putObject("parameters");
      new java.util.TreeMap<>(p.parameters()).forEach(params::put);
      var allowed = n.putArray("allowedParameters");
      new java.util.TreeSet<>(p.allowedParameters()).forEach(allowed::add);
      n.put("connectTimeoutMs", p.connectTimeout().toMillis());
      n.put("requestTimeoutMs", p.requestTimeout().toMillis());
      if (p.pricing() == null) n.putNull("pricing");
      else {
        var price = n.putObject("pricing");
        price.put("currency", p.pricing().currency());
        price.put("inputPerMillion", p.pricing().inputPerMillion());
        price.put("outputPerMillion", p.pricing().outputPerMillion());
      }
      if (p.verification() == null) n.putNull("verification");
      else {
        var v = n.putObject("verification");
        v.put("configDigest", p.verification().configDigest());
        v.put("testedAt", p.verification().testedAt().toString());
        v.put("imagePassed", p.verification().imagePassed());
        v.put("structuredOutputPassed", p.verification().structuredOutputPassed());
      }
    }
    return root.toPrettyString();
  }

  public List<ProviderProfile> read(String json) {
    return read(json, false);
  }

  /** Use only for the application's own local saved profiles, never an imported file. */
  public List<ProviderProfile> readLocal(String json) {
    return read(json, true);
  }

  private List<ProviderProfile> read(String json, boolean retainVerification) {
    try {
      if (json == null
          || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
        StrictJson.bad();
      var root = StrictJson.parse(json);
      StrictJson.fields(root, Set.of("schemaVersion", "profiles"));
      if (!root.get("schemaVersion").isIntegralNumber()
          || root.get("schemaVersion").intValue() != 1
          || !root.get("profiles").isArray()
          || root.get("profiles").size() > (retainVerification ? MAX_LOCAL_PROFILES : 20))
        StrictJson.bad();
      List<ProviderProfile> result = new ArrayList<>();
      Set<String> ids = new LinkedHashSet<>();
      for (var n : root.get("profiles")) {
        StrictJson.fields(
            n,
            Set.of(
                "id",
                "revision",
                "displayName",
                "endpoint",
                "modelId",
                "credentialRef",
                "structuredMode",
                "parameters",
                "allowedParameters",
                "connectTimeoutMs",
                "requestTimeoutMs",
                "pricing",
                "verification"));
        if (!n.get("revision").isIntegralNumber()
            || !n.get("connectTimeoutMs").isIntegralNumber()
            || !n.get("requestTimeoutMs").isIntegralNumber()
            || !n.get("parameters").isObject()
            || !n.get("allowedParameters").isArray()) StrictJson.bad();
        var parameters = new LinkedHashMap<String, String>();
        n.get("parameters")
            .fields()
            .forEachRemaining(
                p -> parameters.put(p.getKey(), StrictJson.string(p.getValue(), 1, 100, false)));
        var allowed = new LinkedHashSet<String>();
        for (var item : n.get("allowedParameters"))
          if (!allowed.add(StrictJson.string(item, 1, 100, false))) StrictJson.bad();
        ProviderProfile.Pricing pricing = null;
        var p = n.get("pricing");
        if (!p.isNull()) {
          StrictJson.fields(p, Set.of("currency", "inputPerMillion", "outputPerMillion"));
          pricing =
              new ProviderProfile.Pricing(
                  StrictJson.string(p.get("currency"), 3, 3, false),
                  decimal(p.get("inputPerMillion")),
                  decimal(p.get("outputPerMillion")));
        }
        ProviderProfile.Verification verification = null;
        var v = n.get("verification");
        if (!v.isNull()) {
          StrictJson.fields(
              v, Set.of("configDigest", "testedAt", "imagePassed", "structuredOutputPassed"));
          if (!v.get("imagePassed").isBoolean() || !v.get("structuredOutputPassed").isBoolean())
            StrictJson.bad();
          var parsed =
              new ProviderProfile.Verification(
                  StrictJson.string(v.get("configDigest"), 64, 64, false),
                  Instant.parse(StrictJson.string(v.get("testedAt"), 1, 100, false)),
                  v.get("imagePassed").booleanValue(),
                  v.get("structuredOutputPassed").booleanValue());
          if (retainVerification) verification = parsed;
        }
        var profile =
            new ProviderProfile(
                StrictJson.string(n.get("id"), 1, 100, false),
                n.get("revision").longValue(),
                StrictJson.string(n.get("displayName"), 1, 200, false),
                URI.create(StrictJson.string(n.get("endpoint"), 1, 2000, false)),
                StrictJson.string(n.get("modelId"), 1, 200, false),
                StrictJson.string(n.get("credentialRef"), 1, 100, false),
                ProviderProfile.StructuredMode.valueOf(
                    StrictJson.string(n.get("structuredMode"), 1, 20, false)),
                parameters,
                allowed,
                Duration.ofMillis(n.get("connectTimeoutMs").longValue()),
                Duration.ofMillis(n.get("requestTimeoutMs").longValue()),
                pricing,
                verification);
        if (!ids.add(profile.id())) StrictJson.bad();
        result.add(profile);
      }
      return List.copyOf(result);
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Failure.INVALID_RESULT);
    }
  }

  private java.math.BigDecimal decimal(JsonNode n) {
    if (n.isNull()) return null;
    if (!n.isNumber()) StrictJson.bad();
    return n.decimalValue();
  }
}
