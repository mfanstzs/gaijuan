package app.deskgrade.adapters.windows.manual;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.ScreenChangeDiagnostic.ChangeScope;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BoundedPngDifferenceTest {
  private static final int WHITE = 0xffffffff;
  private static final int BLACK = 0xff000000;

  @Test
  void reportsNoPixelChangeForEquivalentPixels() throws Exception {
    byte[] first = png(20, 12, (x, y) -> WHITE);
    byte[] second = png(20, 12, (x, y) -> WHITE);

    var result = BoundedPngDifference.compare(first, second);

    assertEquals(0, result.changedPixels());
    assertEquals(240, result.totalPixels());
    assertEquals(ChangeScope.NO_PIXEL_CHANGE, result.changeScope());
  }

  @Test
  void distinguishesEdgeLocalizedAndBroadChanges() throws Exception {
    byte[] baseline = png(100, 80, (x, y) -> WHITE);

    var edge =
        BoundedPngDifference.compare(
            baseline, png(100, 80, (x, y) -> x == 0 && y >= 10 && y < 70 ? BLACK : WHITE));
    assertEquals(60, edge.changedPixels());
    assertEquals(ChangeScope.EDGE_ONLY, edge.changeScope());

    var localized =
        BoundedPngDifference.compare(
            baseline,
            png(100, 80, (x, y) -> x >= 45 && x < 55 && y >= 35 && y < 45 ? BLACK : WHITE));
    assertEquals(100, localized.changedPixels());
    assertEquals(ChangeScope.LOCALIZED, localized.changeScope());

    var broad =
        BoundedPngDifference.compare(baseline, png(100, 80, (x, y) -> x < 50 ? BLACK : WHITE));
    assertEquals(4_000, broad.changedPixels());
    assertEquals(ChangeScope.BROAD, broad.changeScope());
  }

  @Test
  void dimensionMismatchAndInvalidOrOversizedInputStayBounded() throws Exception {
    byte[] baseline = png(10, 10, (x, y) -> WHITE);
    var dimensions = BoundedPngDifference.compare(baseline, png(11, 10, (x, y) -> WHITE));
    assertEquals(-1, dimensions.changedPixels());
    assertEquals(-1, dimensions.totalPixels());
    assertEquals(ChangeScope.DIMENSIONS_CHANGED, dimensions.changeScope());

    var invalid = BoundedPngDifference.compare(baseline, new byte[] {1, 2, 3});
    assertEquals(ChangeScope.UNAVAILABLE, invalid.changeScope());
    assertEquals(-1, invalid.changedPixels());
    assertEquals(-1, invalid.totalPixels());

    var oversized =
        BoundedPngDifference.compare(baseline, new byte[BoundedPngDifference.MAX_PNG_BYTES + 1]);
    assertEquals(ChangeScope.UNAVAILABLE, oversized.changeScope());

    // A highly compressible image can have a tiny byte stream but unsafe decoded dimensions.
    var tooManyPixelsImage = new BufferedImage(2001, 2000, BufferedImage.TYPE_BYTE_BINARY);
    var tooManyPixelsBytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(tooManyPixelsImage, "png", tooManyPixelsBytes));
    assertTrue(tooManyPixelsBytes.size() < BoundedPngDifference.MAX_PNG_BYTES);
    var tooManyPixels = BoundedPngDifference.compare(baseline, tooManyPixelsBytes.toByteArray());
    assertEquals(ChangeScope.UNAVAILABLE, tooManyPixels.changeScope());
  }

  private static byte[] png(int width, int height, IntBinaryOperator pixel) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) image.setRGB(x, y, pixel.applyAsInt(x, y));
    }
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", bytes));
    return bytes.toByteArray();
  }
}
