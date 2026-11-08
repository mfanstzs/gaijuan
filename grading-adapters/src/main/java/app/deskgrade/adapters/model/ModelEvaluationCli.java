package app.deskgrade.adapters.model;

import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.port.CancellationToken;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit command-line entry. validate is offline; run requires --allow-paid and verified local
 * configuration.
 */
public final class ModelEvaluationCli {
  private ModelEvaluationCli() {}

  public static void main(String[] args) {
    try {
      execute(args);
      System.out.println(
          "Evaluation command completed. Inspect the local report and its source labels.");
    } catch (Exception failure) {
      // Do not expose upstream bodies, filesystem contents or credentials in command output.
      System.err.println(
          "Evaluation stopped. Check the manifest, local verified profile, explicit limits and report journal.");
      System.exit(2);
    }
  }

  static void execute(String[] args) throws Exception {
    if (args.length < 1 || !Set.of("generate-synthetic", "validate", "run").contains(args[0]))
      throw new IllegalArgumentException();
    Map<String, String> options = options(args);
    if (args[0].equals("generate-synthetic")) {
      SyntheticEvaluationDataset.create(
          Path.of(required(options, "output-dir")), Integer.parseInt(required(options, "samples")));
      return;
    }
    boolean live = args[0].equals("run");
    if (live && !options.containsKey("allow-paid"))
      throw new IllegalArgumentException("Explicit fee authorization required");
    var dataset = EvaluationManifest.read(Path.of(required(options, "manifest")));
    Path report = Path.of(required(options, "report")).toAbsolutePath().normalize();
    Files.createDirectories(report.getParent());
    var metadata = StrictJson.MAPPER.createObjectNode();
    metadata
        .put("datasetId", dataset.datasetId())
        .put("datasetSource", dataset.source().name())
        .put("datasetDigest", dataset.digest())
        .put("samples", dataset.samples().size())
        .put(
            "distinctImageHashes",
            dataset.samples().stream().map(EvaluationManifest.Sample::sha256).distinct().count());
    if (!live) {
      metadata.put("state", "MANIFEST_VALIDATED_OFFLINE").put("modelCalls", 0);
      writeNew(report, metadata.toPrettyString());
      return;
    }
    if (!System.getProperty("os.name", "").startsWith("Windows"))
      throw new IllegalArgumentException("DPAPI requires Windows");
    Path profileFile = Path.of(required(options, "profile-file"));
    if (!Files.isRegularFile(profileFile) || Files.size(profileFile) > 1048576)
      throw new IllegalArgumentException();
    String profileId = required(options, "profile-id");
    var profile =
        new ProfileJsonCodec()
            .readLocal(Files.readString(profileFile)).stream()
                .filter(candidate -> candidate.id().equals(profileId))
                .findFirst()
                .orElseThrow();
    if (!profile.isVerified())
      throw new IllegalArgumentException("Capability check is required first");
    int maxCalls = Integer.parseInt(required(options, "max-calls"));
    BigDecimal maxCost =
        options.containsKey("max-known-cost")
            ? new BigDecimal(options.get("max-known-cost"))
            : null;
    var auth =
        new ModelEvaluationRunner.Authorization(
            true,
            dataset.digest(),
            ModelEvaluationRunner.profileAuthorizationDigest(profile),
            maxCalls,
            maxCost,
            options.containsKey("allow-unknown-cost"));
    Path secrets = Path.of(required(options, "secrets-dir"));
    metadata
        .put("state", "PREPARED")
        .put("modelCalls", 0)
        .put("profileId", profile.id())
        .put("profileDigest", auth.profileDigest())
        .put("modelId", profile.modelId())
        .put("endpoint", profile.endpoint().toString())
        .put("maxCalls", auth.maxCalls())
        .put("maxKnownCost", auth.maxKnownCost())
        .put("allowUnknownCost", auth.allowUnknownCost());
    writeNew(report, metadata.toPrettyString());
    Path calls = report.resolveSibling(report.getFileName() + ".calls.jsonl");
    try (var journal = new FileJournal(calls);
        var runner = new ModelEvaluationRunner(Clock.systemUTC())) {
      var result =
          runner
              .run(
                  dataset,
                  profile,
                  new DpapiSecretStore(secrets),
                  auth,
                  CancellationToken.none(),
                  journal)
              .toCompletableFuture()
              .get();
      Path staged = report.resolveSibling(report.getFileName() + "." + UUID.randomUUID() + ".tmp");
      writeNew(staged, result.toJson().toPrettyString());
      Files.move(
          staged, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Map<String, String> options(String[] args) {
    Set<String> values =
        Set.of(
            "manifest",
            "report",
            "output-dir",
            "samples",
            "profile-file",
            "profile-id",
            "secrets-dir",
            "max-calls",
            "max-known-cost");
    Set<String> flags = Set.of("allow-paid", "allow-unknown-cost");
    Map<String, String> options = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--")) throw new IllegalArgumentException();
      String key = args[i].substring(2);
      String value;
      if (flags.contains(key)) value = "true";
      else if (values.contains(key) && ++i < args.length && !args[i].startsWith("--"))
        value = args[i];
      else throw new IllegalArgumentException();
      if (options.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
    }
    return options;
  }

  private static String required(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing option");
    return value;
  }

  private static void writeNew(Path path, String json) throws java.io.IOException {
    try (FileChannel file =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      writeForced(file, json);
    }
  }

  private static void writeForced(FileChannel file, String json) throws java.io.IOException {
    ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    while (bytes.hasRemaining()) file.write(bytes);
    file.force(true);
  }

  private static final class FileJournal implements ModelEvaluationRunner.Journal, AutoCloseable {
    private final FileChannel output;

    FileJournal(Path path) throws java.io.IOException {
      output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public synchronized void beforeCall(String digest, String sampleId, UUID callId, Instant now) {
      var event = StrictJson.MAPPER.createObjectNode();
      event
          .put("phase", "CALL_PREPARED")
          .put("datasetDigest", digest)
          .put("sampleId", sampleId)
          .put("callId", callId.toString())
          .put("at", now.toString());
      append(event.toString());
    }

    @Override
    public synchronized void afterCall(ModelEvaluationRunner.Row row) {
      var event = ModelEvaluationRunner.rowJson(row);
      event.put("phase", "CALL_COMPLETED");
      append(event.toString());
    }

    private void append(String text) {
      try {
        writeForced(output, text + "\n");
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Evaluation journal cannot be persisted.");
      }
    }

    @Override
    public void close() throws java.io.IOException {
      output.close();
    }
  }
}
