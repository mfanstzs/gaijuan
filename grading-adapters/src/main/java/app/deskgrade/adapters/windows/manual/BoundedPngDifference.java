package app.deskgrade.adapters.windows.manual;

import app.deskgrade.core.application.session.ScreenChangeDiagnostic.ChangeScope;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Failure-only PNG comparison. Input is decoded in memory after bounded header inspection; no
 * image, path, coordinates, digest or text leaves this helper.
 */
final class BoundedPngDifference {
  static final int MAX_PNG_BYTES = 8 * 1024 * 1024;
  static final long MAX_PIXELS = 4_000_000;

  private BoundedPngDifference() {}

  static Result compare(byte[] baseline, byte[] observed) {
    try {
      Decoded first = decode(baseline);
      Decoded second = decode(observed);
      if (first == null || second == null) return Result.unavailable();
      if (first.width() != second.width() || first.height() != second.height())
        return Result.dimensionsChanged();

      int width = first.width();
      int height = first.height();
      long total = (long) width * height;
      long changed = 0;
      int minX = width;
      int minY = height;
      int maxX = -1;
      int maxY = -1;
      int edgeBand = Math.max(1, Math.min(8, Math.min(width, height) / 50));
      boolean edgeOnly = true;
      int[] firstRow = new int[width];
      int[] secondRow = new int[width];
      for (int y = 0; y < height; y++) {
        first.image().getRGB(0, y, width, 1, firstRow, 0, width);
        second.image().getRGB(0, y, width, 1, secondRow, 0, width);
        for (int x = 0; x < width; x++) {
          if (firstRow[x] == secondRow[x]) continue;
          changed++;
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
          if (x >= edgeBand && x < width - edgeBand && y >= edgeBand && y < height - edgeBand)
            edgeOnly = false;
        }
      }
      if (changed == 0) return new Result(0, total, ChangeScope.NO_PIXEL_CHANGE);
      if (edgeOnly) return new Result(changed, total, ChangeScope.EDGE_ONLY);
      long changedBounds = (long) (maxX - minX + 1) * (maxY - minY + 1);
      ChangeScope scope =
          changed * 5 >= total || changedBounds * 2 >= total
              ? ChangeScope.BROAD
              : ChangeScope.LOCALIZED;
      return new Result(changed, total, scope);
    } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
      return Result.unavailable();
    }
  }

  private static Decoded decode(byte[] png) {
    if (png == null || png.length == 0 || png.length > MAX_PNG_BYTES) return null;
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
      Iterator<ImageReader> candidates = ImageIO.getImageReaders(input);
      if (!candidates.hasNext()) return null;
      ImageReader reader = candidates.next();
      try {
        if (!"png".equalsIgnoreCase(reader.getFormatName())) return null;
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        long pixels = (long) width * height;
        if (width < 1 || height < 1 || pixels > MAX_PIXELS) return null;
        ImageReadParam parameters = reader.getDefaultReadParam();
        parameters.setSourceRegion(new Rectangle(0, 0, width, height));
        BufferedImage image = reader.read(0, parameters);
        if (image == null || image.getWidth() != width || image.getHeight() != height) return null;
        return new Decoded(width, height, image);
      } finally {
        reader.dispose();
      }
    } catch (Exception unavailable) {
      return null;
    }
  }

  record Result(long changedPixels, long totalPixels, ChangeScope changeScope) {
    private static Result dimensionsChanged() {
      return new Result(-1, -1, ChangeScope.DIMENSIONS_CHANGED);
    }

    private static Result unavailable() {
      return new Result(-1, -1, ChangeScope.UNAVAILABLE);
    }
  }

  private record Decoded(int width, int height, BufferedImage image) {}
}
