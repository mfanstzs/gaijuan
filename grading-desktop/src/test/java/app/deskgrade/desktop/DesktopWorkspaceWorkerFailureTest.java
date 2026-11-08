package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.EvidenceStore;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionJournal;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import app.deskgrade.core.port.GradingProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopWorkspaceWorkerFailureTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void linkageErrorInWorkerRevokesBindingAndStopsTheController() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    var controller = controller();
    try (workspace) {
      var calibration = calibration();
      var target = target();
      set(workspace, "manualCalibration", calibration);
      var existing =
          new TargetBinding(target, calibration.answerRegion(), new PixelRect(20, 100, 500, 300));
      set(workspace, "binding", existing);
      set(workspace, "manualBinding", manualBinding(workspace, existing, calibration));
      assertTrue(workspace.automaticTarget());
      set(workspace, "controller", controller);
      long ticket = workspace.actionTicket();

      var failed = submit(workspace, () -> throwLinkageError());
      var observed = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
      assertInstanceOf(LinkageError.class, observed.getCause());
      assertTrue(workspace.actionTicket() > ticket);
      assertNull(workspace.binding());
      assertNull(get(workspace, "manualBinding"));
      assertFalse(workspace.automaticTarget());
      awaitStopped(controller);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  @Test
  void expectedBusinessAndCancellationFailuresDoNotTriggerEmergencyStop() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    try (workspace) {
      long ticket = workspace.actionTicket();
      assertThrows(
          ExecutionException.class,
          () ->
              submit(
                      workspace,
                      () -> {
                        throw new IllegalStateException("synthetic business rejection");
                      })
                  .get(5, TimeUnit.SECONDS));
      assertEquals(ticket, workspace.actionTicket());
      var cancellation =
          assertThrows(
              ExecutionException.class,
              () ->
                  submit(
                          workspace,
                          () -> {
                            throw new CancellationException("synthetic cancellation");
                          })
                      .get(5, TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class, cancellation.getCause());
      assertEquals(ticket, workspace.actionTicket());
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  private static Object throwLinkageError() {
    throw new LinkageError("synthetic linkage failure");
  }

  @SuppressWarnings("unchecked")
  private static <T> CompletableFuture<T> submit(DesktopWorkspace workspace, Callable<T> task)
      throws Exception {
    Method method = DesktopWorkspace.class.getDeclaredMethod("work", Callable.class);
    method.setAccessible(true);
    return (CompletableFuture<T>) method.invoke(workspace, task);
  }

  private static SessionController controller() {
    var question =
        new QuestionDefinition(
            "q1",
            1,
            "Synthetic question",
            "Synthetic answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "criterion", "Synthetic criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    var settings =
        new SessionSettings(
            question,
            "model",
            1,
            1,
            "{}",
            1,
            1,
            0,
            null,
            Duration.ofMillis(10),
            Duration.ofSeconds(1));
    SessionPlatform platform = proxy(SessionPlatform.class);
    SessionJournal journal = proxy(SessionJournal.class);
    EvidenceStore evidence = (run, attempt, capture) -> "metadata-only";
    GradingProvider provider =
        (request, cancellation) ->
            CompletableFuture.failedFuture(new IllegalStateException("unused provider"));
    return new SessionController(
        platform,
        journal,
        evidence,
        provider,
        ignored -> BigDecimal.ZERO,
        settings,
        Clock.systemUTC(),
        () -> true,
        ignored -> {});
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (instance, method, arguments) -> {
              Class<?> result = method.getReturnType();
              if (result == boolean.class) return false;
              if (result == int.class) return 0;
              if (result == long.class) return 0L;
              return null;
            });
  }

  private static void awaitStopped(SessionController controller) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (controller.status().state() != SessionState.STOPPED && System.nanoTime() < deadline)
      Thread.sleep(2);
    assertEquals(SessionState.STOPPED, controller.status().state());
  }

  private static ManualPlatformCalibration calibration() {
    return new ManualPlatformCalibration(
        "site-worker",
        1,
        "Synthetic website",
        new ManualPlatformCalibration.WindowLayout(1000, 700, 96, "SyntheticWindow"),
        null,
        new LogicalRect(20, 100, 500, 300),
        new LogicalRect(600, 500, 100, 40),
        new LogicalRect(750, 500, 100, 40),
        null,
        null,
        ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT,
        Instant.parse("2026-09-12T12:00:00Z"));
  }

  private static WindowSnapshot target() {
    var bounds = new PixelRect(0, 0, 1000, 700);
    return new WindowSnapshot(
        new WindowIdentity(123, 456, Instant.parse("2026-09-12T10:00:00Z")),
        "Synthetic target",
        "SyntheticWindow",
        bounds,
        bounds,
        new DisplaySnapshot("test", bounds, bounds, 1, 1),
        96,
        true,
        true,
        false,
        false,
        true);
  }

  private static Object get(DesktopWorkspace workspace, String name) throws Exception {
    Field field = DesktopWorkspace.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(workspace);
  }

  private static void set(DesktopWorkspace workspace, String name, Object value) throws Exception {
    Field field = DesktopWorkspace.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(workspace, value);
  }

  private static Object manualBinding(
      DesktopWorkspace workspace, TargetBinding target, ManualPlatformCalibration calibration)
      throws Exception {
    Class<?> type = Class.forName(DesktopWorkspace.class.getName() + "$ManualBinding");
    var constructor =
        type.getDeclaredConstructor(
            TargetBinding.class,
            ManualPlatformCalibration.class,
            String.class,
            long.class,
            long.class,
            long.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        target,
        calibration,
        "manual-site-v1:" + "a".repeat(64),
        ((AtomicLong) get(workspace, "bindingGeneration")).get(),
        workspace.actionTicket(),
        workspace.accountTicket());
  }
}
