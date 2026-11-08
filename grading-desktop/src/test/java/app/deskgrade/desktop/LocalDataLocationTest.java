package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataLocationTest {
  @TempDir Path directory;

  @Test
  void explicitDataAndLogOverridesRemainUntouchedByDefaultMigration() throws Exception {
    String oldData = System.getProperty("deskgrade.dataDir"),
        oldLogs = System.getProperty("deskgrade.logDir");
    try {
      Path selected = directory.resolve("explicit-data");
      Path logs = directory.resolve("explicit-logs");
      System.setProperty("deskgrade.dataDir", selected.toString());
      System.setProperty("deskgrade.logDir", logs.toString());
      Launcher.prepareDefaultStorage();
      assertEquals(selected, DesktopWorkspace.defaultDataPath());
      assertEquals(logs.toString(), System.getProperty("deskgrade.logDir"));
      assertFalse(java.nio.file.Files.exists(selected));
    } finally {
      restore("deskgrade.dataDir", oldData);
      restore("deskgrade.logDir", oldLogs);
    }
  }

  @Test
  void normalRuntimeUsesTheStableUserHomeDirectoryRatherThanAppData() {
    String old = System.getProperty("deskgrade.dataDir");
    try {
      System.clearProperty("deskgrade.dataDir");
      assertEquals(
          Path.of(System.getProperty("user.home"), ".deskgrade", "data"),
          DesktopWorkspace.defaultDataPath());
    } finally {
      restore("deskgrade.dataDir", old);
    }
  }

  private static void restore(String name, String value) {
    if (value == null) System.clearProperty(name);
    else System.setProperty(name, value);
  }
}
