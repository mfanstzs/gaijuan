package app.deskgrade.core.domain.model;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * User selected endpoint and exact model; no default model ID and no implicit parameter support.
 */
public record ProviderProfile(
    String id,
    long revision,
    String displayName,
    URI endpoint,
    String modelId,
    String credentialRef,
    StructuredMode structuredMode,
    Map<String, String> parameters,
    Set<String> allowedParameters,
    Duration connectTimeout,
    Duration requestTimeout,
    Pricing pricing,
    Verification verification) {
  public ProviderProfile {
    if (id == null
        || !id.matches("[A-Za-z0-9_-]{1,100}")
        || revision < 1
        || displayName == null
        || displayName.isBlank()
        || displayName.length() > 200
        || endpoint == null
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || !("https".equals(endpoint.getScheme())
            || ("http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())))
        || !endpoint.getPath().endsWith("/chat/completions")
        || modelId == null
        || !modelId.matches("[A-Za-z0-9_.:/-]{1,200}")
        || credentialRef == null
        || !credentialRef.matches("[A-Za-z0-9_-]{1,100}")
        || structuredMode == null
        || parameters == null
        || allowedParameters == null
        || connectTimeout == null
        || requestTimeout == null
        || connectTimeout.toMillis() < 100
        || connectTimeout.toSeconds() > 120
        || requestTimeout.toMillis() < 100
        || requestTimeout.toSeconds() > 600) fail();
    parameters = Map.copyOf(parameters);
    allowedParameters = Set.copyOf(allowedParameters);
    if (!Set.of("temperature", "max_tokens", "enable_thinking", "reasoning_effort")
            .containsAll(allowedParameters)
        || !allowedParameters.containsAll(parameters.keySet())) fail();
    try {
      for (var p : parameters.entrySet()) {
        switch (p.getKey()) {
          case "temperature" -> {
            var v = new BigDecimal(p.getValue());
            if (v.signum() < 0 || v.compareTo(new BigDecimal("2")) >= 0) fail();
          }
          case "max_tokens" -> {
            int v = Integer.parseInt(p.getValue());
            if (v < 1 || v > 1000000) fail();
          }
          case "enable_thinking" -> {
            if (!Set.of("true", "false").contains(p.getValue())) fail();
          }
          case "reasoning_effort" -> {
            if (!Set.of("low", "medium", "xhigh").contains(p.getValue())) fail();
          }
          default -> fail();
        }
      }
    } catch (NumberFormatException e) {
      fail();
    }
    // Effort applies only to thinking. Reject ambiguous combinations rather than silently ignoring
    // the user's selected mode; omission still leaves the provider's existing default unchanged.
    if ("false".equals(parameters.get("enable_thinking"))
        && parameters.containsKey("reasoning_effort")) fail();
  }

  public String configDigest() {
    String canonical =
        id
            + "\n"
            + revision
            + "\n"
            + endpoint
            + "\n"
            + modelId
            + "\n"
            + credentialRef
            + "\n"
            + structuredMode
            + "\n"
            + new TreeMap<>(parameters)
            + "\n"
            + new TreeSet<>(allowedParameters)
            + "\n"
            + connectTimeout
            + "\n"
            + requestTimeout;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public boolean isVerified() {
    return verification != null
        && verification.imagePassed()
        && verification.structuredOutputPassed()
        && configDigest().equals(verification.configDigest());
  }

  /** Text-only generation requires a structured-output test, not image capability. */
  public boolean isStructuredVerified() {
    return verification != null
        && verification.structuredOutputPassed()
        && configDigest().equals(verification.configDigest());
  }

  public ProviderProfile withVerification(Verification value) {
    return new ProviderProfile(
        id,
        revision,
        displayName,
        endpoint,
        modelId,
        credentialRef,
        structuredMode,
        parameters,
        allowedParameters,
        connectTimeout,
        requestTimeout,
        pricing,
        value);
  }

  public enum StructuredMode {
    NONE,
    JSON_OBJECT,
    JSON_SCHEMA
  }

  public record Verification(
      String configDigest, Instant testedAt, boolean imagePassed, boolean structuredOutputPassed) {
    public Verification {
      if (configDigest == null || !configDigest.matches("[a-f0-9]{64}") || testedAt == null) fail();
    }
  }

  public record Pricing(String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    public Pricing {
      inputPerMillion = safePrice(inputPerMillion);
      outputPerMillion = safePrice(outputPerMillion);
      if (currency == null
          || !currency.matches("[A-Z]{3}")
          || !validPrice(inputPerMillion)
          || !validPrice(outputPerMillion)) fail();
    }

    private static BigDecimal safePrice(BigDecimal value) {
      if (value == null) return null;
      if (value.scale() <= 18 && value.precision() - value.scale() <= 20) return value;
      return value.stripTrailingZeros();
    }

    private static boolean validPrice(BigDecimal value) {
      return value == null
          || (value.signum() >= 0
              && value.scale() <= 18
              && value.precision() - value.scale() <= 20);
    }
  }

  private static void fail() {
    throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
  }
}
