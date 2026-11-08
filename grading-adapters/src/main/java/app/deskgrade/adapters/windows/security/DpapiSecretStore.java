package app.deskgrade.adapters.windows.security;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.SecretStore;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Current-user DPAPI only. File names identify credentials, never contain the secret. */
public final class DpapiSecretStore implements SecretStore {
  interface Protection {
    byte[] protect(byte[] plaintext);

    byte[] unprotect(byte[] ciphertext);
  }

  private final Path directory;
  private final Protection protection;

  /** Construction is safe on non-Windows; only credential operations load the native library. */
  public DpapiSecretStore(Path directory) {
    this(
        directory,
        new Protection() {
          @Override
          public byte[] protect(byte[] plaintext) {
            requireWindows();
            // CRYPTPROTECT_UI_FORBIDDEN, without CRYPTPROTECT_LOCAL_MACHINE.
            return Crypt32Util.cryptProtectData(plaintext, null, 1, "DeskGrade credential", null);
          }

          @Override
          public byte[] unprotect(byte[] ciphertext) {
            requireWindows();
            return Crypt32Util.cryptUnprotectData(ciphertext, null, 1, null);
          }
        });
  }

  DpapiSecretStore(Path directory, Protection protection) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.protection = Objects.requireNonNull(protection);
  }

  @Override
  public synchronized void put(String credentialId, char[] secret) {
    byte[] plaintext = null;
    byte[] ciphertext = null;
    Path temporary = null;
    try {
      Path target = path(credentialId);
      if (secret == null || secret.length == 0 || secret.length > 8192)
        throw new IllegalArgumentException();
      ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret));
      plaintext = new byte[encoded.remaining()];
      encoded.get(plaintext);
      if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
      ciphertext = protection.protect(plaintext);
      if (ciphertext == null || ciphertext.length == 0 || ciphertext.length > 65536)
        throw new IllegalArgumentException();
      Files.createDirectories(directory);
      if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(target)) throw new IOException();
      temporary = Files.createTempFile(directory, ".credential-", ".tmp");
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer output = ByteBuffer.wrap(ciphertext);
        while (output.hasRemaining()) channel.write(output);
        channel.force(true);
      }
      // Atomic replacement is mandatory. A filesystem without support fails closed.
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
    } catch (Exception | LinkageError failure) {
      throw safeFailure();
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
      if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          /* Only encrypted bytes. */
        }
      }
    }
  }

  @Override
  public synchronized Optional<char[]> read(String credentialId) {
    byte[] ciphertext = null;
    byte[] plaintext = null;
    try {
      Path target = path(credentialId);
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(directory)
          || Files.size(target) > 65536) throw new IOException();
      ciphertext = Files.readAllBytes(target);
      plaintext = protection.unprotect(ciphertext);
      if (plaintext == null || plaintext.length == 0 || plaintext.length > 32768)
        throw new IOException();
      CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext));
      char[] result = new char[decoded.remaining()];
      decoded.get(result);
      if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
      return Optional.of(result);
    } catch (Exception | LinkageError failure) {
      throw safeFailure();
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
      if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
    }
  }

  @Override
  public synchronized void delete(String credentialId) {
    try {
      if (Files.isSymbolicLink(directory)) throw new IOException();
      Files.deleteIfExists(path(credentialId));
    } catch (Exception failure) {
      throw safeFailure();
    }
  }

  private Path path(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw new IllegalArgumentException();
    return directory.resolve(id + ".dpapi");
  }

  private static void requireWindows() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
      throw safeFailure();
  }

  private static DeskGradeException safeFailure() {
    return new DeskGradeException(ErrorCode.SECRET_STORE_FAILURE);
  }
}
