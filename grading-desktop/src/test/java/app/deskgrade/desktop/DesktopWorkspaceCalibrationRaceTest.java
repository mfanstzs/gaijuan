package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopWorkspaceCalibrationRaceTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void staleProfileCannotBindWhileARequestedProfileSelectionIsStillPending() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    try (workspace) {
      WindowSnapshot target = target();
      var oldProfile = profile("site-a", 1);
      set(workspace, "manualCalibration", oldProfile);
      var existing =
          new TargetBinding(
              target,
              oldProfile.answerRegion(),
              new PixelRect(
                  (int) oldProfile.answerRegion().x(),
                  (int) oldProfile.answerRegion().y(),
                  (int) oldProfile.answerRegion().width(),
                  (int) oldProfile.answerRegion().height()));
      set(workspace, "binding", existing);
      set(workspace, "manualBinding", manualBinding(workspace, existing, oldProfile));
      assertTrue(workspace.automaticTarget());

      var rejected = workspace.bindManualAnswer(target.identity(), "site-b", 1);
      var failure = assertThrows(ExecutionException.class, rejected::get);
      assertTrue(failure.getCause().getMessage().contains("框选已变化"));
      assertNull(
          workspace.binding(), "A stale profile mismatch must revoke, never retain, binding");
      assertNull(get(workspace, "manualBinding"));
      assertFalse(workspace.automaticTarget());

      var staleRevision = workspace.bindManualAnswer(target.identity(), "site-a", 2);
      assertThrows(ExecutionException.class, staleRevision::get);
      assertNull(workspace.binding());
      assertNull(get(workspace, "manualBinding"));
    }
    workspace.awaitClosed().get();
  }

  @Test
  void revocationWinningBeforeBindingClaimCannotRestoreManualAuthority() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    try (workspace) {
      WindowSnapshot target = target();
      var saved = profile("site-a", 1);
      set(workspace, "manualCalibration", saved);
      var existing =
          new TargetBinding(target, saved.answerRegion(), new PixelRect(20, 100, 500, 300));
      set(workspace, "binding", existing);
      set(workspace, "manualBinding", manualBinding(workspace, existing, saved));
      assertTrue(workspace.automaticTarget());

      Object lock = get(workspace, "bindingLock");
      var started = new CountDownLatch(1);
      var result = new AtomicReference<CompletableFuture<TargetBinding>>();
      var invocationFailure = new AtomicReference<Throwable>();
      Thread binder =
          new Thread(
              () -> {
                started.countDown();
                try {
                  result.set(
                      workspace.bindManualAnswer(target.identity(), saved.id(), saved.revision()));
                } catch (Throwable failure) {
                  invocationFailure.set(failure);
                }
              },
              "synthetic-binding-race");
      Thread stopper = new Thread(workspace::stop, "synthetic-revocation-race");
      long priorTicket = workspace.actionTicket();
      synchronized (lock) {
        binder.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        awaitBlocked(binder);
        stopper.start();
        awaitTicketChange(workspace, priorTicket);
      }
      binder.join(2_000);
      stopper.join(2_000);
      assertFalse(binder.isAlive());
      assertFalse(stopper.isAlive());
      assertNull(invocationFailure.get());
      var rejected = result.get();
      assertNotNull(rejected);
      assertThrows(ExecutionException.class, rejected::get);
      assertNull(workspace.binding());
      assertFalse(workspace.automaticTarget());
      assertNull(get(workspace, "manualBinding"));
    }
    workspace.awaitClosed().get();
  }

  private static ManualPlatformCalibration profile(String id, long revision) {
    return new ManualPlatformCalibration(
        id,
        revision,
        "测试评分网",
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

  private static void set(DesktopWorkspace workspace, String name, Object value) throws Exception {
    Field field = DesktopWorkspace.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(workspace, value);
  }

  private static Object get(DesktopWorkspace workspace, String name) throws Exception {
    Field field = DesktopWorkspace.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(workspace);
  }

  private static AtomicLong atomic(DesktopWorkspace workspace, String name) throws Exception {
    return (AtomicLong) get(workspace, name);
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
        atomic(workspace, "bindingGeneration").get(),
        workspace.actionTicket(),
        workspace.accountTicket());
  }

  private static void awaitBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
      Thread.sleep(2);
    assertEquals(Thread.State.BLOCKED, thread.getState());
  }

  private static void awaitTicketChange(DesktopWorkspace workspace, long prior)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (workspace.actionTicket() == prior && System.nanoTime() < deadline) Thread.sleep(2);
    assertNotEquals(prior, workspace.actionTicket());
  }
}
