package app.deskgrade.adapters.model;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Printed synthetic samples for tool validation, never a real-student accuracy benchmark. */
public final class SyntheticEvaluationDataset {
  private SyntheticEvaluationDataset() {}

  public static Path create(Path directory, int sampleCount) throws IOException {
    if (sampleCount < 50 || sampleCount > 100)
      throw new IllegalArgumentException("Use 50 to 100 samples.");
    Path root = directory.toAbsolutePath().normalize();
    Files.createDirectories(root.getParent());
    Files.createDirectory(root); // Refuse an existing directory rather than overwrite user samples.
    var rubric =
        new Rubric(
            new QuestionDefinition(
                "synthetic-7-plus-5",
                1,
                "计算7+5。学生的答案在图片中。",
                "12",
                BigDecimal.ONE,
                List.of(
                    new ScoringCriterion(
                        "calculation",
                        "学生填写12得1分，其他数字得0分；无法辨认时UNABLE。",
                        List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
            "只按图片中的学生答案评分；不得将参考答案当学生答案。",
            BigDecimal.ONE,
            true,
            false);
    var manifest = StrictJson.MAPPER.createObjectNode();
    manifest
        .put("schemaVersion", 1)
        .put("datasetId", "synthetic-calculation-" + sampleCount)
        .put("source", "SYNTHETIC")
        .put("anonymizationConfirmed", true);
    manifest.set("rubric", StrictJson.parse(new RubricJsonCodec().write(rubric)));
    var samples = manifest.putArray("samples");
    for (int index = 0; index < sampleCount; index++) {
      String id = String.format(Locale.ROOT, "sample-%03d", index + 1);
      int writtenAnswer = index % 3 == 0 ? 12 : index % 3 == 1 ? 11 : 13;
      var image = new BufferedImage(480, 160, BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      try {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 480, 160);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        graphics.drawString("SYNTHETIC " + id, 20, 30);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        graphics.drawString("Answer: " + writtenAnswer, 20, 105);
      } finally {
        graphics.dispose();
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder unavailable");
      byte[] png = bytes.toByteArray();
      write(root.resolve(id + ".png"), png);
      var row = samples.addObject();
      row.put("sampleId", id)
          .put("image", id + ".png")
          .put("sha256", EvaluationManifest.sha256(png));
      var expected =
          new GradeResult(
              GradeResult.Status.SCORED,
              GradeResult.Readability.CLEAR,
              Integer.toString(writtenAnswer),
              List.of(
                  new GradeResult.CriterionResult(
                      "calculation",
                      writtenAnswer == 12 ? BigDecimal.ONE : BigDecimal.ZERO,
                      null,
                      "Synthetic generated numeric answer")),
              List.of());
      row.set("expected", StrictJson.MAPPER.valueToTree(expected));
    }
    Path path = root.resolve("manifest.json");
    write(path, manifest.toPrettyString().getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private static void write(Path path, byte[] bytes) throws IOException {
    try (FileChannel file =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
    }
  }
}
