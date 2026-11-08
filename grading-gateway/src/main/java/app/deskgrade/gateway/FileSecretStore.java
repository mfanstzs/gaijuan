package app.deskgrade.gateway;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.SecretStore;
import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Read-only Linux service credentials. Only the service UID may access the directory and files. */
final class FileSecretStore implements SecretStore {
  private static final int MAX_BYTES = 32768;
  private static final int MAX_CHARACTERS = 8192;
  private final Path directory;
  private final long currentUid;

  FileSecretStore(Path directory) {
    this(directory, currentUid());
  }

  FileSecretStore(Path directory, long currentUid) {
    if (directory == null || !directory.isAbsolute() || currentUid < 0) throw failure();
    this.directory = directory.normalize();
    this.currentUid = currentUid;
  }

  @Override
  public void put(String credentialId, char[] secret) {
    throw failure();
  }

  @Override
  public Optional<char[]> read(String credentialId) {
    byte[] encoded = null;
    char[] decoded = null;
    try {
      Path file = credentialPath(credentialId);
      Map<String, Object> directoryBefore = privateDirectory();
      Map<String, Object> before;
      try {
        before = attributes(file);
      } catch (NoSuchFileException absent) {
        return Optional.empty();
      }
      requireFile(before);
      encoded = new byte[MAX_BYTES + 1];
      int length;
      try (var channel =
          FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
        length = buffer.position();
      }
      Map<String, Object> after = attributes(file);
      requireFile(after);
      Map<String, Object> directoryAfter = privateDirectory();
      if (length < 1
          || length > MAX_BYTES
          || !before.get("fileKey").equals(after.get("fileKey"))
          || !directoryBefore.get("fileKey").equals(directoryAfter.get("fileKey"))
          || ((Number) after.get("size")).longValue() != length) throw failure();
      CharBuffer text =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded, 0, length));
      try {
        if (text.remaining() < 1 || text.remaining() > MAX_CHARACTERS) throw failure();
        decoded = new char[text.remaining()];
        text.get(decoded);
      } finally {
        if (text.hasArray()) Arrays.fill(text.array(), '\0');
      }
      if (decoded[0] == '\ufeff') throw failure();
      for (char value : decoded)
        if (value == '\0' || value == '\r' || value == '\n') throw failure();
      char[] result = decoded;
      decoded = null;
      return Optional.of(result);
    } catch (Exception | LinkageError unavailable) {
      throw failure();
    } finally {
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
      if (decoded != null) Arrays.fill(decoded, '\0');
    }
  }

  @Override
  public void delete(String credentialId) {
    throw failure();
  }

  private Path credentialPath(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw failure();
    return directory.resolve(id + ".secret");
  }

  private Map<String, Object> privateDirectory() throws IOException {
    for (Path component = directory; component != null; component = component.getParent())
      if (Files.isSymbolicLink(component)) throw failure();
    Map<String, Object> attributes = attributes(directory);
    if (!Boolean.TRUE.equals(attributes.get("isDirectory"))) throw failure();
    requirePrivateMode(attributes, 0700);
    return attributes;
  }

  private void requireFile(Map<String, Object> attributes) {
    if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))
        || ((Number) attributes.get("size")).longValue() < 1
        || ((Number) attributes.get("size")).longValue() > MAX_BYTES) throw failure();
    requirePrivateMode(attributes, 0600);
  }

  private void requirePrivateMode(Map<String, Object> attributes, int expectedMode) {
    if ((((Number) attributes.get("mode")).intValue() & 07777) != expectedMode
        || ((Number) attributes.get("uid")).longValue() != currentUid
        || attributes.get("fileKey") == null) throw failure();
  }

  private static Map<String, Object> attributes(Path path) throws IOException {
    return Files.readAttributes(
        path, "unix:mode,uid,size,isDirectory,isRegularFile,fileKey", LinkOption.NOFOLLOW_LINKS);
  }

  private static long currentUid() {
    try {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).equals("linux"))
        throw failure();
      return new UnixSystem().getUid();
    } catch (Exception | LinkageError unavailable) {
      throw failure();
    }
  }

  private static DeskGradeException failure() {
    return new DeskGradeException(ErrorCode.SECRET_STORE_FAILURE);
  }
}
