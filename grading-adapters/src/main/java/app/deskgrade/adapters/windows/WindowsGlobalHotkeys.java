package app.deskgrade.adapters.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.MSG;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Ctrl+Alt+F8 toggles pause; Ctrl+Alt+F9 stops. Registration conflicts fail closed. */
public final class WindowsGlobalHotkeys implements AutoCloseable {
  public enum Event {
    PAUSE_RESUME,
    STOP,
    UNAVAILABLE
  }

  private static final int PAUSE_ID = 0x4D1;
  private static final int STOP_ID = 0x4D2;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean available = new AtomicBoolean();
  private final AtomicBoolean pausePending = new AtomicBoolean();
  private final AtomicBoolean stopPending = new AtomicBoolean();
  private final Consumer<Event> callback;
  private final Thread thread;
  private volatile int nativeThread;

  public WindowsGlobalHotkeys(Consumer<Event> callback) {
    WindowsNative.requireWindows();
    this.callback = Objects.requireNonNull(callback);
    CompletableFuture<Boolean> ready = new CompletableFuture<>();
    thread = new Thread(() -> messageLoop(ready), "deskgrade-global-hotkeys");
    thread.setDaemon(true);
    thread.start();
    try {
      if (!ready.get(5, TimeUnit.SECONDS))
        throw new IllegalStateException(
            "Global hotkey registration failed or conflicts with another application.");
    } catch (Exception failure) {
      close();
      throw new IllegalStateException(
          "Global hotkey registration failed or conflicts with another application.");
    }
  }

  public boolean available() {
    return available.get() && !closed.get() && thread.isAlive();
  }

  private void messageLoop(CompletableFuture<Boolean> ready) {
    boolean pause = false;
    boolean stop = false;
    try {
      var user = WindowsNative.user();
      nativeThread = Kernel32.INSTANCE.GetCurrentThreadId();
      MSG message = new MSG();
      user.PeekMessage(message, null, 0, 0, 0); // Establish this thread's message queue.
      pause = user.RegisterHotKey(null, PAUSE_ID, 0x4003, 0x77);
      stop = pause && user.RegisterHotKey(null, STOP_ID, 0x4003, 0x78);
      available.set(pause && stop);
      ready.complete(available.get());
      if (!available.get() || closed.get()) return;
      int result;
      while (!closed.get() && (result = user.GetMessage(message, null, 0, 0)) != 0) {
        if (result < 0) break;
        if (message.message == 0x0312) {
          if (message.wParam.intValue() == STOP_ID) dispatch(Event.STOP, stopPending);
          else if (message.wParam.intValue() == PAUSE_ID)
            dispatch(Event.PAUSE_RESUME, pausePending);
        }
      }
    } catch (RuntimeException | LinkageError ignored) {
      ready.complete(false);
    } finally {
      available.set(false);
      if (stop) WindowsNative.user().UnregisterHotKey(null, STOP_ID);
      if (pause) WindowsNative.user().UnregisterHotKey(null, PAUSE_ID);
      if (!closed.get() && ready.isDone()) dispatch(Event.UNAVAILABLE, stopPending);
    }
  }

  private void dispatch(Event event, AtomicBoolean pending) {
    if (!pending.compareAndSet(false, true)) return;
    // Independent stop dispatch cannot wait behind a blocked pause callback or UI thread.
    Thread.ofVirtual()
        .name("deskgrade-hotkey-" + event.name())
        .start(
            () -> {
              try {
                callback.accept(event);
              } finally {
                pending.set(false);
              }
            });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    available.set(false);
    if (nativeThread != 0)
      WindowsNative.user().PostThreadMessage(nativeThread, 0x0012, new WPARAM(0), new LPARAM(0));
    if (Thread.currentThread() != thread) {
      try {
        thread.join(2000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
