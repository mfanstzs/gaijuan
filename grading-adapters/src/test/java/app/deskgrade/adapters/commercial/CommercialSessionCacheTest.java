package app.deskgrade.adapters.commercial;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.port.SecretStore;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CommercialSessionCacheTest {
  @Test
  void unreadableProtectedCacheIsDeletedAndNeverExposesItsFailure() {
    AtomicBoolean deleted = new AtomicBoolean();
    var cache =
        new CommercialSessionCache(
            new SecretStore() {
              @Override
              public void put(String id, char[] secret) {
                fail("Read must not write");
              }

              @Override
              public Optional<char[]> read(String id) {
                throw new IllegalStateException("Synthetic protected-data failure");
              }

              @Override
              public void delete(String id) {
                deleted.set(true);
              }
            });

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> cache.load(URI.create("http://127.0.0.1:8787"), Instant.now()));

    assertTrue(deleted.get(), "An unreadable cache must not be retried on every launch");
    assertEquals("Saved account session is unavailable", failure.getMessage());
    assertNull(
        failure.getCause(), "Protected-store failures must not escape through UI diagnostics");
  }
}
