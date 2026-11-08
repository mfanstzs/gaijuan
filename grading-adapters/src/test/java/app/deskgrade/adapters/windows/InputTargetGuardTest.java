package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InputTargetGuardTest {
  @Test
  void queuedOwnReleaseIsNotCheckedBeforePageVerification() {
    var ownMouseDown = new AtomicBoolean(true);
    var checks = new ArrayList<String>();
    InputTargetGuard.verify(
        () -> {
          checks.add("permission");
          ownMouseDown.set(false);
        },
        () -> checks.add("window"),
        key -> {
          checks.add("key");
          return key == 1 && ownMouseDown.get();
        });
    assertEquals(List.of("permission", "window"), checks.subList(0, 2));
    assertEquals(9, checks.size());
  }

  @Test
  void userModifierPressedDuringSlowPermissionCheckIsRejected() {
    var userControl = new AtomicBoolean(false);
    var failure =
        assertThrows(
            SafeInputException.class,
            () ->
                InputTargetGuard.verify(
                    () -> userControl.set(true),
                    () -> {},
                    key -> key == 0x11 && userControl.get()));
    assertEquals("HELD_KEY_VK:17", failure.diagnosticCode());
  }

  @Test
  void changedWindowAfterSlowPermissionCheckIsRejected() {
    var windowSafe = new AtomicBoolean(true);
    var failure =
        assertThrows(
            SafeInputException.class,
            () ->
                InputTargetGuard.verify(
                    () -> windowSafe.set(false),
                    () -> {
                      if (!windowSafe.get()) throw SafeInputException.code("WINDOW_UNSAFE:FOCUS");
                    },
                    key -> false));
    assertEquals("WINDOW_UNSAFE:FOCUS", failure.diagnosticCode());
  }
}
