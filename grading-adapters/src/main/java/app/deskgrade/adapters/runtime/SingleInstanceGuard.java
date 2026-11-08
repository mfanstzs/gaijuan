package app.deskgrade.adapters.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * OS-owned process exclusion for one canonical data directory. The persistent lock file is never
 * deleted: deleting it could let another process lock a different file while this one is still
 * held.
 */
public final class SingleInstanceGuard implements AutoCloseable {
  private static final Map<Path, SingleInstanceGuard> HELD = new HashMap<>();
  private final Path dataDirectory;
  private final FileChannel channel;

  @SuppressWarnings("unused")
  private final FileLock lock;

  private SingleInstanceGuard(Path dataDirectory, FileChannel channel, FileLock lock) {
    this.dataDirectory = dataDirectory;
    this.channel = channel;
    this.lock = lock;
  }

  /** Empty means another instance owns this directory; storage errors remain errors. */
  public static synchronized Optional<SingleInstanceGuard> tryAcquire(Path directory)
      throws IOException {
    Path real =
        Files.createDirectories(Objects.requireNonNull(directory).toAbsolutePath().normalize())
            .toRealPath();
    if (HELD.containsKey(real)) return Optional.empty();
    FileChannel opened =
        FileChannel.open(
            real.resolve(".deskgrade-instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    try {
      FileLock acquired;
      try {
        acquired = opened.tryLock();
      } catch (OverlappingFileLockException alreadyHeld) {
        opened.close();
        return Optional.empty();
      }
      if (acquired == null) {
        opened.close();
        return Optional.empty();
      }
      var guard = new SingleInstanceGuard(real, opened, acquired);
      HELD.put(real, guard);
      return Optional.of(guard);
    } catch (IOException | RuntimeException failure) {
      try {
        opened.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public Path dataDirectory() {
    return dataDirectory;
  }

  @Override
  public void close() throws IOException {
    synchronized (SingleInstanceGuard.class) {
      if (HELD.get(dataDirectory) != this) return;
      try {
        channel.close();
      } finally {
        if (!channel.isOpen()) HELD.remove(dataDirectory, this);
      }
    }
  }
}
