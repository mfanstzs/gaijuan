package app.deskgrade.adapters.windows;

import app.deskgrade.core.application.session.ActionPermission;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Fresh native checks come after potentially slow page/storage permission checks. */
final class InputTargetGuard {
  private InputTargetGuard() {}

  static void verify(ActionPermission permission, Runnable nativeTargetCheck, IntPredicate held) {
    Objects.requireNonNull(permission);
    try {
      permission.verify();
    } catch (RuntimeException failure) {
      throw SafeInputException.stage("INPUT_PERMISSION", failure);
    }
    nativeTargetCheck.run();
    // SendInput queues a balanced batch; its previous mouse/key-up can still be pending just
    // after SendInput returns. Do not inspect it before the intervening page verification. Also
    // don't let an actual held modifier escape detection during that slower verification.
    for (int key : new int[] {0x10, 0x11, 0x12, 0x5B, 0x5C, 0x01, 0x02}) {
      if (held.test(key)) throw SafeInputException.code("HELD_KEY_VK:" + key);
    }
  }
}
