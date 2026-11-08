package app.deskgrade.adapters.windows.mock;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.SessionPlatform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockCanvasProtocolTest {
  @ParameterizedTest
  @ValueSource(doubles = {1, 1.25, 1.5, 2})
  void decodesVisiblePixelsAtEachRequiredScale(double scale) {
    BufferedImage image = render(bytes(1, 9, 7, 125), scale);
    var result = MockCanvasProtocol.decode(image);
    assertEquals("9", result.answerId());
    assertEquals("3", result.questionId());
    assertEquals("7", result.acknowledgedAnswerId());
    assertEquals(new BigDecimal("1.25"), result.displayedScore());
    assertEquals(7, result.confirmedCount());
    assertEquals(18, result.pageRevision());
    assertEquals(SessionPlatform.Page.READY, result.page());
    assertTrue(result.separateNext());
  }

  @Test
  void handlesEmptyScoreAndExplicitCompletionWithoutUsingPixelsAsAnswerId() {
    var empty = MockCanvasProtocol.decode(render(bytes(1, 1, 0, Integer.MIN_VALUE), 1));
    assertNull(empty.displayedScore());
    assertNull(empty.acknowledgedAnswerId());
    var complete = MockCanvasProtocol.decode(render(bytes(3, 0, 20, Integer.MIN_VALUE), 1));
    assertEquals(SessionPlatform.Page.COMPLETED, complete.page());
    assertNull(complete.answerId());
  }

  @Test
  void rejectsMissingMagicBadChecksumOcclusionAndUnknownState() {
    byte[] badMagic = bytes(1, 1, 0, 0);
    badMagic[0] = 'X';
    assertThrows(IllegalStateException.class, () -> MockCanvasProtocol.decode(render(badMagic, 1)));
    byte[] data = bytes(1, 1, 0, 0);
    data[31] ^= 1;
    BufferedImage corrupt = render(data, 1);
    assertThrows(IllegalStateException.class, () -> MockCanvasProtocol.decode(corrupt));
    BufferedImage obscured = render(bytes(1, 1, 0, 0), 1);
    obscured.setRGB(6, 14, Color.RED.getRGB());
    assertThrows(IllegalStateException.class, () -> MockCanvasProtocol.decode(obscured));
    assertThrows(
        IllegalStateException.class, () -> MockCanvasProtocol.decode(render(bytes(8, 1, 0, 0), 1)));
  }

  private static byte[] bytes(int state, int sequence, int ack, int score) {
    ByteBuffer data = ByteBuffer.allocate(32);
    data.put(new byte[] {'D', 'G', 'M', '1'})
        .putInt(sequence)
        .putShort((short) 3)
        .put((byte) state)
        .put((byte) 2)
        .putInt(score)
        .putInt(ack)
        .putInt(ack)
        .putInt(18)
        .putShort((short) 0);
    int sum = 0;
    for (int i = 0; i < 30; i++) sum += Byte.toUnsignedInt(data.array()[i]);
    data.putShort((short) sum);
    return data.array();
  }

  private static BufferedImage render(byte[] data, double scale) {
    BufferedImage image =
        new BufferedImage((int) (260 * scale), (int) (28 * scale), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.scale(scale, scale);
      graphics.setColor(Color.MAGENTA);
      graphics.fillRect(0, 0, 260, 28);
      for (int i = 0; i < data.length; i++) {
        int gray = Byte.toUnsignedInt(data[i]);
        graphics.setColor(new Color(gray, gray, gray));
        graphics.fillRect(2 + i * 8, 2, 8, 24);
      }
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
