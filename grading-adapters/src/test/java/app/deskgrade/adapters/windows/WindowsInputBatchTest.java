package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.platform.win32.WinUser.INPUT;
import org.junit.jupiter.api.Test;

class WindowsInputBatchTest {
  @Test
  void wholeNumberIsOneBalancedBoundedKeyboardBatch() {
    INPUT[] events = WindowsInputDriver.numericKeys("12.5");
    assertEquals(12, events.length);
    assertShortcut(events, 0, 0x41);
    for (int index = 0; index < 4; index++) {
      assertKey(events[4 + index * 2], 0, "12.5".charAt(index), 4);
      assertKey(events[5 + index * 2], 0, "12.5".charAt(index), 6);
    }
  }

  @Test
  void copyBatchReleasesControlBeforeTheNextShortcut() {
    INPUT[] events = WindowsInputDriver.copyKeys();
    assertEquals(8, events.length);
    assertShortcut(events, 0, 0x41);
    assertShortcut(events, 4, 0x43);
  }

  @Test
  void rejectsNonNumericAndUnboundedInput() {
    for (String text : new String[] {"", "-1", "1\n2", "1e2", "9".repeat(25)})
      assertThrows(SafeInputException.class, () -> WindowsInputDriver.numericKeys(text));
  }

  private void assertShortcut(INPUT[] events, int offset, int letter) {
    assertKey(events[offset], 0x11, 0, 0);
    assertKey(events[offset + 1], letter, 0, 0);
    assertKey(events[offset + 2], letter, 0, 2);
    assertKey(events[offset + 3], 0x11, 0, 2);
  }

  private void assertKey(INPUT event, int virtual, int scan, int flags) {
    assertEquals(1, event.type.intValue());
    assertEquals(virtual, event.input.ki.wVk.intValue());
    assertEquals(scan, event.input.ki.wScan.intValue());
    assertEquals(flags, event.input.ki.dwFlags.intValue());
  }
}
