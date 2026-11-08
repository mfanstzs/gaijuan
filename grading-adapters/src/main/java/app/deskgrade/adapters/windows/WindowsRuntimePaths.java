package app.deskgrade.adapters.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Optional 8.3 alias lookup for the current user's existing runtime directory; no process launch.
 */
public final class WindowsRuntimePaths {
  private WindowsRuntimePaths() {}

  /** Read-only native loading probe for the calling process; never enumerates other windows. */
  public static boolean currentProcessAvailable() {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
    return Integer.toUnsignedLong(Kernel32.INSTANCE.GetCurrentProcessId())
        == ProcessHandle.current().pid();
  }

  private interface PathsApi extends StdCallLibrary {
    int GetShortPathName(String longPath, char[] shortPath, int capacity);
  }

  public static Optional<Path> shortExistingPath(Path directory) {
    try {
      PathsApi api = Native.load("kernel32", PathsApi.class, W32APIOptions.DEFAULT_OPTIONS);
      char[] output = new char[32768];
      int count = api.GetShortPathName(directory.toString(), output, output.length);
      if (count <= 0 || count >= output.length) return Optional.empty();
      return Optional.of(Path.of(new String(output, 0, count)));
    } catch (RuntimeException | LinkageError unavailable) {
      return Optional.empty();
    }
  }
}
