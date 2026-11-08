package app.deskgrade.adapters.windows.security;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DpapiSecretStoreTest {
  @TempDir Path directory;

  @Test
  void storesOnlyProtectedBytesClearsWorkingBuffersAndReplacesAtomically() throws Exception {
    FakeProtection protection = new FakeProtection();
    DpapiSecretStore store = new DpapiSecretStore(directory, protection);
    char[] first = "synthetic-local-test".toCharArray();
    store.put("profile-1", first);
    assertArrayEquals("synthetic-local-test".toCharArray(), first);
    assertArrayEquals(new byte[protection.lastPlaintext.length], protection.lastPlaintext);
    assertFalse(
        new String(Files.readAllBytes(directory.resolve("profile-1.dpapi")), StandardCharsets.UTF_8)
            .contains("synthetic-local-test"));
    assertArrayEquals(first, store.read("profile-1").orElseThrow());
    store.put("profile-1", "replacement".toCharArray());
    assertArrayEquals("replacement".toCharArray(), store.read("profile-1").orElseThrow());
    try (var paths = Files.list(directory)) {
      assertEquals(1, paths.count());
    }
    store.delete("profile-1");
    assertTrue(store.read("profile-1").isEmpty());
  }

  @Test
  void failureHasNoExternalCauseAndPreservesExistingCredential() {
    FakeProtection protection = new FakeProtection();
    DpapiSecretStore store = new DpapiSecretStore(directory, protection);
    store.put("profile", "original".toCharArray());
    protection.fail = true;
    DeskGradeException error =
        assertThrows(
            DeskGradeException.class, () -> store.put("profile", "private-value".toCharArray()));
    assertEquals(ErrorCode.SECRET_STORE_FAILURE, error.code());
    assertNull(error.getCause());
    assertFalse(error.toString().contains("private-value"));
    protection.fail = false;
    assertArrayEquals("original".toCharArray(), store.read("profile").orElseThrow());
    assertThrows(DeskGradeException.class, () -> store.put("../escape", "x".toCharArray()));
  }

  @Test
  void constructionAndAbsentReadDoNotRequireDlls() {
    assertTrue(new DpapiSecretStore(directory).read("absent").isEmpty());
    assertNotNull(new app.deskgrade.adapters.windows.WindowsWindowService());
    assertNotNull(new app.deskgrade.adapters.windows.CoordinateMapper());
  }

  private static final class FakeProtection implements DpapiSecretStore.Protection {
    byte[] lastPlaintext;
    boolean fail;

    @Override
    public byte[] protect(byte[] plaintext) {
      lastPlaintext = plaintext;
      if (fail) throw new IllegalStateException("private-value");
      return transform(plaintext);
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) {
      return transform(ciphertext);
    }

    private byte[] transform(byte[] bytes) {
      byte[] output = Arrays.copyOf(bytes, bytes.length);
      for (int i = 0; i < output.length; i++) output[i] ^= 0x5A;
      return output;
    }
  }
}
