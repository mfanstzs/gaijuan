package app.deskgrade.adapters.model;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.GradingProvider;
import app.deskgrade.core.port.SecretStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sequential image evaluation. Has no dependency on SessionController or any desktop action port.
 */
public final class ModelEvaluationRunner implements AutoCloseable {
  private final Clock clock;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "deskgrade-model-evaluation");
            thread.setDaemon(true);
            return thread;
          });

  public ModelEvaluationRunner(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public record Authorization(
      boolean allowPaid,
      String datasetDigest,
      String profileDigest,
      int maxCalls,
      BigDecimal maxKnownCost,
      boolean allowUnknownCost) {
    public Authorization {
      if (datasetDigest == null
          || profileDigest == null
          || maxCalls < 1
          || maxCalls > 100
          || (maxKnownCost != null && maxKnownCost.signum() <= 0))
        throw new IllegalArgumentException("Invalid explicit evaluation limits.");
    }
  }

  /**
   * Include user-entered prices as well as the exact provider configuration in cost authorization.
   */
  public static String profileAuthorizationDigest(ProviderProfile profile) {
    return EvaluationManifest.sha256(
        (profile.configDigest() + "\n" + profile.pricing()).getBytes(StandardCharsets.UTF_8));
  }

  /** Both callbacks must persist or throw. beforeCall runs before a request can be sent. */
  public interface Journal {
    void beforeCall(String datasetDigest, String sampleId, UUID callId, Instant now);

    void afterCall(Row row);
  }

  public CompletionStage<Report> run(
      EvaluationManifest dataset,
      ProviderProfile profile,
      SecretStore secrets,
      Authorization authorization,
      CancellationToken token,
      Journal journal) {
    validateAuthorization(dataset, profile, authorization);
    Objects.requireNonNull(secrets);
    Objects.requireNonNull(token);
    Objects.requireNonNull(journal);
    return CompletableFuture.supplyAsync(
        () -> {
          try (var provider =
              new OpenAICompatibleVisionProvider(profile, dataset.rubric(), secrets, clock)) {
            return evaluate(
                dataset,
                profile,
                provider,
                GradingResponse.Source.LIVE,
                authorization,
                token,
                journal);
          }
        },
        executor);
  }

  private static void validateAuthorization(
      EvaluationManifest dataset, ProviderProfile profile, Authorization auth) {
    Objects.requireNonNull(dataset);
    Objects.requireNonNull(profile);
    Objects.requireNonNull(auth);
    if (!auth.allowPaid()
        || !dataset.digest().equals(auth.datasetDigest())
        || !profileAuthorizationDigest(profile).equals(auth.profileDigest())
        || !profile.isVerified())
      throw new IllegalArgumentException(
          "Evaluation requires explicit authorization for this dataset and verified profile.");
    if (!auth.allowUnknownCost()
        && (profile.pricing() == null
            || profile.pricing().inputPerMillion() == null
            || profile.pricing().outputPerMillion() == null))
      throw new IllegalArgumentException(
          "Configure known prices or explicitly allow unknown cost.");
  }

  // Package-private fixture entry: production callers must use run() and its real provider gate.
  Report evaluate(
      EvaluationManifest dataset,
      ProviderProfile profile,
      GradingProvider provider,
      GradingResponse.Source expectedSource,
      Authorization authorization,
      CancellationToken token,
      Journal journal) {
    validateAuthorization(dataset, profile, authorization);
    Instant startedAt = clock.instant();
    List<Row> rows = new ArrayList<>();
    BigDecimal knownCost = BigDecimal.ZERO;
    StopReason stopped = StopReason.COMPLETED;
    String datasetDigest = dataset.digest();
    for (var sample : dataset.samples()) {
      if (token.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
        stopped = StopReason.CANCELLED;
        break;
      }
      if (rows.size() >= authorization.maxCalls()) {
        stopped = StopReason.CALL_LIMIT;
        break;
      }
      if (authorization.maxKnownCost() != null
          && knownCost.compareTo(authorization.maxKnownCost()) >= 0) {
        stopped = StopReason.COST_LIMIT;
        break;
      }
      UUID callId = UUID.randomUUID();
      journal.beforeCall(datasetDigest, sample.sampleId(), callId, clock.instant());
      if (token.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
        stopped = StopReason.CANCELLED;
        break;
      }
      long startedNanos = System.nanoTime();
      GradingResponse response = null;
      CompletableFuture<GradingResponse> pending = null;
      String safeError = null;
      GradeResult actual = null;
      try {
        pending =
            provider
                .grade(new GradingRequest(callId, dataset.rubric().question(), sample.png()), token)
                .toCompletableFuture();
        response = pending.get(profile.requestTimeout().toMillis() + 5000, TimeUnit.MILLISECONDS);
        if (response == null
            || !callId.equals(response.requestId())
            || response.source() != expectedSource)
          throw new IllegalArgumentException("Response binding mismatch");
        // Use the complete contract even if a supplied provider incorrectly accepted a result.
        actual =
            new GradeResultJsonCodec()
                .read(
                    StrictJson.MAPPER.valueToTree(response.result()).toString(),
                    dataset.rubric().question());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        safeError = "CANCELLED";
      } catch (Exception failure) {
        safeError = safeCode(failure);
      } finally {
        if (pending != null && !pending.isDone()) pending.cancel(true);
      }
      Duration elapsed =
          response != null && response.elapsed() != null
              ? response.elapsed()
              : Duration.ofNanos(System.nanoTime() - startedNanos);
      var usage = response == null ? null : response.usage();
      Row row =
          new Row(
              sample.sampleId(),
              sample.sha256(),
              callId,
              sample.expected().status(),
              actual == null ? null : actual.status(),
              GradeValidator.validate(dataset.rubric().question(), sample.expected()).totalScore(),
              actual == null
                  ? null
                  : GradeValidator.validate(dataset.rubric().question(), actual).totalScore(),
              scores(sample.expected()),
              actual == null ? Map.of() : scores(actual),
              safeError,
              response == null ? expectedSource : response.source(),
              usage,
              elapsed);
      boolean interrupted = Thread.interrupted();
      try {
        journal.afterCall(row);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      rows.add(row);
      if (usage != null && usage.estimatedCost() != null)
        knownCost = knownCost.add(usage.estimatedCost());
      else if (!authorization.allowUnknownCost()) {
        stopped = StopReason.COST_UNKNOWN;
        break;
      }
      if (token.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
        stopped = StopReason.CANCELLED;
        break;
      }
      // A configuration/authorization failure stops the batch; no model or parameter fallback.
      if (SetCodes.FATAL.contains(safeError == null ? "" : safeError)) {
        stopped = StopReason.PROVIDER_REJECTED;
        break;
      }
    }
    return new Report(
        dataset.datasetId(),
        dataset.source(),
        datasetDigest,
        profile.id(),
        profile.revision(),
        profile.modelId(),
        profile.endpoint().toString(),
        profileAuthorizationDigest(profile),
        profile.pricing() == null ? null : profile.pricing().currency(),
        expectedSource,
        startedAt,
        clock.instant(),
        dataset.samples().size(),
        (int) dataset.samples().stream().map(EvaluationManifest.Sample::sha256).distinct().count(),
        stopped,
        rows);
  }

  private static Map<String, BigDecimal> scores(GradeResult result) {
    Map<String, BigDecimal> scores = new TreeMap<>();
    for (var criterion : result.criteria())
      if (criterion.awarded() != null) scores.put(criterion.criterionId(), criterion.awarded());
    return Map.copyOf(scores);
  }

  private static String safeCode(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    if (cause instanceof ModelCallException model) return "MODEL_" + model.kind().name();
    if (cause instanceof java.util.concurrent.TimeoutException) return "MODEL_TIMEOUT";
    if (cause instanceof java.util.concurrent.CancellationException) return "CANCELLED";
    return "INVALID_RESPONSE";
  }

  private static final class SetCodes {
    private static final java.util.Set<String> FATAL =
        java.util.Set.of(
            "MODEL_AUTHENTICATION",
            "MODEL_MISSING_CREDENTIAL",
            "MODEL_UNVERIFIED_CONFIGURATION",
            "MODEL_HTTP_ERROR");
  }

  public enum StopReason {
    COMPLETED,
    CALL_LIMIT,
    COST_LIMIT,
    COST_UNKNOWN,
    CANCELLED,
    PROVIDER_REJECTED
  }

  public record Row(
      String sampleId,
      String imageSha256,
      UUID callId,
      GradeResult.Status expectedStatus,
      GradeResult.Status actualStatus,
      BigDecimal expectedTotal,
      BigDecimal actualTotal,
      Map<String, BigDecimal> expectedCriteria,
      Map<String, BigDecimal> actualCriteria,
      String safeErrorCode,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      Duration elapsed) {
    public Row {
      expectedCriteria = Map.copyOf(expectedCriteria);
      actualCriteria = Map.copyOf(actualCriteria);
    }
  }

  public record Report(
      String datasetId,
      EvaluationManifest.DataSource datasetSource,
      String datasetDigest,
      String profileId,
      long profileRevision,
      String modelId,
      String endpoint,
      String profileDigest,
      String costCurrency,
      GradingResponse.Source modelSource,
      Instant startedAt,
      Instant completedAt,
      int plannedSamples,
      int distinctImageHashes,
      StopReason stopReason,
      List<Row> rows) {
    public Report {
      rows = List.copyOf(rows);
    }

    /** Counts and explicit denominators, never a fabricated real-model accuracy score. */
    public ObjectNode toJson() {
      var root = StrictJson.MAPPER.createObjectNode();
      root.put("schemaVersion", 1)
          .put("datasetId", datasetId)
          .put("datasetSource", datasetSource.name())
          .put("datasetDigest", datasetDigest)
          .put("profileId", profileId)
          .put("profileRevision", profileRevision)
          .put("modelId", modelId)
          .put("endpoint", endpoint)
          .put("profileDigest", profileDigest)
          .put("costCurrency", costCurrency)
          .put("modelSource", modelSource.name())
          .put("startedAt", startedAt.toString())
          .put("completedAt", completedAt.toString())
          .put("plannedSamples", plannedSamples)
          .put("distinctImageHashes", distinctImageHashes)
          .put("stopReason", stopReason.name());
      int statusCorrect = 0, scored = 0, unable = 0, errors = 0, scoredPairs = 0, exactTotals = 0;
      int criteriaCompared = 0, criteriaExact = 0, costKnown = 0;
      BigDecimal absoluteError = BigDecimal.ZERO, knownCost = BigDecimal.ZERO;
      List<Long> latency = new ArrayList<>();
      var detail = root.putArray("samples");
      for (var row : rows) {
        detail.add(rowJson(row));
        if (row.safeErrorCode() != null) errors++;
        else {
          if (row.actualStatus() == row.expectedStatus()) statusCorrect++;
          if (row.actualStatus() == GradeResult.Status.UNABLE) unable++;
          else scored++;
        }
        if (row.expectedTotal() != null && row.actualTotal() != null) {
          scoredPairs++;
          BigDecimal difference = row.actualTotal().subtract(row.expectedTotal()).abs();
          absoluteError = absoluteError.add(difference);
          if (difference.signum() == 0) exactTotals++;
          for (var criterion : row.expectedCriteria().entrySet()) {
            criteriaCompared++;
            if (criterion.getValue().compareTo(row.actualCriteria().get(criterion.getKey())) == 0)
              criteriaExact++;
          }
        }
        if (row.usage() != null && row.usage().estimatedCost() != null) {
          costKnown++;
          knownCost = knownCost.add(row.usage().estimatedCost());
        }
        if (row.elapsed() != null) latency.add(row.elapsed().toMillis());
      }
      latency.sort(Long::compareTo);
      var metrics = root.putObject("metrics");
      metrics
          .put("attempted", rows.size())
          .put("scored", scored)
          .put("unable", unable)
          .put("errors", errors)
          .put("statusCorrect", statusCorrect)
          .put("statusDenominator", rows.size())
          .put("exactTotals", exactTotals)
          .put("scoredPairDenominator", scoredPairs)
          .put("exactCriteria", criteriaExact)
          .put("criterionDenominator", criteriaCompared)
          .put("knownCostCalls", costKnown)
          .put("unknownCostCalls", rows.size() - costKnown)
          .put("knownCostTotal", costKnown == 0 ? null : knownCost)
          .put(
              "meanAbsoluteTotalError",
              scoredPairs == 0
                  ? null
                  : absoluteError.divide(BigDecimal.valueOf(scoredPairs), 8, RoundingMode.HALF_UP))
          .put(
              "unableRate",
              rows.isEmpty()
                  ? null
                  : BigDecimal.valueOf(unable)
                      .divide(BigDecimal.valueOf(rows.size()), 8, RoundingMode.HALF_UP));
      if (latency.isEmpty()) {
        metrics.putNull("latencyP50Millis");
        metrics.putNull("latencyP95Millis");
      } else {
        metrics.put("latencyP50Millis", latency.get((int) Math.ceil(latency.size() * .50) - 1));
        metrics.put("latencyP95Millis", latency.get((int) Math.ceil(latency.size() * .95) - 1));
      }
      return root;
    }
  }

  public static ObjectNode rowJson(Row row) {
    var node = StrictJson.MAPPER.createObjectNode();
    node.put("sampleId", row.sampleId())
        .put("imageSha256", row.imageSha256())
        .put("callId", row.callId().toString())
        .put("expectedStatus", row.expectedStatus().name())
        .put("actualStatus", row.actualStatus() == null ? null : row.actualStatus().name())
        .put("expectedTotal", row.expectedTotal())
        .put("actualTotal", row.actualTotal())
        .put("source", row.source().name())
        .put("safeErrorCode", row.safeErrorCode());
    node.set("expectedCriteria", StrictJson.MAPPER.valueToTree(row.expectedCriteria()));
    node.set("actualCriteria", StrictJson.MAPPER.valueToTree(row.actualCriteria()));
    node.set("usage", StrictJson.MAPPER.valueToTree(row.usage()));
    if (row.elapsed() == null) node.putNull("elapsedMillis");
    else node.put("elapsedMillis", row.elapsed().toMillis());
    return node;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
