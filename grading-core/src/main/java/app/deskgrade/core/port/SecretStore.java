package app.deskgrade.core.port;

import java.util.Optional;

/**
 * Credential boundary only; no plaintext implementation is supplied. Implementations must protect
 * stored values, never log them, and return caller-owned arrays that the caller clears after use.
 */
public interface SecretStore {
  void put(String credentialId, char[] secret);

  Optional<char[]> read(String credentialId);

  void delete(String credentialId);
}
