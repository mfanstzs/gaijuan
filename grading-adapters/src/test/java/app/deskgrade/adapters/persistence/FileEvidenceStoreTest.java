package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.error.DeskGradeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileEvidenceStoreTest {
  @TempDir Path directory;

  private static SessionPlatform.Capture capture(byte... bytes) throws Exception {
    return new SessionPlatform.Capture(
        bytes,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        Instant.now());
  }

  @Test
  void savedExactBytesAreImmutableAndCapacityDoesNotDeleteEvidence() throws Exception {
    var store = new FileEvidenceStore(directory.resolve("evidence"), 5);
    UUID run = UUID.randomUUID(), attempt = UUID.randomUUID();
    var capture = capture((byte) 1, (byte) 2, (byte) 3);
    Path path = Path.of(store.save(run, attempt, capture));
    assertArrayEquals(capture.png(), Files.readAllBytes(path));
    assertEquals(3, store.usedBytes());
    assertThrows(DeskGradeException.class, () -> store.save(run, attempt, capture));
    assertThrows(DeskGradeException.class, () -> store.save(run, UUID.randomUUID(), capture));
    assertArrayEquals(capture.png(), Files.readAllBytes(path));
    assertEquals(3, store.usedBytes());
  }

  @Test
  void hashMismatchCannotCreateEvidence() {
    var store = new FileEvidenceStore(directory.resolve("evidence"), 100);
    assertThrows(
        DeskGradeException.class,
        () ->
            store.save(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new SessionPlatform.Capture(new byte[] {1, 2}, "0".repeat(64), Instant.now())));
    assertEquals(0, store.usedBytes());
  }

  @Test
  void pruneOnlyDeletesOldFilesInExplicitlyAllowedResolvedRuns() throws Exception {
    var store = new FileEvidenceStore(directory.resolve("evidence"), 100);
    UUID safe = UUID.randomUUID(), unresolved = UUID.randomUUID();
    var capture = capture((byte) 1, (byte) 2);
    Instant now = Instant.now();
    Path oldSafe = Path.of(store.save(safe, UUID.randomUUID(), capture));
    Path freshSafe = Path.of(store.save(safe, UUID.randomUUID(), capture));
    Path oldUnresolved = Path.of(store.save(unresolved, UUID.randomUUID(), capture));
    Files.setLastModifiedTime(oldSafe, FileTime.from(now.minus(Duration.ofDays(60))));
    Files.setLastModifiedTime(oldUnresolved, FileTime.from(now.minus(Duration.ofDays(60))));
    assertEquals(0, store.prune(Set.of(), now, Duration.ofDays(30)));
    assertTrue(Files.exists(oldUnresolved));
    assertEquals(2, store.prune(Set.of(safe), now, Duration.ofDays(30)));
    assertFalse(Files.exists(oldSafe));
    assertTrue(Files.exists(freshSafe));
    assertTrue(Files.exists(oldUnresolved));
    assertEquals(4, store.usedBytes());
  }

  @Test
  void unrecognizedFilesAndDirectoriesAreRetained() throws Exception {
    Path root = directory.resolve("evidence");
    var store = new FileEvidenceStore(root, 100);
    UUID safe = UUID.randomUUID();
    Path saved = Path.of(store.save(safe, UUID.randomUUID(), capture((byte) 1)));
    Path note = saved.getParent().resolve("user-note.txt");
    Files.writeString(note, "keep");
    Files.setLastModifiedTime(note, FileTime.from(Instant.EPOCH));
    assertEquals(0, store.prune(Set.of(safe), Instant.now(), Duration.ofDays(30)));
    assertEquals("keep", Files.readString(note));
  }

  @Test
  void invalidDirectoryAndRetentionFailWithoutTouchingOtherFiles() throws Exception {
    Path file = directory.resolve("file");
    Files.writeString(file, "keep");
    var store = new FileEvidenceStore(file, 100);
    assertThrows(
        DeskGradeException.class,
        () -> store.save(UUID.randomUUID(), UUID.randomUUID(), capture((byte) 1)));
    assertEquals("keep", Files.readString(file));
    assertThrows(
        DeskGradeException.class, () -> store.prune(Set.of(), Instant.now(), Duration.ZERO));
  }
}
