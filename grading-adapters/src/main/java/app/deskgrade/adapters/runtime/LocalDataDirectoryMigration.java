package app.deskgrade.adapters.runtime;

import app.deskgrade.adapters.model.ProfileJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A per-user location outside MSIX AppData virtualization. An upgrade copies only local-version
 * data, retains its source, locks both locations, and publishes an entire staging directory with a
 * single atomic rename. Existing destination data is never merged or overwritten automatically.
 */
public final class LocalDataDirectoryMigration {
  public enum Result {
    NO_LEGACY_DATA,
    ALREADY_INITIALIZED,
    COPIED
  }

  private static final List<String> SETTINGS =
      List.of(
          "settings/local-profiles.json",
          "settings/local-model-selection.json",
          "settings/rubric.json",
          "settings/rubric-subject.txt");
  private static final String DATABASE = "database/local-deskgrade.mv.db";

  private LocalDataDirectoryMigration() {}

  public static Path defaultDirectory(Path userHome) {
    if (userHome == null || !userHome.isAbsolute())
      throw new IllegalArgumentException("An absolute user home is required");
    return userHome.toAbsolutePath().normalize().resolve(".deskgrade/data");
  }

  public static Result copyVisibleLegacyOnce(Path requestedTarget, Path requestedLegacy)
      throws IOException {
    Path target = requestedTarget.toAbsolutePath().normalize();
    Path source = requestedLegacy.toAbsolutePath().normalize();
    if (target.equals(source) || target.startsWith(source) || source.startsWith(target))
      throw new IOException("Local storage locations must be separate");
    Path parent = target.getParent();
    if (parent == null || target.getFileName() == null)
      throw new IOException("Invalid local data destination");
    rejectLinkedAncestors(parent);
    Files.createDirectories(parent);
    rejectLinkedAncestors(parent);
    Path migrationLock = parent.resolve(".data-location-migration.lock");
    try (FileChannel channel =
            FileChannel.open(
                migrationLock,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lock = acquire(channel)) {
      if (initialized(target)) return Result.ALREADY_INITIALIZED;
      if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return Result.NO_LEGACY_DATA;
      rejectLinkedAncestors(source);
      if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid legacy local data directory");
      if (!hasFile(source.resolve(DATABASE))
          && !hasFile(source.resolve("settings/local-profiles.json"))) return Result.NO_LEGACY_DATA;
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Uninitialized destination already exists; preserve it for review");
      // This is the same lock used by all legacy application versions. Never copy a live H2 file.
      try (FileChannel legacyChannel =
              FileChannel.open(
                  source.resolve(".deskgrade-instance.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS);
          FileLock legacyLock = acquire(legacyChannel)) {
        var relatives = filesToCopy(source);
        Path staging = parent.resolve(".data-location-staging-" + UUID.randomUUID());
        Files.createDirectory(staging);
        var manifest = new ObjectMapper().createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("operation", "COPY_VISIBLE_LOCAL_DATA");
        manifest.put("sourceRetained", true);
        manifest.put("createdAt", Instant.now().toString());
        var files = manifest.putArray("files");
        // On failure keep staging and the untouched source for inspection. Never publish partial
        // history, silently create an empty replacement, or delete data to make an upgrade succeed.
        for (Path relative : relatives) {
          Path from = source.resolve(relative), to = staging.resolve(relative);
          requireRegularFile(from);
          Files.createDirectories(to.getParent());
          Files.copy(from, to);
          try (FileChannel copied =
              FileChannel.open(to, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            copied.force(true);
          }
          String expected = sha256(from);
          if (Files.size(from) != Files.size(to) || !expected.equals(sha256(to)))
            throw new IOException("Copied local file did not verify");
          var item = files.addObject();
          item.put("path", relative.toString().replace('\\', '/'));
          item.put("bytes", Files.size(to));
          item.put("sha256", expected);
        }
        Path receipt = staging.resolve("local-storage-migration.json");
        Files.writeString(receipt, manifest.toPrettyString(), StandardOpenOption.CREATE_NEW);
        try (FileChannel written = FileChannel.open(receipt, StandardOpenOption.WRITE)) {
          written.force(true);
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        return Result.COPIED;
      }
    }
  }

  private static List<Path> filesToCopy(Path source) throws IOException {
    Set<Path> selected = new LinkedHashSet<>();
    if (hasFile(source.resolve(DATABASE))) selected.add(Path.of(DATABASE));
    for (String name : SETTINGS) if (hasFile(source.resolve(name))) selected.add(Path.of(name));
    Path profiles = source.resolve("settings/local-profiles.json");
    if (hasFile(profiles)) {
      if (Files.size(profiles) > ProfileJsonCodec.MAX_DOCUMENT_BYTES)
        throw new IOException("Legacy local profile document is too large");
      try {
        for (var profile : new ProfileJsonCodec().readLocal(Files.readString(profiles))) {
          Path credential = Path.of("credentials", profile.credentialRef() + ".dpapi");
          if (hasFile(source.resolve(credential))) selected.add(credential);
        }
      } catch (RuntimeException invalid) {
        throw new IOException("Legacy local model configuration is invalid");
      }
    }
    Path records = source.resolve("records");
    if (Files.exists(records, LinkOption.NOFOLLOW_LINKS)) {
      rejectLinkedAncestors(records);
      try (var entries = Files.newDirectoryStream(records)) {
        for (Path entry : entries) {
          if (entry
              .getFileName()
              .toString()
              .matches("grading-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}\\.jsonl")) {
            requireRegularFile(entry);
            selected.add(Path.of("records", entry.getFileName().toString()));
          }
        }
      }
    }
    return new ArrayList<>(selected);
  }

  private static boolean initialized(Path target) throws IOException {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
    rejectLinkedAncestors(target);
    return hasFile(target.resolve(DATABASE))
        || hasFile(target.resolve("settings/local-profiles.json"))
        || hasFile(target.resolve("local-storage-migration.json"));
  }

  private static boolean hasFile(Path file) throws IOException {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
    requireRegularFile(file);
    return true;
  }

  private static void requireRegularFile(Path file) throws IOException {
    rejectLinkedAncestors(file.getParent());
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Local storage source is not a regular file");
  }

  private static void rejectLinkedAncestors(Path path) throws IOException {
    for (Path current = path; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)
          || Files.exists(current, LinkOption.NOFOLLOW_LINKS)
              && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Local storage directory is invalid");
    }
  }

  private static FileLock acquire(FileChannel channel) throws IOException {
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) throw new IOException("Another DeskGrade instance is using local data");
      return lock;
    } catch (OverlappingFileLockException held) {
      throw new IOException("Another DeskGrade instance is using local data");
    }
  }

  private static String sha256(Path file) throws IOException {
    requireRegularFile(file);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IOException("Local storage verification is unavailable");
    }
  }
}
