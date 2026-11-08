package app.deskgrade.adapters.runtime;

import app.deskgrade.adapters.windows.WindowsRuntimePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Establishes a writable short socket path before HttpClient/NIO chooses its temporary directory.
 */
public final class RuntimeSocketDirectory {
  private static final int MAX_DIRECTORY_BYTES = 72;

  private RuntimeSocketDirectory() {}

  public static Path initialize() throws IOException {
    String explicit = System.getProperty("jdk.net.unixdomain.tmpdir");
    List<Path> candidates = new ArrayList<>();
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    String userDirectory = System.getProperty("user.home");
    if (windows && userDirectory != null && !userDirectory.isBlank())
      // Some Windows environments permit AppData file writes but reject AF_UNIX connects there.
      candidates.add(Path.of(userDirectory, ".deskgrade", "socket-temp"));
    String local = System.getenv("LOCALAPPDATA");
    if (local != null && !local.isBlank())
      candidates.add(Path.of(local, "DeskGrade", "socket-temp"));
    String temporary = System.getProperty("java.io.tmpdir");
    if (temporary != null && !temporary.isBlank())
      candidates.add(Path.of(temporary, "deskgrade-s"));
    Path selected =
        select(
            explicit,
            candidates,
            windows ? WindowsRuntimePaths::shortExistingPath : path -> Optional.empty());
    System.setProperty("jdk.net.unixdomain.tmpdir", selected.toString());
    return selected;
  }

  static Path select(String explicit, List<Path> candidates, Function<Path, Optional<Path>> shorten)
      throws IOException {
    if (explicit != null && !explicit.isBlank()) return prepare(Path.of(explicit), shorten);
    for (Path candidate : candidates) {
      try {
        return prepare(candidate, shorten);
      } catch (IOException | RuntimeException unavailable) {
        /* Try the next user-owned location. */
      }
    }
    throw new IOException("A writable short runtime socket directory is required");
  }

  private static Path prepare(Path requested, Function<Path, Optional<Path>> shorten)
      throws IOException {
    Path real = Files.createDirectories(requested.toAbsolutePath().normalize()).toRealPath();
    Path result = real;
    if (real.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DIRECTORY_BYTES) {
      result =
          shorten.apply(real).orElseThrow(() -> new IOException("Runtime socket path is too long"));
      if (!result.isAbsolute()
          || !Files.isSameFile(real, result)
          || result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DIRECTORY_BYTES)
        throw new IOException("Runtime socket path is too long");
    }
    Path probe = Files.createTempFile(result, ".dg-", ".tmp");
    Files.delete(probe);
    return result;
  }
}
