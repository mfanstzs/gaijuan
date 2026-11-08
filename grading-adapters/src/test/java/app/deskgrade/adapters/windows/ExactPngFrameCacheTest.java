package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.desktop.*;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExactPngFrameCacheTest {
  @Test
  void oneHundredFortyExactFreshFramesEncodeOnceAndOneChangedPixelEncodesAgain() throws Exception {
    var cache = new ExactPngFrameCache();
    var target = binding(101, 0);
    var counts = new Counts();
    byte[] original = encode(image(8, 5, BufferedImage.TYPE_INT_RGB, 0xffe5f5e9));
    for (int repeat = 0; repeat < 140; repeat++) {
      // Each invocation receives a newly created image, never the earlier capture object.
      assertArrayEquals(
          original,
          capture(cache, target, image(8, 5, BufferedImage.TYPE_INT_RGB, 0xffe5f5e9), counts));
    }
    assertEquals(1, counts.encodes);
    assertEquals(139, counts.reuses);
    var changed = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xffe5f5e9);
    changed.setRGB(7, 4, 0xffe5f5e8);
    byte[] changedPng = capture(cache, target, changed, counts);
    assertArrayEquals(encode(changed), changedPng);
    assertFalse(Arrays.equals(original, changedPng));
    assertEquals(2, counts.encodes);
    assertEquals(139, counts.reuses);
    // This is a single entry, not a history of frames: returning to A after B must encode A.
    assertArrayEquals(
        original,
        capture(cache, target, image(8, 5, BufferedImage.TYPE_INT_RGB, 0xffe5f5e9), counts));
    assertEquals(3, counts.encodes);
  }

  @Test
  void anyAlphaBitChangeIsAMissEvenWhenRgbChannelsAreIdentical() throws Exception {
    var cache = new ExactPngFrameCache();
    var target = binding(101, 0);
    var counts = new Counts();
    var first = image(8, 5, BufferedImage.TYPE_INT_ARGB, 0x7f123456);
    capture(cache, target, first, counts);
    assertArrayEquals(encode(first), capture(cache, target, first, counts));
    var changed = image(8, 5, BufferedImage.TYPE_INT_ARGB, 0x7f123456);
    changed.setRGB(0, 0, 0x7e123456);
    assertArrayEquals(encode(changed), capture(cache, target, changed, counts));
    assertEquals(2, counts.encodes);
    assertEquals(1, counts.reuses);
  }

  @Test
  void dimensionsAndCompleteWindowOrRegionBindingMustMatch() throws Exception {
    var cache = new ExactPngFrameCache();
    var counts = new Counts();
    var target = binding(101, 0);
    capture(cache, target, image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456), counts);
    assertFalse(
        cache.prepare(target, image(5, 8, BufferedImage.TYPE_INT_RGB, 0xff123456)).canReuse());
    assertFalse(
        cache
            .prepare(binding(102, 0), image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456))
            .canReuse());
    assertFalse(
        cache
            .prepare(binding(101, 1), image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456))
            .canReuse());
    var old = target.calibratedWindow();
    var anotherProcess =
        new WindowSnapshot(
            new WindowIdentity(101, 999, Instant.EPOCH.plusSeconds(1)),
            old.title(),
            old.windowClass(),
            old.windowBounds(),
            old.clientBounds(),
            old.display(),
            old.dpi(),
            old.foreground(),
            old.visible(),
            old.minimized(),
            old.cloaked(),
            old.interactive());
    assertFalse(
        cache
            .prepare(
                new TargetBinding(anotherProcess, target.answerRegion(), target.calibratedRegion()),
                image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456))
            .canReuse());
    assertEquals(1, counts.encodes);
  }

  @Test
  void sameVisibleRgbWithDifferentImageTypeMustNotReuseEncodedRepresentation() throws Exception {
    var cache = new ExactPngFrameCache();
    var counts = new Counts();
    var target = binding(101, 0);
    var rgb = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
    var argb = image(8, 5, BufferedImage.TYPE_INT_ARGB, 0xff123456);
    assertEquals(rgb.getRGB(0, 0), argb.getRGB(0, 0));
    capture(cache, target, rgb, counts);
    assertArrayEquals(encode(argb), capture(cache, target, argb, counts));
    assertArrayEquals(encode(rgb), capture(cache, target, rgb, counts));
    assertEquals(3, counts.encodes);
    assertEquals(0, counts.reuses);
  }

  @Test
  void unsupportedBytePalettePremultipliedAndCustomColorImagesUseOriginalEncoder()
      throws Exception {
    var linear =
        new DirectColorModel(
            ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
            24,
            0xff0000,
            0xff00,
            0xff,
            0,
            false,
            DataBuffer.TYPE_INT);
    var custom =
        new BufferedImage(linear, linear.createCompatibleWritableRaster(8, 5), false, null);
    assertEquals(Transparency.OPAQUE, custom.getTransparency());
    var images =
        new BufferedImage[] {
          image(8, 5, BufferedImage.TYPE_3BYTE_BGR, 0xff123456),
          image(8, 5, BufferedImage.TYPE_BYTE_INDEXED, 0xff123456),
          image(8, 5, BufferedImage.TYPE_INT_ARGB_PRE, 0x7f123456),
          custom
        };
    for (var frame : images) {
      var cache = new ExactPngFrameCache();
      var counts = new Counts();
      for (int repeat = 0; repeat < 2; repeat++)
        assertArrayEquals(encode(frame), capture(cache, binding(101, 0), frame, counts));
      assertEquals(2, counts.encodes);
      assertEquals(0, counts.reuses);
    }
  }

  @Test
  void subimagesNonPackedStrideOffsetsAndPropertiesAreConservativelyBypassed() throws Exception {
    var standard = image(9, 6, BufferedImage.TYPE_INT_RGB, 0xff123456);
    var color = (DirectColorModel) standard.getColorModel();
    int[] masks = {0xff0000, 0xff00, 0xff};
    var padded = new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT, 8, 5, 9, masks);
    var strideImage =
        new BufferedImage(
            color,
            Raster.createWritableRaster(padded, new DataBufferInt(45), new Point()),
            false,
            null);
    var offsetBuffer = new DataBufferInt(new int[41], 40, 1);
    var offsetImage =
        new BufferedImage(
            color,
            Raster.createPackedRaster(offsetBuffer, 8, 5, 8, masks, new Point()),
            false,
            null);
    var properties = new Hashtable<String, Object>();
    properties.put("synthetic", "metadata");
    var withProperties =
        new BufferedImage(color, color.createCompatibleWritableRaster(8, 5), false, properties);
    var subclass = new BufferedImage(8, 5, BufferedImage.TYPE_INT_RGB) {};
    for (var frame :
        new BufferedImage[] {
          standard.getSubimage(0, 0, 8, 5),
          standard.getSubimage(1, 1, 8, 5),
          strideImage,
          offsetImage,
          withProperties,
          subclass
        }) {
      var cache = new ExactPngFrameCache();
      var counts = new Counts();
      for (int repeat = 0; repeat < 2; repeat++)
        assertArrayEquals(encode(frame), capture(cache, binding(101, 0), frame, counts));
      assertEquals(2, counts.encodes);
      assertEquals(0, counts.reuses);
    }
  }

  @Test
  void sourcePixelsInputPngAndReturnedPngCannotMutateTheCommittedEntry() throws Exception {
    var cache = new ExactPngFrameCache();
    var target = binding(101, 0);
    var frame = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
    var prepared = cache.prepare(target, frame);
    byte[] png = encode(frame);
    byte[] expected = png.clone();
    frame.setRGB(0, 0, 0xff654321);
    cache.commit(prepared, png);
    Arrays.fill(png, (byte) 0);
    var freshA = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
    var matching = cache.prepare(target, freshA);
    assertTrue(matching.canReuse());
    byte[] externallyChanged = matching.reusablePng();
    assertArrayEquals(expected, externallyChanged);
    Arrays.fill(externallyChanged, (byte) 0);
    assertArrayEquals(expected, cache.prepare(target, freshA).reusablePng());
    assertFalse(cache.prepare(target, frame).canReuse());
  }

  @Test
  void preparationOrFailedEncodingAndFailedScopeNeverReplacesTheLastGoodEntry() throws Exception {
    var cache = new ExactPngFrameCache();
    var target = binding(101, 0);
    var counts = new Counts();
    var a = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
    byte[] expected = capture(cache, target, a, counts);
    var b = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff654321);
    assertFalse(cache.prepare(target, b).canReuse());
    // The same prepare/encode/scope/commit order as WindowRegionCapture, without native capture.
    var original = new IOException("Synthetic encoder failure");
    assertSame(
        original,
        assertThrows(
            IOException.class,
            () ->
                capture(
                    cache,
                    target,
                    b,
                    counts,
                    ignored -> {
                      throw original;
                    },
                    () -> {})));
    assertArrayEquals(expected, cache.prepare(target, a).reusablePng());
    assertFalse(cache.prepare(target, b).canReuse());
    var closeFailure = new IllegalStateException("Synthetic scope failure");
    assertSame(
        closeFailure,
        assertThrows(
            IllegalStateException.class,
            () ->
                capture(
                    cache,
                    target,
                    b,
                    counts,
                    ExactPngFrameCacheTest::encode,
                    () -> {
                      throw closeFailure;
                    })));
    assertArrayEquals(expected, cache.prepare(target, a).reusablePng());
    assertFalse(cache.prepare(target, b).canReuse());
  }

  @Test
  void oversizedPixelsAndOversizedPngAreNeverRetained() throws Exception {
    var cache = new ExactPngFrameCache();
    var target = binding(101, 0);
    var large = new BufferedImage(2001, 2000, BufferedImage.TYPE_INT_RGB);
    var skipped = cache.prepare(target, large);
    assertFalse(skipped.canReuse());
    cache.commit(skipped, new byte[] {1, 2, 3});
    assertFalse(cache.prepare(target, large).canReuse());
    var small = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
    cache.commit(cache.prepare(target, small), new byte[ExactPngFrameCache.MAX_PNG_BYTES + 1]);
    assertFalse(cache.prepare(target, small).canReuse());
    var counts = new Counts();
    byte[] expected = capture(cache, target, small, counts);
    assertArrayEquals(expected, cache.prepare(target, small).reusablePng());
  }

  @Test
  @Timeout(15)
  void concurrentTargetsAndCommitOrderCanOnlyChangeHitRateNeverPngAssociation() throws Exception {
    var cache = new ExactPngFrameCache();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first =
          pool.submit(
              () -> {
                var counts = new Counts();
                var frame = image(8, 5, BufferedImage.TYPE_INT_RGB, 0xff123456);
                byte[] expected = encode(frame);
                for (int repeat = 0; repeat < 200; repeat++)
                  assertArrayEquals(expected, capture(cache, binding(101, 0), frame, counts));
                return null;
              });
      var second =
          pool.submit(
              () -> {
                var counts = new Counts();
                var frame = image(8, 5, BufferedImage.TYPE_INT_ARGB, 0x7f654321);
                byte[] expected = encode(frame);
                for (int repeat = 0; repeat < 200; repeat++)
                  assertArrayEquals(expected, capture(cache, binding(102, 1), frame, counts));
                return null;
              });
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  private static byte[] capture(
      ExactPngFrameCache cache, TargetBinding target, BufferedImage frame, Counts counts)
      throws IOException {
    return capture(cache, target, frame, counts, ExactPngFrameCacheTest::encode, () -> {});
  }

  private static byte[] capture(
      ExactPngFrameCache cache,
      TargetBinding target,
      BufferedImage frame,
      Counts counts,
      Encoder encoder,
      WindowRegionCapture.CaptureScope scope)
      throws IOException {
    var prepared = cache.prepare(target, frame);
    byte[] png;
    try (scope) {
      if (prepared.canReuse()) {
        counts.reuses++;
        png = prepared.reusablePng();
      } else {
        counts.encodes++;
        png = encoder.encode(frame);
      }
    }
    cache.commit(prepared, png);
    return png;
  }

  private static byte[] encode(BufferedImage image) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(ImageIO.write(image, "png", output));
    }
    return bytes.toByteArray();
  }

  private static BufferedImage image(int width, int height, int type, int argb) {
    var image = new BufferedImage(width, height, type);
    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
    return image;
  }

  private static TargetBinding binding(long handle, int offset) {
    var bounds = new PixelRect(0, 0, 1000, 700);
    var window =
        new WindowSnapshot(
            new WindowIdentity(handle, 201, Instant.EPOCH),
            "Synthetic target",
            "SyntheticWindow",
            bounds,
            bounds,
            new DisplaySnapshot("test", bounds, bounds, 1, 1),
            96,
            true,
            true,
            false,
            false,
            true);
    return new TargetBinding(
        window, new LogicalRect(20 + offset, 100, 8, 5), new PixelRect(20 + offset, 100, 8, 5));
  }

  private static final class Counts {
    private int encodes;
    private int reuses;
  }

  @FunctionalInterface
  private interface Encoder {
    byte[] encode(BufferedImage image) throws IOException;
  }
}
