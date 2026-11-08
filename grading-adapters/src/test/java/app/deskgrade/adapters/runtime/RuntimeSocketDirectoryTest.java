package app.deskgrade.adapters.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeSocketDirectoryTest {
  @TempDir Path directory;

  @Test
  void explicitDirectoryIsCreatedResolvedAndNotReplacedByDefault() throws Exception {
    Path explicit = directory.resolve("s");
    Path selected =
        RuntimeSocketDirectory.select(
            explicit.toString(), List.of(directory.resolve("other")), ignored -> Optional.empty());
    assertEquals(explicit.toRealPath(), selected);
    assertFalse(Files.exists(directory.resolve("other")));
    try (var files = Files.list(selected)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void unusableDefaultFallsBackButInvalidExplicitValueFailsClosed() throws Exception {
    Path file = Files.writeString(directory.resolve("f"), "unchanged");
    Path fallback = directory.resolve("s");
    Path selected =
        RuntimeSocketDirectory.select(null, List.of(file, fallback), ignored -> Optional.empty());
    assertEquals(fallback.toRealPath(), selected);
    assertThrows(
        java.io.IOException.class,
        () ->
            RuntimeSocketDirectory.select(
                file.toString(), List.of(fallback), ignored -> Optional.empty()));
    assertEquals("unchanged", Files.readString(file));
  }

  @Test
  void excessivePathCannotBeSilentlyAcceptedForNio() {
    Path longPath = directory.resolve("x".repeat(90));
    assertThrows(
        java.io.IOException.class,
        () ->
            RuntimeSocketDirectory.select(
                longPath.toString(), List.of(directory), ignored -> Optional.empty()));
  }
}
