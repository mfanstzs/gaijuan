package app.deskgrade.adapters.windows;

import app.deskgrade.core.application.session.ActionPermission;
import app.deskgrade.core.application.session.AttemptPerformance.Metric;
import app.deskgrade.core.application.session.PerformanceTrace;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.PixelPoint;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser.INPUT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.math.BigDecimal;
import java.util.Objects;

/** The sole production input injection implementation. Caller must own the serial action gate. */
public final class WindowsInputDriver {
  private final WindowsWindowService windows;
  private final CoordinateMapper mapper = new CoordinateMapper();

  public WindowsInputDriver(WindowsWindowService windows) {
    this.windows = Objects.requireNonNull(windows);
  }

  public void click(TargetBinding binding, LogicalRect control, ActionPermission permission) {
    PixelRect guardedControl = mapper.toScreen(control, binding.calibratedWindow());
    PixelPoint screen =
        mapper.clientToScreen(
            control.x() + control.width() / 2,
            control.y() + control.height() / 2,
            binding.calibratedWindow());
    PixelPoint absolute = mapper.toAbsoluteInput(screen, binding.calibratedWindow().display());
    INPUT[] input = inputs(3);
    mouse(input[0], absolute.x(), absolute.y(), 0xC001);
    mouse(input[1], 0, 0, 0x0002);
    mouse(input[2], 0, 0, 0x0004);
    send(binding, guardedControl, input, permission);
  }

  public void fillNumber(
      TargetBinding binding, LogicalRect control, BigDecimal score, ActionPermission permission) {
    Objects.requireNonNull(score);
    String text = score.stripTrailingZeros().toPlainString();
    if (score.signum() < 0 || !text.matches("[0-9]+(?:\\.[0-9]+)?") || text.length() > 24) {
      throw SafeInputException.code("INPUT_INVALID_NUMBER");
    }
    PixelRect guardedControl = mapper.toScreen(control, binding.calibratedWindow());
    click(binding, control, permission);
    // One bounded, balanced keyboard batch. Windows preserves its order without interleaving
    // outside input; don't recapture/perform a remote journal probe between individual digits.
    send(binding, guardedControl, numericKeys(text), permission);
  }

  static INPUT[] numericKeys(String text) {
    if (text == null || text.length() > 24 || !text.matches("[0-9]+(?:\\.[0-9]+)?"))
      throw SafeInputException.code("INPUT_INVALID_NUMBER");
    INPUT[] events = inputs(4 + text.length() * 2);
    shortcutKeys(events, 0, 0x41);
    for (int index = 0; index < text.length(); index++) {
      key(events[4 + index * 2], 0, text.charAt(index), 4);
      key(events[5 + index * 2], 0, text.charAt(index), 6);
    }
    return events;
  }

  static INPUT[] copyKeys() {
    INPUT[] events = inputs(8);
    shortcutKeys(events, 0, 0x41);
    shortcutKeys(events, 4, 0x43);
    return events;
  }

  private static void shortcutKeys(INPUT[] events, int offset, int letter) {
    key(events[offset], 0x11, 0, 0);
    key(events[offset + 1], letter, 0, 0);
    key(events[offset + 2], letter, 0, 2);
    key(events[offset + 3], 0x11, 0, 2);
  }

  /** Reads the selected edit control using an explicitly authorized, fresh Ctrl+C operation. */
  public BigDecimal readNumber(
      TargetBinding binding, LogicalRect control, ActionPermission permission) {
    // Includes the original click, clipboard copy/wait/restore and all permission/native guards;
    // this is not a measurement of clipboard waiting alone. No trace means no clock reads.
    return PerformanceTrace.measureCurrent(
        Metric.CLIPBOARD_READ, () -> readNumberInternal(binding, control, permission));
  }

