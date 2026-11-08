package app.deskgrade.gateway;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Server-side sessions. Callers pass a SHA-256 hash, never the bearer token itself. */
public interface SessionTokenStore extends AutoCloseable {
  record Session(UUID userId, Instant expiresAt) {}

  void put(String tokenHash, UUID userId, Instant expiresAt);

  Optional<Session> find(String tokenHash);

  void delete(String tokenHash);

  /** Invalidates every previously issued session for this user, across gateway processes. */
  void revokeUser(UUID userId);

  /** Throws SERVICE_UNAVAILABLE if the required session service cannot be reached. */
  void verifyAvailable();

  @Override
  void close();
}
