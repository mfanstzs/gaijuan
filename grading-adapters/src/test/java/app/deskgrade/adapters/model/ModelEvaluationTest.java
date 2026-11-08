package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.GradingProvider;
import app.deskgrade.core.port.SecretStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelEvaluationTest {
  @TempDir Path directory;
  private static final Clock CLOCK = Clock.systemUTC();

  @Test
  void loadsFiftyAndHundredImmutableSyntheticSamplesWithoutConfusingRepeatedImages()
      throws Exception {
    for (int count : new int[] {50, 100}) {
      Path path = manifest(count);
      var dataset = EvaluationManifest.read(path);
      assertEquals(count, dataset.samples().size());
      assertEquals(EvaluationManifest.DataSource.SYNTHETIC, dataset.source());
      assertEquals(
          1, dataset.samples().stream().map(EvaluationManifest.Sample::sha256).distinct().count());
      String digest = dataset.digest();
      byte[] mutableCopy = dataset.samples().getFirst().png();
      mutableCopy[0] = 0;
      Files.write(path.getParent().resolve("sample.png"), new byte[] {1});
      assertEquals(digest, dataset.digest());
      assertEquals((byte) 137, dataset.samples().getFirst().png()[0]);
    }
  }

  @Test
  void rejectsSampleCountUnknownFieldsDuplicateIdsBadHashesPathsAndJsonBeforeAnyCall()
      throws Exception {
    for (int count : new int[] {49, 101}) {
      Path path = manifest(count);
      assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(path));
    }
    Path path = manifest(50);
    var original = StrictJson.parse(Files.readString(path));
    List<java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode>> mutations =
        List.of(
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("studentName", "forbidden extra field"),
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("schemaVersion", 4294967297L),
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("anonymizationConfirmed", false),
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("samples").get(1))
                    .put("sampleId", "sample-000"),
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("samples").get(0))
                    .put("sha256", "0".repeat(64)),
            root ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("samples").get(0))
                    .put("image", "../sample.png"));
    for (var mutation : mutations) {
      var edited = original.deepCopy();
      mutation.accept(edited);
      Files.writeString(path, edited.toString());
      assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(path));
    }
    Files.writeString(path, "{\"schemaVersion\":1,\"schemaVersion\":1}");
    assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(path));
    Files.writeString(path, original.toString().substring(0, 30));
    assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(path));
  }

  @Test
  void rejectsInvalidPngEvenWithMatchingHashAndInvalidExpectedScore() throws Exception {
    Path path = manifest(50);
    var root = StrictJson.parse(Files.readString(path));
    byte[] bad = new byte[100];
    Files.write(path.getParent().resolve("sample.png"), bad);
    for (var row : root.path("samples"))
      ((com.fasterxml.jackson.databind.node.ObjectNode) row)
          .put("sha256", EvaluationManifest.sha256(bad));
    Files.writeString(path, root.toString());
    assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(path));
    Path invalidScorePath = manifest(50);
    root = StrictJson.parse(Files.readString(invalidScorePath));
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            root.path("samples").get(0).path("expected").path("criteria").get(0))
        .put("awarded", 3);
    Files.writeString(invalidScorePath, root.toString());
    assertThrows(IllegalArgumentException.class, () -> EvaluationManifest.read(invalidScorePath));
  }

  @Test
  void fullSyntheticRunReportsActualDisagreementsUnableErrorsLatencyAndUnknownCost()
      throws Exception {
    var dataset = EvaluationManifest.read(manifest(50));
    var profile = verifiedProfile();
    AtomicInteger calls = new AtomicInteger();
    GradingProvider mock =
        (request, token) -> {
          int index = calls.getAndIncrement();
          if (index == 4)
            return CompletableFuture.failedFuture(
                new IllegalStateException("untrusted provider text"));
          GradeResult actual =
              index == 1
                  ? scored("p", "0")
                  : index == 2
                      ? unable()
                      : index == 3 ? scored("wrong-point", "1") : scored("p", "1");
          BigDecimal cost =
              index == 2
                  ? null
                  : new BigDecimal(index == 1 ? "0.02" : index == 3 ? "0.03" : "0.01");
          return CompletableFuture.completedFuture(
              new GradingResponse(
                  request.requestId(),
                  GradingResponse.Source.MOCK,
                  actual,
                  CLOCK.instant(),
                  cost == null ? null : new GradingResponse.Usage(1L, 2L, cost),
                  Duration.ofMillis(5)));
        };
    var journal = new MemoryJournal();
    try (var runner = new ModelEvaluationRunner(CLOCK)) {
      var report =
          runner.evaluate(
              dataset,
              profile,
              mock,
              GradingResponse.Source.MOCK,
              authorization(dataset, profile, 50, null, true),
              CancellationToken.none(),
              journal);
      var json = report.toJson();
      var metrics = json.path("metrics");
      assertEquals(50, calls.get());
      assertEquals(50, journal.before.size());
      assertEquals(50, journal.after.size());
      assertEquals(ModelEvaluationRunner.StopReason.COMPLETED, report.stopReason());
      assertEquals("MOCK", json.path("modelSource").asText());
      assertEquals("SYNTHETIC", json.path("datasetSource").asText());
      assertEquals(47, metrics.path("scored").asInt());
      assertEquals(1, metrics.path("unable").asInt());
      assertEquals(2, metrics.path("errors").asInt());
      assertEquals(47, metrics.path("statusCorrect").asInt());
      assertEquals(46, metrics.path("exactTotals").asInt());
      assertEquals(47, metrics.path("scoredPairDenominator").asInt());
      assertEquals(46, metrics.path("exactCriteria").asInt());
      assertEquals(48, metrics.path("knownCostCalls").asInt());
      assertEquals(2, metrics.path("unknownCostCalls").asInt());
      assertEquals(
          0, new BigDecimal("0.51").compareTo(metrics.path("knownCostTotal").decimalValue()));
      assertTrue(json.path("samples").get(2).path("actualTotal").isNull());
      assertTrue(json.path("samples").get(3).path("actualTotal").isNull());
      assertFalse(json.toString().contains("untrusted provider text"));
      assertFalse(json.toString().contains("studentText"));
    }
  }

  @Test
  void authorizationAndVerificationFailuresDoNotReadSecretsOrCallProvider() throws Exception {
    var dataset = EvaluationManifest.read(manifest(50));
    var profile = verifiedProfile();
    AtomicInteger secretReads = new AtomicInteger();
    SecretStore secrets =
        new SecretStore() {
          public void put(String id, char[] secret) {
            fail("No credential writes");
          }

          public Optional<char[]> read(String id) {
            secretReads.incrementAndGet();
            return Optional.empty();
          }

          public void delete(String id) {
            fail("No credential deletion");
          }
        };
    try (var runner = new ModelEvaluationRunner(CLOCK)) {
      var auth = authorization(dataset, profile, 50, null, true);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runner.run(
                  dataset,
                  profile,
                  secrets,
                  new ModelEvaluationRunner.Authorization(
                      false, auth.datasetDigest(), auth.profileDigest(), 50, null, true),
                  CancellationToken.none(),
                  new MemoryJournal()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runner.run(
                  dataset,
                  profile.withVerification(null),
                  secrets,
                  auth,
                  CancellationToken.none(),
                  new MemoryJournal()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runner.run(
                  dataset,
                  profile,
                  secrets,
                  new ModelEvaluationRunner.Authorization(
                      true, "wrong", auth.profileDigest(), 50, null, true),
                  CancellationToken.none(),
                  new MemoryJournal()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runner.run(
                  dataset,
                  profile,
                  secrets,
                  authorization(dataset, profile, 50, null, false),
                  CancellationToken.none(),
                  new MemoryJournal()));
      assertEquals(0, secretReads.get());
    }
  }

  @Test
  void beforeCallPersistenceFailurePreventsProviderInvocation() throws Exception {
    var dataset = EvaluationManifest.read(manifest(50));
    var profile = verifiedProfile();
    AtomicInteger calls = new AtomicInteger();
    try (var runner = new ModelEvaluationRunner(CLOCK)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              runner.evaluate(
                  dataset,
                  profile,
                  (request, token) -> {
                    calls.incrementAndGet();
                    return new CompletableFuture<>();
                  },
                  GradingResponse.Source.MOCK,
                  authorization(dataset, profile, 50, null, true),
                  CancellationToken.none(),
                  new ModelEvaluationRunner.Journal() {
                    public void beforeCall(String digest, String id, UUID callId, Instant now) {
                      throw new IllegalStateException("disk unavailable");
                    }

                    public void afterCall(ModelEvaluationRunner.Row row) {
                      fail("No completion expected");
                    }
                  }));
      assertEquals(0, calls.get());
    }
  }

  @Test
  void callAndKnownCostLimitsAndCancellationStopNewRequests() throws Exception {
    var dataset = EvaluationManifest.read(manifest(50));
    var profile = verifiedProfile();
    AtomicInteger calls = new AtomicInteger();
    GradingProvider mock =
        (request, token) -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(
              new GradingResponse(
                  request.requestId(),
                  GradingResponse.Source.MOCK,
                  scored("p", "1"),
                  CLOCK.instant(),
                  new GradingResponse.Usage(1L, 1L, new BigDecimal("0.60")),
                  Duration.ofMillis(1)));
        };
    try (var runner = new ModelEvaluationRunner(CLOCK)) {
      var limited =
          runner.evaluate(
              dataset,
              profile,
              mock,
              GradingResponse.Source.MOCK,
              authorization(dataset, profile, 2, null, true),
              CancellationToken.none(),
              new MemoryJournal());
      assertEquals(2, calls.get());
      assertEquals(ModelEvaluationRunner.StopReason.CALL_LIMIT, limited.stopReason());
      calls.set(0);
      var budget =
          runner.evaluate(
              dataset,
              profile,
              mock,
              GradingResponse.Source.MOCK,
              authorization(dataset, profile, 50, new BigDecimal("0.50"), true),
              CancellationToken.none(),
              new MemoryJournal());
      assertEquals(1, calls.get());
      assertEquals(ModelEvaluationRunner.StopReason.COST_LIMIT, budget.stopReason());
      calls.set(0);
      AtomicBoolean cancelled = new AtomicBoolean();
      var cancelledRun =
          runner.evaluate(
              dataset,
              profile,
              mock,
              GradingResponse.Source.MOCK,
              authorization(dataset, profile, 50, null, true),
              cancelled::get,
              new MemoryJournal() {
                @Override
                public void afterCall(ModelEvaluationRunner.Row row) {
                  super.afterCall(row);
                  cancelled.set(true);
                }
              });
      assertEquals(1, calls.get());
      assertEquals(ModelEvaluationRunner.StopReason.CANCELLED, cancelledRun.stopReason());
    }
  }

  @Test
  void authenticationFailureStopsBatchWithUnknownCostAndNoRetry() throws Exception {
    var dataset = EvaluationManifest.read(manifest(50));
    var profile = verifiedProfile();
    AtomicInteger calls = new AtomicInteger();
    try (var runner = new ModelEvaluationRunner(CLOCK)) {
      var result =
          runner.evaluate(
              dataset,
              profile,
              (request, token) -> {
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new ModelCallException(ModelCallException.Kind.AUTHENTICATION, 401));
              },
              GradingResponse.Source.MOCK,
              authorization(dataset, profile, 50, null, true),
              CancellationToken.none(),
              new MemoryJournal());
      assertEquals(1, calls.get());
      assertEquals(ModelEvaluationRunner.StopReason.PROVIDER_REJECTED, result.stopReason());
      assertTrue(result.toJson().path("metrics").path("knownCostTotal").isNull());
      assertEquals(1, result.toJson().path("metrics").path("unknownCostCalls").asInt());
    }
  }

  @Test
  void cliValidateIsOfflineAndRunWithoutAuthorizationFailsBeforeReadingAnyInput() throws Exception {
    Path path = manifest(50);
    Path report = directory.resolve("import.json");
    ModelEvaluationCli.execute(
        new String[] {"validate", "--manifest", path.toString(), "--report", report.toString()});
    var result = StrictJson.parse(Files.readString(report));
    assertEquals("MANIFEST_VALIDATED_OFFLINE", result.path("state").asText());
    assertEquals(0, result.path("modelCalls").asInt());
    assertFalse(result.has("accuracy"));
    assertThrows(
        IllegalArgumentException.class, () -> ModelEvaluationCli.execute(new String[] {"run"}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelEvaluationCli.execute(
                new String[] {"validate", "--api-key", "synthetic-invalid-option"}));
  }

  @Test
  void syntheticGeneratorCreatesFiftyLocalImagesAndRefusesOverwrite() throws Exception {
    Path output = directory.resolve("generated");
    ModelEvaluationCli.execute(
        new String[] {"generate-synthetic", "--output-dir", output.toString(), "--samples", "50"});
    var dataset = EvaluationManifest.read(output.resolve("manifest.json"));
    assertEquals(50, dataset.samples().size());
    assertEquals(EvaluationManifest.DataSource.SYNTHETIC, dataset.source());
    assertEquals(
        50, dataset.samples().stream().map(EvaluationManifest.Sample::sha256).distinct().count());
    assertEquals(
        BigDecimal.ONE, dataset.samples().getFirst().expected().criteria().getFirst().awarded());
    assertEquals(
        BigDecimal.ZERO, dataset.samples().get(1).expected().criteria().getFirst().awarded());
    assertThrows(
        java.nio.file.FileAlreadyExistsException.class,
        () -> SyntheticEvaluationDataset.create(output, 50));
  }

  private Path manifest(int count) throws Exception {
    Path folder = Files.createTempDirectory(directory, "dataset-");
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xffffff);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] png = output.toByteArray();
    Files.write(folder.resolve("sample.png"), png);
    var rubric =
        new Rubric(
            new QuestionDefinition(
                "q",
                1,
                "Synthetic short answer",
                "Synthetic reference",
                new BigDecimal("2"),
                List.of(
                    new ScoringCriterion(
                        "p",
                        "Synthetic fixed rule",
                        List.of(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("2"))))),
            "Synthetic fixed rubric",
            BigDecimal.ONE,
            true,
            false);
    var root = StrictJson.MAPPER.createObjectNode();
    root.put("schemaVersion", 1)
        .put("datasetId", "synthetic-dataset")
        .put("source", "SYNTHETIC")
        .put("anonymizationConfirmed", true);
    root.set("rubric", StrictJson.parse(new RubricJsonCodec().write(rubric)));
    var samples = root.putArray("samples");
    for (int i = 0; i < count; i++) {
      var sample = samples.addObject();
      sample
          .put("sampleId", String.format(java.util.Locale.ROOT, "sample-%03d", i))
          .put("image", "sample.png")
          .put("sha256", EvaluationManifest.sha256(png));
      sample.set("expected", StrictJson.MAPPER.valueToTree(scored("p", "1")));
    }
    Path path = folder.resolve("manifest.json");
    Files.writeString(path, root.toString());
    return path;
  }

  private static GradeResult scored(String point, String score) {
    return new GradeResult(
        GradeResult.Status.SCORED,
        GradeResult.Readability.CLEAR,
        "Synthetic student answer",
        List.of(
            new GradeResult.CriterionResult(
                point, new BigDecimal(score), null, "Synthetic expected score")),
        List.of());
  }

  private static GradeResult unable() {
    return new GradeResult(
        GradeResult.Status.UNABLE,
        GradeResult.Readability.UNREADABLE,
        null,
        List.of(new GradeResult.CriterionResult("p", null, null, "Synthetic unreadable")),
        List.of("Synthetic unreadable"));
  }

  private static ProviderProfile verifiedProfile() {
    var profile =
        QwenEndpointCatalog.unverifiedProfile(
            "synthetic",
            1,
            "SYNTHETIC TEST ONLY",
            java.net.URI.create("https://no-network.invalid/v1/chat/completions"),
            "synthetic-vision",
            "unused-fixture-reference");
    return profile.withVerification(
        new ProviderProfile.Verification(profile.configDigest(), CLOCK.instant(), true, true));
  }

  private static ModelEvaluationRunner.Authorization authorization(
      EvaluationManifest dataset,
      ProviderProfile profile,
      int maxCalls,
      BigDecimal maxCost,
      boolean allowUnknown) {
    return new ModelEvaluationRunner.Authorization(
        true,
        dataset.digest(),
        ModelEvaluationRunner.profileAuthorizationDigest(profile),
        maxCalls,
        maxCost,
        allowUnknown);
  }

  private static class MemoryJournal implements ModelEvaluationRunner.Journal {
    final List<UUID> before = new ArrayList<>();
    final List<ModelEvaluationRunner.Row> after = new ArrayList<>();

    public void beforeCall(String digest, String sampleId, UUID callId, Instant now) {
      before.add(callId);
    }

    public void afterCall(ModelEvaluationRunner.Row row) {
      assertTrue(before.contains(row.callId()));
      after.add(row);
    }
  }
}