  private BigDecimal readNumberInternal(
      TargetBinding binding, LogicalRect control, ActionPermission permission) {
    PixelRect guarded = mapper.toScreen(control, binding.calibratedWindow());
    Runnable verify = () -> verifyInputTarget(binding, guarded, permission);
    verify.run();
    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    var access =
        new ClipboardNumberReadback.ClipboardAccess() {
          public Object snapshot() {
            return clipboard.getContents(null);
          }

          public long sequence() {
            return Integer.toUnsignedLong(ClipboardNative.API.GetClipboardSequenceNumber());
          }

          public void setText(String text) {
            clipboard.setContents(new StringSelection(text), null);
          }

          public String text() {
            try {
              if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
              Object text = clipboard.getData(DataFlavor.stringFlavor);
              return text instanceof String value ? value : null;
            } catch (Exception unavailable) {
              throw SafeInputException.stage("CLIPBOARD_UNAVAILABLE", unavailable);
            }
          }

          public boolean ownedByTarget() {
            HWND owner = ClipboardNative.API.GetClipboardOwner();
            if (owner == null) return false;
            var process = new IntByReference();
            WindowsNative.user().GetWindowThreadProcessId(owner, process);
            return Integer.toUnsignedLong(process.getValue())
                == binding.calibratedWindow().identity().processId();
          }

          public void restore(Object saved) {
            clipboard.setContents(
                saved instanceof Transferable value ? value : new StringSelection(""), null);
          }
        };
    return ClipboardNumberReadback.read(
        access,
        () -> {
          click(binding, control, permission);
          send(binding, guarded, copyKeys(), permission);
        },
        verify,
        () -> Thread.sleep(50));
  }

  private interface ClipboardUser32 extends StdCallLibrary {
    int GetClipboardSequenceNumber();

    HWND GetClipboardOwner();
  }

  private static final class ClipboardNative {
    private static final ClipboardUser32 API =
        Native.load("user32", ClipboardUser32.class, W32APIOptions.DEFAULT_OPTIONS);
  }

  private void send(
      TargetBinding binding,
      PixelRect guardedControl,
      INPUT[] inputs,
      ActionPermission permission) {
    verifyInputTarget(binding, guardedControl, permission);
    // Each tiny batch is balanced (all key/button downs include their ups). Windows inserts
    // SendInput arrays serially without interspersing other keyboard/mouse input.
    int sent =
        WindowsNative.user()
            .SendInput(new DWORD(inputs.length), inputs, inputs[0].size())
            .intValue();
    if (sent != inputs.length) {
      releasePartialBatch(inputs, Math.max(0, Math.min(sent, inputs.length)));
      throw SafeInputException.code("SENDINPUT_PARTIAL:" + sent + ":" + inputs.length);
    }
  }

  private void verifyInputTarget(
      TargetBinding binding, PixelRect guardedControl, ActionPermission permission) {
    InputTargetGuard.verify(
        permission,
        () -> {
          var safety = windows.check(binding);
          if (safety != WindowSafety.Failure.NONE)
            throw SafeInputException.code("WINDOW_UNSAFE:" + safety.name());
          if (windows.isOccluded(binding.calibratedWindow().identity().hwnd(), guardedControl))
            throw SafeInputException.code("CONTROL_OBSCURED");
        },
        key -> (WindowsNative.user().GetAsyncKeyState(key) & 0x8000) != 0);
  }

  /** Cleanup only releases keys/buttons whose down event entered a partial native batch. */
  private void releasePartialBatch(INPUT[] inputs, int sent) {
    for (int i = 0; i < sent; i++) {
      INPUT original = inputs[i];
      INPUT[] release = inputs(1);
      if (original.type.intValue() == 1 && (original.input.ki.dwFlags.intValue() & 2) == 0) {
        key(
            release[0],
            original.input.ki.wVk.intValue(),
            original.input.ki.wScan.intValue(),
            original.input.ki.dwFlags.intValue() | 2);
        WindowsNative.user().SendInput(new DWORD(1), release, release[0].size());
      } else if (original.type.intValue() == 0 && (original.input.mi.dwFlags.intValue() & 2) != 0) {
        mouse(release[0], 0, 0, 4);
        WindowsNative.user().SendInput(new DWORD(1), release, release[0].size());
      }
    }
  }

  private static INPUT[] inputs(int size) {
    return (INPUT[]) new INPUT().toArray(size);
  }

  private static void mouse(INPUT input, int x, int y, int flags) {
    input.type = new DWORD(0);
    input.input.setType("mi");
    input.input.mi.dx = new LONG(x);
    input.input.mi.dy = new LONG(y);
    input.input.mi.dwFlags = new DWORD(flags);
  }

  private static void key(INPUT input, int virtualKey, int scan, int flags) {
    input.type = new DWORD(1);
    input.input.setType("ki");
    input.input.ki.wVk = new WORD(virtualKey);
    input.input.ki.wScan = new WORD(scan);
    input.input.ki.dwFlags = new DWORD(flags);
  }
}
