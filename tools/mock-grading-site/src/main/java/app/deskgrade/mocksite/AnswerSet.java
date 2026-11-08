package app.deskgrade.mocksite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Immutable answer-image sources available to the local-only grading fixture. */
public enum AnswerSet {
  SYNTHETIC("synthetic", 0),
  HANDWRITTEN_SIX("handwritten-six", 6);

  private static final int MAX_FIXTURE_BYTES = 512 * 1024;
  private final String option;
  private final int fixedCount;

  AnswerSet(String option, int fixedCount) {
    this.option = option;
    this.fixedCount = fixedCount;
  }

  public static AnswerSet parse(String value) {
    // Keep the old option working for existing local launch shortcuts.
    if ("handwritten-five".equals(value)) return HANDWRITTEN_SIX;
    for (var answerSet : values()) if (answerSet.option.equals(value)) return answerSet;
    throw new IllegalArgumentException("Unknown answer set");
  }

  public int count(int requested) {
    if (fixedCount == 0) return requested;
    if (requested != fixedCount)
      throw new IllegalArgumentException("This answer set has a fixed size");
    return fixedCount;
  }

  public String option() {
    return option;
  }

  byte[] png(int sequence, boolean blank) throws IOException {
    if (blank || this == SYNTHETIC) return SyntheticAnswers.png(sequence, blank);
    if (sequence < 1 || sequence > HandwrittenHolder.IMAGES.size())
      throw new IllegalArgumentException("Answer image is outside the fixture set");
    return HandwrittenHolder.IMAGES.get(sequence - 1);
  }

  private static final class HandwrittenHolder {
    private static final List<byte[]> IMAGES = load();

    private static List<byte[]> load() {
      var images = new ArrayList<byte[]>();
      for (int sequence = 1; sequence <= 6; sequence++) {
        String resource = "/answers/handwritten-answer-%02d.png".formatted(sequence);
        try (var input = AnswerSet.class.getResourceAsStream(resource)) {
          if (input == null) throw new IllegalStateException("Missing handwritten answer fixture");
          byte[] bytes = input.readNBytes(MAX_FIXTURE_BYTES + 1);
          if (bytes.length < 8 || bytes.length > MAX_FIXTURE_BYTES || !hasPngSignature(bytes))
            throw new IllegalStateException("Invalid handwritten answer fixture");
          var image = ImageIO.read(new ByteArrayInputStream(bytes));
          if (image == null || image.getWidth() != 828 || image.getHeight() != 209)
            throw new IllegalStateException("Unexpected handwritten answer dimensions");
          images.add(bytes);
        } catch (IOException failure) {
          throw new ExceptionInInitializerError("Unable to load handwritten answer fixtures");
        }
      }
      return List.copyOf(images);
    }

    private static boolean hasPngSignature(byte[] bytes) {
      byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
      return java.util.Arrays.equals(signature, java.util.Arrays.copyOf(bytes, signature.length));
    }
  }
}
