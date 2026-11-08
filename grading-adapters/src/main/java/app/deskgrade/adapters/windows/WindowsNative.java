package app.deskgrade.adapters.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.Locale;

/** DLLs are loaded only after an explicit Windows operation. */
final class WindowsNative {
  interface ExtraUser32 extends StdCallLibrary {
    int GetDpiForWindow(HWND hwnd);

    boolean ClientToScreen(HWND hwnd, POINT point);

    boolean IsIconic(HWND hwnd);

    Pointer SetThreadDpiAwarenessContext(Pointer context);

    Pointer OpenInputDesktop(int flags, boolean inherit, int access);

    boolean CloseDesktop(Pointer desktop);

    Pointer GetThreadDesktop(int threadId);

    boolean GetUserObjectInformation(
        Pointer object, int index, char[] text, int size, IntByReference needed);
  }

  interface Dwm extends StdCallLibrary {
    int DwmGetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);

    int DwmFlush();
  }

  private static class Loaded {
    static final User32 USER = User32.INSTANCE;
    static final ExtraUser32 EXTRA =
        Native.load("user32", ExtraUser32.class, W32APIOptions.DEFAULT_OPTIONS);
    static final Dwm DWM = Native.load("dwmapi", Dwm.class);
  }

  static void requireWindows() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
      throw new IllegalStateException("Windows desktop required.");
    }
  }

  static User32 user() {
    requireWindows();
    return Loaded.USER;
  }

  static ExtraUser32 extra() {
    requireWindows();
    return Loaded.EXTRA;
  }

  static Dwm dwm() {
    requireWindows();
    return Loaded.DWM;
  }

  static HWND hwnd(long value) {
    return new HWND(Pointer.createConstant(value));
  }

  static long value(HWND hwnd) {
    return hwnd == null ? 0 : Pointer.nativeValue(hwnd.getPointer());
  }

  static DpiScope physicalCoordinates() {
    Pointer previous = extra().SetThreadDpiAwarenessContext(Pointer.createConstant(-4L));
    if (previous == null) throw new IllegalStateException("Per-monitor DPI context unavailable.");
    return new DpiScope(previous);
  }

  static final class DpiScope implements AutoCloseable {
    private final Pointer previous;

    private DpiScope(Pointer previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      extra().SetThreadDpiAwarenessContext(previous);
    }
  }
}
