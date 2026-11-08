package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import java.awt.Color;
import java.awt.Frame;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Interactive Windows tests touch only a newly created synthetic test target. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WindowsDesktopWindowsIT {
  @Test
  @Order(5)
  void selectionActivationAllowsAVisibleClientThatExtendsPastTheMonitorEdge() throws Exception {
    JFrame frame = new JFrame("DeskGrade edge selection " + UUID.randomUUID());
    WindowsWindowService windows = new WindowsWindowService();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            frame.setBounds(-20, 40, 500, 360);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
          });
      long hwnd =
          windows.listWindows().stream()
              .filter(choice -> choice.title().equals(frame.getTitle()))
              .findFirst()
              .orElseThrow()
              .identity()
              .hwnd();
      var snapshot = windows.snapshot(hwnd);
      assertTrue(snapshot.clientBounds().x() < 0, () -> "fixture=" + snapshot.clientBounds());
      double scale = snapshot.dpi() / 96.0;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              windows.bind(
                  hwnd,
                  new LogicalRect(
                      0,
                      0,
                      snapshot.clientBounds().width() / scale,
                      snapshot.clientBounds().height() / scale)));
      var robot = new java.awt.Robot();
      robot.mouseMove(
          Math.max(20, snapshot.clientBounds().x() + 100), snapshot.clientBounds().y() + 100);
      robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
      robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
      await(() -> windows.snapshot(hwnd).foreground());
      assertTrue(windows.activateForSelection(snapshot.identity()));
      assertTrue(windows.snapshot(hwnd).foreground());
      assertDoesNotThrow(() -> windows.bind(hwnd, new LogicalRect(40, 20, 120, 80)));
    } finally {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  @Test
  @Order(4)
  void actualSelectedPixelsIdentityMovementOcclusionMinimizeAndMonitor() throws Exception {
    JFrame frame = new JFrame("DeskGrade synthetic capture " + UUID.randomUUID());
    JWindow obstruction = new JWindow(frame);
    WindowsWindowService windows = new WindowsWindowService();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            frame.setBounds(80, 80, 500, 400);
            frame.setAlwaysOnTop(true);
            frame.getContentPane().setBackground(Color.GREEN);
            frame.setVisible(true);
            frame.toFront();
          });
      long handle =
          windows.listWindows().stream()
              .filter(choice -> choice.title().equals(frame.getTitle()))
              .findFirst()
              .orElseThrow()
              .identity()
              .hwnd();
      TargetBinding binding = windows.bind(handle, new LogicalRect(40, 40, 160, 100));
      assertEquals(
          ProcessHandle.current().pid(), binding.calibratedWindow().identity().processId());
      awaitBound(windows, binding);
      await(() -> windows.check(binding) == WindowSafety.Failure.NONE);
      // Visible HWND does not mean the newly opened synthetic fixture has presented its first
      // frame. Finish Swing's first paint and wait for this process's compositor presentation.
      SwingUtilities.invokeAndWait(
          () ->
              frame
                  .getRootPane()
                  .paintImmediately(
                      0, 0, frame.getRootPane().getWidth(), frame.getRootPane().getHeight()));
      assertEquals(0, WindowsNative.dwm().DwmFlush());
      // DWM's opening animation can still blend the already-painted fixture with its backdrop.
      // Wait for visible, exact fixture pixels, never use elapsed time as proof of readiness.
      await(
          () -> {
            try {
              var candidate = new WindowRegionCapture(windows, Clock.systemUTC()).capture(binding);
              var pixels = ImageIO.read(new ByteArrayInputStream(candidate.png()));
              return pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2)
                  == Color.GREEN.getRGB();
            } catch (Exception notReady) {
              return false;
            }
          });
      var capture = new WindowRegionCapture(windows, Clock.systemUTC()).capture(binding);
      assertEquals(binding.calibratedRegion().width(), capture.width());
      assertEquals(binding.calibratedRegion().height(), capture.height());
      var image = ImageIO.read(new ByteArrayInputStream(capture.png()));
      java.nio.file.Files.createDirectories(
          java.nio.file.Path.of("target/native-capture-diagnostic"));
      java.nio.file.Files.write(
          java.nio.file.Path.of("target/native-capture-diagnostic/selected.png"), capture.png());
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("target/native-capture-diagnostic/geometry.txt"),
          "calibration="
              + binding.calibratedWindow().clientBounds()
              + "\nselected="
              + binding.calibratedRegion()
              + "\ndpi="
              + binding.calibratedWindow().dpi()
              + "\ndisplay="
              + binding.calibratedWindow().display()
              + "\n");
      assertEquals(Color.GREEN.getRGB(), image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
      assertEquals(64, capture.sha256().length());

      CountDownLatch invalidated = new CountDownLatch(1);
      try (WindowChangeMonitor monitor =
          new WindowChangeMonitor(
              windows, binding, Duration.ofMillis(50), failure -> invalidated.countDown())) {
        SwingUtilities.invokeAndWait(() -> frame.setLocation(120, 80));
        assertTrue(invalidated.await(2, TimeUnit.SECONDS));
        assertTrue(monitor.invalidated());
        assertEquals(WindowSafety.Failure.GEOMETRY, windows.check(binding));
        assertThrows(
            IllegalStateException.class,
            () -> new WindowRegionCapture(windows, Clock.systemUTC()).capture(binding));
      }

      TargetBinding moved = windows.bind(handle, new LogicalRect(40, 40, 160, 100));
      var awt =
          new CoordinateMapper()
              .toAwt(moved.calibratedRegion(), moved.calibratedWindow().display());
      SwingUtilities.invokeAndWait(
          () -> {
            obstruction.setBounds(awt.x(), awt.y(), awt.width(), awt.height());
            obstruction.setAlwaysOnTop(true);
            obstruction.setBackground(Color.RED);
            obstruction.setVisible(true);
            obstruction.toFront();
          });
      await(() -> windows.isOccluded(handle, moved.calibratedRegion()));
      assertNotEquals(WindowSafety.Failure.NONE, windows.check(moved));
      SwingUtilities.invokeAndWait(
          () -> {
            obstruction.setVisible(false);
            frame.setState(Frame.ICONIFIED);
          });
      await(() -> windows.check(moved) != WindowSafety.Failure.NONE);
      assertThrows(
          IllegalStateException.class,
          () -> new WindowRegionCapture(windows, Clock.systemUTC()).capture(moved));
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            obstruction.dispose();
            frame.dispose();
          });
    }
  }

  @Test
  @Order(1)
  void realGlobalRegistrationDetectsConflictsAndReleasesBothHotkeys() {
    try (WindowsGlobalHotkeys first = new WindowsGlobalHotkeys(event -> {})) {
      assertTrue(first.available());
      assertThrows(IllegalStateException.class, () -> new WindowsGlobalHotkeys(event -> {}));
      assertTrue(first.available());
    }
    try (WindowsGlobalHotkeys registeredAgain = new WindowsGlobalHotkeys(event -> {})) {
      assertTrue(registeredAgain.available());
    }
  }

  @Test
  @Order(3)
  void uncoveredAnswerDoesNotAuthorizeAnObscuredScoreOrSubmitControl() throws Exception {
    JFrame frame = new JFrame("DeskGrade control occlusion target " + UUID.randomUUID());
    JWindow obstruction = new JWindow(frame);
    WindowsWindowService windows = new WindowsWindowService();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            frame.setBounds(80, 80, 500, 400);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
          });
      long hwnd =
          windows.listWindows().stream()
              .filter(choice -> choice.title().equals(frame.getTitle()))
              .findFirst()
              .orElseThrow()
              .identity()
              .hwnd();
      TargetBinding binding = windows.bind(hwnd, new LogicalRect(20, 20, 100, 60));
      awaitBound(windows, binding);
      LogicalRect control = new LogicalRect(20, 220, 160, 44);
      var physical = new CoordinateMapper().toScreen(control, binding.calibratedWindow());
      var awt = new CoordinateMapper().toAwtExact(physical, binding.calibratedWindow().display());
      SwingUtilities.invokeAndWait(
          () -> {
            obstruction.setFocusableWindowState(false);
            obstruction.setBounds(awt.x(), awt.y(), awt.width(), awt.height());
            obstruction.setAlwaysOnTop(true);
            obstruction.setVisible(true);
          });
      await(() -> windows.isOccluded(hwnd, physical));
      assertEquals(WindowSafety.Failure.NONE, windows.check(binding));
      var authorizations = new java.util.concurrent.atomic.AtomicInteger();
      WindowsInputDriver input = new WindowsInputDriver(windows);
      assertThrows(
          IllegalStateException.class,
          () -> input.click(binding, control, authorizations::incrementAndGet));
      assertThrows(
          IllegalStateException.class,
          () ->
              input.fillNumber(
                  binding, control, java.math.BigDecimal.ONE, authorizations::incrementAndGet));
      assertEquals(
          0,
          authorizations.get(),
          "Occluded controls must be rejected before requesting native input permission");
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            obstruction.dispose();
            frame.dispose();
          });
    }
  }

  @Test
  @Order(2)
  void pauseAndStopHotkeysDeliverWhileSyntheticWindowIsForeground() throws Exception {
    JFrame frame = new JFrame("DeskGrade hotkey synthetic target " + UUID.randomUUID());
    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch stopped = new CountDownLatch(1);
    try (WindowsGlobalHotkeys hotkeys =
        new WindowsGlobalHotkeys(
            event -> {
              if (event == WindowsGlobalHotkeys.Event.PAUSE_RESUME) paused.countDown();
              if (event == WindowsGlobalHotkeys.Event.STOP) stopped.countDown();
            })) {
      SwingUtilities.invokeAndWait(
          () -> {
            frame.setBounds(80, 80, 400, 240);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
          });
      WindowsWindowService windows = new WindowsWindowService();
      long hwnd =
          windows.listWindows().stream()
              .filter(choice -> choice.title().equals(frame.getTitle()))
              .findFirst()
              .orElseThrow()
              .identity()
              .hwnd();
      TargetBinding binding = windows.bind(hwnd, new LogicalRect(20, 20, 80, 60));
      awaitBound(windows, binding);
      assertTrue(hotkeys.available());
      sendTestHotkey(0x77);
      assertTrue(paused.await(1, TimeUnit.SECONDS));
      assertEquals(WindowSafety.Failure.NONE, windows.check(binding));
      sendTestHotkey(0x78);
      assertTrue(stopped.await(1, TimeUnit.SECONDS));
    } finally {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  /** Test stimulus only; registered chords are consumed by our native message loop. */
  private static void sendTestHotkey(int virtualKey) {
    var events =
        (com.sun.jna.platform.win32.WinUser.INPUT[])
            new com.sun.jna.platform.win32.WinUser.INPUT().toArray(6);
    int[] keys = {0x11, 0x12, virtualKey, virtualKey, 0x12, 0x11};
    for (int i = 0; i < events.length; i++) {
      events[i].type = new com.sun.jna.platform.win32.WinDef.DWORD(1);
      events[i].input.setType("ki");
      events[i].input.ki.wVk = new com.sun.jna.platform.win32.WinDef.WORD(keys[i]);
      events[i].input.ki.dwFlags = new com.sun.jna.platform.win32.WinDef.DWORD(i < 3 ? 0 : 2);
    }
    assertEquals(
        events.length,
        WindowsNative.user()
            .SendInput(
                new com.sun.jna.platform.win32.WinDef.DWORD(events.length),
                events,
                events[0].size())
            .intValue());
  }

  private static void await(BooleanSupplier check) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
    assertTrue(check.getAsBoolean(), "Synthetic window did not reach expected state.");
  }

  private static void awaitBound(WindowsWindowService windows, TargetBinding binding)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!windows.activateBoundWindow(binding) && System.nanoTime() < deadline) Thread.sleep(25);
    assertEquals(
        WindowSafety.Failure.NONE,
        windows.check(binding),
        () ->
            "Synthetic target binding="
                + binding
                + " current="
                + windows.snapshot(binding.calibratedWindow().identity().hwnd()));
  }
}
