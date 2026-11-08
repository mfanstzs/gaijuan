package app.deskgrade.adapters.commercial;

import static app.deskgrade.adapters.commercial.CommercialJson.*;

import app.deskgrade.core.port.SecretStore;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Entire cached session is protected by the supplied secret store, including user metadata. */
public final class CommercialSessionCache {
  private static final String ID = "commercial-session-v1";
  private final SecretStore secrets;

  public CommercialSessionCache(SecretStore secrets) {
    this.secrets = Objects.requireNonNull(secrets);
  }

  void save(URI service, char[] token, CommercialClient.SessionView account) {
    var node = MAPPER.createObjectNode();
    node.put("schemaVersion", 1);
    node.put("service", service.toString());
    node.put("token", new String(token));
    node.put("accountId", account.accountId());
    node.put("username", account.username());
    node.put("role", account.role());
    node.put("credits", account.credits());
    node.put("expiresAt", account.expiresAt().toString());
    char[] encoded = node.toString().toCharArray();
    try {
      secrets.put(ID, encoded);
    } finally {
      Arrays.fill(encoded, '\0');
    }
  }

  Optional<Saved> load(URI service, Instant now) {
    char[] encoded = null;
    try {
      var value = secrets.read(ID);
      if (value.isEmpty()) return Optional.empty();
      encoded = value.orElseThrow();
      if (encoded.length > 8192) throw new IllegalArgumentException();
      var node = MAPPER.readTree(new String(encoded));
      fields(
          node,
          Set.of(
              "schemaVersion",
              "service",
              "token",
              "accountId",
              "username",
              "role",
              "credits",
              "expiresAt"));
      if (!node.get("schemaVersion").isIntegralNumber() || node.get("schemaVersion").asInt() != 1)
        throw new IllegalArgumentException();
      if (!service.toString().equals(text(node.get("service"), 1, 2048))) {
        clear();
        return Optional.empty();
      }
      Instant expiry = Instant.parse(text(node.get("expiresAt"), 1, 100));
      if (!expiry.isAfter(now)) {
        clear();
        return Optional.empty();
      }
      String token = text(node.get("token"), 16, 4096);
      if (!token.matches("[A-Za-z0-9._~-]+")) throw new IllegalArgumentException();
      var account =
          new CommercialClient.SessionView(
              text(node.get("accountId"), 1, 200),
              text(node.get("username"), 3, 32),
              text(node.get("role"), 1, 32),
              nonnegative(node.get("credits")),
              expiry);
      return Optional.of(new Saved(token.toCharArray(), account));
    } catch (Exception failure) {
      clear();
      throw new IllegalStateException("Saved account session is unavailable");
    } finally {
      if (encoded != null) Arrays.fill(encoded, '\0');
    }
  }

  public void clear() {
    secrets.delete(ID);
  }

  static final class Saved implements AutoCloseable {
    final char[] token;
    final CommercialClient.SessionView account;

    Saved(char[] token, CommercialClient.SessionView account) {
      this.token = token;
      this.account = account;
    }

    @Override
    public void close() {
      Arrays.fill(token, '\0');
    }

    @Override
    public String toString() {
      return "SavedSession[redacted]";
    }
  }
}
