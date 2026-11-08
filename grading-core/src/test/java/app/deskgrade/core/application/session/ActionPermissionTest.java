package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionPermissionTest {
  @Test
  void defaultPermissionRunsTheSuppliedTargetGuardBetweenAuthorityChecks() {
    AtomicInteger verifications = new AtomicInteger();
    AtomicInteger targetChecks = new AtomicInteger();
    ActionPermission permission = verifications::incrementAndGet;
    permission.verifyForTarget(
        () -> {
          assertEquals(1, verifications.get());
          targetChecks.incrementAndGet();
        });
    assertEquals(2, verifications.get());
    assertEquals(1, targetChecks.get());
  }

  @Test
  void revocationInsideTargetGuardCannotAuthorizeTheFollowingInput() {
    InputActionGate gate = new InputActionGate();
    gate.bindExecutorThread();
    long epoch = gate.open();
    ActionPermission permission = gate.permission(epoch, () -> {});
    AtomicBoolean input = new AtomicBoolean();
    DeskGradeException failure =
        assertThrows(
            DeskGradeException.class,
            () -> {
              permission.verifyForTarget(gate::revoke);
              input.set(true);
            });
    assertEquals(ErrorCode.CANCELLED, failure.code());
    assertFalse(input.get());
  }

  @Test
  void hotkeyLossInsideTargetGuardCannotAuthorizeTheFollowingInput() {
    InputActionGate gate = new InputActionGate();
    gate.bindExecutorThread();
    long epoch = gate.open();
    AtomicBoolean hotkeyAvailable = new AtomicBoolean(true);
    ActionPermission permission =
        gate.permission(
            epoch,
            () -> {
              if (!hotkeyAvailable.get()) throw new IllegalStateException("Hotkey unavailable");
            });
    assertThrows(
        IllegalStateException.class,
        () -> permission.verifyForTarget(() -> hotkeyAvailable.set(false)));
  }
}
