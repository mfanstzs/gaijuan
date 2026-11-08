package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileSecretStoreTest {
  @TempDir Path temp;

  @Test
  void invalidReferencesFailWithoutReadingOrDisclosingAFile() {
    var store = new FileSecretStore(temp, 1);
    for (String reference :
        new String[] {
          "../synthetic-secret", "/synthetic-secret", "key/child", "key\\child", "", "a\n"
        }) safeFailure(() -> store.read(reference));
    safeFailure(() -> store.read(null));
  }

  @Test
  void serverStoreDoesNotProvideCredentialMutationOperations() throws Exception {
    var store = new FileSecretStore(temp, 1);
    safeFailure(() -> store.put("key", "synthetic-secret".toCharArray()));
    safeFailure(() -> store.delete("key"));
    try (var files = Files.list(temp)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsGatewayKeepsCurrentUserDpapiStorage() {
    assertInstanceOf(DpapiSecretStore.class, GatewayMain.serverSecrets(temp));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void linuxGatewayReadsPrivateFilesAndReturnsCallerOwnedArrays() throws Exception {
    Path directory = directory();
    credential(directory, "qwen-live", "synthetic-secret");
    var store = GatewayMain.serverSecrets(directory);
    assertInstanceOf(FileSecretStore.class, store);
    char[] first = store.read("qwen-live").orElseThrow();
    try {
      assertArrayEquals("synthetic-secret".toCharArray(), first);
      first[0] = 'x';
      char[] second = store.read("qwen-live").orElseThrow();
      try {
        assertArrayEquals("synthetic-secret".toCharArray(), second);
      } finally {
        Arrays.fill(second, '\0');
      }
    } finally {
      Arrays.fill(first, '\0');
    }
    assertTrue(store.read("not-configured").isEmpty());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void refusesReadableByGroupOrOthersAndRechecksPermissionChanges() throws Exception {
    Path directory = directory();
    Path file = credential(directory, "qwen-live", "synthetic-secret");
    var store = new FileSecretStore(directory);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    safeFailure(() -> store.read("qwen-live"));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    char[] value = store.read("qwen-live").orElseThrow();
    Arrays.fill(value, '\0');
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
    safeFailure(() -> store.read("qwen-live"));
    // A missing credential must not hide an insecure directory configuration.
    safeFailure(() -> store.read("missing"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void refusesAnotherUidAndSpecialModeBits() throws Exception {
    Path directory = directory();
    Path file = credential(directory, "qwen-live", "synthetic-secret");
    long owner = ((Number) Files.getAttribute(directory, "unix:uid")).longValue();
    safeFailure(() -> new FileSecretStore(directory, owner + 1).read("qwen-live"));
    Files.setAttribute(file, "unix:mode", 04600);
    safeFailure(() -> new FileSecretStore(directory).read("qwen-live"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void refusesCredentialAndAncestorSymbolicLinks() throws Exception {
    Path directory = directory();
    Path file = credential(directory, "real", "synthetic-secret");
    Files.createSymbolicLink(directory.resolve("qwen-live.secret"), file);
    safeFailure(() -> new FileSecretStore(directory).read("qwen-live"));
    Path linked = temp.resolve("linked");
    Files.createSymbolicLink(linked, directory);
    safeFailure(() -> new FileSecretStore(linked).read("real"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void refusesEmptyOversizedMalformedAndHeaderBreakingSecrets() throws Exception {
    Path directory = directory();
    var store = new FileSecretStore(directory);
    for (String value :
        new String[] {
          "", "x".repeat(32769), "x".repeat(8193), "key\n", "key\r", "key\0", "\ufeffkey"
        }) {
      credential(directory, "qwen-live", value);
      safeFailure(() -> store.read("qwen-live"));
    }
    Path file = credential(directory, "qwen-live", "synthetic-secret");
    Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
    safeFailure(() -> store.read("qwen-live"));
  }

  private Path directory() throws Exception {
    return Files.createDirectory(
        temp.resolve("service-credentials"),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private Path credential(Path directory, String reference, String value) throws Exception {
    Path file = directory.resolve(reference + ".secret");
    Files.writeString(file, value, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    return file;
  }

  private static void safeFailure(org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(DeskGradeException.class, operation);
    assertEquals(ErrorCode.SECRET_STORE_FAILURE, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic-secret"));
  }
}
