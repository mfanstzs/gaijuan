package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.DisplaySnapshot;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser.MONITORINFO;
import com.sun.jna.ptr.IntByReference;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read-only window inspection, with a separate explicitly requested foreground operation. */
public final class WindowsWindowService {
  private static final long SELECTION_ACTIVATION_TIMEOUT_NANOS = 3_000_000_000L;
  private static final long SELECTION_ACTIVATION_POLL_MILLIS = 25L;

  public record WindowChoice(WindowIdentity identity, String title, String windowClass) {
    @Override
    public String toString() {
      return title + " [PID " + identity.processId() + "]";
    }
  }

  private final CoordinateMapper mapper = new CoordinateMapper();

  public List<WindowChoice> listWindows() {
    User32 user = WindowsNative.user();
    List<WindowChoice> result = new ArrayList<>();
    if (!user.EnumWindows(
            (hwnd, data) -> {
              if (result.size() >= 256) return false;
              if (user.IsWindowVisible(hwnd) && user.GetWindowTextLength(hwnd) > 0) {
                try {
                  result.add(new WindowChoice(identity(hwnd), title(hwnd), windowClass(hwnd)));
                } catch (RuntimeException ignored) {
                  /* Closed/protected processes cannot be bound. */
                }
              }
              return true;
            },
            null)
        && result.size() < 256) throw new IllegalStateException("Window enumeration failed.");
    return List.copyOf(result);
  }

  public WindowSnapshot snapshot(long handle) {
    try (var ignored = WindowsNative.physicalCoordinates()) {
      User32 user = WindowsNative.user();
      HWND hwnd = WindowsNative.hwnd(handle);
      if (!user.IsWindow(hwnd)) throw new IllegalStateException("Target window unavailable.");
      WindowIdentity identity = identity(hwnd);
      RECT outer = new RECT();
      RECT client = new RECT();
      POINT origin = new POINT();
      if (!user.GetWindowRect(hwnd, outer)
          || !user.GetClientRect(hwnd, client)
          || !WindowsNative.extra().ClientToScreen(hwnd, origin))
        throw new IllegalStateException("Target geometry unavailable.");
      int dpi = WindowsNative.extra().GetDpiForWindow(hwnd);
      return new WindowSnapshot(
          identity,
          title(hwnd),
          windowClass(hwnd),
          rect(outer),
          new PixelRect(origin.x, origin.y, client.right - client.left, client.bottom - client.top),
          singleDisplay(hwnd),
          dpi,
          WindowsNative.value(user.GetForegroundWindow()) == handle,
          user.IsWindowVisible(hwnd),
          WindowsNative.extra().IsIconic(hwnd),
          isCloaked(hwnd),
          interactiveDesktop());
    }
  }

  public TargetBinding bind(long handle, LogicalRect region) {
    WindowSnapshot window = snapshot(handle);
    PixelRect physical = mapper.toScreen(region, window);
    if (!window.display().physicalBounds().contains(physical))
      throw new IllegalArgumentException("Region crosses display.");
    return new TargetBinding(window, region, physical);
  }

  public WindowSafety.Failure check(TargetBinding binding) {
    try {
      if (!mapper
          .toScreen(binding.answerRegion(), binding.calibratedWindow())
          .equals(binding.calibratedRegion())) {
        return WindowSafety.Failure.GEOMETRY;
      }
      WindowSnapshot now = snapshot(binding.calibratedWindow().identity().hwnd());
      return WindowSafety.compare(
          binding.calibratedWindow(),
          now,
          isOccluded(now.identity().hwnd(), binding.calibratedRegion()));
    } catch (RuntimeException | LinkageError ignored) {
      return WindowSafety.Failure.UNAVAILABLE;
    }
  }

