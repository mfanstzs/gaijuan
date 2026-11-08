package app.deskgrade.core.port;

import app.deskgrade.core.domain.RunSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Initial record port. This M0 contract is insufficient for durable submission intent transactions.
 */
public interface RunRepository {
  StorageKind storageKind();

  void save(RunSnapshot snapshot);

  Optional<RunSnapshot> findById(UUID runId);

  enum StorageKind {
    IN_MEMORY,
    PERSISTENT
  }
}
