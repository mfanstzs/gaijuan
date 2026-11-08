package app.deskgrade.adapters.calibration;

import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;

/** Atomic position-only snapshots. Loading deliberately cannot construct a TargetBinding. */
public final class ManualCalibrationStore {
  private final Path file;
  private final ManualCalibrationJsonCodec codec = new ManualCalibrationJsonCodec();

  public ManualCalibrationStore(Path file) {
    if (file == null
        || file.getFileName() == null
        || !file.getFileName().toString().endsWith(".json"))
      throw new IllegalArgumentException("标定文件必须是 JSON 文件。");
    this.file = file.toAbsolutePath().normalize();
    if (this.file.getParent() == null) throw new IllegalArgumentException("标定文件目录不合法。");
  }

  public synchronized Optional<ManualPlatformCalibration> load() {
    try {
      rejectLinks(file);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw storageFailure();
      try (var stream =
          Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = stream.readNBytes(ManualCalibrationJsonCodec.MAX_BYTES + 1);
        if (bytes.length > ManualCalibrationJsonCodec.MAX_BYTES) throw storageFailure();
        String json =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return Optional.of(codec.read(json));
      }
    } catch (IOException failure) {
      throw storageFailure();
    }
  }

  public synchronized void save(ManualPlatformCalibration value) {
    if (value == null) throw new IllegalArgumentException("标定配置不能为空。");
    byte[] bytes = codec.write(value).getBytes(StandardCharsets.UTF_8);
    Path temporary = null;
    try {
      rejectLinks(file);
      Files.createDirectories(file.getParent());
      rejectLinks(file);
      Path locking = file.resolveSibling(file.getFileName() + ".lock");
      rejectLinks(locking);
      try (var lockChannel =
              FileChannel.open(
                  locking,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS);
          var processLock = lockChannel.tryLock()) {
        if (processLock == null) throw storageFailure();
        var previous = load();
        if (previous.isPresent()) {
          var current = previous.orElseThrow();
          if (current.equals(value)) return;
          if (!current.id().equals(value.id())
              || current.revision() == Long.MAX_VALUE
              || value.revision() != current.revision() + 1)
            throw new IllegalArgumentException("标定版本已变化；请重新加载后增加一个版本再保存。");
        } else if (value.revision() != 1) {
          throw new IllegalArgumentException("首次标定必须从版本 1 开始。");
        }
        temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (var channel =
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) channel.write(buffer);
          channel.force(true);
        }
        rejectLinks(file);
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        temporary = null;
      }
    } catch (IOException | java.nio.channels.OverlappingFileLockException failure) {
      throw storageFailure();
    } finally {
      if (temporary != null
          && temporary.getParent().equals(file.getParent())
          && temporary.getFileName().toString().startsWith(file.getFileName() + ".")) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static void rejectLinks(Path path) throws IOException {
    Path current = path;
    while (current != null) {
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        BasicFileAttributes attributes =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Windows junctions/reparse points are commonly reported as "other", not symbolic links.
        if (attributes.isSymbolicLink() || attributes.isOther()) throw storageFailure();
      }
      current = current.getParent();
    }
  }

  private static IllegalStateException storageFailure() {
    return new IllegalStateException("无法安全读写标定文件；原配置未获得新的执行权限。");
  }
}
