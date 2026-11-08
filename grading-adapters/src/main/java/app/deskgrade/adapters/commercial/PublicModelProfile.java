package app.deskgrade.adapters.commercial;

import java.time.Duration;

/** Minimal non-secret model binding published to ordinary authenticated desktop clients. */
public record PublicModelProfile(
    String profileId,
    long revision,
    String displayName,
    String modelId,
    Duration requestTimeout,
    boolean verified,
    Role role) {
  public PublicModelProfile {
    if (profileId == null
        || !profileId.matches("[A-Za-z0-9_-]{1,100}")
        || revision < 1
        || displayName == null
        || displayName.isBlank()
        || displayName.codePointCount(0, displayName.length()) > 200
        || modelId == null
        || !modelId.matches("[A-Za-z0-9_.:/-]{1,200}")
        || requestTimeout == null
        || requestTimeout.toMillis() < 100
        || requestTimeout.toMillis() > 600_000
        || role == null) throw new IllegalArgumentException("Invalid public model profile");
  }

  /** Compatibility-friendly identity accessor without turning this into a ProviderProfile. */
  public String id() {
    return profileId;
  }

  public boolean isVerified() {
    return verified;
  }

  public enum Role {
    PRIMARY,
    FALLBACK
  }
}
