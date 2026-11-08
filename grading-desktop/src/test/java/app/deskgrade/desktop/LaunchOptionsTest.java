package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchOptionsTest {
  @TempDir Path directory;

  @Test
  void ordinaryLaunchHasNoSmokeDataOrReport() throws Exception {
    assertNull(LaunchOptions.parse(new String[0]).smokeRoot());
  }

  @Test
  void smokeRequiresExplicitNewAbsoluteDirectoryAndCannotReadExistingData() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> LaunchOptions.parse(new String[] {"--package-smoke"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> LaunchOptions.parse(new String[] {"--package-smoke", "relative"}));
    assertThrows(
        java.io.IOException.class,
        () -> LaunchOptions.parse(new String[] {"--package-smoke", directory.toString()}));
    Path root = directory.resolve("全新 smoke");
    var options = LaunchOptions.parse(new String[] {"--package-smoke", root.toString()});
    assertEquals(root.toRealPath(), options.smokeRoot());
    assertTrue(Files.isDirectory(root));
    assertThrows(
        java.io.IOException.class,
        () -> LaunchOptions.parse(new String[] {"--package-smoke", root.toString()}));
  }

  @Test
  void unknownOrExtraArgumentsNeverEnableSmoke() {
    assertThrows(
        IllegalArgumentException.class, () -> LaunchOptions.parse(new String[] {"--auto"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> LaunchOptions.parse(new String[] {"--package-smoke", "x", "extra"}));
  }
}
