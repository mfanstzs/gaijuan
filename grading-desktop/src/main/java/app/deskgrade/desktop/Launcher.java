package app.deskgrade.desktop;

import app.deskgrade.adapters.runtime.RuntimeSocketDirectory;
import java.nio.file.Path;
import javafx.application.Application;

/** Non-Application launcher for Maven and ordinary classpath development launches. */
public final class Launcher {
  private static LaunchOptions options = new LaunchOptions(null);
  private static String startupFailure;

  private Launcher() {}

  public static void main(String[] args) {
    DeskGradeApplication.installGlobalExceptionHandler();
    try {
      options = LaunchOptions.parse(args);
      options.applySmokeProperties();
      prepareDefaultStorage();
      RuntimeSocketDirectory.initialize();
    } catch (Exception | LinkageError failure) {
      startupFailure =
          "无法准备本地启动目录或迁移旧记录。原数据未删除；请先退出其他 DeskGrade，检查当前用户 .deskgrade/data 目录权限或未完成迁移，再重试。打包自检须指定全新绝对目录。"
              + "如临时目录路径过长，请显式设置 jdk.net.unixdomain.tmpdir 为当前用户可写的短路径。";
    }
    Application.launch(DeskGradeApplication.class);
  }

  static Path smokeRoot() {
    return options.smokeRoot();
  }

  /** Also used by direct JavaFX/IDE launches, before exclusion or logging opens any data files. */
  static void prepareDefaultStorage() throws java.io.IOException {
    String override = System.getProperty("deskgrade.dataDir");
    if (override != null && !override.isBlank()) return;
    // Do not initialize DesktopWorkspace's static logger before the atomic migration: Logback
    // would otherwise create target/logs and turn a fresh destination into a partial directory.
    Path target =
        app.deskgrade.adapters.runtime.LocalDataDirectoryMigration.defaultDirectory(
            Path.of(System.getProperty("user.home")));
    String local = System.getenv("LOCALAPPDATA");
    Path legacy =
        Path.of(
            local == null || local.isBlank() ? System.getProperty("user.home") : local,
            "DeskGrade");
    app.deskgrade.adapters.runtime.LocalDataDirectoryMigration.copyVisibleLegacyOnce(
        target, legacy);
    if (System.getProperty("deskgrade.logDir", "").isBlank())
      System.setProperty("deskgrade.logDir", target.resolve("logs").toString());
  }

  static String startupFailure() {
    return startupFailure;
  }

  static void failClosedBeforeApplication() {
    if (startupFailure == null) startupFailure = "桌面启动线程发生未处理错误，已禁止启动；请检查日志和发布包完整性。";
  }
}
