package app.deskgrade.desktop;

import app.deskgrade.adapters.runtime.SingleInstanceGuard;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Desktop entry point; saved runs recover paused and require explicit user action. */
public final class DeskGradeApplication extends Application {
  private static volatile DeskGradeApplication activeApplication;
  private static final AtomicBoolean handlingUncaught = new AtomicBoolean();
  private Logger log;
  private DesktopWorkspace workspace;
  private SingleInstanceGuard instance;
  private PackageSmoke smoke;
  private boolean stopped;

  @Override
  public void start(Stage stage) {
    activeApplication = this;
    Thread.currentThread()
        .setUncaughtExceptionHandler(DeskGradeApplication::handleUncaughtException);
    if (Launcher.startupFailure() != null) {
      showExitMessage("DeskGrade 无法启动", Launcher.startupFailure());
      return;
    }
    try {
      Launcher.prepareDefaultStorage();
      var acquired = SingleInstanceGuard.tryAcquire(DesktopWorkspace.defaultDataPath());
      if (acquired.isEmpty()) {
        showExitMessage(
            "DeskGrade 已在运行", "此数据目录已有 DeskGrade 正在运行，请返回原窗口。" + "本次启动不会打开数据库或恢复会话，此提示将在 4 秒后关闭。");
        return;
      }
      instance = acquired.orElseThrow();
      // Logging is initialized only after exclusion; another instance must not share a rolling
      // file.
      log = LoggerFactory.getLogger(DeskGradeApplication.class);
      workspace = new DesktopWorkspace(instance.dataDirectory());
      showWorkspace(stage);
    } catch (Exception | LinkageError failure) {
      stop();
      showExitMessage("DeskGrade 无法启动", "本地数据目录或界面无法打开，请检查目录权限和发布包是否完整。未开始阅卷。");
    }
  }

  static void installGlobalExceptionHandler() {
    Thread.setDefaultUncaughtExceptionHandler(DeskGradeApplication::handleUncaughtException);
  }

  static void handleUncaughtException(Thread thread, Throwable failure) {
    if (failure == null || !handlingUncaught.compareAndSet(false, true)) return;
    try {
      var active = activeApplication;
      if (active == null) {
        Launcher.failClosedBeforeApplication();
        return;
      }
      var current = active.workspace;
      if (current != null) current.emergencyStop(failure);
      var logger = active.log;
      if (logger != null)
        logger
            .atError()
            .setCause(failure)
            .addKeyValue("event", "desktop_uncaught_fail_stop")
            .addKeyValue("thread", thread == null ? "unknown" : thread.getName())
            .log("Unhandled desktop error; action authority revoked");
    } catch (Throwable ignored) {
      // Never recurse from the process-wide handler. Workspace revocation is attempted first.
    } finally {
      handlingUncaught.set(false);
    }
  }

  private void showWorkspace(Stage stage) {
    Scene scene = new Scene(new DeskGradeShell(workspace, stage), 1100, 760);
    scene
        .getStylesheets()
        .add(
            DeskGradeApplication.class
                .getResource("/app/deskgrade/desktop/shell.css")
                .toExternalForm());
    stage.setTitle("DeskGrade · 单题自动阅卷助手");
    stage.setMinWidth(960);
    stage.setMinHeight(700);
    stage.setScene(scene);
    stage.show();
    log.atInfo()
        .addKeyValue("event", "desktop_shell_shown")
        .addKeyValue("configuration", "unconfigured")
        .addKeyValue("operational_actions_enabled", false)
        .log("Desktop shell shown");
    if (Launcher.smokeRoot() != null) {
      smoke = new PackageSmoke(Launcher.smokeRoot());
      smoke.start(stage, workspace);
    }
  }

  private static void showExitMessage(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle(title);
    alert.setHeaderText(title);
    alert.setContentText(message);
    PauseTransition dismiss = new PauseTransition(Duration.seconds(4));
    dismiss.setOnFinished(
        event -> {
          alert.close();
          Platform.exit();
        });
    alert.setOnHidden(
        event -> {
          dismiss.stop();
          Platform.exit();
        });
    alert.show();
    dismiss.play();
  }

  @Override
  public void stop() {
    if (stopped) return;
    stopped = true;
    boolean cleaned = workspace == null;
    if (workspace != null) {
      try {
        workspace.close();
        workspace.awaitClosed().get(5, java.util.concurrent.TimeUnit.SECONDS);
        cleaned = true;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (Exception unfinished) {
        log.atWarn()
            .addKeyValue("event", "desktop_cleanup_incomplete")
            .log("Local cleanup did not finish within the exit deadline");
      }
    }
    boolean released = instance == null;
    if (cleaned && instance != null) {
      try {
        instance.close();
        released = true;
      } catch (java.io.IOException failure) {
        log.atWarn()
            .addKeyValue("event", "instance_lock_cleanup_incomplete")
            .log("Process lock retained until exit");
      }
    }
    // On timeout/error, deliberately retain the lock. The OS releases it when this process exits.
    if (smoke != null) smoke.finish(cleaned, released);
    if (log != null)
      log.atInfo().addKeyValue("event", "desktop_shell_closed").log("Desktop shell closed");
    activeApplication = null;
  }
}
