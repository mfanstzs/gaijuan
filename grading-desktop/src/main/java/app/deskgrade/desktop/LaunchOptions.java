package app.deskgrade.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only an explicit smoke invocation can create an isolated diagnostic workspace. */
record LaunchOptions(Path smokeRoot) {
  static LaunchOptions parse(String[] args) throws IOException {
    if (args.length == 0) return new LaunchOptions(null);
    if (args.length != 2 || !"--package-smoke".equals(args[0]))
      throw new IllegalArgumentException(
          "Expected --package-smoke followed by a new absolute directory");
    Path requested = Path.of(args[1]);
    if (!requested.isAbsolute())
      throw new IllegalArgumentException("Smoke directory must be absolute");
    // createDirectory, not createDirectories: an existing workspace is never reused or inspected.
    Path root = Files.createDirectory(requested.normalize()).toRealPath();
    return new LaunchOptions(root);
  }

  void applySmokeProperties() {
    if (smokeRoot == null) return;
    System.setProperty("deskgrade.dataDir", smokeRoot.resolve("data").toString());
    System.setProperty("deskgrade.logDir", smokeRoot.resolve("logs").toString());
    System.setProperty("deskgrade.smokeReport", smokeRoot.resolve("report.json").toString());
  }
}
