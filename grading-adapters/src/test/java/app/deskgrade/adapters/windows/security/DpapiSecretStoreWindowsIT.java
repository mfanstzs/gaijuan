package app.deskgrade.adapters.windows.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual current-user Windows DPAPI, using only a synthetic test credential. */
class DpapiSecretStoreWindowsIT {
  @TempDir Path directory;

  @Test
  void nativeCurrentUserRoundTripAndTamperRejection() throws Exception {
    DpapiSecretStore store = new DpapiSecretStore(directory);
    char[] value = "DeskGrade-synthetic-DPAPI-check-中文".toCharArray();
    store.put("synthetic", value);
    assertArrayEquals(value, new DpapiSecretStore(directory).read("synthetic").orElseThrow());
    Path file = directory.resolve("synthetic.dpapi");
    byte[] protectedBytes = Files.readAllBytes(file);
    assertFalse(new String(protectedBytes, StandardCharsets.UTF_8).contains("DeskGrade-synthetic"));
    Arrays.fill(protectedBytes, (byte) 0x3A);
    Files.write(file, protectedBytes);
    assertThrows(app.deskgrade.core.error.DeskGradeException.class, () -> store.read("synthetic"));
    store.delete("synthetic");
    assertTrue(store.read("synthetic").isEmpty());
  }
}
