package app.deskgrade.adapters.model;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.model.Rubric;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/** Immutable, locally verified 50–100 sample input. Loading this file performs no network work. */
public record EvaluationManifest(
    String datasetId, DataSource source, Rubric rubric, List<Sample> samples) {
  public EvaluationManifest {
    if (datasetId == null
        || !datasetId.matches("[A-Za-z0-9_-]{1,64}")
        || source == null
        || rubric == null
        || samples == null
        || samples.size() < 50
        || samples.size() > 100) throw invalid();
    rubric.validateReady();
    samples = List.copyOf(samples);
    if (samples.stream().map(Sample::sampleId).distinct().count() != samples.size())
      throw invalid();
    if (samples.stream().mapToLong(sample -> sample.png.length).sum() > 64 * 1024 * 1024)
      throw invalid();
    for (Sample sample : samples) GradeValidator.validate(rubric.question(), sample.expected());
  }

  public enum DataSource {
    SYNTHETIC,
    ANONYMIZED_REAL
  }

  public String digest() {
    StringBuilder canonical =
        new StringBuilder(datasetId)
            .append('\n')
            .append(source)
            .append('\n')
            .append(new RubricJsonCodec().write(rubric));
    for (Sample sample : samples)
      canonical
          .append('\n')
          .append(sample.sampleId())
          .append('\n')
          .append(sample.imagePath())
          .append('\n')
          .append(sample.sha256())
          .append('\n')
          .append(StrictJson.MAPPER.valueToTree(sample.expected()).toString());
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  public record Sample(
      String sampleId, String imagePath, String sha256, byte[] png, GradeResult expected) {
    public Sample {
      if (sampleId == null
          || !sampleId.matches("[A-Za-z0-9_-]{1,64}")
          || imagePath == null
          || !imagePath.matches("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.png")
          || sha256 == null
          || !sha256.matches("[a-f0-9]{64}")
          || png == null
          || expected == null) throw invalid();
      png = png.clone();
      if (!sha256.equals(EvaluationManifest.sha256(png))) throw invalid();
      validatePng(png);
    }

    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  public static EvaluationManifest read(Path manifestPath) {
    try {
      Path file = manifestPath.toAbsolutePath().normalize();
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1048576)
        throw invalid();
      Path directory = file.getParent().toRealPath();
      String sourceJson = Files.readString(file, StandardCharsets.UTF_8);
      var root = StrictJson.parse(sourceJson);
      StrictJson.fields(
          root,
          Set.of(
              "schemaVersion",
              "datasetId",
              "source",
              "anonymizationConfirmed",
              "rubric",
              "samples"));
      if (!root.get("schemaVersion").isIntegralNumber()
          || !root.get("schemaVersion").canConvertToInt()
          || root.get("schemaVersion").intValue() != 1
          || !root.get("anonymizationConfirmed").isBoolean()
          || !root.get("anonymizationConfirmed").booleanValue()) throw invalid();
      var dataSource = DataSource.valueOf(StrictJson.string(root.get("source"), 1, 20, false));
      var rubric = new RubricJsonCodec().read(root.get("rubric").toString());
      var entries = root.get("samples");
      if (!entries.isArray() || entries.size() < 50 || entries.size() > 100) throw invalid();
      List<Sample> samples = new ArrayList<>();
      Set<String> sampleIds = new HashSet<>();
      long totalBytes = 0;
      for (var entry : entries) {
        StrictJson.fields(entry, Set.of("sampleId", "image", "sha256", "expected"));
        String sampleId = StrictJson.string(entry.get("sampleId"), 1, 64, false);
        if (!sampleIds.add(sampleId)) throw invalid();
        String relative = StrictJson.string(entry.get("image"), 1, 240, false);
        if (!relative.matches("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.png")) throw invalid();
        Path image = directory.resolve(relative).normalize();
        if (!image.startsWith(directory)) throw invalid();
        Path checking = directory;
        for (Path component : directory.relativize(image)) {
          checking = checking.resolve(component);
          var attributes =
              Files.readAttributes(checking, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isSymbolicLink() || attributes.isOther()) throw invalid();
        }
        if (!image.toRealPath().startsWith(directory)
            || !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        long bytes = Files.size(image);
        totalBytes += bytes;
        if (bytes < 33 || bytes > 10 * 1024 * 1024 || totalBytes > 64 * 1024 * 1024)
          throw invalid();
        byte[] png;
        try (var stream = Files.newInputStream(image)) {
          png = stream.readNBytes(10 * 1024 * 1024 + 1);
        }
        var expected =
            new GradeResultJsonCodec().read(entry.get("expected").toString(), rubric.question());
        samples.add(
            new Sample(
                sampleId,
                relative,
                StrictJson.string(entry.get("sha256"), 64, 64, false),
                png,
                expected));
      }
      return new EvaluationManifest(
          StrictJson.string(root.get("datasetId"), 1, 64, false), dataSource, rubric, samples);
    } catch (RuntimeException | IOException failure) {
      throw invalid();
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static void validatePng(byte[] png) {
    try {
      if (png.length < 33 || png.length > 10 * 1024 * 1024) throw invalid();
      byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
      for (int i = 0; i < signature.length; i++) if (png[i] != signature[i]) throw invalid();
      var header = java.nio.ByteBuffer.wrap(png);
      if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) throw invalid();
      int width = header.getInt(16), height = header.getInt(20);
      if (width < 1 || height < 1 || (long) width * height > 16000000) throw invalid();
      var image = ImageIO.read(new ByteArrayInputStream(png));
      if (image == null || image.getWidth() != width || image.getHeight() != height)
        throw invalid();
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid anonymous evaluation manifest or PNG evidence.");
  }
}
