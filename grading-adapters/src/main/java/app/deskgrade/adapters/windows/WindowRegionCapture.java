package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.PixelRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Captures only the explicitly selected region. The selection overlay must already be hidden.
 * Window visibility does not prove that application content is ready; the platform owns that check.
 */
public final class WindowRegionCapture {
  @FunctionalInterface
  public interface CaptureTimingObserver {
    CaptureTimingObserver NONE = timing -> {};

    void onCapture(CaptureTiming timing);
  }

  private volatile CaptureTimingObserver timingObserver = CaptureTimingObserver.NONE;

  /** Observer runs on the capture caller thread after the scope closes; it must not perform I/O. */
  public void setTimingObserver(CaptureTimingObserver observer) {
    timingObserver = Objects.requireNonNull(observer);
  }

  @FunctionalInterface
  public interface CaptureScope extends AutoCloseable {
    @Override
    void close();
  }

  @FunctionalInterface
  public interface CaptureBoundary {
    CaptureScope beforeCapture(TargetBinding binding);
  }

  private static final CaptureScope NO_DECORATION = () -> {};
  private volatile CaptureBoundary captureBoundary = binding -> NO_DECORATION;

  /** Trusted local UI only; decorations must be absent for the complete raw capture scope. */
  public void setCaptureBoundary(CaptureBoundary boundary) {
    captureBoundary = Objects.requireNonNull(boundary);
  }

  public static void flushDesktopComposition() {
    WindowsNative.requireWindows();
    if (WindowsNative.dwm().DwmFlush() != 0)
      throw new IllegalStateException("Desktop composition could not be synchronized.");
  }

  public record PngCapture(byte[] png, int width, int height, String sha256, Instant capturedAt) {
    public PngCapture {
      png = png.clone();
    }

    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  private final WindowsWindowService windows;
  private final CoordinateMapper mapper = new CoordinateMapper();
  private final Clock clock;
  private final ExactPngFrameCache pngCache = new ExactPngFrameCache();

  private record PendingCapture(
      PngCapture result, ExactPngFrameCache.Prepared prepared, byte[] encodedPng) {}

  public WindowRegionCapture(WindowsWindowService windows, Clock clock) {
    this.windows = Objects.requireNonNull(windows);
    this.clock = Objects.requireNonNull(clock);
  }

  public PngCapture capture(TargetBinding binding) {
    WindowsNative.requireWindows();
    CaptureTimingObserver observer = timingObserver;
    if (observer != CaptureTimingObserver.NONE) return captureObserved(binding, observer);
    PendingCapture pending;
    try (var ignored = Objects.requireNonNull(captureBoundary.beforeCapture(binding))) {
      pending = captureUndecorated(binding, null);
    }
    // A close/fence failure must never publish an otherwise unverified frame into the memo.
    pngCache.commit(pending.prepared(), pending.encodedPng());
    return pending.result();
  }

  private PngCapture captureObserved(TargetBinding binding, CaptureTimingObserver observer) {
    var timing = new CaptureTimingCollector(System::nanoTime);
    boolean successful = false;
    try {
      timing.begin(CaptureTiming.Phase.BOUNDARY_PREPARE);
      CaptureScope original = Objects.requireNonNull(captureBoundary.beforeCapture(binding));
      timing.end();
      PendingCapture pending;
      try (CaptureScope ignored =
          () -> {
            timing.begin(CaptureTiming.Phase.BOUNDARY_CLOSE);
            try {
              original.close();
            } finally {
              timing.end();
            }
          }) {
        pending = captureUndecorated(binding, timing);
      }
      pngCache.commit(pending.prepared(), pending.encodedPng());
      successful = true;
      return pending.result();
    } finally {
      publishTiming(observer, timing.finish(successful));
    }
  }

  static void publishTiming(CaptureTimingObserver observer, CaptureTiming timing) {
    try {
      observer.onCapture(timing);
    } catch (RuntimeException | LinkageError ignored) {
      // Performance diagnostics cannot change a capture result, its original exception, or scope.
    }
  }

  private PendingCapture captureUndecorated(TargetBinding binding, CaptureTimingCollector timing) {
    WindowsNative.requireWindows();
    if (timing != null) timing.begin(CaptureTiming.Phase.SAFETY_BEFORE);
    requireSafe(binding);
    if (timing != null) timing.end();
    PixelRect region = binding.calibratedRegion();
    if ((long) region.width() * region.height() > 20_000_000)
      throw new IllegalArgumentException("Selected region is too large.");
    var display = binding.calibratedWindow().display();
    // Never silently expand a selection or resample student strokes to conceal calibration errors.
    PixelRect awt = mapper.toAwtExact(region, display);
    try {
      if (timing != null) timing.begin(CaptureTiming.Phase.RAW_CAPTURE);
      Robot robot =
          new Robot(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0]);
      var variants =
          robot.createMultiResolutionScreenCapture(
              new Rectangle(awt.x(), awt.y(), awt.width(), awt.height()));
      Image image =
          variants.getResolutionVariants().stream()
              .filter(
                  candidate ->
                      candidate.getWidth(null) == region.width()
                          && candidate.getHeight(null) == region.height())
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("Native capture size differs from calibration."));
      if (!(image instanceof BufferedImage buffered))
        throw new IllegalStateException("Capture image unavailable.");
      if (timing != null) timing.end();
      if (timing != null) timing.begin(CaptureTiming.Phase.SAFETY_AFTER);
      requireSafe(binding);
      if (timing != null) timing.end();
      if (timing != null) timing.begin(CaptureTiming.Phase.PIXEL_COMPARE);
      var prepared = pngCache.prepare(binding, buffered);
      if (timing != null) timing.end();
      byte[] png;
      if (prepared.canReuse()) {
        if (timing != null) timing.begin(CaptureTiming.Phase.PNG_REUSE);
        png = prepared.reusablePng();
      } else {
        if (timing != null) timing.begin(CaptureTiming.Phase.PNG_ENCODE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
          if (!ImageIO.write(buffered, "png", output))
            throw new IllegalStateException("PNG encoder unavailable.");
        }
        png = bytes.toByteArray();
      }
      if (timing != null) timing.end();
      if (timing != null) timing.begin(CaptureTiming.Phase.DIGEST_AND_RESULT);
      PngCapture result =
          new PngCapture(
              png,
              region.width(),
              region.height(),
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)),
              clock.instant());
      if (timing != null) timing.end();
      return new PendingCapture(result, prepared, png);
    } catch (Exception failure) {
      throw new IllegalStateException("Selected region capture could not be verified.");
    }
  }

  private void requireSafe(TargetBinding binding) {
    if (windows.check(binding) != WindowSafety.Failure.NONE)
      throw new IllegalStateException("Target window is unsafe for capture.");
  }
}
