package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.PauseReason;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopWorkspaceScanHookTest {
  @TempDir Path directory;

  @Test
  void brokenVisualCallbackCannotPreventPauseStopEmergencyOrClose() throws Exception {
    var workspace = new DesktopWorkspace(directory);
    var revoked = new AtomicInteger();
    workspace.onActionRevoked = revoked::incrementAndGet;
    workspace.onAnswerScan =
        (target, active) -> {
          throw new IllegalStateException("Synthetic callback failure");
        };
    long initial = workspace.actionTicket();
    assertDoesNotThrow(() -> workspace.pause(PauseReason.USER_PAUSED));
    assertTrue(workspace.actionTicket() > initial);
    assertEquals(1, revoked.get());
    assertDoesNotThrow(workspace::stop);
    assertEquals(2, revoked.get());
    assertDoesNotThrow(() -> workspace.emergencyStop(new RuntimeException("Synthetic failure")));
    assertEquals(3, revoked.get());
    assertDoesNotThrow(workspace::close);
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    assertNull(workspace.binding());
    assertFalse(workspace.ready());
  }

  @Test
  void unavailableFxLinkageCannotPreventResourceClosure() throws Exception {
    var workspace = new DesktopWorkspace(directory);
    workspace.onAnswerScan =
        (target, active) -> {
          throw new NoClassDefFoundError("Synthetic unavailable UI");
        };
    assertDoesNotThrow(workspace::close);
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }
}
