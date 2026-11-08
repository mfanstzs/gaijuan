package app.deskgrade.desktop;

import app.deskgrade.adapters.commercial.CommercialClient;
import app.deskgrade.adapters.commercial.CommercialSessionCache;
import app.deskgrade.adapters.commercial.CommercialSessionJournal;
import app.deskgrade.adapters.commercial.GatewayException;
import app.deskgrade.adapters.commercial.GatewayGradingProvider;
import app.deskgrade.adapters.commercial.PublicModelProfile;
import app.deskgrade.adapters.model.MockGradingProvider;
import app.deskgrade.adapters.model.OpenAICompatibleVisionProvider;
import app.deskgrade.adapters.model.ProfileJsonCodec;
import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.adapters.persistence.LocalLoggingSessionJournal;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.persistence.PersistentSessionJournal;
import app.deskgrade.adapters.persistence.RecordsCsvExporter;
import app.deskgrade.adapters.windows.CaptureTiming;
import app.deskgrade.adapters.windows.WindowChangeMonitor;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowsGlobalHotkeys;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.adapters.windows.manual.ManualCoordinatePlatformAdapter;
import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.domain.desktop.*;
import app.deskgrade.core.domain.model.*;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.GradingProvider;
import app.deskgrade.core.port.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** UI orchestration only. The session controller is the sole owner of desktop input permissions. */
final class DesktopWorkspace implements AutoCloseable {
  private SessionController.Status lastFailureStatus;

  static final class LocalProfileStorageException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    enum Reason {
      TOO_MANY_PROFILES,
      DOCUMENT_TOO_LARGE,
      INVALID_CONFIGURATION
    }

    private final Reason reason;

    LocalProfileStorageException(Reason reason) {
      super("Local model configuration rejected: " + Objects.requireNonNull(reason).name());
      this.reason = reason;
    }