  /** Call only for the user's explicit target-selection/start action; never to steal lost focus. */
  public boolean activateBoundWindow(TargetBinding binding) {
    try {
      long handle = binding.calibratedWindow().identity().hwnd();
      WindowSnapshot now = snapshot(handle);
      if (!now.identity().equals(binding.calibratedWindow().identity())
          || now.minimized()
          || !now.visible()
          || now.cloaked()
          || !now.interactive()) return false;
      if (!WindowsNative.user().SetForegroundWindow(WindowsNative.hwnd(handle))) return false;
      return check(binding) == WindowSafety.Failure.NONE;
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  /**
   * Brings a user-selected window forward before the selection overlay is shown.
   *
   * <p>This deliberately does not create a whole-client binding or require the whole client area to
   * be unobscured. Maximized Windows applications can legitimately have a client origin one or two
   * pixels outside the monitor. Strict geometry, focus and occlusion checks remain mandatory for
   * the actual capture and input bindings.
   */
  public boolean activateForSelection(WindowIdentity expected) {
    try {
      long handle = expected.hwnd();
      HWND hwnd = WindowsNative.hwnd(handle);
      long deadline = System.nanoTime() + SELECTION_ACTIVATION_TIMEOUT_NANOS;
      while (true) {
        WindowSnapshot now = snapshot(handle);
        if (!now.identity().equals(expected)
            || now.minimized()
            || !now.visible()
            || now.cloaked()
            || !now.interactive()) return false;
        if (now.foreground()) return true;
        WindowsNative.user().SetForegroundWindow(hwnd);
        if (System.nanoTime() >= deadline) return false;
        Thread.sleep(SELECTION_ACTIVATION_POLL_MILLIS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  public boolean isOccluded(long handle, PixelRect region) {
    try (var ignored = WindowsNative.physicalCoordinates()) {
      User32 user = WindowsNative.user();
      HWND above =
          user.GetWindow(
              WindowsNative.hwnd(handle), new com.sun.jna.platform.win32.WinDef.DWORD(3));
      int count = 0;
      while (WindowsNative.value(above) != 0) {
        if (++count > 4096) return true;
        if (user.IsWindowVisible(above)
            && !WindowsNative.extra().IsIconic(above)
            && !isCloaked(above)) {
          RECT bounds = new RECT();
          if (!user.GetWindowRect(above, bounds)) return true;
          if (bounds.right > bounds.left
              && bounds.bottom > bounds.top
              && rect(bounds).intersects(region)) return true;
        }
        above = user.GetWindow(above, new com.sun.jna.platform.win32.WinDef.DWORD(3));
      }
      return false;
    }
  }

  private static DisplaySnapshot singleDisplay(HWND hwnd) {
    User32 user = WindowsNative.user();
    GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
    if (screens.length != 1 || user.GetSystemMetrics(80) != 1) {
      throw new IllegalStateException("This calibration supports one display only.");
    }
    MONITORINFO monitor = new MONITORINFO();
    if (!user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), monitor).booleanValue())
      throw new IllegalStateException("Display unavailable.");
    GraphicsConfiguration config = screens[0].getDefaultConfiguration();
    Rectangle awt = config.getBounds();
    AffineTransform scale = config.getDefaultTransform();
    PixelRect physical = rect(monitor.rcMonitor);
    if (Math.abs(awt.width * scale.getScaleX() - physical.width()) > 1
        || Math.abs(awt.height * scale.getScaleY() - physical.height()) > 1) {
      throw new IllegalStateException("AWT and Win32 display calibration disagree.");
    }
    return new DisplaySnapshot(
        screens[0].getIDstring(),
        physical,
        new PixelRect(awt.x, awt.y, awt.width, awt.height),
        scale.getScaleX(),
        scale.getScaleY());
  }

  private static WindowIdentity identity(HWND hwnd) {
    IntByReference pid = new IntByReference();
    if (WindowsNative.user().GetWindowThreadProcessId(hwnd, pid) == 0)
      throw new IllegalStateException("Process unavailable.");
    long processId = Integer.toUnsignedLong(pid.getValue());
    Instant started =
        ProcessHandle.of(processId)
            .flatMap(process -> process.info().startInstant())
            .orElseThrow(() -> new IllegalStateException("Process identity unavailable."));
    return new WindowIdentity(WindowsNative.value(hwnd), processId, started);
  }

  private static String title(HWND hwnd) {
    char[] text = new char[1024];
    WindowsNative.user().GetWindowText(hwnd, text, text.length);
    return Native.toString(text);
  }

  private static String windowClass(HWND hwnd) {
    char[] text = new char[256];
    WindowsNative.user().GetClassName(hwnd, text, text.length);
    return Native.toString(text);
  }

  private static boolean isCloaked(HWND hwnd) {
    IntByReference cloak = new IntByReference();
    if (WindowsNative.dwm().DwmGetWindowAttribute(hwnd, 14, cloak, 4) < 0) {
      throw new IllegalStateException("Window visibility could not be verified.");
    }
    return cloak.getValue() != 0;
  }

  private static boolean interactiveDesktop() {
    var extra = WindowsNative.extra();
    Pointer input = extra.OpenInputDesktop(0, false, 1);
    if (input == null) return false;
    try {
      Pointer thread = extra.GetThreadDesktop(Kernel32.INSTANCE.GetCurrentThreadId());
      return objectName(input).equals(objectName(thread));
    } finally {
      extra.CloseDesktop(input);
    }
  }

  private static String objectName(Pointer handle) {
    char[] text = new char[256];
    if (handle == null
        || !WindowsNative.extra()
            .GetUserObjectInformation(
                handle, 2, text, text.length * Native.WCHAR_SIZE, new IntByReference()))
      throw new IllegalStateException("Desktop identity unavailable.");
    return Native.toString(text);
  }

  private static PixelRect rect(RECT rect) {
    return new PixelRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
  }
}
