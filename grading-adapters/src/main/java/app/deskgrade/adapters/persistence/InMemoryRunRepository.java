package app.deskgrade.adapters.persistence;

import app.deskgrade.core.domain.RunSnapshot;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.RunRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Development/test storage. Nothing is persisted; never use this for real submission intents. */
public final class InMemoryRunRepository implements RunRepository {
  private final ConcurrentMap<UUID, RunSnapshot> snapshots = new ConcurrentHashMap<>();

  @Override
  public StorageKind storageKind() {
    return StorageKind.IN_MEMORY;
  }

  @Override
  public void save(RunSnapshot snapshot) {
    if (snapshot == null) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    snapshots.put(snapshot.runId(), snapshot);
  }

  @Override
  public Optional<RunSnapshot> findById(UUID runId) {
    if (runId == null) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    return Optional.ofNullable(snapshots.get(runId));
  }
}