    Reason reason() {
      return reason;
    }
  }

  enum SelectionFailure {
    WINDOW_UNAVAILABLE,
    WINDOW_CHANGED,
    ACTIVATION_FAILED
  }

  static final class SelectionPreparationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final SelectionFailure reason;

    SelectionPreparationException(SelectionFailure reason) {
      super("Selection preparation failed: " + Objects.requireNonNull(reason));
      this.reason = reason;
    }

    SelectionFailure reason() {
      return reason;
    }
  }

  enum RubricGenerationPendingFailure {
    CONFLICT,
    CORRUPT,
    STORAGE_UNAVAILABLE
  }

  /** Safe UI-facing category; it deliberately contains no account, answer or fingerprint data. */
  static final class RubricGenerationPendingException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final RubricGenerationPendingFailure reason;

    RubricGenerationPendingException(RubricGenerationPendingFailure reason) {
      super(
          switch (Objects.requireNonNull(reason)) {
            case CONFLICT -> "A different rubric generation request is still unresolved";
            case CORRUPT -> "The rubric generation pending receipt is invalid";
            case STORAGE_UNAVAILABLE ->
                "The rubric generation pending receipt could not be persisted";
          });
      this.reason = reason;
    }

    RubricGenerationPendingFailure reason() {
      return reason;
    }
  }

  record Loaded(
      Rubric rubric,
      String rubricSubject,
      List<ProviderProfile> profiles,
      List<SessionJournal.RunRecord> runs) {}

  record UnresolvedSubmissionReview(
      GradingRecords.Owner owner,
      long accountTicket,
      long actionTicket,
      long bindingGeneration,
      ManualPlatformCalibration calibration,
      String targetIdentity,
      List<PersistentSessionJournal.UnresolvedSubmission> submissions) {
    UnresolvedSubmissionReview {
      Objects.requireNonNull(targetIdentity);
      submissions = List.copyOf(submissions);
    }
  }

  private record UnresolvedDecision(
      GradingRecords.Owner owner, String targetIdentity, UUID runId) {}

  record AccountView(
      CommercialClient.SessionView account,
      List<PublicModelProfile> models,
      List<CommercialClient.PackageView> packages,
      List<CommercialClient.OrderView> orders) {
    static AccountView empty() {
      return new AccountView(null, List.of(), List.of(), List.of());
    }
  }

  record ModelSelection(PublicModelProfile commercial, ProviderProfile byok) {
    ModelSelection {
      if ((commercial == null) == (byok == null))
        throw new IllegalArgumentException("Exactly one model selection is required");
    }

    static ModelSelection of(PublicModelProfile profile) {
      return new ModelSelection(Objects.requireNonNull(profile), null);
    }

    static ModelSelection of(ProviderProfile profile) {
      return new ModelSelection(null, Objects.requireNonNull(profile));
    }

    String id() {
      return commercial != null ? commercial.id() : byok.id();
    }

    long revision() {
      return commercial != null ? commercial.revision() : byok.revision();
    }

    String displayName() {
      return commercial != null ? commercial.displayName() : byok.displayName();
    }

    String modelId() {
      return commercial != null ? commercial.modelId() : byok.modelId();
    }

    Duration requestTimeout() {
      return commercial != null ? commercial.requestTimeout() : byok.requestTimeout();
    }

    boolean isVerified() {
      return commercial != null ? commercial.isVerified() : byok.isVerified();
    }

    URI endpoint() {
      if (byok == null) throw new IllegalStateException("Commercial endpoint is server-private");
      return byok.endpoint();
    }

    ProviderProfile.StructuredMode structuredMode() {
      if (byok == null) throw new IllegalStateException("Commercial format is server-private");
      return byok.structuredMode();
    }
  }

  /** Independent role choices; changing an editor tab never changes the active grading model. */
  record LocalModelSelection(String primaryId, String fallbackId, String rubricId) {
    LocalModelSelection {
      if (primaryId == null
          || !primaryId.matches("[A-Za-z0-9_-]{1,100}")
          || rubricId == null
          || !rubricId.matches("[A-Za-z0-9_-]{1,100}"))
        throw new IllegalArgumentException("Primary and rule-generation models are required");
      fallbackId = fallbackId == null ? "" : fallbackId;
      if (!fallbackId.isEmpty() && !fallbackId.matches("[A-Za-z0-9_-]{1,100}"))
        throw new IllegalArgumentException("Invalid fallback model");
      if (primaryId.equals(fallbackId))
        throw new IllegalArgumentException("Fallback must use another model profile");
    }
  }

  private record RubricGenerationInput(
      String subject, String referenceAnswer, BigDecimal maximumScore, String sha256) {}

  private record RubricGenerationScope(
      long accountEpoch,
      CommercialClient client,
      String accountId,
      String serviceAddress,
      String ownerKey) {}

  private enum PendingRubricGenerationState {
    PENDING,
    RESULT_CONFIRMED
  }

  private record PendingRubricGeneration(
      UUID requestId,
      String accountId,
      String serviceAddress,
      RubricGenerationInput input,
      PendingRubricGenerationState state) {
    boolean matches(RubricGenerationScope scope, RubricGenerationInput candidate) {
      return accountId.equals(scope.accountId())
          && serviceAddress.equals(scope.serviceAddress())
          && input.sha256().equals(candidate.sha256())
          && input.subject().equals(candidate.subject())
          && input.referenceAnswer().equals(candidate.referenceAnswer())
          && input.maximumScore().compareTo(candidate.maximumScore()) == 0;
    }
  }

  private record ActiveRubricGeneration(
      String accountId,
      String serviceAddress,
      String inputSha256,
      CompletableFuture<CommercialClient.GeneratedRubric> result) {
    boolean matches(RubricGenerationScope scope, RubricGenerationInput input) {
      return accountId.equals(scope.accountId())
          && serviceAddress.equals(scope.serviceAddress())
          && inputSha256.equals(input.sha256());
    }
  }

  private record RubricSaveTransaction(
      UUID transactionId,
      String rubricBeforeSha256,
      String subjectBeforeSha256,
      String rubricAfterSha256,
      String subjectAfterSha256,
      String rubricJson,
      String subject) {}

  private static final int RUBRIC_FILE_MAX_BYTES = 4 * 1024 * 1024;
  private static final int RUBRIC_SAVE_JOURNAL_MAX_BYTES = 8 * 1024 * 1024;
  private static final String RUBRIC_SAVE_STATE = "PREPARED";

  private final Path root;
  private final boolean developerMode;
  private final boolean localMode;
  private final Clock clock = Clock.systemUTC();
  private final ExecutorService worker;
  private final ObjectMapper json = new ObjectMapper();
  private final ObjectMapper strictJson =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final WindowsWindowService windows = new WindowsWindowService();
  private final WindowRegionCapture capture = new WindowRegionCapture(windows, clock);
  private final DpapiSecretStore secrets;
  static final URI SERVICE_ENDPOINT = ServiceEndpoint.bundled();
  private final URI defaultService;
  private final CommercialSessionCache sessionCache;
  private volatile boolean restoringAccount;
  private final AtomicLong revision = new AtomicLong(1);
  private final AtomicLong trialEpoch = new AtomicLong();
  private final AtomicLong actionRequests = new AtomicLong();
  private volatile long activeAuthorization;
  private volatile long activeSessionAccountEpoch;
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();
  private final Map<String, ProviderProfile> profiles =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private volatile PersistentSessionJournal journal;
  private volatile long journalAccountEpoch = -1;
  private EvidenceStore evidence;
  private volatile TargetBinding binding;
  private final Object bindingLock = new Object();
  private final AtomicLong bindingGeneration = new AtomicLong();
  private volatile ManualPlatformCalibration manualCalibration;

  private record ManualBinding(
      TargetBinding target,
      ManualPlatformCalibration positions,
      String targetIdentity,
      long bindingGeneration,
      long actionTicket,
      long accountEpoch) {}

  private volatile ManualBinding manualBinding;
  private ManualCoordinatePlatformAdapter manualPlatform;
  private String manualPlatformQuestion;
  private volatile SessionController controller;
  private volatile boolean unresolvedRecoveryActive;
  private final Map<UnresolvedDecision, UUID> unresolvedDecisionIds = new ConcurrentHashMap<>();
  private volatile boolean liveSyntheticSession;
  private ManagedProvider automaticProvider;
  private volatile boolean commercialMode = true;
  private volatile LocalModelSelection localModelSelection = LocalModelDefaults.selection();
  private volatile CompletableFuture<CommercialClient.GeneratedRubric> localRubricGeneration;
  private String localRubricGenerationInput;
  private volatile LocalLoggingSessionJournal localJournal;
  private volatile URI gatewayAddress;
  private volatile CommercialClient commercialClient;
  private volatile AccountView accountView = AccountView.empty();
  private volatile long accountPolicyRevision;
  private final AtomicLong accountEpoch = new AtomicLong();
  private final Object accountLock = new Object();
  private final Object rubricGenerationLock = new Object();
  private final Map<String, ActiveRubricGeneration> activeRubricGenerations = new HashMap<>();
  private volatile WindowsGlobalHotkeys hotkeys;
  private WindowChangeMonitor monitor;
  private volatile boolean ready;
  private volatile boolean closed;
  private Rubric rubric = initialRubric();
  private volatile String rubricSubject = "";
  private volatile String selectedSlot = "A";
  private int lastMaxSubmissions = 500;
  private int lastMaxCalls = 500;
  Consumer<SessionController.Status> onStatus = ignored -> {};
  volatile BiConsumer<TargetBinding, Boolean> onAnswerScan = (target, grading) -> {};
  private final Object answerScanLock = new Object();

  void setCaptureBoundary(WindowRegionCapture.CaptureBoundary boundary) {
    capture.setCaptureBoundary(boundary);
  }

  private void hideAnswerScan() {
    try {
      synchronized (answerScanLock) {
        onAnswerScan.accept(null, false);
      }
    } catch (RuntimeException | LinkageError decorationFailure) {
      // Visual cleanup is not allowed to interrupt emergency revocation or resource disposal.
      // A surviving window decoration still has to pass the mandatory capture boundary.
      try {
        org.slf4j.LoggerFactory.getLogger(DesktopWorkspace.class)
            .warn("Scan decoration cleanup failed; desktop action revocation continues");
      } catch (RuntimeException | LinkageError ignored) {
        // Cleanup cannot depend on a healthy logger or JavaFX runtime.
      }
    }
  }

  Consumer<GradingPresentation> onResult = ignored -> {};
  Consumer<String> onNotice = ignored -> {};
  Runnable onResumeRequested = () -> {};
  Runnable onActionRevoked = () -> {};
  Runnable onAccountChanged = () -> {};

  DesktopWorkspace(Path root) {
    this(root, newWorker(), null, SERVICE_ENDPOINT, false, true);
  }

  /** Explicit package-private injection for developer-only tests. */
  DesktopWorkspace(Path root, boolean developerMode) {
    this(root, newWorker(), null, SERVICE_ENDPOINT, developerMode, !developerMode);
  }

  DesktopWorkspace(Path root, ExecutorService worker) {
    this(root, worker, null, SERVICE_ENDPOINT, false, true);
  }

  /** Explicit package-private injection for developer-only tests. */
  DesktopWorkspace(Path root, ExecutorService worker, boolean developerMode) {
    this(root, worker, null, SERVICE_ENDPOINT, developerMode, !developerMode);
  }

  DesktopWorkspace(Path root, ExecutorService worker, SecretStore sessionSecrets, URI service) {
    // A package-private, explicitly injected compatibility harness for old gateway tests only.
    this(root, worker, sessionSecrets, service, false, false);
  }

  private DesktopWorkspace(
      Path root,
      ExecutorService worker,
      SecretStore sessionSecrets,
      URI service,
      boolean developerMode,
      boolean localMode) {
    this.root = root.toAbsolutePath().normalize();
    this.developerMode = developerMode;
    this.localMode = localMode;
    if (localMode) capture.setTimingObserver(DesktopWorkspace::recordCaptureTiming);
    commercialMode = !localMode;
    this.worker = Objects.requireNonNull(worker);
    defaultService = Objects.requireNonNull(service);
    secrets = new DpapiSecretStore(this.root.resolve("credentials"));
    sessionCache = new CommercialSessionCache(sessionSecrets == null ? secrets : sessionSecrets);
  }

  static void recordCaptureTiming(CaptureTiming timing) {
    // Thread-local attribution excludes background window monitors and unrelated UI captures.
    PerformanceTrace.record(
        AttemptPerformance.Metric.CAPTURE_TOTAL, timing.elapsedNanos(), timing.successful());
    timing
        .phaseNanos()
        .forEach(
            (phase, nanos) ->
                PerformanceTrace.record(
                    switch (phase) {
                      case BOUNDARY_PREPARE -> AttemptPerformance.Metric.CAPTURE_BOUNDARY_PREPARE;
                      case SAFETY_BEFORE -> AttemptPerformance.Metric.CAPTURE_SAFETY_BEFORE;
                      case RAW_CAPTURE -> AttemptPerformance.Metric.CAPTURE_RAW;
                      case SAFETY_AFTER -> AttemptPerformance.Metric.CAPTURE_SAFETY_AFTER;
                      case PIXEL_COMPARE -> AttemptPerformance.Metric.CAPTURE_PIXEL_COMPARE;
                      case PNG_ENCODE -> AttemptPerformance.Metric.CAPTURE_PNG_ENCODE;
                      case PNG_REUSE -> AttemptPerformance.Metric.CAPTURE_PNG_REUSE;
                      case DIGEST_AND_RESULT -> AttemptPerformance.Metric.CAPTURE_DIGEST_AND_RESULT;
                      case BOUNDARY_CLOSE -> AttemptPerformance.Metric.CAPTURE_BOUNDARY_CLOSE;
                    },
                    nanos,
                    true));
  }

  private static ExecutorService newWorker() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "deskgrade-ui-worker");
          t.setDaemon(true);
          t.setUncaughtExceptionHandler(DeskGradeApplication::handleUncaughtException);
          return t;
        });
  }

  long actionTicket() {
    return actionRequests.get();
  }

  private void requireTicket(long ticket) {
    if (closed || actionRequests.get() != ticket)
      throw new CancellationException("User action has been revoked");
  }

  static Path defaultDataPath() {
    String override = System.getProperty("deskgrade.dataDir");
    if (override != null && !override.isBlank()) return Path.of(override);
    return app.deskgrade.adapters.runtime.LocalDataDirectoryMigration.defaultDirectory(
        Path.of(System.getProperty("user.home")));
  }

  CompletableFuture<Loaded> initialize() {
    restoringAccount = true;
    return work(() -> {
          Files.createDirectories(root.resolve("settings"));
          recoverPendingRubricSave();
          List<SessionJournal.RunRecord> recovered = List.of();
          if (localMode) {
            localJournal =
                new LocalLoggingSessionJournal(
                    new H2SessionJournal(root.resolve("database/local-deskgrade")),
                    root.resolve("records"));
            journal = localJournal;
            recovered = journal.recoverInterrupted(clock.instant());
          } else if (developerMode) {
            journal = new H2SessionJournal(root.resolve("database/deskgrade"));
            recovered = journal.recoverInterrupted(clock.instant());
          }
          evidence = new MetadataOnlyEvidenceStore();
          Path rubricFile = root.resolve("settings/rubric.json");
          if (Files.exists(rubricFile))
            rubric = new RubricJsonCodec().read(Files.readString(rubricFile));
          Path rubricSubjectFile = root.resolve("settings/rubric-subject.txt");
          if (Files.exists(rubricSubjectFile)) {
            if (Files.size(rubricSubjectFile) > 512)
              throw new IllegalArgumentException("Rubric subject configuration too large");
            rubricSubject = normalizeRubricSubject(Files.readString(rubricSubjectFile));
          }
          Path modelFile = profileFile();
          if (Files.exists(modelFile)) {
            if (Files.size(modelFile) > 1024 * 1024)
              throw new IllegalArgumentException("Model configuration is too large");
            for (ProviderProfile p : new ProfileJsonCodec().readLocal(Files.readString(modelFile)))
              profiles.put(p.id(), p);
          }
          if (localMode) {
            if (profiles.isEmpty()) {
              for (ProviderProfile profile : LocalModelDefaults.profiles())
                profiles.put(profile.id(), profile);
              persistProfiles();
            }
            loadLocalModelSelection();
            profiles.values().forEach(localJournal::registerProfile);
          }
          Path accessFile = root.resolve("settings/access.json");
          if (developerMode && Files.exists(accessFile)) {
            if (Files.size(accessFile) > 8192)
              throw new IllegalArgumentException("Access configuration too large");
            var access = json.readTree(Files.readString(accessFile));
            if (access.path("schemaVersion").asInt() != 1)
              throw new IllegalArgumentException("Access configuration version");
            commercialMode = !developerMode || !"BYOK".equals(access.path("mode").asText());
            String service = access.path("serviceAddress").asText();
            if (developerMode && !service.isBlank()) {
              gatewayAddress = URI.create(service);
            }
          }
          if (!localMode && gatewayAddress == null) gatewayAddress = defaultService;
          if (developerMode && !commercialMode) clearManualCalibration();
          if (localMode) clearManualCalibration();
          else commercialClient = new CommercialClient(gatewayAddress, clock);
          ready = true;
          return new Loaded(
              rubric,
              rubricSubject,
              List.copyOf(profiles.values()),
              developerMode || localMode ? recovered : List.of());
        })
        .thenCompose(
            loaded ->
                restoreAccount()
                    .handle(
                        (ignored, failure) -> {
                          if (failure != null) onNotice.accept("保存的登录暂时无法验证，请重新登录或稍后重试；未启动阅卷。");
                          return loaded;
                        }))
        .whenComplete(
            (ignored, failure) -> {
              restoringAccount = false;
              onAccountChanged.run();
            });
  }

  boolean restoringAccount() {
    return restoringAccount;
  }

  private CompletableFuture<AccountView> restoreAccount() {
    long epoch = accountEpoch.get();
    if (!commercialMode) return CompletableFuture.completedFuture(AccountView.empty());
    return work(() -> {
          requireAccountEpoch(epoch);
          return commercialClient.restoreSession(sessionCache);
        })
        .thenCompose(CompletionStage::toCompletableFuture)
        .thenComposeAsync(
            saved -> {
              requireAccountEpoch(epoch);
              if (saved.isEmpty()) return CompletableFuture.completedFuture(AccountView.empty());
              bindAccountJournal(epoch, commercialClient);
              var restoredAccount = saved.orElseThrow();
              synchronized (accountLock) {
                requireAccountEpoch(epoch);
                accountView = new AccountView(restoredAccount, List.of(), List.of(), List.of());
              }
              clearManualCalibration(epoch);
              requireAccountEpoch(epoch);
              return refreshAccount(epoch);
            },
            worker)
        .whenComplete((ignored, failure) -> handleAccountFailure(epoch, failure));
  }

  boolean ready() {
    return ready && !closed;
  }

  boolean commercialMode() {
    return commercialMode;
  }

  boolean localMode() {
    return localMode;
  }

  boolean localOnlyRuntime() {
    return localMode && !commercialMode && commercialClient == null;
  }

  boolean developerMode() {
    return developerMode;
  }

  long accountTicket() {
    return accountEpoch.get();
  }

  String gatewayAddress() {
    return gatewayAddress == null ? "" : gatewayAddress.toString();
  }

  AccountView accountView() {
    if (localMode) return AccountView.empty();
    synchronized (accountLock) {
      if (!developerMode && (journal == null || journalAccountEpoch != accountEpoch.get()))
        return AccountView.empty();
      var view = accountView;
      var client = commercialClient;
      var account = client == null ? null : client.currentSession().orElse(null);
      if (account == null
          || view.account() == null
          || !account.accountId().equals(view.account().accountId())) return AccountView.empty();
      var policy = client.currentModelPolicy().orElse(null);
      boolean current = policy != null && policy.revision() == accountPolicyRevision;
      return new AccountView(
          account, current ? view.models() : List.of(), view.packages(), view.orders());
    }
  }

  boolean commercialCreditsAvailable() {
    var account = accountView().account();
    return account != null && account.credits() > 0;
  }

  private CompletableFuture<CommercialClient.GeneratedRubric> generateLocalRubric(
      String subject, String referenceAnswer, BigDecimal maximumScore) {
    final RubricGenerationInput input;
    try {
      input = rubricGenerationInput(subject, referenceAnswer, maximumScore);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    synchronized (rubricGenerationLock) {
      if (localRubricGeneration != null && !localRubricGeneration.isDone()) {
        if (input.sha256().equals(localRubricGenerationInput)) return localRubricGeneration.copy();
        return CompletableFuture.failedFuture(
            new RubricGenerationPendingException(RubricGenerationPendingFailure.CONFLICT));
      }
      pause(PauseReason.USER_PAUSED);
      long epoch = trialEpoch.get();
      UUID requestId = UUID.randomUUID();
      Instant started = clock.instant();
      var result = new CompletableFuture<CommercialClient.GeneratedRubric>();
      localRubricGeneration = result;
      localRubricGenerationInput = input.sha256();
      work(() -> {
            if (!generationModelAvailable())
              throw new IllegalStateException("请先在模型设置中保存 Key 并验证规则生成模型的文本能力");
            ProviderProfile profile =
                Objects.requireNonNull(profiles.get(localModelSelection.rubricId()));
            requireSavedProfile(profile);
            requireJournal().assertWritable();
            localJournal.beginUtilityModelCall(
                requestId,
                LocalLoggingSessionJournal.UtilityPurpose.RUBRIC_GENERATION,
                profile,
                started);
            return new LocalGenerationContext(
                profile,
                new OpenAICompatibleVisionProvider(
                    profile,
                    secrets,
                    clock,
                    () -> {
                      if (closed || trialEpoch.get() != epoch)
                        throw new CancellationException("Configuration changed before request");
                      requireSavedProfile(profile);
                    }));
          })
          .thenCompose(
              context ->
                  context
                      .provider()
                      .generateRubric(
                          input.subject(),
                          input.referenceAnswer(),
                          input.maximumScore(),
                          () -> closed || trialEpoch.get() != epoch)
                      .toCompletableFuture()
                      .handle(
                          (generated, failure) ->
                              work(
                                  () -> {
                                    localJournal.recordUtilityModelCall(
                                        requestId,
                                        LocalLoggingSessionJournal.UtilityPurpose.RUBRIC_GENERATION,
                                        context.profile(),
                                        generated == null ? null : generated.usage(),
                                        generated == null
                                            ? Duration.between(started, clock.instant())
                                            : generated.elapsed(),
                                        failure == null ? null : modelFailureCode(failure),
                                        clock.instant());
                                    if (failure != null) throw new CompletionException(failure);
                                    if (closed || trialEpoch.get() != epoch)
                                      throw new CancellationException(
                                          "Late generated rubric discarded");
                                    requireSavedProfile(context.profile());
                                    return new CommercialClient.GeneratedRubric(
                                        requestId,
                                        generated.scoreStep(),
                                        generated.criteria(),
                                        clock.instant(),
                                        generated.usage(),
                                        0,
                                        false);
                                  }))
                      .thenCompose(java.util.function.Function.identity())
                      .whenComplete((generated, failure) -> context.provider().close()))
          .whenComplete(
              (generated, failure) -> {
                if (failure == null) result.complete(generated);
                else result.completeExceptionally(unwrapCompletionFailure(failure));
              });
      return result.copy();
    }
  }

  private record LocalGenerationContext(
      ProviderProfile profile, OpenAICompatibleVisionProvider provider) {}

  CompletableFuture<CommercialClient.GeneratedRubric> generateRubric(
      String subject, String referenceAnswer, BigDecimal maximumScore) {
    if (localMode) return generateLocalRubric(subject, referenceAnswer, maximumScore);
    RubricGenerationInput input;
    RubricGenerationScope scope;
    try {
      input = rubricGenerationInput(subject, referenceAnswer, maximumScore);
      long epoch = accountEpoch.get();
      synchronized (accountLock) {
        requireAccountEpoch(epoch);
        var client = commercialClient;
        if (!commercialMode || client == null || !client.isAuthenticated())
          throw new IllegalStateException("Login required");
        var account = accountView().account();
        if (account == null) throw new IllegalStateException("Login required");
        if (account.credits() <= 0) throw new IllegalStateException("No credits remaining");
        scope =
            new RubricGenerationScope(
                epoch,
                client,
                account.accountId(),
                Objects.requireNonNull(gatewayAddress).toString(),
                calibrationOwnerKey(account.accountId(), gatewayAddress));
      }
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }

    ActiveRubricGeneration operation;
    synchronized (rubricGenerationLock) {
      ActiveRubricGeneration active = activeRubricGenerations.get(scope.ownerKey());
      if (active != null && !active.result().isDone()) {
        if (active.matches(scope, input)) return active.result().copy();
        return CompletableFuture.failedFuture(
            new RubricGenerationPendingException(RubricGenerationPendingFailure.CONFLICT));
      }
      operation =
          new ActiveRubricGeneration(
              scope.accountId(), scope.serviceAddress(), input.sha256(), new CompletableFuture<>());
      activeRubricGenerations.put(scope.ownerKey(), operation);
    }

    CompletableFuture<CommercialClient.GeneratedRubric> pipeline =
        work(() -> {
              synchronized (accountLock) {
                requireRubricGenerationScope(scope);
                return prepareRubricGeneration(scope, input);
              }
            })
            .thenCompose(pending -> dispatchRubricGeneration(scope, input, pending))
            .thenApplyAsync(
                generated -> {
                  // The direct client result has already marked the pending receipt confirmed.
                  // This separate guard only prevents a late result from mutating another UI
                  // account. The UI finalizes the receipt only after saving or rejecting it.
                  synchronized (accountLock) {
                    requireRubricGenerationScope(scope);
                  }
                  return generated;
                },
                worker);
    pipeline.whenComplete(
        (generated, failure) -> {
          synchronized (rubricGenerationLock) {
            if (activeRubricGenerations.get(scope.ownerKey()) == operation)
              activeRubricGenerations.remove(scope.ownerKey());
          }
          if (failure == null) operation.result().complete(generated);
          else operation.result().completeExceptionally(unwrapCompletionFailure(failure));
          if (accountEpoch.get() == scope.accountEpoch() && commercialClient == scope.client())
            onAccountChanged.run();
        });
    return operation.result().copy();
  }

  /**
   * Removes a server-confirmed receipt only after the generated rule was durably saved or the user
   * explicitly rejected that confirmed result. Unknown outcomes cannot be finalized here.
   */
  CompletableFuture<Void> finalizeConfirmedRubricGeneration(UUID requestId) {
    Objects.requireNonNull(requestId);
    if (localMode) return CompletableFuture.completedFuture(null);
    RubricGenerationScope scope;
    try {
      long epoch = accountEpoch.get();
      synchronized (accountLock) {
        requireAccountEpoch(epoch);
        var client = commercialClient;
        if (!commercialMode || client == null || !client.isAuthenticated())
          throw new IllegalStateException("Login required");
        var account = client.currentSession().orElseThrow();
        scope =
            new RubricGenerationScope(
                epoch,
                client,
                account.accountId(),
                Objects.requireNonNull(gatewayAddress).toString(),
                calibrationOwnerKey(account.accountId(), gatewayAddress));
      }
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    return work(
        () -> {
          synchronized (accountLock) {
            requireRubricGenerationScope(scope);
            synchronized (rubricGenerationLock) {
              PendingRubricGeneration current = readPendingRubricGeneration(scope.ownerKey());
              if (current == null) return null;
              if (!current.requestId().equals(requestId)
                  || !current.accountId().equals(scope.accountId())
                  || !current.serviceAddress().equals(scope.serviceAddress())
                  || current.state() != PendingRubricGenerationState.RESULT_CONFIRMED)
                throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CONFLICT);
              deletePendingRubricGeneration(scope.ownerKey());
            }
          }
          return null;
        });
  }

  private CompletableFuture<CommercialClient.GeneratedRubric> dispatchRubricGeneration(
      RubricGenerationScope scope, RubricGenerationInput input, PendingRubricGeneration pending) {
    CompletionStage<CommercialClient.GeneratedRubric> direct;
    try {
      synchronized (accountLock) {
        requireRubricGenerationScope(scope);
        direct =
            scope
                .client()
                .generateRubric(
                    pending.requestId(),
                    input.subject(),
                    input.referenceAnswer(),
                    input.maximumScore());
      }
    } catch (RuntimeException failure) {
      direct = CompletableFuture.failedFuture(failure);
    }
    return direct
        .handle(
            (generated, failure) -> {
              if (failure == null) {
                markPendingRubricGenerationConfirmed(pending);
                return generated;
              }
              Throwable cause = unwrapCompletionFailure(failure);
              if (retainPendingRubricGeneration(pending, cause))
                ensurePendingRubricGeneration(pending);
              else clearKnownFailedRubricGeneration(pending);
              throw new CompletionException(cause);
            })
        .toCompletableFuture();
  }

  private void requireRubricGenerationScope(RubricGenerationScope scope) {
    requireAccountEpoch(scope.accountEpoch());
    if (!commercialMode
        || commercialClient != scope.client()
        || gatewayAddress == null
        || !gatewayAddress.toString().equals(scope.serviceAddress()))
      throw new CancellationException("Account or service changed");
    var account = scope.client().currentSession().orElse(null);
    if (account == null || !account.accountId().equals(scope.accountId()))
      throw new CancellationException("Account changed");
  }

  private PendingRubricGeneration prepareRubricGeneration(
      RubricGenerationScope scope, RubricGenerationInput input) {
    synchronized (rubricGenerationLock) {
      PendingRubricGeneration existing = readPendingRubricGeneration(scope.ownerKey());
      if (existing != null) {
        if (!existing.matches(scope, input))
          throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CONFLICT);
        return existing;
      }
      PendingRubricGeneration created =
          new PendingRubricGeneration(
              UUID.randomUUID(),
              scope.accountId(),
              scope.serviceAddress(),
              input,
              PendingRubricGenerationState.PENDING);
      writePendingRubricGeneration(scope.ownerKey(), created);
      PendingRubricGeneration verified = readPendingRubricGeneration(scope.ownerKey());
      if (!samePendingRubricGeneration(created, verified))
        throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
      return created;
    }
  }

  private void markPendingRubricGenerationConfirmed(PendingRubricGeneration expected) {
    synchronized (rubricGenerationLock) {
      String ownerKey = pendingRubricGenerationOwnerKey(expected);
      PendingRubricGeneration current = readPendingRubricGeneration(ownerKey);
      if (!samePendingRubricGeneration(expected, current))
        throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
      if (current.state() == PendingRubricGenerationState.RESULT_CONFIRMED) return;
      writePendingRubricGeneration(
          ownerKey,
          new PendingRubricGeneration(
              current.requestId(),
              current.accountId(),
              current.serviceAddress(),
              current.input(),
              PendingRubricGenerationState.RESULT_CONFIRMED));
    }
  }

  private void ensurePendingRubricGeneration(PendingRubricGeneration expected) {
    synchronized (rubricGenerationLock) {
      String ownerKey = pendingRubricGenerationOwnerKey(expected);
      PendingRubricGeneration current = readPendingRubricGeneration(ownerKey);
      if (current == null) {
        writePendingRubricGeneration(ownerKey, expected);
        return;
      }
      if (!samePendingRubricGeneration(expected, current))
        throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
    }
  }

  private void clearKnownFailedRubricGeneration(PendingRubricGeneration expected) {
    synchronized (rubricGenerationLock) {
      String ownerKey = pendingRubricGenerationOwnerKey(expected);
      PendingRubricGeneration current = readPendingRubricGeneration(ownerKey);
      if (current == null) return;
      if (!samePendingRubricGeneration(expected, current))
        throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
      if (current.state() == PendingRubricGenerationState.RESULT_CONFIRMED) return;
      deletePendingRubricGeneration(ownerKey);
    }
  }

  private static boolean retainPendingRubricGeneration(
      PendingRubricGeneration pending, Throwable failure) {
    if (pending.state() == PendingRubricGenerationState.RESULT_CONFIRMED) return true;
    if (failure instanceof GatewayException gateway) {
      if (gateway.chargedCredits() == null) return true;
      return Set.of(
              GatewayException.Code.TIMEOUT,
              GatewayException.Code.NETWORK,
              GatewayException.Code.SESSION_CHANGED,
              GatewayException.Code.CLIENT_CLOSED,
              GatewayException.Code.CANCELLED,
              GatewayException.Code.REQUEST_IN_PROGRESS,
              GatewayException.Code.DISPATCH_UNKNOWN,
              GatewayException.Code.STORE_UNAVAILABLE,
              GatewayException.Code.INTERNAL_ERROR)
          .contains(gateway.code());
    }
    if (failure instanceof CancellationException) return true;
    // All input validation happens before the receipt is written. Any other late local failure has
    // an uncertain dispatch boundary, so preserving the receipt is the only safe default.
    return !(failure instanceof IllegalArgumentException);
  }

  private PendingRubricGeneration readPendingRubricGeneration(String ownerKey) {
    Path path = pendingRubricGenerationPath(ownerKey);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try {
      if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException();
      byte[] bytes;
      try (var input = Files.newInputStream(path)) {
        bytes = input.readNBytes(512 * 1024 + 1);
      }
      if (bytes.length == 0 || bytes.length > 512 * 1024) throw new IllegalArgumentException();
      var node = strictJson.readTree(bytes);
      if (node == null || !node.isObject()) throw new IllegalArgumentException();
      Set<String> names = new HashSet<>();
      node.fieldNames().forEachRemaining(names::add);
      if (!names.equals(
          Set.of(
              "schemaVersion",
              "requestId",
              "accountId",
              "serviceAddress",
              "inputSha256",
              "subject",
              "referenceAnswer",
              "maxScore",
              "state"))) throw new IllegalArgumentException();
      var schema = node.get("schemaVersion");
      if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 1)
        throw new IllegalArgumentException();
      String rawRequestId = pendingText(node, "requestId", 36);
      UUID requestId = UUID.fromString(rawRequestId);
      if (!requestId.toString().equalsIgnoreCase(rawRequestId))
        throw new IllegalArgumentException();
      String accountId = pendingText(node, "accountId", 100);
      if (!accountId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
      String serviceAddress = pendingText(node, "serviceAddress", 2048);
      validatePendingServiceAddress(serviceAddress);
      if (!pendingRubricGenerationOwnerKey(accountId, serviceAddress).equals(ownerKey))
        throw new IllegalArgumentException();
      String inputSha256 = pendingText(node, "inputSha256", 64);
      if (!inputSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      String subject = pendingText(node, "subject", 200);
      String referenceAnswer = pendingText(node, "referenceAnswer", 128_000);
      var maximumNode = node.get("maxScore");
      if (maximumNode == null || !maximumNode.isNumber()) throw new IllegalArgumentException();
      RubricGenerationInput recovered =
          rubricGenerationInput(subject, referenceAnswer, maximumNode.decimalValue());
      if (!recovered.sha256().equals(inputSha256)) throw new IllegalArgumentException();
      PendingRubricGenerationState state =
          PendingRubricGenerationState.valueOf(pendingText(node, "state", 32));
      return new PendingRubricGeneration(requestId, accountId, serviceAddress, recovered, state);
    } catch (RubricGenerationPendingException failure) {
      throw failure;
    } catch (Exception | LinkageError failure) {
      throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
    }
  }

  private void writePendingRubricGeneration(String ownerKey, PendingRubricGeneration pending) {
    if (!pendingRubricGenerationOwnerKey(pending).equals(ownerKey))
      throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
    try {
      var node = json.createObjectNode();
      node.put("schemaVersion", 1);
      node.put("requestId", pending.requestId().toString());
      node.put("accountId", pending.accountId());
      node.put("serviceAddress", pending.serviceAddress());
      node.put("inputSha256", pending.input().sha256());
      node.put("subject", pending.input().subject());
      node.put("referenceAnswer", pending.input().referenceAnswer());
      node.put("maxScore", pending.input().maximumScore());
      node.put("state", pending.state().name());
      byte[] encoded = json.writeValueAsBytes(node);
      if (encoded.length > 512 * 1024) throw new IllegalArgumentException();
      saveText(pendingRubricGenerationPath(ownerKey), new String(encoded, StandardCharsets.UTF_8));
    } catch (Exception | LinkageError failure) {
      throw new RubricGenerationPendingException(
          RubricGenerationPendingFailure.STORAGE_UNAVAILABLE);
    }
  }

  private void deletePendingRubricGeneration(String ownerKey) {
    try {
      // A directory-entry removal is atomic: another process sees either the complete receipt or
      // no receipt, never a partially cleared JSON document.
      Files.deleteIfExists(pendingRubricGenerationPath(ownerKey));
    } catch (Exception | LinkageError failure) {
      throw new RubricGenerationPendingException(
          RubricGenerationPendingFailure.STORAGE_UNAVAILABLE);
    }
  }

  private Path pendingRubricGenerationPath(String ownerKey) {
    if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}"))
      throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
    return root.resolve("settings/accounts")
        .resolve(ownerKey)
        .resolve("rubric-generation-pending.json");
  }

  private static String pendingRubricGenerationOwnerKey(PendingRubricGeneration pending) {
    return pendingRubricGenerationOwnerKey(pending.accountId(), pending.serviceAddress());
  }

  private static String pendingRubricGenerationOwnerKey(String accountId, String serviceAddress) {
    try {
      return calibrationOwnerKey(accountId, URI.create(serviceAddress));
    } catch (RuntimeException failure) {
      throw new RubricGenerationPendingException(RubricGenerationPendingFailure.CORRUPT);
    }
  }

  private static String pendingText(
      com.fasterxml.jackson.databind.JsonNode node, String field, int maximumCharacters) {
    var value = node.get(field);
    if (value == null || !value.isTextual()) throw new IllegalArgumentException();
    String text = value.textValue();
    if (text.isEmpty() || text.length() > maximumCharacters || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException();
    return text;
  }

  private static void validatePendingServiceAddress(String value) {
    URI service = URI.create(value);
    if (service.getHost() == null
        || service.getUserInfo() != null
        || service.getRawQuery() != null
        || service.getRawFragment() != null
        || !(service.getRawPath().isEmpty() || service.getRawPath().equals("/"))
        || !("https".equals(service.getScheme())
            || ("http".equals(service.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(service.getHost()))))
      throw new IllegalArgumentException();
  }

  private static boolean samePendingRubricGeneration(
      PendingRubricGeneration expected, PendingRubricGeneration actual) {
    return actual != null
        && expected.requestId().equals(actual.requestId())
        && expected.accountId().equals(actual.accountId())
        && expected.serviceAddress().equals(actual.serviceAddress())
        && expected.input().sha256().equals(actual.input().sha256())
        && expected.input().subject().equals(actual.input().subject())
        && expected.input().referenceAnswer().equals(actual.input().referenceAnswer())
        && expected.input().maximumScore().compareTo(actual.input().maximumScore()) == 0;
  }

  private static RubricGenerationInput rubricGenerationInput(
      String subject, String referenceAnswer, BigDecimal maximumScore) {
    String normalizedSubject = normalizeRubricGenerationText(subject, 100, true);
    String normalizedReference = normalizeRubricGenerationText(referenceAnswer, 64_000, false);
    BigDecimal normalizedMaximum = maximumScore == null ? null : maximumScore.stripTrailingZeros();
    if (normalizedMaximum == null
        || normalizedMaximum.signum() <= 0
        || normalizedMaximum.compareTo(new BigDecimal("100")) > 0
        || normalizedMaximum.scale() > 8)
      throw new IllegalArgumentException("Invalid rubric generation input");
    return new RubricGenerationInput(
        normalizedSubject,
        normalizedReference,
        normalizedMaximum,
        rubricGenerationInputSha256(normalizedSubject, normalizedReference, normalizedMaximum));
  }

  private static String normalizeRubricGenerationText(
      String value, int maximumCodePoints, boolean singleLine) {
    if (value == null) throw new IllegalArgumentException("Invalid rubric generation input");
    String normalized = value.trim();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1
        || length > maximumCodePoints
        || normalized.indexOf('\0') >= 0
        || singleLine && normalized.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid rubric generation input");
    return normalized;
  }

  private static String rubricGenerationInputSha256(
      String subject, String referenceAnswer, BigDecimal maximumScore) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("rubric-generation-v1".getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(subject.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(referenceAnswer.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return HexFormat.of()
          .formatHex(digest.digest(maximumScore.toPlainString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static Throwable unwrapCompletionFailure(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0;
        depth < 10
            && (current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null
            && current.getCause() != current;
        depth++) current = current.getCause();
    return current;
  }

  long modelPolicyRevision() {
    return accountPolicyRevision;
  }

  private void revokeAccount() {
    if (closed) return;
    invalidate();
    stop();
    synchronized (accountLock) {
      accountEpoch.incrementAndGet();
      accountView = AccountView.empty();
      accountPolicyRevision = 0;
      if (!developerMode) {
        var oldJournal = journal;
        journal = null;
        journalAccountEpoch = -1;
        if (oldJournal != null) oldJournal.close();
      }
      synchronized (bindingLock) {
        bindingGeneration.incrementAndGet();
        binding = null;
        manualBinding = null;
      }
      clearManualCalibration();
      var client = commercialClient;
      if (client != null) client.logout();
    }
    // Same worker serializes cache writes and removal; an old login cannot recreate the cache.
    worker.execute(
        () -> {
          try {
            app.deskgrade.adapters.runtime.ResourceCleanup.closeAll(
                sessionCache::clear,
                this::closeMonitor,
                this::closeAutomaticProvider,
                () -> {
                  var registered = hotkeys;
                  hotkeys = null;
                  if (registered != null) registered.close();
                });
          } catch (Exception | LinkageError failure) {
            onNotice.accept("退出清理未全部完成；本地执行已停止，请检查数据目录权限。");
          }
        });
    onAccountChanged.run();
  }

  /** Called as soon as the service field changes; the old account cannot authorize further work. */
  void accountServiceEdited() {
    if (localMode) return;
    revokeAccount();
  }

  void logoutAccount() {
    if (localMode) return;
    revokeAccount();
  }

  CompletableFuture<Void> setCommercialMode(boolean next) {
    if (localMode)
      return next
          ? CompletableFuture.failedFuture(new IllegalStateException("本地版本不连接账号服务器"))
          : CompletableFuture.completedFuture(null);
    if (!next && !developerMode)
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              "Personal provider mode is available only in explicit developer mode"));
    revokeAccount();
    long epoch = accountEpoch.get();
    return work(
        () -> {
          requireAccountEpoch(epoch);
          saveAccess(next, gatewayAddress);
          synchronized (accountLock) {
            requireAccountEpoch(epoch);
            commercialMode = next;
          }
          if (!next) clearManualCalibration(epoch);
          return null;
        });
  }

  CompletableFuture<Void> saveGatewayAddress(String address) {
    if (localMode) return CompletableFuture.failedFuture(new IllegalStateException("本地版本不连接账号服务器"));
    if (!developerMode && !defaultService.toString().equals(address.trim()))
      return CompletableFuture.failedFuture(new IllegalStateException("账号服务地址由应用固定配置"));
    revokeAccount();
    long epoch = accountEpoch.get();
    return work(
        () -> {
          requireAccountEpoch(epoch);
          URI selected = URI.create(address.trim());
          CommercialClient next = new CommercialClient(selected, clock);
          try {
            saveAccess(commercialMode, selected);
            requireAccountEpoch(epoch);
          } catch (Exception failure) {
            next.close();
            throw failure;
          }
          CommercialClient previous;
          synchronized (accountLock) {
            try {
              requireAccountEpoch(epoch);
            } catch (RuntimeException failure) {
              next.close();
              throw failure;
            }
            previous = commercialClient;
            commercialClient = next;
            gatewayAddress = selected;
          }
          if (previous != null) previous.close();
          return null;
        });
  }

  private void saveAccess(boolean commercial, URI service) throws Exception {
    saveText(
        root.resolve("settings/access.json"),
        json.writeValueAsString(
            Map.of(
                "schemaVersion",
                1,
                "mode",
                commercial ? "COMMERCIAL" : "BYOK",
                "serviceAddress",
                service == null ? "" : service.toString())));
  }

  private void requireAccountEpoch(long epoch) {
    if (closed || accountEpoch.get() != epoch) throw new CancellationException("Account changed");
  }

  CompletableFuture<AccountView> authenticate(String username, char[] password, boolean register) {
    if (localMode) {
      if (password != null) Arrays.fill(password, '\0');
      return CompletableFuture.failedFuture(new IllegalStateException("本地版本无需账号登录"));
    }
    revokeAccount();
    long epoch = accountEpoch.get();
    return work(() -> {
          try {
            synchronized (accountLock) {
              requireAccountEpoch(epoch);
              if (!commercialMode || commercialClient == null)
                throw new IllegalStateException("Account service is not configured");
              return register
                  ? commercialClient.register(username, password)
                  : commercialClient.login(username, password);
            }
          } finally {
            Arrays.fill(password, '\0');
          }
        })
        .thenCompose(CompletionStage::toCompletableFuture)
        .thenComposeAsync(
            account -> {
              bindAccountJournal(epoch, commercialClient);
              synchronized (accountLock) {
                requireAccountEpoch(epoch);
                commercialClient.saveSession(sessionCache);
                accountView = new AccountView(account, List.of(), List.of(), List.of());
              }
              clearManualCalibration(epoch);
              requireAccountEpoch(epoch);
              onAccountChanged.run();
              return refreshAccount(epoch);
            },
            worker)
        .whenComplete(
            (ignored, failure) -> {
              Arrays.fill(password, '\0');
              handleAccountFailure(epoch, failure);
            });
  }

  CompletableFuture<AccountView> refreshAccount() {
    if (localMode) return CompletableFuture.completedFuture(AccountView.empty());
    return refreshAccount(accountEpoch.get());
  }

  private CompletableFuture<AccountView> refreshAccount(long epoch) {
    var client = commercialClient;
    try {
      requireAccountEpoch(epoch);
      if (!commercialMode || client == null || !client.isAuthenticated())
        throw new IllegalStateException("Login required");
      return client
          .me()
          .thenCombine(
              client.models(),
              (account, models) -> new AccountView(account, models, List.of(), List.of()))
          .thenCombine(
              client.packages(),
              (view, packs) -> new AccountView(view.account(), view.models(), packs, List.of()))
          .thenCombine(
              client.orders(),
              (view, orders) ->
                  new AccountView(view.account(), view.models(), view.packages(), orders))
          .thenApplyAsync(
              view -> {
                bindAccountJournal(epoch, client);
                boolean modelsChanged;
                synchronized (accountLock) {
                  requireAccountEpoch(epoch);
                  if (client != commercialClient)
                    throw new CancellationException("Service changed");
                  long policyRevision = client.currentModelPolicy().orElseThrow().revision();
                  modelsChanged =
                      !accountView.models().equals(view.models())
                          || accountPolicyRevision != policyRevision;
                  accountPolicyRevision = policyRevision;
                  accountView = view;
                }
                if (modelsChanged) invalidate();
                if (view.account().credits() == 0) stopIfIdleForCredits();
                onAccountChanged.run();
                return view;
              },
              worker)
          .whenComplete(
              (ignored, failure) -> {
                if (failure != null && accountEpoch.get() == epoch && commercialClient == client) {
                  handleAccountFailure(epoch, failure);
                  Throwable cause = failure;
                  while (cause.getCause() != null && cause.getCause() != cause)
                    cause = cause.getCause();
                  if (cause instanceof GatewayException gateway
                      && gateway.code() == GatewayException.Code.MODEL_POLICY_CHANGED) invalidate();
                  onAccountChanged.run();
                }
              })
          .toCompletableFuture();
    } catch (RuntimeException failure) {
      handleAccountFailure(epoch, failure);
      return CompletableFuture.failedFuture(failure);
    }
  }

  /** Runs on the worker; no accountLock is held while waiting for MySQL commit acknowledgements. */
  private void bindAccountJournal(long epoch, CommercialClient client) {
    if (localMode) throw new IllegalStateException("Local records cannot bind a server account");
    requireAccountEpoch(epoch);
    if (developerMode) return;
    if (journal != null && journalAccountEpoch == epoch) return;
    var next = new CommercialSessionJournal(Objects.requireNonNull(client));
    boolean installed = false;
    try {
      next.assertWritable();
      requireAccountEpoch(epoch);
      next.recoverInterrupted(clock.instant());
      synchronized (accountLock) {
        requireAccountEpoch(epoch);
        if (commercialClient != client) throw new CancellationException("Service changed");
        var previous = journal;
        journal = next;
        journalAccountEpoch = epoch;
        installed = true;
        if (previous != null) previous.close();
      }
    } catch (RuntimeException failure) {
      onNotice.accept("在线阅卷记录暂时不可用，已禁止启动阅卷；请稍后重新登录再试。");
      throw failure;
    } finally {
      if (!installed) next.close();
    }
  }

  private PersistentSessionJournal requireJournal() {
    var selected = journal;
    if (selected == null
        || (!localMode && !developerMode && journalAccountEpoch != accountEpoch.get()))
      throw new IllegalStateException(localMode ? "本地阅卷记录尚未就绪，请检查数据目录权限" : "在线阅卷记录尚未就绪，请重新登录后再试");
    return selected;
  }

  private void handleAccountFailure(long epoch, Throwable failure) {
    if (failure == null || accountEpoch.get() != epoch || closed) return;
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    if (cause instanceof GatewayException gateway
        && (gateway.code() == GatewayException.Code.AUTH_REQUIRED
            || gateway.code() == GatewayException.Code.ACCOUNT_DISABLED)) {
      revokeAccount();
      onNotice.accept("登录已失效或账号不可用，请重新登录。");
    }
  }

  private void stopForCredits() {
    var c = controller;
    if (c != null) c.stop(PauseReason.CREDITS_EXHAUSTED);
    onNotice.accept("可用次数已用完，本轮阅卷已停止；补充次数后可重新开始。");
  }

  private void stopIfIdleForCredits() {
    var current = status();
    if (current == null
        || Set.of(
                SessionState.IDLE,
                SessionState.PRECHECK,
                SessionState.WAIT_READY,
                SessionState.PAUSED,
                SessionState.STOPPED,
                SessionState.COMPLETED)
            .contains(current.state())) stopForCredits();
  }

  CompletableFuture<CommercialClient.OrderView> createPackageOrder(String packageId) {
    long epoch = accountEpoch.get();
    var client = commercialClient;
    if (!commercialMode || client == null || !client.isAuthenticated())
      return CompletableFuture.failedFuture(new IllegalStateException("Login required"));
    return client
        .createOrder(packageId)
        .thenCompose(
            order -> {
              requireAccountEpoch(epoch);
              return refreshAccount(epoch).thenApply(ignored -> order);
            })
        .toCompletableFuture();
  }

  TargetBinding binding() {
    return binding;
  }

  /** Revokes all desktop action authority before a newly entered web address is loaded. */
  CompletableFuture<Void> resetEmbeddedBrowserTarget() {
    invalidate();
    synchronized (bindingLock) {
      bindingGeneration.incrementAndGet();
      binding = null;
      manualBinding = null;
      manualCalibration = null;
    }
    return work(
        () -> {
          closeActiveDesktopSession();
          manualPlatform = null;
          manualPlatformQuestion = null;
          return null;
        });
  }

  /**
   * Fixed synthetic regions never authorize input; every website uses this run's manual positions.
   */
  boolean automaticSyntheticTarget() {
    return false;
  }

  boolean automaticManualTarget() {
    var saved = manualBinding;
    var target = binding;
    return target != null
        && saved != null
        && saved.target().equals(target)
        && saved.positions().equals(manualCalibration)
        && saved.positions().hasRequiredNavigationRegions()
        && saved.bindingGeneration() == bindingGeneration.get()
        && saved.actionTicket() == actionTicket()
        && saved.accountEpoch() == accountEpoch.get();
  }

  boolean automaticTarget() {
    return automaticManualTarget();
  }

  ManualPlatformCalibration manualCalibration() {
    return manualCalibration;
  }

  private void clearManualCalibration() {
    clearManualCalibration(null);
  }

  /** Coordinates are intentionally session-only; login and account changes never read old files. */
  private void clearManualCalibration(Long expectedAccountEpoch) {
    synchronized (accountLock) {
      if (expectedAccountEpoch != null) requireAccountEpoch(expectedAccountEpoch);
      synchronized (bindingLock) {
        bindingGeneration.incrementAndGet();
        binding = null;
        manualBinding = null;
        manualCalibration = null;
      }
      manualPlatform = null;
      manualPlatformQuestion = null;
    }
  }

  static String calibrationOwnerKey(String accountId, URI service) {
    if (accountId == null || accountId.isBlank() || service == null)
      throw new IllegalArgumentException("Calibration account identity is unavailable");
    try {
      String scheme =
          Objects.requireNonNull(service.getScheme(), "service scheme").toLowerCase(Locale.ROOT);
      String host =
          Objects.requireNonNull(service.getHost(), "service host").toLowerCase(Locale.ROOT);
      int port = service.getPort();
      if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))
        port = -1;
      URI origin = new URI(scheme, null, host, port, null, null, null);
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((origin.toASCIIString() + "\n" + accountId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException | java.net.URISyntaxException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  static String manualTargetIdentity(GradingRecords.Owner owner, String targetScope) {
    Objects.requireNonNull(owner, "owner");
    if (targetScope == null) throw new IllegalArgumentException("请先在应用内打开当前阅卷网站。");
    String normalized = targetScope.strip();
    if (normalized.isEmpty()
        || normalized.length() > 2048
        || normalized.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("当前阅卷网站标识无效，请重新打开网站。");
    String material =
        "DeskGrade manual site v1\0"
            + owner.gateway()
            + "\0"
            + owner.accountId()
            + "\0"
            + normalized;
    return "manual-site-v1:" + sha256(material.getBytes(StandardCharsets.UTF_8));
  }

  CompletableFuture<TargetBinding> bindManualAnswer(
      WindowIdentity selected, String expectedCalibrationId, long expectedCalibrationRevision) {
    return bindManualAnswer(selected, expectedCalibrationId, expectedCalibrationRevision, null);
  }

  CompletableFuture<TargetBinding> bindEphemeralManualAnswer(
      String targetScope,
      WindowIdentity selected,
      ManualPlatformCalibration.NavigationMode navigationMode,
      WindowSnapshot expected,
      LogicalRect answer,
      LogicalRect score,
      LogicalRect submit,
      LogicalRect submissionAck,
      LogicalRect next) {
    try {
      Objects.requireNonNull(selected, "selected");
      Objects.requireNonNull(expected, "expected");
      if (!selected.equals(expected.identity()))
        throw new IllegalArgumentException("框选窗口与当前选择不一致，请重新框选。");
      long expectedAccountEpoch = accountEpoch.get();
      String targetIdentity = manualTargetIdentity(recordOwner(), targetScope);
      var value =
          ManualPlatformCalibration.capture(
              "run-" + UUID.randomUUID().toString().replace("-", ""),
              1,
              "本次手工框选",
              expected,
              null,
              answer,
              score,
              submit,
              submissionAck,
              next,
              navigationMode,
              clock.instant());
      synchronized (bindingLock) {
        requireAccountEpoch(expectedAccountEpoch);
        bindingGeneration.incrementAndGet();
        binding = null;
        manualBinding = null;
        manualCalibration = value;
      }
      invalidate();
      return bindManualAnswer(
          selected, value.id(), value.revision(), expectedAccountEpoch, targetIdentity);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private CompletableFuture<TargetBinding> bindManualAnswer(
      WindowIdentity selected,
      String expectedCalibrationId,
      long expectedCalibrationRevision,
      Long requiredAccountEpoch) {
    return bindManualAnswer(
        selected,
        expectedCalibrationId,
        expectedCalibrationRevision,
        requiredAccountEpoch,
        "manual-calibration:" + expectedCalibrationId);
  }

  private CompletableFuture<TargetBinding> bindManualAnswer(
      WindowIdentity selected,
      String expectedCalibrationId,
      long expectedCalibrationRevision,
      Long requiredAccountEpoch,
      String targetIdentity) {
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(targetIdentity, "targetIdentity");
    final long generation;
    final long ticket;
    final long bindingAccountEpoch;
    final ManualPlatformCalibration value;
    final RuntimeException rejection;
    synchronized (bindingLock) {
      long previousTicket = actionRequests.get();
      bindingAccountEpoch = accountEpoch.get();
      value = manualCalibration;
      boolean expectedProfile =
          value != null
              && expectedCalibrationId != null
              && expectedCalibrationId.equals(value.id())
              && expectedCalibrationRevision == value.revision();
      if (!actionRequests.compareAndSet(previousTicket, previousTicket + 1))
        return CompletableFuture.failedFuture(
            new CancellationException("Manual binding authority changed before it was claimed"));
      ticket = previousTicket + 1;
      generation = bindingGeneration.incrementAndGet();
      revision.incrementAndGet();
      trialEpoch.incrementAndGet();
      binding = null;
      manualBinding = null;
      if (requiredAccountEpoch != null && requiredAccountEpoch != bindingAccountEpoch)
        rejection = new CancellationException("Account changed before manual binding was claimed");
      else if (accountEpoch.get() != bindingAccountEpoch)
        rejection = new CancellationException("Account changed before manual binding was claimed");
      else if (!expectedProfile) rejection = new IllegalStateException("本次框选已变化；未绑定任何位置，请重新完成框选。");
      else rejection = null;
    }
    pauseAfterClaimedRevocation(PauseReason.CONFIG_CHANGED);
    if (rejection != null) return CompletableFuture.failedFuture(rejection);
    return work(
        () -> {
          requireTicket(ticket);
          requireAccountEpoch(bindingAccountEpoch);
          closeActiveDesktopSession();
          manualPlatform = null;
          manualPlatformQuestion = null;
          if (!value.hasRequiredNavigationRegions())
            throw new IllegalStateException("点击下一页模式缺少提交成功提示区或下一页按钮");
          var current = windows.snapshot(selected.hwnd());
          if (!current.identity().equals(selected) || !value.matchesLayout(current))
            throw new IllegalStateException("当前窗口或布局已变化，请重新完成本次框选。");
          TargetBinding next = windows.bind(selected.hwnd(), value.answerRegion());
          if (!selected.equals(next.calibratedWindow().identity())
              || !value.matchesLayout(next.calibratedWindow()))
            throw new SelectionPreparationException(SelectionFailure.WINDOW_CHANGED);
          verifyStableAnswer(next);
          requireTicket(ticket);
          requireAccountEpoch(bindingAccountEpoch);
          synchronized (bindingLock) {
            requireTicket(ticket);
            requireAccountEpoch(bindingAccountEpoch);
            if (bindingGeneration.get() != generation
                || !value.equals(manualCalibration)
                || !expectedCalibrationId.equals(manualCalibration.id())
                || expectedCalibrationRevision != manualCalibration.revision())
              throw new CancellationException("Manual binding was superseded");
            binding = next;
            manualBinding =
                new ManualBinding(
                    next, value, targetIdentity, generation, ticket, bindingAccountEpoch);
          }
          return next;
        });
  }

  private void verifyStableAnswer(TargetBinding answer) {
    if (!windows.activateBoundWindow(answer))
      throw new SelectionPreparationException(SelectionFailure.ACTIVATION_FAILED);
    var first = capture.capture(answer);
    try {
      Thread.sleep(ManualCoordinatePlatformAdapter.SCREEN_STABILITY.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Answer stability check was interrupted");
    }
    var second = capture.capture(answer);
    requireStableAnswer(first.png(), first.sha256(), second.png(), second.sha256());
  }

  static void requireStableAnswer(
      byte[] firstPng, String firstSha256, byte[] secondPng, String secondSha256) {
    Objects.requireNonNull(firstPng);
    Objects.requireNonNull(secondPng);
    if (firstSha256 == null
        || secondSha256 == null
        || !firstSha256.matches("[0-9a-f]{64}")
        || !firstSha256.equals(sha256(firstPng))
        || !secondSha256.equals(sha256(secondPng))
        || !firstSha256.equals(secondSha256)
        || !MessageDigest.isEqual(firstPng, secondPng))
      throw new IllegalStateException("答题区域画面不稳定；没有绑定自动操作，请保持页面静止后重试。");
  }

  boolean hotkeysAvailable() {
    var h = hotkeys;
    return h != null && h.available();
  }

  SessionController.Status status() {
    var c = controller;
    return c == null || activeSessionAccountEpoch != accountEpoch.get() ? null : c.status();
  }

  CompletableFuture<List<WindowsWindowService.WindowChoice>> listWindows() {
    return work(windows::listWindows);
  }

  CompletableFuture<WindowSnapshot> inspect(long hwnd) {
    return work(() -> windows.snapshot(hwnd));
  }

  CompletableFuture<WindowSnapshot> prepareSelection(WindowIdentity expected) {
    long ticket = actionTicket();
    return work(
        () -> {
          requireTicket(ticket);
          WindowSnapshot current = selectionSnapshot(expected);
          if (!current.identity().equals(expected))
            throw new SelectionPreparationException(SelectionFailure.WINDOW_CHANGED);
          if (!windows.activateForSelection(expected))
            throw new SelectionPreparationException(SelectionFailure.ACTIVATION_FAILED);
          requireTicket(ticket);
          WindowSnapshot activated = selectionSnapshot(expected);
          if (!activated.identity().equals(expected))
            throw new SelectionPreparationException(SelectionFailure.WINDOW_CHANGED);
          return activated;
        });
  }

  private WindowSnapshot selectionSnapshot(WindowIdentity expected) {
    try {
      return windows.snapshot(expected.hwnd());
    } catch (RuntimeException | LinkageError unavailable) {
      throw new SelectionPreparationException(SelectionFailure.WINDOW_UNAVAILABLE);
    }
  }

  CompletableFuture<TargetBinding> bind(long hwnd, LogicalRect rectangle) {
    return bind(hwnd, rectangle, null, null);
  }

  CompletableFuture<TargetBinding> bind(WindowSnapshot expected, LogicalRect rectangle) {
    Objects.requireNonNull(expected, "expected");
    return bind(expected.identity().hwnd(), rectangle, expected.identity(), expected);
  }

  private CompletableFuture<TargetBinding> bind(
      long hwnd, LogicalRect rectangle, WindowIdentity expected) {
    return bind(hwnd, rectangle, expected, null);
  }

  private CompletableFuture<TargetBinding> bind(
      long hwnd, LogicalRect rectangle, WindowIdentity expected, WindowSnapshot expectedSnapshot) {
    invalidate();
    final long selectedGeneration;
    synchronized (bindingLock) {
      binding = null;
      manualBinding = null;
      selectedGeneration = bindingGeneration.incrementAndGet();
    }
    long ticket = actionTicket();
    return work(
        () -> {
          requireTicket(ticket);
          closeActiveDesktopSession();
          manualPlatform = null;
          manualPlatformQuestion = null;
          TargetBinding next = windows.bind(hwnd, rectangle);
          if (expected != null && !expected.equals(next.calibratedWindow().identity()))
            throw new SelectionPreparationException(SelectionFailure.WINDOW_CHANGED);
          if (expectedSnapshot != null
              && !sameSelectionTarget(expectedSnapshot, next.calibratedWindow()))
            throw new SelectionPreparationException(SelectionFailure.WINDOW_CHANGED);
          requireTicket(ticket);
          synchronized (bindingLock) {
            requireTicket(ticket);
            if (bindingGeneration.get() != selectedGeneration)
              throw new CancellationException("Target binding was superseded");
            binding = next;
          }
          return next;
        });
  }

  private void closeActiveDesktopSession() {
    hideAnswerScan();
    closeMonitor();
    if (controller != null) {
      controller.close();
      controller = null;
    }
    liveSyntheticSession = false;
    closeAutomaticProvider();
  }

  private static boolean sameSelectionTarget(WindowSnapshot expected, WindowSnapshot current) {
    return expected.identity().equals(current.identity())
        && expected.windowClass().equals(current.windowClass())
        && expected.windowBounds().equals(current.windowBounds())
        && expected.clientBounds().equals(current.clientBounds())
        && expected.display().equals(current.display())
        && expected.dpi() == current.dpi();
  }

  CompletableFuture<Void> registerHotkeys() {
    return work(
        () -> {
          if (hotkeysAvailable()) return null;
          if (hotkeys != null) hotkeys.close();
          hotkeys =
              new WindowsGlobalHotkeys(
                  event -> {
                    if (event == WindowsGlobalHotkeys.Event.STOP) {
                      stop();
                      return;
                    }
                    if (event == WindowsGlobalHotkeys.Event.UNAVAILABLE) {
                      pause(PauseReason.HOTKEY_UNAVAILABLE);
                      return;
                    }
                    pause(PauseReason.USER_PAUSED);
                    onNotice.accept("已安全暂停并撤销本轮自动权限；须重新绑定当前页面并开始新任务。 ");
                  });
          return null;
        });
  }

  void invalidate() {
    revision.incrementAndGet();
    trialEpoch.incrementAndGet();
    pause(PauseReason.CONFIG_CHANGED);
  }

  /**
   * Completes a revocation whose action ticket was already atomically claimed under bindingLock.
   */
  private void pauseAfterClaimedRevocation(PauseReason reason) {
    hideAnswerScan();
    try {
      var current = controller;
      if (current != null) current.pause(reason);
    } finally {
      onActionRevoked.run();
    }
  }

  void pause(PauseReason reason) {
    actionRequests.incrementAndGet();
    trialEpoch.incrementAndGet();
    hideAnswerScan();
    var c = controller;
    if (c != null) c.pause(reason);
    onActionRevoked.run();
  }

  void stop() {
    stop(PauseReason.USER_STOPPED);
  }

  void emergencyStop(Throwable failure) {
    actionRequests.incrementAndGet();
    trialEpoch.incrementAndGet();
    hideAnswerScan();
    synchronized (bindingLock) {
      bindingGeneration.incrementAndGet();
      binding = null;
      manualBinding = null;
    }
    manualPlatform = null;
    manualPlatformQuestion = null;
    try {
      var current = controller;
      if (current != null) current.stop(PauseReason.LOCAL_FAILURE, failure);
    } catch (RuntimeException | LinkageError ignored) {
      // The synchronous action ticket revocation above remains effective even if journaling fails.
    }
    try {
      closeMonitor();
    } catch (RuntimeException | LinkageError ignored) {
      // Monitor cleanup cannot be allowed to reopen action authority.
    }
    try {
      onActionRevoked.run();
    } catch (RuntimeException | LinkageError ignored) {
      // A broken UI callback must not interfere with the already closed action gate.
    }
    try {
      onNotice.accept("桌面线程发生未处理错误，已紧急停止并撤销自动操作；请保存日志后重新启动。");
    } catch (RuntimeException | LinkageError ignored) {
      // The emergency path cannot rely on a healthy JavaFX callback.
    }
  }

  private void stop(PauseReason reason) {
    actionRequests.incrementAndGet();
    trialEpoch.incrementAndGet();
    hideAnswerScan();
    var c = controller;
    if (c != null) c.stop(reason);
    onActionRevoked.run();
  }

  CompletableFuture<Void> chooseSlot(String slot) {
    if (commercialMode)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Administrator assigns the commercial model"));
    invalidate();
    selectedSlot = slot;
    long ticket = actionTicket();
    return work(
        () -> {
          requireTicket(ticket);
          // Resume installs the selected profile under a new revision after fresh prechecks.
          return null;
        });
  }

  boolean liveSyntheticSession() {
    return liveSyntheticSession;
  }

  CompletableFuture<Void> startMock(boolean explicitMockMode, int maxSubmissions, int maxCalls) {
    return startMock(explicitMockMode, maxSubmissions, maxCalls, actionTicket());
  }

  CompletableFuture<Void> startMock(
      boolean explicitMockMode, int maxSubmissions, int maxCalls, long ticket) {
    return startSynthetic(explicitMockMode, null, maxSubmissions, maxCalls, ticket);
  }

  CompletableFuture<Void> startLiveSynthetic(
      ProviderProfile profile, int maxSubmissions, int maxCalls, long ticket) {
    return startLiveSynthetic(ModelSelection.of(profile), maxSubmissions, maxCalls, ticket);
  }

  CompletableFuture<Void> startLiveSynthetic(
      ModelSelection profile, int maxSubmissions, int maxCalls, long ticket) {
    if (profile == null)
      return CompletableFuture.failedFuture(new IllegalArgumentException("Profile required"));
    return startSynthetic(true, profile, maxSubmissions, maxCalls, ticket);
  }

  private CompletableFuture<Void> startSynthetic(
      boolean explicitlyAuthorized,
      ModelSelection liveProfile,
      int maxSubmissions,
      int maxCalls,
      long ticket) {
    return work(
        () -> {
          requireTicket(ticket);
          if (unresolvedRecoveryActive) throw new IllegalStateException("正在处理旧未决批次，请等待处理完成后重新绑定。");
          if (liveProfile == null && !developerMode)
            throw new IllegalStateException("Synthetic model is available only in developer mode");
          if (liveProfile != null) requireLiveProfile(liveProfile);
          if (!explicitlyAuthorized || !ready() || !hotkeysAvailable())
            throw new IllegalStateException("Automatic binding authorization or precheck missing");
          TargetBinding target = Objects.requireNonNull(binding, "No target binding");
          if (!automaticTarget()) throw new IllegalStateException("Automatic target is not bound");
          if (!windows.activateBoundWindow(target))
            throw new IllegalStateException("Target activation failed");
          var sessionJournal = requireJournal();
          sessionJournal.assertWritable();
          closeMonitor();
          if (controller != null) controller.close();
          closeAutomaticProvider();
          SessionSettings settings =
              liveProfile == null
                  ? mockSettings(maxSubmissions, maxCalls)
                  : liveSyntheticSettings(liveProfile, maxSubmissions, maxCalls);
          var platform = automaticPlatform(target, settings.question().id());
          if (!platform.supportsUnattended())
            throw new IllegalStateException(
                "Bound target is unavailable; check its window and positions");
          GradingProvider selectedProvider = mockProvider();
          if (liveProfile != null) {
            automaticProvider = realProvider(liveProfile, automaticRubric());
            selectedProvider = automaticProvider;
          }
          liveSyntheticSession = liveProfile != null;
          lastMaxSubmissions = maxSubmissions;
          lastMaxCalls = maxCalls;
          activeAuthorization = ticket;
          activeSessionAccountEpoch = accountEpoch.get();
          controller =
              new SessionController(
                  platform,
                  sessionJournal,
                  (run, attempt, image) -> {
                    String path = evidence.save(run, attempt, image);
                    return path;
                  },
                  selectedProvider,
                  r -> GradeValidator.validate(settings.question(), r).totalScore(),
                  settings,
                  clock,
                  () -> hotkeysAvailable() && actionRequests.get() == activeAuthorization,
                  this::publish,
                  () -> !commercialMode || commercialCreditsAvailable());
          try {
            requireTicket(ticket);
          } catch (CancellationException cancelled) {
            controller.close();
            throw cancelled;
          }
          controller.start().toCompletableFuture().join();
          requireTicket(ticket);
          startMonitor(target);
          return null;
        });
  }

  private SessionPlatform automaticPlatform(TargetBinding target, String questionId) {
    synchronized (bindingLock) {
      if (!automaticManualTarget())
        throw new IllegalStateException("Named manual website calibration is not bound");
      if (manualPlatform != null && !questionId.equals(manualPlatformQuestion))
        throw new IllegalStateException("题号已更改，请重新使用保存的位置绑定后开始。");
      if (manualPlatform == null) {
        manualPlatform =
            new ManualCoordinatePlatformAdapter(
                windows,
                target,
                manualBinding.positions(),
                capture,
                manualBinding.targetIdentity(),
                questionId,
                clock);
        manualPlatformQuestion = questionId;
      }
      return manualPlatform;
    }
  }

  CompletableFuture<Void> resumeMock() {
    return resumeMock(lastMaxSubmissions, lastMaxCalls, actionTicket());
  }

  CompletableFuture<Void> resumeMock(int maxSubmissions, int maxCalls, long ticket) {
    return resumeSynthetic(null, maxSubmissions, maxCalls, ticket);
  }

  CompletableFuture<Void> resumeLiveSynthetic(
      ProviderProfile profile, int maxSubmissions, int maxCalls, long ticket) {
    return resumeLiveSynthetic(ModelSelection.of(profile), maxSubmissions, maxCalls, ticket);
  }

  CompletableFuture<Void> resumeLiveSynthetic(
      ModelSelection profile, int maxSubmissions, int maxCalls, long ticket) {
    if (profile == null)
      return CompletableFuture.failedFuture(new IllegalArgumentException("Profile required"));
    return resumeSynthetic(profile, maxSubmissions, maxCalls, ticket);
  }

  private CompletableFuture<Void> resumeSynthetic(
      ModelSelection liveProfile, int maxSubmissions, int maxCalls, long ticket) {
    return work(
        () -> {
          requireTicket(ticket);
          if (unresolvedRecoveryActive) throw new IllegalStateException("正在处理旧未决批次，请等待处理完成后重新绑定。");
          if (liveSyntheticSession != (liveProfile != null))
            throw new IllegalStateException("Automation mode changed; start a new session");
          if (liveProfile != null) requireLiveProfile(liveProfile);
          var c = Objects.requireNonNull(controller);
          if (c.status().state() != SessionState.PAUSED || !hotkeysAvailable())
            throw new IllegalStateException("Resume unavailable");
          TargetBinding target = Objects.requireNonNull(binding);
          revision.incrementAndGet();
          SessionSettings settings =
              liveProfile == null
                  ? mockSettings(maxSubmissions, maxCalls)
                  : liveSyntheticSettings(liveProfile, maxSubmissions, maxCalls);
          if (!automaticTarget()) throw new IllegalStateException("Automatic target is not bound");
          if (!windows.activateBoundWindow(target))
            throw new IllegalStateException("Target activation failed");
          if (!automaticPlatform(target, settings.question().id()).supportsUnattended())
            throw new IllegalStateException("Visible platform capability test failed");
          ManagedProvider nextLive =
              liveProfile == null ? null : realProvider(liveProfile, automaticRubric());
          try {
            c.changeConfiguration(
                    settings,
                    nextLive == null ? mockProvider() : nextLive,
                    r -> GradeValidator.validate(settings.question(), r).totalScore())
                .toCompletableFuture()
                .join();
          } catch (RuntimeException failure) {
            if (nextLive != null) nextLive.close();
            throw failure;
          }
          closeAutomaticProvider();
          automaticProvider = nextLive;
          lastMaxSubmissions = maxSubmissions;
          lastMaxCalls = maxCalls;
          requireTicket(ticket);
          if (!automaticTarget()) throw new IllegalStateException("Automatic target is not bound");
          if (!windows.activateBoundWindow(target))
            throw new IllegalStateException("Target activation failed");
          var platformCheck = automaticPlatform(target, settings.question().id());
          if (!platformCheck.supportsUnattended())
            throw new IllegalStateException("Visible platform capability test failed");
          requireTicket(ticket);
          if (!automaticTarget()) throw new CancellationException("Target authority was revoked");
          activeAuthorization = ticket;
          c.resume().toCompletableFuture().join();
          requireTicket(ticket);
          closeMonitor();
          startMonitor(target);
          return null;
        });
  }

  CompletableFuture<Void> reconcile() {
    return work(
        () -> {
          if (controller != null) {
            if (!windows.activateBoundWindow(Objects.requireNonNull(binding)))
              throw new IllegalStateException("Target activation failed");
            controller.reconcileUnknown().toCompletableFuture().join();
          }
          return null;
        });
  }

  /**
   * Explicit, read-only recovery lookup. It does not need a live window or desktop input authority.
   */
  CompletableFuture<UnresolvedSubmissionReview> unresolvedSubmissions() {
    try {
      long epoch = accountTicket();
      var owner = recordOwner();
      final UnresolvedSubmissionReview scope;
      synchronized (bindingLock) {
        requireAccountEpoch(epoch);
        requireIdleForUnresolvedRecovery();
        var currentBinding = Objects.requireNonNull(manualBinding, "请先完成本次框选并绑定，再处理当前批次的未知提交。");
        scope =
            new UnresolvedSubmissionReview(
                owner,
                epoch,
                actionTicket(),
                bindingGeneration.get(),
                Objects.requireNonNull(manualCalibration, "请先完成本次框选并绑定，再处理当前批次的未知提交。"),
                currentBinding.targetIdentity(),
                List.of());
      }
      return work(
          () -> {
            requireUnresolvedScope(scope, scope.actionTicket());
            var rows =
                requireJournal().unresolvedSubmissions(scope.owner(), scope.targetIdentity());
            requireUnresolvedScope(scope, scope.actionTicket());
            return new UnresolvedSubmissionReview(
                scope.owner(),
                scope.accountTicket(),
                scope.actionTicket(),
                scope.bindingGeneration(),
                scope.calibration(),
                scope.targetIdentity(),
                rows);
          });
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  /**
   * End a reviewed historical batch without confirming, resubmitting or discarding its evidence.
   */
  CompletableFuture<Void> abandonUnresolvedRun(UnresolvedSubmissionReview review, UUID runId) {
    final long revokedTicket;
    final SessionController closing;
    try {
      Objects.requireNonNull(review);
      Objects.requireNonNull(runId);
      if (!recordOwner().equals(review.owner()))
        throw new CancellationException("账号已变化，请重新查看旧未决记录。");
      synchronized (bindingLock) {
        requireUnresolvedScope(review, review.actionTicket());
        if (unresolvedRecoveryActive
            || review.submissions().stream()
                .noneMatch(
                    row ->
                        row.run().runId().equals(runId)
                            && row.run().targetIdentity().equals(review.targetIdentity())))
          throw new IllegalStateException("待处理批次已变化，请刷新后重新选择。");
        unresolvedRecoveryActive = true;
        revokedTicket = actionRequests.incrementAndGet();
        trialEpoch.incrementAndGet();
        revision.incrementAndGet();
        closing = controller;
      }
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    try {
      onActionRevoked.run();
      if (closing != null) closing.close();
    } catch (RuntimeException failure) {
      unresolvedRecoveryActive = false;
      return CompletableFuture.failedFuture(failure);
    }
    UUID decisionId =
        unresolvedDecisionIds.computeIfAbsent(
            new UnresolvedDecision(review.owner(), review.targetIdentity(), runId),
            ignored -> UUID.randomUUID());
    return work(() -> {
          requireUnresolvedScope(review, revokedTicket);
          if (controller != closing) throw new CancellationException("阅卷任务已变化，请重新检查旧批次。");
          closeMonitor();
          closeAutomaticProvider();
          requireJournal()
              .abandonUnresolvedRun(
                  review.owner(), runId, review.targetIdentity(), decisionId, clock.instant());
          synchronized (bindingLock) {
            requireUnresolvedScope(review, revokedTicket);
            if (controller != closing) throw new CancellationException("阅卷任务已变化，请重新检查旧批次。");
            controller = null;
            liveSyntheticSession = false;
            bindingGeneration.incrementAndGet();
            binding = null;
            manualBinding = null;
            manualPlatform = null;
            manualPlatformQuestion = null;
          }
          return (Void) null;
        })
        .whenComplete((ignored, failure) -> unresolvedRecoveryActive = false);
  }

  private void requireUnresolvedScope(UnresolvedSubmissionReview review, long expectedAction) {
    requireAccountEpoch(review.accountTicket());
    requireTicket(expectedAction);
    if (bindingGeneration.get() != review.bindingGeneration()
        || !Objects.equals(manualCalibration, review.calibration()))
      throw new CancellationException("账号、网站配置或绑定已变化，请刷新旧未决记录。");
    requireIdleForUnresolvedRecovery();
  }

  private void requireIdleForUnresolvedRecovery() {
    var current = status();
    if (current != null
        && !Set.of(
                SessionState.IDLE,
                SessionState.PAUSED,
                SessionState.STOPPED,
                SessionState.COMPLETED,
                SessionState.SUBMISSION_UNKNOWN,
                SessionState.FAILED)
            .contains(current.state())) throw new IllegalStateException("请先停止当前阅卷任务，再处理旧未决批次。");
  }

  private void startMonitor(TargetBinding target) {
    monitor =
        new WindowChangeMonitor(
            windows, target, Duration.ofMillis(100), failure -> stop(PauseReason.TARGET_CHANGED));
  }

  private void closeMonitor() {
    if (monitor != null) {
      monitor.close();
      monitor = null;
    }
  }

  private void publish(SessionController.Status status) {
    if (status.failureDiagnostic() != null && !status.equals(lastFailureStatus)) {
      lastFailureStatus = status;
      var localFailureLog =
          org.slf4j.LoggerFactory.getLogger(DesktopWorkspace.class)
              .atWarn()
              .addKeyValue("event", "session_action_fail_stop")
              .addKeyValue("run_id", status.runId())
              .addKeyValue("phase", status.failureDiagnostic().phase().name())
              .addKeyValue("codes", status.failureDiagnostic().codes());
      if (status.failureDiagnostic().screenChange() != null)
        localFailureLog.addKeyValue(
            "screen_change",
            LocalScreenChangePresentation.localFields(status.failureDiagnostic().screenChange()));
      localFailureLog.log("Desktop action stopped; bounded diagnostic recorded");
    }
    long originalAction = activeAuthorization;
    long originalAccount = activeSessionAccountEpoch;
    var c = controller;
    var presentation =
        c == null
            ? Optional.<GradingPresentation>empty()
            : GradingPresentation.automatic(
                status, c.status(), c.lastResponse(), originalAction, originalAccount);
    synchronized (answerScanLock) {
      if (originalAccount != accountEpoch.get() || originalAction != actionTicket()) return;
      onAnswerScan.accept(
          binding,
          status.state() == SessionState.GRADING
              && c != null
              && c == controller
              && status.equals(c.status())
              && automaticManualTarget()
              && !closed);
    }
    onStatus.accept(status);
    presentation.ifPresent(onResult);
  }

  private GradingProvider mockProvider() {
    QuestionDefinition question = syntheticQuestion();
    var result =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "MOCK_" + selectedSlot + " 合成固定结果，不代表模型效果",
            question.criteria().stream()
                .map(
                    c ->
                        new GradeResult.CriterionResult(
                            c.id(),
                            c.allowedScores().stream().max(BigDecimal::compareTo).orElseThrow(),
                            "合成测试数据",
                            "显式 MOCK 固定分项"))
                .toList(),
            List.of());
    return new MockGradingProvider(result, clock, worker);
  }

  private SessionSettings mockSettings(int maxSubmissions, int maxCalls) throws Exception {
    String snapshot =
        json.writeValueAsString(
            Map.of(
                "mode",
                "MOCK",
                "profile",
                "MOCK_" + selectedSlot,
                "modelRevision",
                revision.get(),
                "platformRevision",
                revision.get(),
                "question",
                syntheticQuestion(),
                "maxSubmissions",
                maxSubmissions,
                "maxCalls",
                maxCalls,
                "binding",
                bindingSnapshot(),
                "platform",
                platformSnapshot()));
    return new SessionSettings(
        syntheticQuestion(),
        "MOCK_" + selectedSlot,
        revision.get(),
        revision.get(),
        snapshot,
        maxSubmissions,
        maxCalls,
        0,
        null,
        Duration.ofMillis(200),
        Duration.ofSeconds(20));
  }

  private void requireLiveProfile(ModelSelection profile) {
    if (commercialMode) {
      var view = accountView();
      if (profile.commercial() == null
          || view.account() == null
          || !view.models().contains(profile.commercial()))
        throw new IllegalStateException("Login and server model selection required");
    } else {
      if (profile.byok() == null)
        throw new IllegalStateException("Saved model configuration differs");
      requireSavedProfile(profile.byok());
    }
    if (!profile.isVerified()) throw new IllegalStateException("Image capability is not verified");
    if (commercialMode) {
      if (!commercialCreditsAvailable())
        throw new IllegalStateException("Account credits are insufficient");
      return;
    }
    if (!Files.isRegularFile(
        root.resolve("credentials").resolve(profile.byok().credentialRef() + ".dpapi"),
        LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Encrypted credential required");
  }

  private SessionSettings liveSyntheticSettings(
      ModelSelection profile, int maxSubmissions, int maxCalls) throws Exception {
    if (localMode) maxCalls = 0; // No account quota; the user-selected submission stop remains.
    Rubric fixed = automaticRubric();
    fixed.validateReady();
    String snapshot =
        json.writeValueAsString(
            Map.of(
                "mode",
                automaticManualTarget()
                    ? "LIVE_MODEL_MANUAL_COORDINATES"
                    : "LIVE_MODEL_SYNTHETIC_PLATFORM",
                "rubric",
                new RubricJsonCodec().write(fixed),
                "profile",
                profileSnapshot(profile),
                "access",
                accessSnapshot(profile),
                "binding",
                bindingSnapshot(),
                "maxSubmissions",
                maxSubmissions,
                "maxCalls",
                maxCalls,
                "platform",
                platformSnapshot()));
    return new SessionSettings(
        fixed.question(),
        profile.id(),
        revision
            .get(), // Session provider generation; the server profile revision stays in snapshot.
        revision.get(),
        snapshot,
        maxSubmissions,
        maxCalls,
        0,
        null,
        Duration.ofMillis(200),
        Duration.ofSeconds(20));
  }

  Rubric automaticRubric() {
    Rubric selected = developerMode ? syntheticVisionRubric() : rubric;
    selected.validateReady();
    return selected;
  }

  static Rubric syntheticVisionRubric() {
    return new Rubric(
        new QuestionDefinition(
            "1",
            1,
            "读取合成图片 Fixture 行的加法算式及 Explanation 行，判断算式和说明是否正确。",
            "加法的正确结果为两个加数之和；Explanation 中 adding both quantities 表示把两个数量相加。",
            new BigDecimal("4"),
            List.of(
                new ScoringCriterion(
                    "calculation",
                    "图片中等号右侧数字等于两加数之和得2分，否则0分。",
                    List.of(BigDecimal.ZERO, new BigDecimal("2"))),
                new ScoringCriterion(
                    "expression",
                    "图片中完整写出两个加数、加号、等号和结果得1分，否则0分。",
                    List.of(BigDecimal.ZERO, BigDecimal.ONE)),
                new ScoringCriterion(
                    "explanation",
                    "Explanation行正确说明相加两个数量得1分，否则0分。",
                    List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "仅评图片中可见的合成算式。calculation 0或2分；expression 0或1分；explanation 0或1分。空白或无法辨读返回UNABLE，不得照抄参考答案或推测满分。",
        BigDecimal.ONE,
        true,
        false);
  }

  private void closeAutomaticProvider() {
    if (automaticProvider != null) {
      automaticProvider.close();
      automaticProvider = null;
    }
  }

  private ManagedProvider realProvider(ModelSelection profile, Rubric fixed) {
    synchronized (accountLock) {
      if (localMode) {
        requireLiveProfile(profile);
        ProviderProfile fallback = profiles.get(localModelSelection.fallbackId());
        if (fallback != null && !fallback.id().equals(profile.id()))
          requireLiveProfile(ModelSelection.of(fallback));
        return new ManagedProvider(
            new LocalFallbackGradingProvider(
                new OpenAICompatibleVisionProvider(profile.byok(), fixed, secrets, clock),
                fallback == null
                    ? null
                    : new OpenAICompatibleVisionProvider(fallback, fixed, secrets, clock)),
            null,
            -1);
      }
      if (!commercialMode)
        return new ManagedProvider(
            new OpenAICompatibleVisionProvider(profile.byok(), fixed, secrets, clock), null, -1);
      requireLiveProfile(profile);
      var client = Objects.requireNonNull(commercialClient);
      return new ManagedProvider(
          new GatewayGradingProvider(client, fixed, profile.id()), client, accountEpoch.get());
    }
  }

  private final class ManagedProvider implements FallbackGradingProvider, AutoCloseable {
    private final GradingProvider delegate;
    private final CommercialClient accountClient;
    private final long boundAccountEpoch;

    private ManagedProvider(GradingProvider delegate, CommercialClient client, long epoch) {
      this.delegate = delegate;
      accountClient = client;
      boundAccountEpoch = epoch;
    }

    @Override
    public CompletionStage<GradingResponse> grade(
        GradingRequest request, app.deskgrade.core.port.CancellationToken token) {
      return guardedDispatch(() -> delegate.grade(request, token));
    }

    @Override
    public Optional<String> fallbackProfileId() {
      return delegate instanceof FallbackGradingProvider fallback
          ? fallback.fallbackProfileId()
          : Optional.empty();
    }

    @Override
    public CompletionStage<GradingResponse> gradeFallback(
        UUID primaryRequestId,
        GradingRequest request,
        app.deskgrade.core.port.CancellationToken token) {
      if (!(delegate instanceof FallbackGradingProvider fallback))
        return CompletableFuture.failedFuture(
            new IllegalStateException("Fallback model is unavailable"));
      return guardedDispatch(() -> fallback.gradeFallback(primaryRequestId, request, token));
    }

    private CompletionStage<GradingResponse> guardedDispatch(
        java.util.function.Supplier<CompletionStage<GradingResponse>> dispatch) {
      if (accountClient != null) {
        try {
          requireAccountEpoch(boundAccountEpoch);
          if (!commercialMode
              || commercialClient != accountClient
              || !commercialCreditsAvailable()) {
            stopForCredits();
            onNotice.accept("账号未登录或次数不足，请刷新余额后再开始。");
            return CompletableFuture.failedFuture(
                new IllegalStateException("Account credits are insufficient"));
          }
        } catch (RuntimeException failure) {
          return CompletableFuture.failedFuture(failure);
        }
      }
      final CompletionStage<GradingResponse> operation;
      try {
        operation = Objects.requireNonNull(dispatch.get());
      } catch (RuntimeException failure) {
        accountFailure(failure);
        return CompletableFuture.failedFuture(failure);
      }
      return operation.whenComplete(
          (result, failure) -> {
            if (accountClient != null) accountFailure(failure);
          });
    }

    private void accountFailure(Throwable failure) {
      if (accountClient == null) return;
      if (failure != null && accountEpoch.get() == boundAccountEpoch) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        if (cause instanceof GatewayException gateway
            && gateway.code().name().equals("INSUFFICIENT_CREDITS")) stopForCredits();
        if (cause instanceof GatewayException gateway
            && gateway.code() == GatewayException.Code.MODEL_POLICY_CHANGED) {
          invalidate();
          onNotice.accept("评分服务配置已更新，旧评分失效。请刷新账号后重新开始；不会自动重试。");
        }
      }
      handleAccountFailure(boundAccountEpoch, failure);
      onAccountChanged.run();
    }

    @Override
    public void close() {
      try {
        ((AutoCloseable) delegate).close();
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException("Model resources could not close");
      }
    }
  }

  private Object profileSnapshot(ModelSelection profile) {
    if (!commercialMode) return new ProfileJsonCodec().write(List.of(profile.byok()));
    // Commercial routing is entirely server managed. Persisting the concrete profile/model names in
    // the local session snapshot would expose an implementation detail without helping recovery.
    return Map.of("selection", "SERVER_MANAGED");
  }

  private Map<String, Object> accessSnapshot(ModelSelection profile) {
    if (localMode) return Map.of("mode", "LOCAL");
    if (!commercialMode) return Map.of("mode", "BYOK");
    Objects.requireNonNull(accountView().account(), "Login required");
    var owner = recordOwner();
    String ownerScope =
        sha256((owner.gateway() + "\n" + owner.accountId()).getBytes(StandardCharsets.UTF_8));
    return Map.of("mode", "COMMERCIAL", "routing", "SERVER_MANAGED", "ownerScope", ownerScope);
  }

  CompletableFuture<Rubric> saveRubric(Rubric next) {
    return saveRubric(next, rubricSubject);
  }

  CompletableFuture<Rubric> saveRubric(Rubric next, String subject) {
    Objects.requireNonNull(next);
    invalidate();
    return work(
        () -> {
          recoverPendingRubricSave();
          String normalizedSubject = normalizeRubricSubject(subject);
          saveRubricTransaction(next, normalizedSubject);
          rubric = next;
          rubricSubject = normalizedSubject;
          return next;
        });
  }

  String rubricSubject() {
    return rubricSubject;
  }

  private static String normalizeRubricSubject(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.length() > 100
        || normalized.chars().anyMatch(character -> Character.isISOControl(character)))
      throw new IllegalArgumentException(
          "Rubric subject must be a single line of at most 100 characters");
    return normalized;
  }

  /**
   * Commits the rubric and its subject as one recoverable logical value. The two target-file
   * replacements remain individually atomic; the write-ahead journal makes every intermediate
   * combination safe to roll forward after an interrupted save or process restart.
   */
  private void saveRubricTransaction(Rubric next, String normalizedSubject) throws Exception {
    Path rubricPath = root.resolve("settings/rubric.json");
    Path subjectPath = root.resolve("settings/rubric-subject.txt");
    Path journalPath = root.resolve("settings/rubric-save.pending.json");
    RubricJsonCodec codec = new RubricJsonCodec();
    String rubricJson = codec.write(next);
    validateRubricSavePayload(rubricJson, normalizedSubject, codec);

    var transaction =
        new RubricSaveTransaction(
            UUID.randomUUID(),
            fileSha256OrAbsent(rubricPath, RUBRIC_FILE_MAX_BYTES),
            fileSha256OrAbsent(subjectPath, 512),
            sha256(rubricJson.getBytes(StandardCharsets.UTF_8)),
            sha256(normalizedSubject.getBytes(StandardCharsets.UTF_8)),
            rubricJson,
            normalizedSubject);
    writeRubricSaveTransaction(journalPath, transaction);
    RubricSaveTransaction persisted = readRubricSaveTransaction(journalPath);
    if (!transaction.equals(persisted)) throw invalidRubricSaveTransaction();
    rollForwardRubricSave(journalPath, persisted);
  }

  /** Recover before loading either target and before starting every subsequent save. */
  private void recoverPendingRubricSave() throws Exception {
    Path journalPath = root.resolve("settings/rubric-save.pending.json");
    if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return;
    rollForwardRubricSave(journalPath, readRubricSaveTransaction(journalPath));
  }

  private void rollForwardRubricSave(Path journalPath, RubricSaveTransaction transaction)
      throws Exception {
    Path rubricPath = root.resolve("settings/rubric.json");
    Path subjectPath = root.resolve("settings/rubric-subject.txt");
    String currentRubric = fileSha256OrAbsent(rubricPath, RUBRIC_FILE_MAX_BYTES);
    String currentSubject = fileSha256OrAbsent(subjectPath, 512);

    // Validate both files before changing either one. A value other than the recorded before/after
    // image means the files were modified outside this transaction, so overwriting would destroy
    // evidence and could silently pair unrelated settings.
    requireBeforeOrAfter(
        currentRubric, transaction.rubricBeforeSha256(), transaction.rubricAfterSha256());
    requireBeforeOrAfter(
        currentSubject, transaction.subjectBeforeSha256(), transaction.subjectAfterSha256());

    if (!Objects.equals(currentRubric, transaction.rubricAfterSha256()))
      saveText(rubricPath, transaction.rubricJson());
    if (!Objects.equals(currentSubject, transaction.subjectAfterSha256()))
      saveText(subjectPath, transaction.subject());

    if (!Objects.equals(
            fileSha256OrAbsent(rubricPath, RUBRIC_FILE_MAX_BYTES), transaction.rubricAfterSha256())
        || !Objects.equals(fileSha256OrAbsent(subjectPath, 512), transaction.subjectAfterSha256()))
      throw invalidRubricSaveTransaction();

    // Refuse to delete a journal that was replaced or changed while recovery was in progress.
    if (!transaction.equals(readRubricSaveTransaction(journalPath)))
      throw invalidRubricSaveTransaction();
    Files.delete(journalPath);
    if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) throw invalidRubricSaveTransaction();
  }

  private static void requireBeforeOrAfter(String current, String before, String after) {
    if (!Objects.equals(current, before) && !Objects.equals(current, after))
      throw invalidRubricSaveTransaction();
  }

  private void writeRubricSaveTransaction(Path path, RubricSaveTransaction transaction)
      throws Exception {
    var node = json.createObjectNode();
    node.put("schemaVersion", 1);
    node.put("transactionId", transaction.transactionId().toString());
    node.put("state", RUBRIC_SAVE_STATE);
    putNullableText(node, "rubricBeforeSha256", transaction.rubricBeforeSha256());
    putNullableText(node, "subjectBeforeSha256", transaction.subjectBeforeSha256());
    node.put("rubricAfterSha256", transaction.rubricAfterSha256());
    node.put("subjectAfterSha256", transaction.subjectAfterSha256());
    node.put("rubricJson", transaction.rubricJson());
    node.put("subject", transaction.subject());
    byte[] bytes = json.writeValueAsBytes(node);
    if (bytes.length == 0 || bytes.length > RUBRIC_SAVE_JOURNAL_MAX_BYTES)
      throw invalidRubricSaveTransaction();
    saveText(path, new String(bytes, StandardCharsets.UTF_8));
  }

  private RubricSaveTransaction readRubricSaveTransaction(Path path) {
    try {
      byte[] bytes = readBoundedRegularFile(path, RUBRIC_SAVE_JOURNAL_MAX_BYTES, false);
      var node = strictJson.readTree(bytes);
      if (node == null || !node.isObject()) throw invalidRubricSaveTransaction();
      Set<String> names = new HashSet<>();
      node.fieldNames().forEachRemaining(names::add);
      if (!names.equals(
          Set.of(
              "schemaVersion",
              "transactionId",
              "state",
              "rubricBeforeSha256",
              "subjectBeforeSha256",
              "rubricAfterSha256",
              "subjectAfterSha256",
              "rubricJson",
              "subject"))) throw invalidRubricSaveTransaction();
      if (!node.get("schemaVersion").isIntegralNumber()
          || !node.get("schemaVersion").canConvertToInt()
          || node.get("schemaVersion").intValue() != 1
          || !node.get("state").isTextual()
          || !RUBRIC_SAVE_STATE.equals(node.get("state").textValue()))
        throw invalidRubricSaveTransaction();

      String rawTransactionId = requiredJournalText(node, "transactionId", 36, false);
      UUID transactionId = UUID.fromString(rawTransactionId);
      if (!transactionId.toString().equalsIgnoreCase(rawTransactionId))
        throw invalidRubricSaveTransaction();
      String rubricBefore = optionalJournalSha256(node, "rubricBeforeSha256");
      String subjectBefore = optionalJournalSha256(node, "subjectBeforeSha256");
      String rubricAfter = requiredJournalSha256(node, "rubricAfterSha256");
      String subjectAfter = requiredJournalSha256(node, "subjectAfterSha256");
      String rubricJson = requiredJournalText(node, "rubricJson", RUBRIC_FILE_MAX_BYTES, false);
      String subject = requiredJournalText(node, "subject", 100, true);
      validateRubricSavePayload(rubricJson, subject, new RubricJsonCodec());
      if (!rubricAfter.equals(sha256(rubricJson.getBytes(StandardCharsets.UTF_8)))
          || !subjectAfter.equals(sha256(subject.getBytes(StandardCharsets.UTF_8))))
        throw invalidRubricSaveTransaction();
      return new RubricSaveTransaction(
          transactionId,
          rubricBefore,
          subjectBefore,
          rubricAfter,
          subjectAfter,
          rubricJson,
          subject);
    } catch (IllegalStateException failure) {
      throw failure;
    } catch (Exception | LinkageError failure) {
      throw invalidRubricSaveTransaction();
    }
  }

  private static void validateRubricSavePayload(
      String rubricJson, String subject, RubricJsonCodec codec) {
    if (rubricJson == null
        || rubricJson.isEmpty()
        || rubricJson.getBytes(StandardCharsets.UTF_8).length > RUBRIC_FILE_MAX_BYTES
        || !subject.equals(normalizeRubricSubject(subject))) throw invalidRubricSaveTransaction();
    Rubric decoded = codec.read(rubricJson);
    if (!rubricJson.equals(codec.write(decoded))) throw invalidRubricSaveTransaction();
  }

  private static String optionalJournalSha256(
      com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    if (value == null) throw invalidRubricSaveTransaction();
    if (value.isNull()) return null;
    return requiredJournalSha256(node, field);
  }

  private static String requiredJournalSha256(
      com.fasterxml.jackson.databind.JsonNode node, String field) {
    String value = requiredJournalText(node, field, 64, false);
    if (!value.matches("[0-9a-f]{64}")) throw invalidRubricSaveTransaction();
    return value;
  }

  private static String requiredJournalText(
      com.fasterxml.jackson.databind.JsonNode node,
      String field,
      int maximumCharacters,
      boolean emptyAllowed) {
    var value = node.get(field);
    if (value == null || !value.isTextual()) throw invalidRubricSaveTransaction();
    String text = value.textValue();
    if ((!emptyAllowed && text.isEmpty())
        || text.length() > maximumCharacters
        || text.indexOf('\0') >= 0) throw invalidRubricSaveTransaction();
    return text;
  }

  private static void putNullableText(
      com.fasterxml.jackson.databind.node.ObjectNode node, String field, String value) {
    if (value == null) node.putNull(field);
    else node.put(field, value);
  }

  private static String fileSha256OrAbsent(Path path, int maximumBytes) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    return sha256(readBoundedRegularFile(path, maximumBytes, true));
  }

  private static byte[] readBoundedRegularFile(Path path, int maximumBytes, boolean emptyAllowed)
      throws Exception {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw invalidRubricSaveTransaction();
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(maximumBytes + 1);
    }
    if (bytes.length > maximumBytes || !emptyAllowed && bytes.length == 0)
      throw invalidRubricSaveTransaction();
    return bytes;
  }

  private static IllegalStateException invalidRubricSaveTransaction() {
    return new IllegalStateException(
        "Saved rubric transaction is invalid; recovery stopped without guessing");
  }

  CompletableFuture<ProviderProfile> saveProfile(ProviderProfile profile) {
    invalidate();
    return work(
        () -> {
          if (commercialMode) throw new IllegalStateException("Server models are read only");
          persistProfile(profile);
          return profile;
        });
  }

  CompletableFuture<ProviderProfile> saveCredential(ProviderProfile profile, char[] password) {
    invalidate();
    return work(() -> {
          try {
            if (commercialMode)
              throw new IllegalStateException("Commercial mode never accepts supplier credentials");
            requireSavedProfile(profile);
            ProviderProfile invalidated = invalidateSharedCredentialProfiles(profile);
            secrets.put(profile.credentialRef(), password);
            return invalidated;
          } finally {
            if (password != null) Arrays.fill(password, '\0');
          }
        })
        .whenComplete(
            (saved, failure) -> {
              if (password != null) Arrays.fill(password, '\0');
            });
  }

  CompletableFuture<ProviderProfile> forgetCredential(ProviderProfile profile) {
    invalidate();
    return work(
        () -> {
          if (commercialMode)
            throw new IllegalStateException("Server credentials cannot be changed locally");
          requireSavedProfile(profile);
          ProviderProfile invalidated = invalidateSharedCredentialProfiles(profile);
          secrets.delete(profile.credentialRef());
          return invalidated;
        });
  }

  private ProviderProfile invalidateSharedCredentialProfiles(ProviderProfile profile)
      throws Exception {
    var next = new LinkedHashMap<>(profiles);
    for (ProviderProfile candidate : List.copyOf(next.values()))
      if (candidate.credentialRef().equals(profile.credentialRef()))
        next.put(candidate.id(), revisedProfile(candidate));
    saveProfileDocument(List.copyOf(next.values()));
    synchronized (profiles) {
      profiles.clear();
      profiles.putAll(next);
    }
    if (localJournal != null) next.values().forEach(localJournal::registerProfile);
    return profiles.get(profile.id());
  }

  CompletableFuture<Boolean> hasCredential(ProviderProfile profile) {
    if (commercialMode)
      return CompletableFuture.completedFuture(
          accountView().account() != null && accountView().models().contains(profile));
    return work(
        () ->
            Files.isRegularFile(
                root.resolve("credentials").resolve(profile.credentialRef() + ".dpapi"),
                LinkOption.NOFOLLOW_LINKS));
  }

  CompletableFuture<ProviderProfile> testTextCapability(ProviderProfile profile) {
    if (!localMode)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Text-only verification requires the local edition"));
    pause(PauseReason.USER_PAUSED);
    long epoch = trialEpoch.get();
    UUID call = UUID.randomUUID();
    Instant started = clock.instant();
    return work(() -> {
          requireSavedProfile(profile);
          var ledger = Objects.requireNonNull(localJournal);
          ledger.beginUtilityModelCall(
              call, LocalLoggingSessionJournal.UtilityPurpose.CAPABILITY_CHECK, profile, started);
          return new OpenAICompatibleVisionProvider(
              profile,
              secrets,
              clock,
              () -> {
                if (closed || trialEpoch.get() != epoch)
                  throw new CancellationException("Configuration changed before request");
                requireSavedProfile(profile);
              });
        })
        .thenCompose(
            provider ->
                provider
                    .textCapabilityTestDetailed(() -> closed || trialEpoch.get() != epoch)
                    .toCompletableFuture()
                    .handle(
                        (result, failure) ->
                            work(
                                () -> {
                                  localJournal.recordUtilityModelCall(
                                      call,
                                      LocalLoggingSessionJournal.UtilityPurpose.CAPABILITY_CHECK,
                                      profile,
                                      result == null ? null : result.usage(),
                                      result == null
                                          ? Duration.between(started, clock.instant())
                                          : result.elapsed(),
                                      failure != null
                                          ? modelFailureCode(failure)
                                          : result != null
                                                  && !result.verification().structuredOutputPassed()
                                              ? "MODEL_CAPABILITY_MISMATCH"
                                              : null,
                                      clock.instant());
                                  if (failure != null) throw new CompletionException(failure);
                                  if (closed || trialEpoch.get() != epoch)
                                    throw new CancellationException(
                                        "Late capability result discarded");
                                  requireSavedProfile(profile);
                                  ProviderProfile verified =
                                      profile.withVerification(result.verification());
                                  persistProfile(verified);
                                  return verified;
                                }))
                    .thenCompose(java.util.function.Function.identity())
                    .whenComplete((result, failure) -> provider.close()));
  }

  CompletableFuture<Boolean> hasCredential(PublicModelProfile profile) {
    return CompletableFuture.completedFuture(
        commercialMode
            && profile != null
            && accountView().account() != null
            && accountView().models().contains(profile));
  }

  CompletableFuture<ProviderProfile> testCapability(ProviderProfile profile) {
    if (commercialMode)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Server verifies commercial models"));
    pause(PauseReason.USER_PAUSED);
    long epoch = trialEpoch.get();
    var context = new java.util.concurrent.atomic.AtomicReference<CapabilityContext>();
    return work(() -> {
          requireSavedProfile(profile);
          return new OpenAICompatibleVisionProvider(profile, rubric, secrets, clock);
        })
        .thenCompose(
            provider ->
                provider
                    .capabilityTestDetailed(
                        () -> closed || trialEpoch.get() != epoch,
                        png -> {
                          try {
                            if (closed || trialEpoch.get() != epoch)
                              throw new IllegalStateException(
                                  "Configuration changed before request");
                            UUID run = UUID.randomUUID(),
                                attempt = UUID.randomUUID(),
                                call = UUID.randomUUID();
                            Rubric challenge = capabilityRubric();
                            var settings =
                                readOnlySettings(
                                    ModelSelection.of(profile), challenge, "CAPABILITY_TEST");
                            Instant now = clock.instant();
                            String hash =
                                HexFormat.of()
                                    .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(png));
                            var captured = new SessionPlatform.Capture(png, hash, now);
                            journal.beginRun(
                                run, "synthetic-capability:" + profile.id(), settings, now);
                            String path = evidence.save(run, attempt, captured);
                            var view =
                                new SessionPlatform.View(
                                    "capability-image-" + attempt,
                                    challenge.question().id(),
                                    0,
                                    SessionPlatform.Page.READY,
                                    null,
                                    0,
                                    null,
                                    false);
                            journal.beginAttempt(
                                run, attempt, 1, view, epoch, path, captured, settings);
                            journal.beginModelCall(call, run, attempt, profile.id(), now);
                            context.set(new CapabilityContext(run, attempt, call, now));
                          } catch (Exception failure) {
                            throw new CompletionException(failure);
                          }
                        })
                    .toCompletableFuture()
                    .handle(
                        (result, failure) ->
                            work(
                                () -> {
                                  var saved = context.get();
                                  if (saved != null)
                                    journal.completeModelCallMetrics(
                                        saved.call(),
                                        GradingResponse.Source.LIVE,
                                        result == null ? null : result.usage(),
                                        result == null
                                            ? Duration.between(saved.started(), clock.instant())
                                            : result.elapsed(),
                                        failure != null
                                            ? modelFailureCode(failure)
                                            : result != null
                                                    && (!result.verification().imagePassed()
                                                        || !result
                                                            .verification()
                                                            .structuredOutputPassed())
                                                ? "MODEL_CAPABILITY_MISMATCH"
                                                : null,
                                        clock.instant());
                                  if (failure != null) {
                                    if (saved != null)
                                      journal.transition(
                                          saved.run(),
                                          saved.attempt(),
                                          SessionState.PAUSED,
                                          PauseReason.MODEL_FAILED,
                                          clock.instant());
                                    throw new CompletionException(failure);
                                  }
                                  if (closed || trialEpoch.get() != epoch) {
                                    if (saved != null)
                                      journal.transition(
                                          saved.run(),
                                          saved.attempt(),
                                          SessionState.PAUSED,
                                          PauseReason.CONFIG_CHANGED,
                                          clock.instant());
                                    throw new IllegalStateException(
                                        "Late capability result discarded");
                                  }
                                  ProviderProfile verified =
                                      profile.withVerification(result.verification());
                                  persistProfile(verified);
                                  journal.transition(
                                      saved.run(),
                                      saved.attempt(),
                                      SessionState.COMPLETED,
                                      PauseReason.NONE,
                                      clock.instant());
                                  return verified;
                                }))
                    .thenCompose(java.util.function.Function.identity())
                    .whenComplete((ignored, error) -> provider.close()));
  }

  private record CapabilityContext(UUID run, UUID attempt, UUID call, Instant started) {}

  private static Rubric capabilityRubric() {
    return new Rubric(
        new QuestionDefinition(
            "synthetic-vision-capability",
            1,
            "仅读取合成图的六位数字",
            "期望值只存在图片和本地校验中",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "vision-check", "能力诊断，不生成阅卷分数", List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "能力测试只记录视觉验证和实际调用用量，不生成学生评分结果或提交动作。",
        BigDecimal.ONE,
        false,
        false);
  }

  CompletableFuture<GradingPresentation> trial(ProviderProfile profile) {
    return trial(ModelSelection.of(profile));
  }

  CompletableFuture<GradingPresentation> trial(ModelSelection profile) {
    pause(PauseReason.USER_PAUSED);
    long epoch = trialEpoch.get();
    long imageTicket = actionTicket();
    long originalAccount = accountTicket();
    return work(() -> {
          requireAccountEpoch(originalAccount);
          requireLiveProfile(profile);
          var trialJournal = requireJournal();
          Rubric fixedRubric = rubric;
          fixedRubric.validateReady();
          if (!windows.activateBoundWindow(Objects.requireNonNull(binding)))
            throw new IllegalStateException("Target activation failed");
          var image = capture.capture(binding);
          requireTicket(imageTicket);
          UUID run = UUID.randomUUID(), attempt = UUID.randomUUID(), call = UUID.randomUUID();
          SessionSettings settings = readOnlySettings(profile, fixedRubric, "READ_ONLY_TRIAL");
          var captured =
              new SessionPlatform.Capture(image.png(), image.sha256(), image.capturedAt());
          trialJournal.beginRun(
              run, "read-only:" + binding.calibratedWindow().identity(), settings, clock.instant());
          String path = evidence.save(run, attempt, captured);
          var view =
              new SessionPlatform.View(
                  "read-only-capture-" + attempt,
                  fixedRubric.question().id(),
                  0,
                  SessionPlatform.Page.READY,
                  null,
                  0,
                  null,
                  false);
          trialJournal.beginAttempt(run, attempt, 1, view, epoch, path, captured, settings);
          ManagedProvider provider = realProvider(profile, fixedRubric);
          try {
            requireTicket(imageTicket);
            trialJournal.beginModelCall(call, run, attempt, profile.id(), clock.instant());
            return new TrialContext(
                run,
                attempt,
                new GradingRequest(call, fixedRubric.question(), image.png()),
                provider,
                trialJournal);
          } catch (Exception failure) {
            provider.close();
            throw failure;
          }
        })
        .thenCompose(
            context -> {
              var request = context.request();
              var provider = context.provider();
              return provider
                  .grade(request, () -> closed || trialEpoch.get() != epoch)
                  .toCompletableFuture()
                  .handle(
                      (response, failure) ->
                          work(
                              () -> {
                                context
                                    .journal()
                                    .completeModelCall(
                                        request.requestId(),
                                        response,
                                        failure == null ? null : modelFailureCode(failure),
                                        clock.instant());
                                if (failure != null) {
                                  context
                                      .journal()
                                      .transition(
                                          context.run(),
                                          context.attempt(),
                                          SessionState.PAUSED,
                                          PauseReason.MODEL_FAILED,
                                          clock.instant());
                                  throw new CompletionException(failure);
                                }
                                if (closed
                                    || trialEpoch.get() != epoch
                                    || response == null
                                    || !request.requestId().equals(response.requestId())) {
                                  context
                                      .journal()
                                      .transition(
                                          context.run(),
                                          context.attempt(),
                                          SessionState.PAUSED,
                                          PauseReason.CONFIG_CHANGED,
                                          clock.instant());
                                  throw new IllegalStateException("Late trial result discarded");
                                }
                                var validated =
                                    GradeValidator.validate(request.question(), response.result());
                                context
                                    .journal()
                                    .saveGrade(context.attempt(), response, validated.totalScore());
                                context
                                    .journal()
                                    .transition(
                                        context.run(),
                                        context.attempt(),
                                        SessionState.COMPLETED,
                                        PauseReason.NONE,
                                        clock.instant());
                                return new GradingPresentation(
                                    context.run(),
                                    response,
                                    validated.totalScore(),
                                    imageTicket,
                                    originalAccount);
                              }))
                  .thenCompose(java.util.function.Function.identity())
                  .whenComplete((ignored, error) -> provider.close());
            });
  }

  private record TrialContext(
      UUID run,
      UUID attempt,
      GradingRequest request,
      ManagedProvider provider,
      PersistentSessionJournal journal) {}

  private SessionSettings readOnlySettings(ModelSelection profile, Rubric fixedRubric, String mode)
      throws Exception {
    String snapshot =
        json.writeValueAsString(
            Map.of(
                "mode",
                mode,
                "rubric",
                new RubricJsonCodec().write(fixedRubric),
                "profile",
                profileSnapshot(profile),
                "access",
                accessSnapshot(profile),
                "binding",
                bindingSnapshot()));
    return new SessionSettings(
        fixedRubric.question(),
        profile.id(),
        profile.revision(),
        revision.get(),
        snapshot,
        1,
        1,
        0,
        null,
        Duration.ofMillis(200),
        Duration.ofSeconds(20));
  }

  private static String modelFailureCode(Throwable error) {
    return FailureDiagnostic.from(SessionState.GRADING, error).codes().getFirst();
  }

  CompletableFuture<List<SessionJournal.RunRecord>> runs() {
    return work(
        () -> {
          requireDeveloperRecords();
          return journal.runs();
        });
  }

  CompletableFuture<List<SessionJournal.AttemptRecord>> attempts(UUID runId) {
    return work(
        () -> {
          requireDeveloperRecords();
          return journal.attempts(runId);
        });
  }

  CompletableFuture<String> recordAudit(SessionJournal.AttemptRecord row) {
    return work(
        () -> {
          requireDeveloperRecords();
          return journal.attemptAuditJson(row.attemptId());
        });
  }

  CompletableFuture<byte[]> evidenceImage(SessionJournal.AttemptRecord row) {
    return work(
        () -> {
          requireDeveloperRecords();
          Path path = Path.of(row.evidencePath()).toAbsolutePath().normalize();
          if (!path.startsWith(root.resolve("captures")) || Files.size(path) > 20 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid evidence path");
          return Files.readAllBytes(path);
        });
  }

  CompletableFuture<Void> exportCsv(Path destination, UUID runId) {
    return work(
        () -> {
          requireDeveloperRecords();
          RecordsCsvExporter.write(destination, journal.attempts(runId));
          return null;
        });
  }

  private void requireDeveloperRecords() {
    if (!developerMode && !localMode)
      throw new IllegalStateException("Records require the current account scope");
  }

  private GradingRecords.Owner recordOwner() {
    if (localMode) return GradingRecords.Owner.local();
    if (developerMode && !commercialMode) return GradingRecords.Owner.localDeveloper();
    var account = accountView().account();
    if (account == null) throw new IllegalStateException("Login required");
    return new GradingRecords.Owner(gatewayAddress(), account.accountId());
  }

  RecordsView.DataSource recordsSource() {
    return new RecordsView.DataSource() {
      @Override
      public String storageLocation() {
        return "完整记录："
            + root.resolve(
                localMode ? "database/local-deskgrade.mv.db" : "database/deskgrade.mv.db")
            + " · 日志摘要："
            + root.resolve("records");
      }

      public CompletionStage<GradingRecords.Page> query(GradingRecords.Query query) {
        long epoch = accountTicket();
        return work(
            () -> {
              requireAccountEpoch(epoch);
              var owner = recordOwner();
              var selectedJournal = requireJournal();
              var page = selectedJournal.queryRecords(owner, query);
              requireAccountEpoch(epoch);
              return page;
            });
      }

      public CompletionStage<String> detail(UUID attemptId) {
        long epoch = accountTicket();
        return work(
            () -> {
              requireAccountEpoch(epoch);
              var selectedJournal = requireJournal();
              String value = selectedJournal.recordJson(recordOwner(), attemptId);
              requireAccountEpoch(epoch);
              return value;
            });
      }

      public CompletionStage<Void> exportJson(Path destination, GradingRecords.Query query) {
        long epoch = accountTicket();
        return work(
            () -> {
              requireAccountEpoch(epoch);
              var owner = recordOwner();
              Path target = destination.toAbsolutePath().normalize();
              if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                  || Files.isSymbolicLink(target)
                  || !Files.isDirectory(target.getParent()))
                throw new IllegalArgumentException("Select a JSON file in an existing directory");
              Path temporary =
                  target.getParent().resolve(".deskgrade-records-" + UUID.randomUUID() + ".json");
              try {
                var selectedJournal = requireJournal();
                selectedJournal.exportJson(temporary, owner, query);
                synchronized (accountLock) {
                  requireAccountEpoch(epoch);
                  if (!owner.equals(recordOwner()))
                    throw new CancellationException("Account changed");
                  Files.move(
                      temporary,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }
              } finally {
                Files.deleteIfExists(temporary);
              }
              return null;
            });
      }
    };
  }

  CompletableFuture<Void> exportProfiles(Path destination) {
    return work(
        () -> {
          saveText(destination, new ProfileJsonCodec().write(List.copyOf(profiles.values())));
          return null;
        });
  }

  private void persistProfiles() throws Exception {
    saveProfileDocument(profiles());
  }

  private void persistProfile(ProviderProfile profile) throws Exception {
    var next = new LinkedHashMap<>(profiles);
    next.put(profile.id(), profile);
    saveProfileDocument(List.copyOf(next.values()));
    synchronized (profiles) {
      profiles.clear();
      profiles.putAll(next);
    }
    if (localJournal != null) localJournal.registerProfile(profile);
  }

  private void saveProfileDocument(List<ProviderProfile> candidates) throws Exception {
    if (candidates.size() > ProfileJsonCodec.MAX_LOCAL_PROFILES)
      throw new LocalProfileStorageException(LocalProfileStorageException.Reason.TOO_MANY_PROFILES);
    var codec = new ProfileJsonCodec();
    String document = codec.write(candidates);
    if (document.getBytes(StandardCharsets.UTF_8).length > ProfileJsonCodec.MAX_DOCUMENT_BYTES)
      throw new LocalProfileStorageException(
          LocalProfileStorageException.Reason.DOCUMENT_TOO_LARGE);
    try {
      codec.readLocal(
          document); // Never replace a ready profile file with one a restart cannot read.
    } catch (RuntimeException failure) {
      throw new LocalProfileStorageException(
          LocalProfileStorageException.Reason.INVALID_CONFIGURATION);
    }
    saveText(profileFile(), document);
  }

  private Path profileFile() {
    return root.resolve(localMode ? "settings/local-profiles.json" : "settings/profiles.json");
  }

  List<ProviderProfile> profiles() {
    synchronized (profiles) {
      return List.copyOf(profiles.values());
    }
  }

  LocalModelSelection localModelSelection() {
    return localModelSelection;
  }

  private void loadLocalModelSelection() throws Exception {
    Path file = root.resolve("settings/local-model-selection.json");
    if (!Files.exists(file)) {
      saveLocalModelSelectionDocument(localModelSelection);
      return;
    }
    if (Files.size(file) > 4096)
      throw new IllegalArgumentException("Local model selection is too large");
    var value = strictJson.readTree(Files.readString(file));
    if (value.path("schemaVersion").asInt() != 1)
      throw new IllegalArgumentException("Local model selection version");
    var selection =
        new LocalModelSelection(
            value.path("primaryId").asText(),
            value.path("fallbackId").asText(),
            value.path("rubricId").asText());
    requireModelRoleProfiles(selection);
    localModelSelection = selection;
  }

  CompletableFuture<LocalModelSelection> saveLocalModelSelection(
      String primaryId, String fallbackId, String rubricId) {
    final LocalModelSelection next;
    try {
      next = new LocalModelSelection(primaryId, fallbackId, rubricId);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    invalidate();
    return work(
        () -> {
          if (!localMode)
            throw new IllegalStateException("Local model roles require the local edition");
          requireModelRoleProfiles(next);
          saveLocalModelSelectionDocument(next);
          localModelSelection = next;
          return next;
        });
  }

  private void requireModelRoleProfiles(LocalModelSelection selection) {
    if (!profiles.containsKey(selection.primaryId())
        || !profiles.containsKey(selection.rubricId())
        || !selection.fallbackId().isEmpty() && !profiles.containsKey(selection.fallbackId()))
      throw new IllegalArgumentException("请先保存对应的模型配置");
  }

  private void saveLocalModelSelectionDocument(LocalModelSelection selection) throws Exception {
    saveText(
        root.resolve("settings/local-model-selection.json"),
        json.writeValueAsString(
            Map.of(
                "schemaVersion",
                1,
                "primaryId",
                selection.primaryId(),
                "fallbackId",
                selection.fallbackId(),
                "rubricId",
                selection.rubricId())));
  }

  static boolean textCapabilityVerified(ProviderProfile profile) {
    return profile != null && profile.isStructuredVerified();
  }

  boolean generationModelAvailable() {
    return generationModelUnavailableReason().isEmpty();
  }

  /** Active saved generation role only; an unassigned editor draft cannot revoke this role. */
  String generationModelUnavailableReason() {
    if (!localMode) return "当前运行方式不支持本机评分点生成。";
    if (closed) return "程序已关闭，无法生成评分点。";
    if (!ready()) return "本地数据尚未初始化完成，请稍后重试。";
    var selection = localModelSelection;
    if (selection == null || selection.rubricId() == null || selection.rubricId().isBlank())
      return "尚未指定评分规则生成模型，请完成模型用途配置。";
    ProviderProfile profile = profiles.get(selection.rubricId());
    if (profile == null) return "当前评分规则生成模型配置不存在，请重新选择并完成配置。";
    if (!credentialFilePresent(profile)) return "当前评分规则生成模型尚未缓存 Key，请填写并保存自己的 Key。";
    if (!textCapabilityVerified(profile))
      return profile.verification() != null
              && !profile.configDigest().equals(profile.verification().configDigest())
          ? "当前评分规则生成模型配置已更改，请重新验证文本与结构化输出能力。"
          : "当前评分规则生成模型的文本与结构化输出能力尚未验证，请先完成验证。";
    return "";
  }

  private boolean credentialFilePresent(ProviderProfile profile) {
    return profile != null
        && Files.isRegularFile(
            root.resolve("credentials").resolve(profile.credentialRef() + ".dpapi"),
            LinkOption.NOFOLLOW_LINKS);
  }

  private void requireSavedProfile(ProviderProfile profile) {
    if (commercialMode) {
      var view = accountView();
      if (view.account() == null || !view.models().contains(profile))
        throw new IllegalStateException("Login and server model selection required");
      return;
    }
    if (!profile.equals(profiles.get(profile.id())))
      throw new IllegalStateException("Saved model configuration differs");
  }

  private void saveText(Path path, String text) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".deskgrade-", ".tmp");
    try {
      try (var channel =
          java.nio.channels.FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        var bytes = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Map<String, Object> bindingSnapshot() {
    if (commercialMode || localMode) {
      if (binding == null) return Map.of("bound", false, "coordinatesPersisted", false);
      return Map.of(
          "bound", true,
          "authority", "EPHEMERAL_CURRENT_WINDOW",
          "coordinatesPersisted", false);
    }
    return bindingSnapshot(binding);
  }

  private Map<String, Object> platformSnapshot() {
    var manual = manualBinding;
    if (manual == null || !automaticManualTarget()) return Map.of("mode", "PLATFORM_PROTOCOL");
    var positions = manual.positions();
    if (commercialMode || localMode) {
      return Map.of(
          "mode",
          "MANUAL_COORDINATES",
          "coordinatesPersisted",
          false,
          "answerIdentitySource",
          "ANSWER_REGION_EXACT_RECAPTURE",
          "navigationMode",
          positions.navigationMode().name(),
          "submissionConfirmationEvidence",
          positions.navigationMode() == ManualPlatformCalibration.NavigationMode.CLICK_NEXT
              ? "STABLE_ACK_REGION_CHANGE_BEFORE_NEXT"
              : "STABLE_ANSWER_REGION_TRANSITION");
    }
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("mode", "MANUAL_COORDINATES");
    snapshot.put("calibrationId", positions.id());
    snapshot.put("calibrationRevision", positions.revision());
    snapshot.put("answerIdentitySource", "ANSWER_REGION_EXACT_RECAPTURE");
    snapshot.put("layout", positions.layout());
    snapshot.put("answerRegion", positions.answerRegion());
    snapshot.put("scoreRegion", positions.scoreRegion());
    snapshot.put("submitRegion", positions.submitRegion());
    snapshot.put("submissionAckRegion", positions.submissionAckRegion());
    snapshot.put("nextRegion", positions.nextRegion());
    snapshot.put("navigationMode", positions.navigationMode().name());
    snapshot.put(
        "submissionConfirmationEvidence",
        positions.navigationMode() == ManualPlatformCalibration.NavigationMode.CLICK_NEXT
            ? "STABLE_ACK_REGION_CHANGE_BEFORE_NEXT"
            : "STABLE_ANSWER_REGION_TRANSITION");
    snapshot.put(
        "answerConsistency",
        Map.of(
            "verifiedAtBinding",
            true,
            "method",
            "CURRENT_ANSWER_STABLE_AT_BIND",
            "stabilityMillis",
            ManualCoordinatePlatformAdapter.SCREEN_STABILITY.toMillis()));
    snapshot.put("savedAt", positions.savedAt().toString());
    return snapshot;
  }

  private static Map<String, Object> bindingSnapshot(TargetBinding target) {
    if (target == null) return Map.of("bound", false);
    WindowSnapshot w = target.calibratedWindow();
    return Map.of(
        "identity",
        Map.of(
            "hwnd",
            w.identity().hwnd(),
            "pid",
            w.identity().processId(),
            "processStartedAt",
            w.identity().processStartedAt().toString()),
        "title",
        w.title(),
        "windowClass",
        w.windowClass(),
        "windowBounds",
        w.windowBounds(),
        "clientBounds",
        w.clientBounds(),
        "display",
        w.display(),
        "dpi",
        w.dpi(),
        "answerRegion",
        target.answerRegion(),
        "calibratedRegion",
        target.calibratedRegion());
  }

  private static ProviderProfile revisedProfile(ProviderProfile p) {
    return new ProviderProfile(
        p.id(),
        p.revision() + 1,
        p.displayName(),
        p.endpoint(),
        p.modelId(),
        p.credentialRef(),
        p.structuredMode(),
        p.parameters(),
        p.allowedParameters(),
        p.connectTimeout(),
        p.requestTimeout(),
        p.pricing(),
        null);
  }

  private static QuestionDefinition syntheticQuestion() {
    return new QuestionDefinition(
        "1",
        1,
        "仅用于模拟工具的固定合成题",
        "合成测试参考",
        new BigDecimal("4"),
        List.of(
            new ScoringCriterion(
                "meaning", "合成 2 分项", List.of(BigDecimal.ZERO, new BigDecimal("2"))),
            new ScoringCriterion("shi", "合成 1 分项", List.of(BigDecimal.ZERO, BigDecimal.ONE)),
            new ScoringCriterion("yi", "合成 1 分项", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
  }

  private static Rubric initialRubric() {
    return new Rubric(
        new QuestionDefinition(
            "example-translation-4points",
            1,
            "[原文缺失]",
            "这几种形势，都利于我方坚守而不利于强攻，这是不变的道理。",
            new BigDecimal("4"),
            List.of(
                new ScoringCriterion(
                    "meaning",
                    "准确译出句子大意给2分，否则该项0分。",
                    List.of(BigDecimal.ZERO, new BigDecimal("2"))),
                new ScoringCriterion(
                    "shi",
                    "在对应语义位置正确翻译为这、这几种、这几样、这样等意思，给1分；不是全文出现相同字就算正确。",
                    List.of(BigDecimal.ZERO, BigDecimal.ONE)),
                new ScoringCriterion(
                    "yi",
                    "在对应语义位置正确表达变、改变、变化、变动等意思，给1分；不得改变原句含义。",
                    List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "能够准确译出句子大意，给2分；若句子大意翻译错误，不得此2分。\n关键字“是”翻译为“这、这几种、这几样、这样”等正确释义，给1分，翻译错误不得分。\n关键字“易”翻译为“变、改变、变化、变动”等正确释义，给1分，翻译错误不得分。\n本题满分共4分，要求以直译为主、意译为辅，翻译语句完整，关键点翻译正确方可满分。",
        BigDecimal.ONE,
        true,
        false);
  }

  private <T> CompletableFuture<T> work(Callable<T> task) {
    if (closed)
      return CompletableFuture.failedFuture(new IllegalStateException("Workspace closed"));
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return task.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          } catch (Error failure) {
            emergencyStop(failure);
            throw failure;
          }
        },
        worker);
  }

  CompletableFuture<Void> awaitClosed() {
    return terminated;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ready = false;
    actionRequests.incrementAndGet();
    trialEpoch.incrementAndGet();
    hideAnswerScan();
    synchronized (accountLock) {
      accountEpoch.incrementAndGet();
      accountView = AccountView.empty();
      // Normal application close preserves the encrypted login cache and server session.
      // CommercialClient.close below revokes only this process's in-memory authority.
    }
    var c = controller;
    if (c != null) c.pause(PauseReason.RECOVERED);
    worker.execute(
        () -> {
          try {
            app.deskgrade.adapters.runtime.ResourceCleanup.closeAll(
                this::closeMonitor,
                controller,
                this::closeAutomaticProvider,
                hotkeys,
                commercialClient,
                journal);
            terminated.complete(null);
          } catch (Exception | LinkageError failure) {
            terminated.completeExceptionally(failure);
          }
        });
    worker.shutdown();
  }
}
