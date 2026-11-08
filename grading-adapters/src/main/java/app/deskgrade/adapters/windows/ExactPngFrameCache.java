package app.deskgrade.adapters.windows;

import app.deskgrade.core.domain.desktop.TargetBinding;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.Arrays;
import java.util.Objects;

/**
 * One bounded, immutable PNG encoding memo. This never supplies pixels for capture: callers must
 * acquire and verify a fresh image first, then commit only after their complete capture scope
 * closes. Unknown representations are deliberately not canonicalized or compared through lossy
 * getRGB.
 */
final class ExactPngFrameCache {
  static final int MAX_PIXELS = 4_000_000;
  static final int MAX_PNG_BYTES = 16 * 1024 * 1024;
  private volatile Entry last;

  Prepared prepare(TargetBinding binding, BufferedImage image) {
    Objects.requireNonNull(binding);
    Objects.requireNonNull(image);
    if (!eligible(image)) return Prepared.BYPASS;
    var raster = image.getRaster();
    // getDataElements returns a detached int[] in this supported representation. It preserves every
    // raw channel/alpha bit instead of converting color spaces or truncating to an ARGB view.
    int[] pixels = (int[]) raster.getDataElements(0, 0, image.getWidth(), image.getHeight(), null);
    var shape =
        new Shape(
            image.getWidth(),
            image.getHeight(),
            image.getType(),
            image.isAlphaPremultiplied(),
            image.getColorModel(),
            (SinglePixelPackedSampleModel) raster.getSampleModel());
    Entry previous = last;
    boolean matches =
        previous != null
            && previous.binding.equals(binding)
            && previous.shape.equals(shape)
            && Arrays.equals(previous.pixels, pixels);
    return new Prepared(binding, shape, pixels, matches ? previous : null);
  }

  /** No preparation or successful encoding alone authorizes a cache entry. */
  void commit(Prepared prepared, byte[] png) {
    Objects.requireNonNull(prepared);
    Objects.requireNonNull(png);
    if (prepared == Prepared.BYPASS || prepared.reusable != null || png.length > MAX_PNG_BYTES)
      return;
    last = new Entry(prepared.binding, prepared.shape, prepared.pixels, png.clone());
  }

  private static boolean eligible(BufferedImage image) {
    int type = image.getType();
    if (image.getClass() != BufferedImage.class
        || type != BufferedImage.TYPE_INT_RGB && type != BufferedImage.TYPE_INT_ARGB
        || (long) image.getWidth() * image.getHeight() > MAX_PIXELS
        || image.getColorModel().getClass() != DirectColorModel.class
        || image.getColorModel().getColorSpace() != ColorSpace.getInstance(ColorSpace.CS_sRGB)
        || image.getPropertyNames() != null) return false;
    var raster = image.getRaster();
    if (raster.getDataBuffer().getClass() != DataBufferInt.class
        || raster.getDataBuffer().getNumBanks() != 1
        || raster.getDataBuffer().getOffset() != 0
        || raster.getParent() != null
        || raster.getSampleModel().getClass() != SinglePixelPackedSampleModel.class
        || raster.getTransferType() != DataBuffer.TYPE_INT
        || raster.getNumDataElements() != 1
        || raster.getSampleModelTranslateX() != 0
        || raster.getSampleModelTranslateY() != 0) return false;
    var samples = (SinglePixelPackedSampleModel) raster.getSampleModel();
    return samples.getWidth() == image.getWidth()
        && samples.getHeight() == image.getHeight()
        && samples.getScanlineStride() == image.getWidth();
  }

  static final class Prepared {
    private static final Prepared BYPASS = new Prepared(null, null, null, null);
    private final TargetBinding binding;
    private final Shape shape;
    private final int[] pixels;
    private final Entry reusable;

    private Prepared(TargetBinding binding, Shape shape, int[] pixels, Entry reusable) {
      this.binding = binding;
      this.shape = shape;
      this.pixels = pixels;
      this.reusable = reusable;
    }

    boolean canReuse() {
      return reusable != null;
    }

    byte[] reusablePng() {
      if (reusable == null) throw new IllegalStateException("A fresh PNG encoding is required.");
      return reusable.png.clone();
    }
  }

  private record Shape(
      int width,
      int height,
      int imageType,
      boolean alphaPremultiplied,
      ColorModel colorModel,
      SinglePixelPackedSampleModel sampleModel) {}

  private static final class Entry {
    private final TargetBinding binding;
    private final Shape shape;
    private final int[] pixels;
    private final byte[] png;

    private Entry(TargetBinding binding, Shape shape, int[] pixels, byte[] png) {
      this.binding = binding;
      this.shape = shape;
      this.pixels = pixels;
      this.png = png;
    }
  }
}
