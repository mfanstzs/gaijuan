package app.deskgrade.adapters.windows.mock;

import app.deskgrade.core.application.session.SessionPlatform;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

/** Public, visible diagnostic canvas of the synthetic mock tool; never a school-platform reader. */
public final class MockCanvasProtocol {
  private MockCanvasProtocol() {}

  public static SessionPlatform.View decode(BufferedImage image) {
    if (image == null || image.getWidth() < 260 || image.getHeight() < 28) throw invalid();
    requireMagenta(image, 0.5, 14);
    requireMagenta(image, 259, 14);
    requireMagenta(image, 130, 0.5);
    requireMagenta(image, 130, 27);
    byte[] bytes = new byte[32];
    for (int index = 0; index < bytes.length; index++) {
      int color = pixel(image, 6 + 8 * index, 14);
      int r = (color >> 16) & 255;
      int g = (color >> 8) & 255;
      int b = color & 255;
      if (r != g || g != b) throw invalid();
      bytes[index] = (byte) r;
    }
    if (bytes[0] != 'D' || bytes[1] != 'G' || bytes[2] != 'M' || bytes[3] != '1') throw invalid();
    int checksum = 0;
    for (int index = 0; index < 30; index++) checksum += Byte.toUnsignedInt(bytes[index]);
    ByteBuffer data = ByteBuffer.wrap(bytes);
    if ((checksum & 65535) != Short.toUnsignedInt(data.getShort(30))) throw invalid();
    long sequence = Integer.toUnsignedLong(data.getInt(4));
    int question = Short.toUnsignedInt(data.getShort(8));
    int state = Byte.toUnsignedInt(bytes[10]);
    int mode = Byte.toUnsignedInt(bytes[11]);
    int score = data.getInt(12);
    long ack = Integer.toUnsignedLong(data.getInt(16));
    long confirmed = Integer.toUnsignedLong(data.getInt(20));
    long revision = Integer.toUnsignedLong(data.getInt(24));
    if (question == 0
        || state < 1
        || state > 7
        || mode < 1
        || mode > 2
        || sequence > 1_000_000
        || ack > 1_000_000
        || confirmed > 1_000_000
        || (state == 3 && sequence != 0)
        || (state != 3 && sequence == 0)) throw invalid();
    SessionPlatform.Page page =
        switch (state) {
          case 1 -> SessionPlatform.Page.READY;
          case 2 -> SessionPlatform.Page.SUBMITTED;
          case 3 -> SessionPlatform.Page.COMPLETED;
          case 4 -> SessionPlatform.Page.LOADING;
          case 5 -> SessionPlatform.Page.LOGIN_EXPIRED;
          case 6 -> SessionPlatform.Page.POPUP;
          case 7 -> SessionPlatform.Page.ERROR;
          default -> throw invalid();
        };
    return new SessionPlatform.View(
        sequence == 0 ? null : Long.toString(sequence),
        Integer.toString(question),
        revision,
        page,
        ack == 0 ? null : Long.toString(ack),
        (int) confirmed,
        score == Integer.MIN_VALUE ? null : BigDecimal.valueOf(score, 2),
        mode == 2);
  }

  private static void requireMagenta(BufferedImage image, double x, double y) {
    if ((pixel(image, x, y) & 0xFFFFFF) != 0xFF00FF) throw invalid();
  }

  private static int pixel(BufferedImage image, double x, double y) {
    int px = Math.min(image.getWidth() - 1, (int) (x * image.getWidth() / 260.0));
    int py = Math.min(image.getHeight() - 1, (int) (y * image.getHeight() / 28.0));
    return image.getRGB(px, py);
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Synthetic platform visible protocol is invalid or obscured.");
  }
}
