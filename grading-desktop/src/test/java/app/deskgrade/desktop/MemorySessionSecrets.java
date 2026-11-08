package app.deskgrade.desktop;

import app.deskgrade.core.port.SecretStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Synthetic fixture only; production session storage always uses current-user DPAPI. */
final class MemorySessionSecrets implements SecretStore {
  private final Map<String, char[]> values = new HashMap<>();

  public synchronized void put(String id, char[] value) {
    delete(id);
    values.put(id, value.clone());
  }

  public synchronized Optional<char[]> read(String id) {
    char[] value = values.get(id);
    return value == null ? Optional.empty() : Optional.of(value.clone());
  }

  public synchronized void delete(String id) {
    char[] value = values.remove(id);
    if (value != null) Arrays.fill(value, '\0');
  }

  synchronized boolean empty() {
    return values.isEmpty();
  }
}
