package app.deskgrade.desktop;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** Explicit constructor injection only for personal-provider fixtures. */
final class DesktopTestMode {
  private DesktopTestMode() {}

  static DesktopWorkspace developer(Path root) {
    return new DesktopWorkspace(root, true);
  }

  static DesktopWorkspace developer(Path root, ExecutorService worker) {
    return new DesktopWorkspace(root, worker, true);
  }
}
