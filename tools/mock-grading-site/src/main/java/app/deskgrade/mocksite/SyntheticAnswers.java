package app.deskgrade.mocksite;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Generates only labeled synthetic fixtures; no student information is read or stored. */
public final class SyntheticAnswers {
  private SyntheticAnswers() {}

  public static byte[] png(int sequence, boolean blank) throws IOException {
    BufferedImage image = new BufferedImage(720, 220, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, image.getWidth(), image.getHeight());
      if (!blank) {
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(24, 48, 78));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        g.drawString("SYNTHETIC ANSWER " + MockPlatform.answerId(sequence), 24, 44);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 21));
        g.drawString(
            "Fixture "
                + sequence
                + ": "
                + (sequence % 7 + 2)
                + " + "
                + (sequence % 11 + 1)
                + " = "
                + (sequence % 7 + sequence % 11 + 3),
            24,
            99);
        g.drawString("Explanation variant " + sequence + ": adding both quantities.", 24, 142);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(new Color(89, 108, 126));
        g.drawString("DEVELOPMENT FIXTURE - NO REAL STUDENT DATA", 24, 193);
      }
    } finally {
      g.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  /**
   * A deterministic final frame for screen-observed automation.
   *
   * <p>Leaving the last {@code <img>} without a source is not a reliable visual transition in
   * JavaFX WebView: its previously decoded frame may remain painted. A distinct completion image
   * gives the desktop adapter stable, passive evidence that the final submit advanced the page.
   */
  public static byte[] completionPng() throws IOException {
    BufferedImage image = new BufferedImage(720, 220, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    try {
      g.setColor(new Color(235, 248, 241));
      g.fillRect(0, 0, image.getWidth(), image.getHeight());
      g.setColor(new Color(28, 126, 79));
      g.fillRect(0, 0, 18, image.getHeight());
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
      g.drawString("ALL ANSWERS COMPLETED", 64, 91);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
      g.setColor(new Color(54, 91, 73));
      g.drawString("The final submission has advanced to the completed page.", 64, 137);
    } finally {
      g.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }
}
