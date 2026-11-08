package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.EvidenceStore;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Only explicit resolved-run allow-lists can prune; unresolved evidence is never auto-deleted. */
public final class FileEvidenceStore implements EvidenceStore {
  private Path root;
  private final long maxBytes;

  public FileEvidenceStore(Path root, long maxBytes) {
    this.root = root.toAbsolutePath().normalize();
    if (maxBytes < 1) throw new IllegalArgumentException("Invalid evidence capacity");
    this.maxBytes = maxBytes;
  }

  @Override
  public synchronized String save(UUID runId, UUID attemptId, SessionPlatform.Capture capture) {
    Path created = null;
    try {
      initialize();
      byte[] png = capture.png();
      String actualHash =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
      if (!actualHash.equals(capture.sha256())) throw new IllegalStateException();
      if (png.length > maxBytes || usedBytes() > maxBytes - png.length)
        throw new IllegalStateException();
      Path directory = runDirectory(runId);
      Files.createDirectories(directory);
      checkDirectory(directory);
      Path target = directory.resolve(attemptId + ".png").normalize();
      if (!target.startsWith(root) || Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalStateException();
      try (FileChannel channel =
          FileChannel.open(
              target,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        created = target;
        ByteBuffer bytes = ByteBuffer.wrap(png);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      return target.toString();
    } catch (Exception error) {
      // Only remove the brand-new partial file from this failed save, never an existing capture.
      if (created != null) {
        try {
          if (created.startsWith(root) && !Files.isSymbolicLink(created))
            Files.deleteIfExists(created);
        } catch (Exception ignored) {
        }
      }
      throw failure();
    }
  }

  /**
   * Caller obtains safe IDs from the journal after checking terminal state and no unresolved
   * intent.
   */
  public synchronized long prune(Set<UUID> explicitlySafeRunIds, Instant now, Duration retention) {
    if (explicitlySafeRunIds == null
        || now == null
        || retention == null
        || retention.isNegative()
        || retention.isZero()) throw failure();
    try {
      initialize();
      Instant cutoff = now.minus(retention);
      long removed = 0;
      for (UUID runId : Set.copyOf(explicitlySafeRunIds)) {
        Path directory = runDirectory(runId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
        checkDirectory(directory);
        try (var paths = Files.list(directory)) {
          for (Path candidate : paths.toList()) {
            if (!candidate.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.png")) continue;
            if (!candidate.normalize().startsWith(directory)) throw new IllegalStateException();
            var attributes =
                Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther())
              throw new IllegalStateException();
            if (!attributes.isRegularFile()
                || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)) continue;
            long size = attributes.size();
            Files.delete(candidate);
            removed += size;
          }
        }
        try (var remaining = Files.list(directory)) {
          if (remaining.findAny().isEmpty()) Files.delete(directory);
        }
      }
      return removed;
    } catch (Exception error) {
      throw failure();
    }
  }

  public synchronized long usedBytes() {
    try {
      initialize();
      long total = 0;
      try (var paths = Files.walk(root)) {
        for (Path candidate : (Iterable<Path>) paths::iterator) {
          var attributes =
              Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isSymbolicLink() || attributes.isOther())
            throw new IllegalStateException();
          if (attributes.isRegularFile()) total = Math.addExact(total, attributes.size());
        }
      }
      return total;
    } catch (Exception error) {
      throw failure();
    }
  }

  private void initialize() throws java.io.IOException {
    Files.createDirectories(root);
    for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
      var attributes =
          Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther())
        throw new java.io.IOException("Evidence root was redirected");
    }
    // Windows DOS short paths and ordinary case normalization are the same directory.
    root = root.toRealPath();
    checkDirectory(root);
  }

  private Path runDirectory(UUID runId) {
    Path directory = root.resolve(java.util.Objects.requireNonNull(runId).toString()).normalize();
    if (!directory.startsWith(root) || !directory.getParent().equals(root)) throw failure();
    return directory;
  }

  private void checkDirectory(Path directory) throws java.io.IOException {
    var attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
      throw new java.io.IOException("Unsafe evidence directory");
    if (!directory.toRealPath().equals(directory))
      throw new java.io.IOException("Evidence directory was redirected");
  }

  private static DeskGradeException failure() {
    return new DeskGradeException(ErrorCode.STORAGE_FAILURE);
  }
}
