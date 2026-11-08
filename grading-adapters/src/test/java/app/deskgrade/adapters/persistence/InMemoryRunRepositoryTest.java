package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.RunSnapshot;
import app.deskgrade.core.port.RunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRunRepositoryTest {
  @Test
  void storesAndReplacesSnapshotsByRunIdWithoutClaimingPersistence() {
    InMemoryRunRepository repository = new InMemoryRunRepository();
    UUID runId = UUID.randomUUID();
    RunSnapshot first = new RunSnapshot(runId, Instant.parse("2026-09-10T01:00:00Z"), List.of());
    RunSnapshot next = new RunSnapshot(runId, Instant.parse("2026-09-10T01:01:00Z"), List.of());

    assertEquals(RunRepository.StorageKind.IN_MEMORY, repository.storageKind());
    assertTrue(repository.findById(runId).isEmpty());
    repository.save(first);
    assertSame(first, repository.findById(runId).orElseThrow());
    repository.save(next);
    assertSame(next, repository.findById(runId).orElseThrow());
    assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    assertTrue(new InMemoryRunRepository().findById(runId).isEmpty());
  }

  @Test
  void storedSnapshotsDoNotExposeMutableCollections() {
    InMemoryRunRepository repository = new InMemoryRunRepository();
    var responses = new ArrayList<GradingResponse>();
    RunSnapshot snapshot = new RunSnapshot(UUID.randomUUID(), Instant.EPOCH, responses);
    repository.save(snapshot);
    responses.add(null);

    RunSnapshot loaded = repository.findById(snapshot.runId()).orElseThrow();
    assertTrue(loaded.responses().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> loaded.responses().add(null));
  }
}
