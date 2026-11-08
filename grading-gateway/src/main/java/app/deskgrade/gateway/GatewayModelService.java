package app.deskgrade.gateway;

import app.deskgrade.adapters.commercial.PublicModelProfile;
import app.deskgrade.adapters.model.GradeResultJsonCodec;
import app.deskgrade.adapters.model.ModelCallTrace;
import app.deskgrade.adapters.model.OpenAICompatibleVisionProvider;
import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Dispatch ownership comes from the persistent PREPARED insert, not an in-memory retry cache. */
public final class GatewayModelService {
  static final int MAX_REQUEST_BYTES = 9 * 1024 * 1024;
  static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
  static final int MAX_IMAGE_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
  static final long MAX_IMAGE_PIXELS = 10_000_000L;
  static final int MAX_RUBRIC_UTF8_BYTES = 512 * 1024;
  static final int MAX_RUBRIC_GENERATION_REQUEST_BYTES = 512 * 1024;
  static final int MAX_CRITERIA = 50;
  static final int MAX_ALLOWED_SCORES = 2_000;
  static final int MAX_CRITERION_RULE_CHARS = 4_000;
  static final int MAX_TOTAL_TEXT_CHARS = 160_000;
  private static final String PUBLIC_PRIMARY_PROFILE_ID = "service_primary";
  private static final String PUBLIC_FALLBACK_PROFILE_ID = "service_fallback";
  private static final String PUBLIC_PRIMARY_MODEL_ID = "online-grading-primary";
  private static final String PUBLIC_FALLBACK_MODEL_ID = "online-grading-fallback";
  private static final long PUBLIC_POLICY_REVISION_EPOCH = 4_000_000_000_000L;
  private static final Duration PUBLIC_REQUEST_TIMEOUT = Duration.ofMinutes(10);

  @FunctionalInterface
  public interface Engine {
    CompletionStage<GradingResponse> grade(
        ProviderProfile profile, Rubric rubric, GradingRequest request, Runnable beforeDispatch);

    default CompletionStage<GradingResponse> grade(
        ProviderProfile profile,
        Rubric rubric,
        GradingRequest request,
        Runnable beforeDispatch,
        ModelCallTrace trace) {
      return grade(profile, rubric, request, beforeDispatch);
    }
  }

  @FunctionalInterface
  public interface RubricEngine {
    CompletionStage<GeneratedRubric> generate(
        ProviderProfile profile,
        String subject,
        String referenceAnswer,
        BigDecimal maxScore,
        Runnable beforeDispatch);

    default CompletionStage<GeneratedRubric> generate(
        ProviderProfile profile,
        String subject,
        String referenceAnswer,
        BigDecimal maxScore,
        Runnable beforeDispatch,
        ModelCallTrace trace) {
      return generate(profile, subject, referenceAnswer, maxScore, beforeDispatch);
    }
  }

  public record GeneratedRubric(
      BigDecimal scoreStep,
      List<ScoringCriterion> criteria,
      GradingResponse.Usage usage,
      Duration elapsed) {
    public GeneratedRubric {
      criteria = criteria == null ? null : List.copyOf(criteria);
    }
  }

  public record Reply(int status, ObjectNode body) {}

  public record ModelPolicy(
      long revision,
      String profileId,
      String modelId,
      String fallbackProfileId,
      String fallbackModelId) {}

  public record PolicyChange(ModelPolicy policy, UUID auditEntryId, boolean replayed) {}

  public record RubricModelPolicy(long revision, String profileId, String modelId) {}

  public record RubricPolicyChange(RubricModelPolicy policy, UUID auditEntryId, boolean replayed) {}

  private final GatewayStore store;
  private final Map<String, ProviderProfile> profiles;
  private final Map<String, String> frozenFallbackByPrimary;
  private final Engine engine;
  private final RubricEngine rubricEngine;
  private final java.util.function.Predicate<ProviderProfile> available;
  private final Clock clock;

  public GatewayModelService(GatewayStore store, List<ProviderProfile> profiles, Engine engine) {
    this(store, profiles, engine, unavailableRubricEngine(), profile -> true, Clock.systemUTC());
  }

  public GatewayModelService(
      GatewayStore store,
      List<ProviderProfile> profiles,
      Engine engine,
      RubricEngine rubricEngine) {
    this(store, profiles, engine, rubricEngine, profile -> true, Clock.systemUTC());
  }

  GatewayModelService(
      GatewayStore store,
      List<ProviderProfile> profiles,
      Engine engine,
      java.util.function.Predicate<ProviderProfile> available) {
    this(store, profiles, engine, unavailableRubricEngine(), available, Clock.systemUTC());
  }

  GatewayModelService(
      GatewayStore store,
      List<ProviderProfile> profiles,
      Engine engine,
      RubricEngine rubricEngine,
      java.util.function.Predicate<ProviderProfile> available,
      Clock clock) {
    this.store = store;
    this.engine = engine;
    this.rubricEngine = rubricEngine;
    this.available = available;
    this.clock = clock;
    if (profiles.size() > 100) throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    var indexed = new LinkedHashMap<String, ProviderProfile>();
    for (var profile : profiles) {
      if (indexed.putIfAbsent(profile.id(), profile) != null)
        throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
      store.bindProfile(profile.id(), profile.configDigest());
    }
    this.profiles = Map.copyOf(indexed);
    var fallbacks = new LinkedHashMap<String, String>();
    var fallbackCandidates =
        indexed.values().stream()
            .filter(this::isAvailable)
            .sorted(java.util.Comparator.comparing(ProviderProfile::id))
            .toList();
    for (var primary : indexed.values()) {
      fallbackCandidates.stream()
          .filter(candidate -> !candidate.id().equals(primary.id()))
          .filter(candidate -> !candidate.modelId().equals(primary.modelId()))
          .findFirst()
          .ifPresent(candidate -> fallbacks.put(primary.id(), candidate.id()));
    }
    this.frozenFallbackByPrimary = Map.copyOf(fallbacks);
  }

  public static GatewayModelService live(
      GatewayStore store,
      List<ProviderProfile> profiles,
      SecretStore secrets,
      boolean allowLive,
      Clock clock) {
    return new GatewayModelService(
        store,
        profiles,
        new Engine() {
          @Override
          public CompletionStage<GradingResponse> grade(
              ProviderProfile profile, Rubric rubric, GradingRequest request, Runnable before) {
            return grade(profile, rubric, request, before, ModelCallTrace.NONE);
          }

          @Override
          public CompletionStage<GradingResponse> grade(
              ProviderProfile profile,
              Rubric rubric,
              GradingRequest request,
              Runnable before,
              ModelCallTrace trace) {
            var provider =
                new OpenAICompatibleVisionProvider(profile, rubric, secrets, clock, before, trace);
            var original = provider.grade(request, () -> false).toCompletableFuture();
            original.whenComplete((value, failure) -> provider.close());
            return original;
          }
        },
        new RubricEngine() {
          @Override
          public CompletionStage<GeneratedRubric> generate(
              ProviderProfile profile,
              String subject,
              String referenceAnswer,
              BigDecimal maxScore,
              Runnable before) {
            return generate(
                profile, subject, referenceAnswer, maxScore, before, ModelCallTrace.NONE);
          }

          @Override
          public CompletionStage<GeneratedRubric> generate(
              ProviderProfile profile,
              String subject,
              String referenceAnswer,
              BigDecimal maxScore,
              Runnable before,
              ModelCallTrace trace) {
            var provider =
                new OpenAICompatibleVisionProvider(profile, secrets, clock, before, trace);
            var original =
                provider
                    .generateRubric(subject, referenceAnswer, maxScore, CancellationToken.none())
                    .thenApply(
                        generated ->
                            new GeneratedRubric(
                                generated.scoreStep(),
                                generated.criteria(),
                                generated.usage(),
                                generated.elapsed()))
                    .toCompletableFuture();
            original.whenComplete((value, failure) -> provider.close());
            return original;
          }
        },
        profile -> allowLive && hasSecret(secrets, profile.credentialRef()),
        clock);
  }

  private static RubricEngine unavailableRubricEngine() {
    return (profile, subject, referenceAnswer, maxScore, before) ->
        java.util.concurrent.CompletableFuture.failedFuture(
            new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE));
  }

  private static ObjectNode gradingContext(Rubric rubric) {
    var context = GatewayJson.MAPPER.createObjectNode();
    var encoded =
        GatewayJson.parse(
            new RubricJsonCodec().write(rubric).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    context.set("rubric", encoded);
    var question = context.putObject("question");
    question.put("id", rubric.question().id());
    question.put("prompt", rubric.question().prompt());
    question.put("standardAnswer", rubric.question().standardAnswer());
    question.put("maxScore", rubric.question().maximumScore());
    question.set("criteria", encoded.path("criteria").deepCopy());
    return context;
  }

  private static ObjectNode generationContext(
      String subject, String referenceAnswer, BigDecimal maxScore) {
    var context = GatewayJson.MAPPER.createObjectNode();
    var generation = context.putObject("generation");
    generation.put("subject", subject);
    generation.put("referenceAnswer", referenceAnswer);
    generation.put("maxScore", maxScore);
    return context;
  }

  private ModelCallTrace callTrace(
      UUID userId,
      UUID requestId,
      String kind,
      ObjectNode context,
      java.util.concurrent.atomic.AtomicReference<GatewayException.Code> rejection) {
    return callTrace(userId, requestId, kind, context, rejection, null, null);
  }

  private ModelCallTrace callTrace(
      UUID userId,
      UUID requestId,
      String kind,
      ObjectNode context,
      java.util.concurrent.atomic.AtomicReference<GatewayException.Code> rejection,
      GatewayConnectRetryAudit retryAudit,
      java.util.function.IntConsumer retryAuthorization) {
    return new ModelCallTrace() {
      @Override
      public void requestPrepared(String requestJson) {
        try {
          var snapshot = context.deepCopy();
          snapshot.put("schemaVersion", 1);
          snapshot.put("kind", kind);
          snapshot.set(
              "providerRequest",
              GatewayJson.parse(requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          store.recordModelRequest(userId, requestId, kind, snapshot.toString());
        } catch (RuntimeException failure) {
          rejection.set(GatewayException.Code.STORAGE_FAILURE);
          throw failure;
        }
      }

      @Override
      public void outputReceived(String modelOutput) {
        try {
          store.recordModelOutput(userId, requestId, kind, modelOutput);
        } catch (RuntimeException failure) {
          rejection.set(GatewayException.Code.STORAGE_FAILURE);
          throw failure;
        }
      }

      @Override
      public void connectRetryScheduled(int attemptNumber, Duration delay) {
        try {
          if (retryAudit == null || retryAuthorization == null || !"GRADING".equals(kind))
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          retryAudit.scheduled(attemptNumber, delay);
        } catch (GatewayException rejected) {
          rejection.set(rejected.code());
          throw rejected;
        }
      }

      @Override
      public void authorizeConnectRetry(int attemptNumber) {
        try {
          if (retryAudit == null || retryAuthorization == null || !"GRADING".equals(kind))
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          retryAudit.authorize(attemptNumber, () -> retryAuthorization.accept(attemptNumber));
        } catch (GatewayException rejected) {
          rejection.set(rejected.code());
          throw rejected;
        }
      }
    };
  }

  /** Ordinary accounts receive only a minimal primary/alternate binding, never provider config. */
  public List<PublicModelProfile> publicProfiles() {
    var policy = policy();
    var profile = policy.profileId() == null ? null : profiles.get(policy.profileId());
    if (!isAvailable(profile)) return List.of();
    var fallback =
        policy.fallbackProfileId() == null ? null : profiles.get(policy.fallbackProfileId());
    long publicRevision = publicPolicyRevision(policy.revision());
    var primaryView = publicProfile(PublicModelProfile.Role.PRIMARY, publicRevision);
    return isAvailable(fallback)
        ? List.of(primaryView, publicProfile(PublicModelProfile.Role.FALLBACK, publicRevision))
        : List.of(primaryView);
  }

  private static PublicModelProfile publicProfile(PublicModelProfile.Role role, long revision) {
    boolean primary = role == PublicModelProfile.Role.PRIMARY;
    return new PublicModelProfile(
        primary ? PUBLIC_PRIMARY_PROFILE_ID : PUBLIC_FALLBACK_PROFILE_ID,
        revision,
        primary ? "在线评分服务" : "备用评分服务",
        primary ? PUBLIC_PRIMARY_MODEL_ID : PUBLIC_FALLBACK_MODEL_ID,
        PUBLIC_REQUEST_TIMEOUT,
        true,
        role);
  }

  /** Public policy contains protocol aliases only; the administrator policy retains real IDs. */
  public ObjectNode publicPolicyJson() {
    var policy = policy();
    if (policy.revision() == 0) return policyJson(policy);
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("revision", publicPolicyRevision(policy.revision()));
    result.put("profileId", PUBLIC_PRIMARY_PROFILE_ID);
    result.put("modelId", PUBLIC_PRIMARY_MODEL_ID);
    var fallback =
        policy.fallbackProfileId() == null ? null : profiles.get(policy.fallbackProfileId());
    if (!isAvailable(fallback)) {
      result.putNull("fallbackProfileId");
      result.putNull("fallbackModelId");
    } else {
      result.put("fallbackProfileId", PUBLIC_FALLBACK_PROFILE_ID);
      result.put("fallbackModelId", PUBLIC_FALLBACK_MODEL_ID);
    }
    return result;
  }

  private static long publicPolicyRevision(long internalRevision) {
    if (internalRevision == 0) return 0;
    try {
      return Math.addExact(PUBLIC_POLICY_REVISION_EPOCH, internalRevision);
    } catch (ArithmeticException invalid) {
      throw new GatewayException(GatewayException.Code.INTERNAL_ERROR);
    }
  }

  public ModelPolicy policy() {
    return effectivePolicy(store.modelPolicy());
  }

  public ModelPolicy adminPolicy(UUID adminId) {
    return effectivePolicy(store.adminModelPolicy(adminId));
  }

  public ObjectNode adminModels(UUID adminId) {
    var policy = adminPolicy(adminId);
    var rubricPolicy = adminRubricPolicy(adminId);
    var result = GatewayJson.MAPPER.createObjectNode();
    result.set("policy", policyJson(policy));
    result.set("rubricPolicy", rubricPolicyJson(rubricPolicy));
    var entries = result.putArray("models");
    profiles.values().stream()
        .sorted(java.util.Comparator.comparing(ProviderProfile::id))
        .forEach(
            profile -> {
              var entry = entries.addObject();
              entry.put("profileId", profile.id());
              entry.put("modelId", profile.modelId());
              entry.put("displayName", profile.displayName());
              entry.put("revision", profile.revision());
              entry.put("verified", profile.isVerified());
              entry.put("available", isAvailable(profile));
              entry.put("active", profile.id().equals(policy.profileId()));
              entry.put("fallback", profile.id().equals(policy.fallbackProfileId()));
              entry.put("rubricActive", profile.id().equals(rubricPolicy.profileId()));
            });
    return result;
  }

  public PolicyChange selectPolicy(
      UUID adminId, UUID requestId, String profileId, long expectedRevision, String reason) {
    var changed =
        store.selectModelPolicy(
            adminId,
            requestId,
            profileId,
            expectedRevision,
            reason,
            id -> {
              var profile = profiles.get(id);
              return isAvailable(profile) ? profile.modelId() : null;
            });
    return new PolicyChange(
        effectivePolicy(changed.policy()), changed.auditEntryId(), changed.replayed());
  }

  public RubricModelPolicy rubricPolicy() {
    return rubricPolicy(store.rubricModelPolicy());
  }

  public RubricModelPolicy adminRubricPolicy(UUID adminId) {
    return rubricPolicy(store.adminRubricModelPolicy(adminId));
  }

  public RubricPolicyChange selectRubricPolicy(
      UUID adminId, UUID requestId, String profileId, long expectedRevision, String reason) {
    var changed =
        store.selectRubricModelPolicy(
            adminId,
            requestId,
            profileId,
            expectedRevision,
            reason,
            id -> {
              var profile = profiles.get(id);
              return isAvailable(profile) ? profile.modelId() : null;
            });
    return new RubricPolicyChange(
        rubricPolicy(changed.policy()), changed.auditEntryId(), changed.replayed());
  }

  static ObjectNode policyJson(ModelPolicy policy) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("revision", policy.revision());
    result.put("profileId", policy.profileId());
    result.put("modelId", policy.modelId());
    result.put("fallbackProfileId", policy.fallbackProfileId());
    result.put("fallbackModelId", policy.fallbackModelId());
    return result;
  }

  static ObjectNode rubricPolicyJson(RubricModelPolicy policy) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("revision", policy.revision());
    result.put("profileId", policy.profileId());
    result.put("modelId", policy.modelId());
    return result;
  }

  private static RubricModelPolicy rubricPolicy(GatewayStore.ModelPolicy stored) {
    return new RubricModelPolicy(stored.revision(), stored.profileId(), stored.modelId());
  }

  private ModelPolicy effectivePolicy(GatewayStore.ModelPolicy stored) {
    if (stored.revision() == 0) return new ModelPolicy(0, null, null, null, null);
    ProviderProfile primary = profiles.get(stored.profileId());
    String fallbackId = primary == null ? null : frozenFallbackByPrimary.get(stored.profileId());
    ProviderProfile fallback = fallbackId == null ? null : profiles.get(fallbackId);
    return new ModelPolicy(
        stored.revision(),
        stored.profileId(),
        stored.modelId(),
        fallback == null ? null : fallback.id(),
        fallback == null ? null : fallback.modelId());
  }

  private boolean isAvailable(ProviderProfile profile) {
    return profile != null && profile.isVerified() && available.test(profile);
  }

  public Reply grade(UUID userId, JsonNode body) {
    return grade(userId, body, null);
  }

  /** Resolves the ordinary-user protocol aliases without accepting real provider identifiers. */
  public Reply gradePublic(UUID userId, JsonNode body, String sessionToken) {
    if (body == null || !body.isObject())
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    UUID requestId = GatewayJson.uuid(body, "requestId");
    boolean fallback = body.has("fallbackOfRequestId");
    String publicProfileId = GatewayJson.string(body, "profileId", 1, 100);
    var revisionNode = body.get("policyRevision");
    if (revisionNode == null
        || !revisionNode.isIntegralNumber()
        || !revisionNode.canConvertToLong()
        || revisionNode.longValue() < 1)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    long suppliedPublicRevision = revisionNode.longValue();

    String expectedAlias = fallback ? PUBLIC_FALLBACK_PROFILE_ID : PUBLIC_PRIMARY_PROFILE_ID;
    if (!expectedAlias.equals(publicProfileId))
      throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);

    String internalProfileId;
    long internalRevision;
    var existing = store.findRequest(userId, requestId);
    if (existing != null) {
      if (existing.policyRevision() == null)
        throw new GatewayException(GatewayException.Code.IDEMPOTENCY_CONFLICT);
      internalProfileId = existing.profileId();
      internalRevision = existing.policyRevision();
    } else {
      var policy = policy();
      internalProfileId = fallback ? policy.fallbackProfileId() : policy.profileId();
      internalRevision = policy.revision();
      if (internalProfileId == null || internalRevision == 0)
        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
    }
    if (suppliedPublicRevision != publicPolicyRevision(internalRevision))
      throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);

    var translated = ((ObjectNode) body).deepCopy();
    translated.put("profileId", internalProfileId);
    translated.put("policyRevision", internalRevision);
    return grade(userId, translated, sessionToken);
  }

  public Reply grade(UUID userId, JsonNode body, String sessionToken) {
    boolean fallback = body != null && body.has("fallbackOfRequestId");
    Long revision = null;
    if (body != null && body.has("policyRevision")) {
      if (fallback)
        GatewayJson.fields(
            body,
            "requestId",
            "profileId",
            "policyRevision",
            "fallbackOfRequestId",
            "rubric",
            "imageBase64");
      else
        GatewayJson.fields(
            body, "requestId", "profileId", "policyRevision", "rubric", "imageBase64");
      var value = body.get("policyRevision");
      if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
        throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
      revision = value.longValue();
    } else {
      // Compatibility permits the old body only when replaying a version 1 receipt.
      GatewayJson.fields(body, "requestId", "profileId", "rubric", "imageBase64");
    }
    UUID requestId = GatewayJson.uuid(body, "requestId");
    UUID fallbackOf = fallback ? GatewayJson.uuid(body, "fallbackOfRequestId") : null;
    if (fallback && !requestId.equals(FallbackGradingProvider.fallbackRequestId(fallbackOf)))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    String profileId = GatewayJson.string(body, "profileId", 1, 100);
    Rubric rubric;
    try {
      rubric = new RubricJsonCodec().read(body.get("rubric").toString());
      rubric.validateReady();
      validateRubricCost(rubric);
    } catch (RuntimeException invalid) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
    byte[] image;
    try {
      image =
          Base64.getDecoder()
              .decode(GatewayJson.string(body, "imageBase64", 1, MAX_IMAGE_BASE64_CHARS));
      validatePng(image);
    } catch (RuntimeException invalid) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
    String canonicalRubric = new RubricJsonCodec().write(rubric);
    String fingerprint =
        fingerprint(
            revision == null ? profileId : revision + "\0" + profileId, canonicalRubric, image);
    var existing = store.findRequest(userId, requestId);
    if (existing != null) {
      Arrays.fill(image, (byte) 0);
      if (!existing.fingerprint().equals(fingerprint)
          || !java.util.Objects.equals(existing.policyRevision(), revision))
        throw new GatewayException(GatewayException.Code.IDEMPOTENCY_CONFLICT);
      return reply(existing, true);
    }
    if (revision == null) {
      Arrays.fill(image, (byte) 0);
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
    var policy = policy();
    if (policy.revision() != revision) {
      Arrays.fill(image, (byte) 0);
      throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
    }
    GatewayStore.Request primary = null;
    if (fallback) {
      if (policy.fallbackProfileId() == null) {
        Arrays.fill(image, (byte) 0);
        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
      }
      if (!profileId.equals(policy.fallbackProfileId())) {
        Arrays.fill(image, (byte) 0);
        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
      }
      primary = store.findRequest(userId, fallbackOf);
      String primaryFingerprint =
          fingerprint(revision + "\0" + policy.profileId(), canonicalRubric, image);
      if (!eligiblePrimary(primary, policy, primaryFingerprint)) {
        Arrays.fill(image, (byte) 0);
        throw new GatewayException(GatewayException.Code.FALLBACK_NOT_ELIGIBLE);
      }
    } else if (!profileId.equals(policy.profileId())) {
      Arrays.fill(image, (byte) 0);
      throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
    }
    var profile = profiles.get(profileId);
    if (!isAvailable(profile)) {
      Arrays.fill(image, (byte) 0);
      throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
    }
    GatewayStore.Prepared prepared;
    try {
      prepared =
          fallback
              ? store.prepareFallback(
                  userId,
                  requestId,
                  fingerprint,
                  profile.modelId(),
                  profileId,
                  policy.revision(),
                  policy.profileId(),
                  policy.modelId())
              : store.prepare(
                  userId, requestId, fingerprint, profile.modelId(), profileId, revision);
    } catch (GatewayException rejected) {
      Arrays.fill(image, (byte) 0);
      if (rejected.code() == GatewayException.Code.INSUFFICIENT_QUOTA)
        return insufficient(userId, requestId);
      throw rejected;
    } catch (RuntimeException rejected) {
      Arrays.fill(image, (byte) 0);
      throw rejected;
    }
    if (!prepared.created()) {
      Arrays.fill(image, (byte) 0);
      return reply(prepared.request(), true);
    }
    var dispatchRejection =
        new java.util.concurrent.atomic.AtomicReference<GatewayException.Code>();
    var dispatchCommitted = new java.util.concurrent.atomic.AtomicBoolean();
    var retryAudit = new GatewayConnectRetryAudit(requestId);
    java.util.concurrent.CompletableFuture<GradingResponse> pending = null;
    try {
      pending =
          engine
              .grade(
                  profile,
                  rubric,
                  new GradingRequest(requestId, rubric.question(), image),
                  () -> {
                    try {
                      if (!isAvailable(profile))
                        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
                      if (!policy.equals(policy()))
                        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
                      if (fallback)
                        store.beforeFallbackDispatch(
                            userId,
                            requestId,
                            sessionToken,
                            policy.profileId(),
                            policy.modelId(),
                            policy.revision());
                      else store.beforeDispatch(userId, requestId, sessionToken);
                      dispatchCommitted.set(true);
                      retryAudit.initialDispatchAuthorized();
                    } catch (GatewayException rejected) {
                      dispatchRejection.set(rejected.code());
                      throw rejected;
                    }
                  },
                  callTrace(
                      userId,
                      requestId,
                      "GRADING",
                      gradingContext(rubric),
                      dispatchRejection,
                      retryAudit,
                      attemptNumber -> {
                        if (!isAvailable(profile))
                          throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
                        if (!policy.equals(policy()))
                          throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
                        store.authorizeConnectRetry(
                            userId,
                            requestId,
                            sessionToken,
                            profile.id(),
                            profile.modelId(),
                            policy.profileId(),
                            policy.modelId(),
                            policy.revision());
                      }))
              .toCompletableFuture();
      var response = awaitGrading(pending, profile, retryAudit);
      if (!store.request(userId, requestId).charged())
        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
      if (response == null || !response.requestId().equals(requestId))
        throw new InvalidModelResult();
      // Validate the same strict contract and criterion allowlist at the gateway boundary too.
      var result = GatewayJson.MAPPER.valueToTree(response.result());
      try {
        new GradeResultJsonCodec().read(result.toString(), rubric.question());
      } catch (RuntimeException invalid) {
        throw new InvalidModelResult();
      }
      if (!policy.equals(policy()))
        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
      ObjectNode output = GatewayJson.MAPPER.createObjectNode();
      output.put("requestId", requestId.toString());
      output.put("source", response.source().name());
      output.set("result", result);
      output.put("completedAt", response.completedAt().toString());
      if (response.elapsed() == null) output.putNull("elapsedMs");
      else output.put("elapsedMs", response.elapsed().toMillis());
      if (response.usage() == null) output.putNull("usage");
      else output.set("usage", GatewayJson.MAPPER.valueToTree(response.usage()));
      var completed =
          fallback
              ? store.finishFallback(
                  userId,
                  requestId,
                  output.toString(),
                  null,
                  false,
                  policy.profileId(),
                  policy.modelId(),
                  policy.revision())
              : store.finish(userId, requestId, output.toString(), null, false);
      retryAudit.completed(completed.state());
      return reply(completed, false);
    } catch (Exception | LinkageError failure) {
      if (pending != null && !pending.isDone()) pending.cancel(true);
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      GatewayStore.Request request;
      try {
        request = store.request(userId, requestId);
      } catch (RuntimeException unavailable) {
        return unavailable(requestId, dispatchCommitted.get());
      }
      String error = request.charged() ? "MODEL_FAILED" : "MODEL_UNAVAILABLE";
      Throwable cause = failure;
      while ((cause instanceof java.util.concurrent.ExecutionException
              || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (dispatchRejection.get() == GatewayException.Code.INSUFFICIENT_QUOTA
          || (cause instanceof GatewayException gateway
              && gateway.code() == GatewayException.Code.INSUFFICIENT_QUOTA))
        error = "INSUFFICIENT_CREDITS";
      if (dispatchRejection.get() == GatewayException.Code.STORAGE_FAILURE)
        error = "STORE_UNAVAILABLE";
      if (dispatchRejection.get() == GatewayException.Code.UNAUTHENTICATED) error = "AUTH_REQUIRED";
      if (dispatchRejection.get() == GatewayException.Code.MODEL_POLICY_CHANGED)
        error = "MODEL_POLICY_CHANGED";
      if (cause instanceof GatewayException gateway
          && gateway.code() == GatewayException.Code.MODEL_POLICY_CHANGED)
        error = "MODEL_POLICY_CHANGED";
      if (request.charged() && isInvalidModelResult(cause)) error = "MODEL_RESULT_INVALID";
      // Supplier network/timeout failures after dispatch cannot prove whether it received the
      // request.
      boolean unknown =
          request.charged() && dispatchRejection.get() == null && isUncertainDispatchFailure(cause);
      if (unknown) error = "DISPATCH_UNKNOWN";
      try {
        store.recordModelFailure(
            userId,
            requestId,
            "GRADING",
            modelFailure(cause, dispatchRejection.get(), request.charged()));
        var completed =
            fallback
                ? store.finishFallback(
                    userId,
                    requestId,
                    null,
                    error,
                    unknown,
                    policy.profileId(),
                    policy.modelId(),
                    policy.revision())
                : store.finish(userId, requestId, null, error, unknown);
        retryAudit.completed(completed.state());
        return reply(completed, false);
      } catch (RuntimeException unavailable) {
        return unavailable(requestId, dispatchCommitted.get());
      }
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
      Arrays.fill(image, (byte) 0);
      retryAudit.finish();
    }
  }

  private static GradingResponse awaitGrading(
      java.util.concurrent.CompletableFuture<GradingResponse> pending,
      ProviderProfile profile,
      GatewayConnectRetryAudit audit)
      throws Exception {
    long started = System.nanoTime();
    long initialMillis = profile.requestTimeout().toMillis() + 5000;
    try {
      return pending.get(initialMillis, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException timeout) {
      // Ordinary suppliers still keep the original bound. Only a confirmed scheduled connect
      // retry extends this owner to the provider's bounded, shared transport budget.
      if (!audit.hasScheduledRetry()) throw timeout;
      long totalNanos =
          OpenAICompatibleVisionProvider.gradeTransportBudget(profile).plusSeconds(5).toNanos();
      long remaining = totalNanos - (System.nanoTime() - started);
      if (remaining <= 0) throw timeout;
      return pending.get(remaining, TimeUnit.NANOSECONDS);
    }
  }

  public Reply generateRubric(UUID userId, JsonNode body, String sessionToken) {
    GatewayJson.fields(body, "requestId", "subject", "referenceAnswer", "maxScore");
    UUID requestId = GatewayJson.uuid(body, "requestId");
    String subject = generationText(body, "subject", 100, true);
    String referenceAnswer = generationText(body, "referenceAnswer", 64_000, false);
    BigDecimal maxScore = generationMaximum(body.get("maxScore"));
    String fingerprint = rubricGenerationFingerprint(subject, referenceAnswer, maxScore);
    var existing = store.findRubricGenerationRequest(userId, requestId);
    if (existing != null) {
      if (!existing.fingerprint().equals(fingerprint))
        throw new GatewayException(GatewayException.Code.IDEMPOTENCY_CONFLICT);
      return reply(existing, true, "RUBRIC_GENERATION");
    }

    var policy = rubricPolicy();
    if (policy.revision() < 1 || policy.profileId() == null)
      throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
    var profile = profiles.get(policy.profileId());
    if (!isAvailable(profile) || !profile.modelId().equals(policy.modelId()))
      throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);

    GatewayStore.Prepared prepared;
    try {
      prepared =
          store.prepareRubricGeneration(
              userId, requestId, fingerprint, profile.modelId(), profile.id(), policy.revision());
    } catch (GatewayException rejected) {
      if (rejected.code() == GatewayException.Code.INSUFFICIENT_QUOTA)
        return insufficient(userId, requestId);
      throw rejected;
    }
    if (!prepared.created()) return reply(prepared.request(), true, "RUBRIC_GENERATION");

    var dispatchRejection =
        new java.util.concurrent.atomic.AtomicReference<GatewayException.Code>();
    var dispatchCommitted = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.CompletableFuture<GeneratedRubric> pending = null;
    try {
      pending =
          rubricEngine
              .generate(
                  profile,
                  subject,
                  referenceAnswer,
                  maxScore,
                  () -> {
                    try {
                      if (!isAvailable(profile))
                        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
                      if (!policy.equals(rubricPolicy()))
                        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
                      store.beforeRubricGenerationDispatch(userId, requestId, sessionToken);
                      dispatchCommitted.set(true);
                    } catch (GatewayException rejected) {
                      dispatchRejection.set(rejected.code());
                      throw rejected;
                    }
                  },
                  callTrace(
                      userId,
                      requestId,
                      "RUBRIC_GENERATION",
                      generationContext(subject, referenceAnswer, maxScore),
                      dispatchRejection))
              .toCompletableFuture();
      GeneratedRubric generated =
          pending.get(profile.requestTimeout().toMillis() + 5000, TimeUnit.MILLISECONDS);
      if (!store.rubricGenerationRequest(userId, requestId).charged())
        throw new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE);
      GeneratedRubric validated = validateGeneratedRubric(generated, maxScore);
      if (!policy.equals(rubricPolicy()))
        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);

      ObjectNode output = GatewayJson.MAPPER.createObjectNode();
      output.put("requestId", requestId.toString());
      output.put("scoreStep", validated.scoreStep());
      var criteria = output.putArray("criteria");
      for (var criterion : validated.criteria()) {
        var item = criteria.addObject();
        item.put("id", criterion.id());
        item.put("rule", criterion.description());
        var allowed = item.putArray("allowedScores");
        criterion.allowedScores().forEach(allowed::add);
      }
      output.put("completedAt", Instant.ofEpochMilli(clock.millis()).toString());
      if (validated.usage() == null) output.putNull("usage");
      else output.set("usage", GatewayJson.MAPPER.valueToTree(validated.usage()));
      return reply(
          store.finishRubricGeneration(userId, requestId, output.toString(), null, false),
          false,
          "RUBRIC_GENERATION");
    } catch (Exception | LinkageError failure) {
      if (pending != null && !pending.isDone()) pending.cancel(true);
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      GatewayStore.Request request;
      try {
        request = store.rubricGenerationRequest(userId, requestId);
      } catch (RuntimeException unavailable) {
        return unavailable(requestId, dispatchCommitted.get());
      }
      String error = request.charged() ? "MODEL_FAILED" : "MODEL_UNAVAILABLE";
      Throwable cause = failure;
      while ((cause instanceof java.util.concurrent.ExecutionException
              || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (dispatchRejection.get() == GatewayException.Code.INSUFFICIENT_QUOTA
          || (cause instanceof GatewayException gateway
              && gateway.code() == GatewayException.Code.INSUFFICIENT_QUOTA))
        error = "INSUFFICIENT_CREDITS";
      if (dispatchRejection.get() == GatewayException.Code.STORAGE_FAILURE)
        error = "STORE_UNAVAILABLE";
      if (dispatchRejection.get() == GatewayException.Code.UNAUTHENTICATED) error = "AUTH_REQUIRED";
      if (dispatchRejection.get() == GatewayException.Code.MODEL_POLICY_CHANGED
          || (cause instanceof GatewayException gateway
              && gateway.code() == GatewayException.Code.MODEL_POLICY_CHANGED))
        error = "MODEL_POLICY_CHANGED";
      if (request.charged() && isInvalidModelResult(cause)) error = "MODEL_RESULT_INVALID";
      boolean unknown =
          request.charged()
              && dispatchRejection.get() != GatewayException.Code.STORAGE_FAILURE
              && isUncertainDispatchFailure(cause);
      if (unknown) error = "DISPATCH_UNKNOWN";
      try {
        store.recordModelFailure(
            userId,
            requestId,
            "RUBRIC_GENERATION",
            modelFailure(cause, dispatchRejection.get(), request.charged()));
        return reply(
            store.finishRubricGeneration(userId, requestId, null, error, unknown),
            false,
            "RUBRIC_GENERATION");
      } catch (RuntimeException unavailable) {
        return unavailable(requestId, dispatchCommitted.get());
      }
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  private static GeneratedRubric validateGeneratedRubric(
      GeneratedRubric generated, BigDecimal expectedMaximum) {
    if (generated == null || generated.criteria() == null) throw new InvalidModelResult();
    BigDecimal scoreStep;
    try {
      scoreStep = normalizedScore(generated.scoreStep(), true);
    } catch (RuntimeException invalid) {
      throw new InvalidModelResult();
    }
    if (generated.criteria().isEmpty() || generated.criteria().size() > MAX_CRITERIA)
      throw new InvalidModelResult();
    var ids = new java.util.HashSet<String>();
    var criteria = new java.util.ArrayList<ScoringCriterion>();
    BigDecimal maxima = BigDecimal.ZERO;
    try {
      for (var criterion : generated.criteria()) {
        if (criterion == null
            || criterion.id() == null
            || !criterion.id().matches("[A-Za-z][A-Za-z0-9_-]{0,99}")
            || !ids.add(criterion.id())
            || criterion.description() == null) throw new IllegalArgumentException();
        String rule = criterion.description().trim();
        if (rule.isEmpty() || rule.length() > MAX_CRITERION_RULE_CHARS || rule.indexOf('\0') >= 0)
          throw new IllegalArgumentException();
        var values = criterion.allowedScores();
        if (values == null || values.size() < 2 || values.size() > 101)
          throw new IllegalArgumentException();
        var normalized = new java.util.ArrayList<BigDecimal>();
        BigDecimal previous = null;
        for (var value : values) {
          BigDecimal score = normalizedScore(value, false);
          if (previous != null && score.compareTo(previous) <= 0)
            throw new IllegalArgumentException();
          if (score.remainder(scoreStep).signum() != 0) throw new IllegalArgumentException();
          normalized.add(score);
          previous = score;
        }
        if (normalized.getFirst().signum() != 0) throw new IllegalArgumentException();
        maxima = maxima.add(normalized.getLast());
        criteria.add(new ScoringCriterion(criterion.id(), rule, normalized));
      }
    } catch (RuntimeException invalid) {
      throw new InvalidModelResult();
    }
    if (maxima.compareTo(expectedMaximum) != 0) throw new InvalidModelResult();
    return new GeneratedRubric(scoreStep, criteria, generated.usage(), generated.elapsed());
  }

  private static BigDecimal normalizedScore(BigDecimal value, boolean positive) {
    if (value == null) throw new IllegalArgumentException();
    BigDecimal normalized = value.stripTrailingZeros();
    if (normalized.signum() < 0
        || positive && normalized.signum() == 0
        || normalized.scale() > 8
        || normalized.precision() - normalized.scale() > 3) throw new IllegalArgumentException();
    return normalized;
  }

  private static String generationText(
      JsonNode body, String field, int maximumCodePoints, boolean singleLine) {
    String value = GatewayJson.string(body, field, 1, maximumCodePoints * 2).trim();
    int length = value.codePointCount(0, value.length());
    if (length < 1
        || length > maximumCodePoints
        || value.indexOf('\0') >= 0
        || singleLine && value.codePoints().anyMatch(Character::isISOControl))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return value;
  }

  private static BigDecimal generationMaximum(JsonNode node) {
    if (node == null || !node.isNumber())
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    BigDecimal value = node.decimalValue().stripTrailingZeros();
    if (value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0 || value.scale() > 8)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return value;
  }

  private static String rubricGenerationFingerprint(
      String subject, String referenceAnswer, BigDecimal maxScore) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update("rubric-generation-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(referenceAnswer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return HexFormat.of()
          .formatHex(
              digest.digest(
                  maxScore.toPlainString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new GatewayException(GatewayException.Code.INTERNAL_ERROR);
    }
  }

  private static boolean eligiblePrimary(
      GatewayStore.Request primary, ModelPolicy policy, String expectedFingerprint) {
    if (primary == null
        || !primary.charged()
        || !expectedFingerprint.equals(primary.fingerprint())
        || !policy.profileId().equals(primary.profileId())
        || !policy.modelId().equals(primary.modelId())
        || !java.util.Objects.equals(policy.revision(), primary.policyRevision())) return false;
    if (primary.state().equals("FAILED")) return "MODEL_RESULT_INVALID".equals(primary.errorCode());
    if (!primary.state().equals("SUCCEEDED") || primary.responseJson() == null) return false;
    try {
      JsonNode response =
          GatewayJson.parse(
              primary.responseJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "UNABLE".equals(response.path("result").path("status").asText());
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static boolean isInvalidModelResult(Throwable failure) {
    if (failure instanceof InvalidModelResult) return true;
    return failure instanceof ProviderException provider
        && List.of(
                ProviderException.Failure.EMPTY_RESPONSE,
                ProviderException.Failure.TRUNCATED,
                ProviderException.Failure.INVALID_JSON,
                ProviderException.Failure.INVALID_RESULT)
            .contains(provider.failure());
  }

  private static GatewayModelFailure modelFailure(
      Throwable cause, GatewayException.Code rejection, boolean dispatched) {
    if (rejection != null)
      return GatewayModelFailure.local(new GatewayException(rejection), dispatched, false);
    if (cause instanceof ProviderException provider)
      return GatewayModelFailure.provider(provider, dispatched);
    return GatewayModelFailure.local(cause, dispatched, isInvalidModelResult(cause));
  }

  private static boolean isUncertainDispatchFailure(Throwable cause) {
    if (cause instanceof ProviderException provider)
      return List.of(
              ProviderException.Failure.NETWORK,
              ProviderException.Failure.TIMEOUT,
              ProviderException.Failure.CANCELLED,
              ProviderException.Failure.INTERNAL_ERROR)
          .contains(provider.failure());
    // Unexpected local failures cannot establish that an already dispatched request was rejected.
    return !(cause instanceof GatewayException || cause instanceof InvalidModelResult);
  }

  private static final class InvalidModelResult extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  public ObjectNode status(UUID userId, UUID requestId) {
    var request = store.request(userId, requestId);
    var out = GatewayJson.MAPPER.createObjectNode();
    out.put("requestId", requestId.toString());
    out.put("state", request.state().equals("UNKNOWN") ? "DISPATCH_UNKNOWN" : request.state());
    out.put("creditsRemaining", store.account(userId).balance());
    out.put("chargedCredits", request.charged() ? 1 : 0);
    out.put("replayed", true);
    if (List.of("FAILED", "UNKNOWN", "NOT_DISPATCHED").contains(request.state())) {
      var failure = store.modelFailure(userId, requestId, "GRADING");
      if (failure != null) out.put("diagnosticCode", failure.diagnosticCode());
    }
    return out;
  }

  private Reply reply(GatewayStore.Request request, boolean replayed) {
    return reply(request, replayed, "GRADING");
  }

  private Reply reply(GatewayStore.Request request, boolean replayed, String kind) {
    ObjectNode output;
    int status;
    if (request.state().equals("SUCCEEDED")) {
      output =
          (ObjectNode)
              GatewayJson.parse(
                  request.responseJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      status = 200;
    } else {
      String code =
          switch (request.state()) {
            case "PREPARED", "DISPATCHING" -> "REQUEST_IN_PROGRESS";
            case "UNKNOWN" -> "DISPATCH_UNKNOWN";
            default -> request.errorCode() == null ? "MODEL_FAILED" : request.errorCode();
          };
      if (code.equals("RESTART_BEFORE_DISPATCH")) code = "MODEL_UNAVAILABLE";
      if (code.equals("DISPATCH_OUTCOME_UNKNOWN")) code = "DISPATCH_UNKNOWN";
      output = GatewayServer.error(code);
      output.put("requestId", request.requestId().toString());
      if (List.of("FAILED", "UNKNOWN", "NOT_DISPATCHED").contains(request.state())) {
        var failure = store.modelFailure(request.userId(), request.requestId(), kind);
        if (failure != null)
          ((ObjectNode) output.path("error")).put("diagnosticCode", failure.diagnosticCode());
      }
      status =
          switch (code) {
            case "AUTH_REQUIRED" -> 401;
            case "INSUFFICIENT_CREDITS" -> 402;
            case "REQUEST_IN_PROGRESS", "DISPATCH_UNKNOWN", "MODEL_POLICY_CHANGED" -> 409;
            case "MODEL_UNAVAILABLE", "STORE_UNAVAILABLE" -> 503;
            default -> 502;
          };
    }
    output.put("creditsRemaining", store.account(request.userId()).balance());
    output.put("chargedCredits", request.charged() ? 1 : 0);
    output.put("replayed", replayed);
    return new Reply(status, output);
  }

  private Reply insufficient(UUID userId, UUID requestId) {
    ObjectNode output = GatewayServer.error("INSUFFICIENT_CREDITS");
    output.put("requestId", requestId.toString());
    output.put("creditsRemaining", store.account(userId).balance());
    output.put("chargedCredits", 0);
    output.put("replayed", false);
    return new Reply(402, output);
  }

  private static boolean hasSecret(SecretStore store, String ref) {
    if (store == null) return false;
    try {
      var optional = store.read(ref);
      if (optional.isEmpty()) return false;
      char[] secret = optional.get();
      try {
        return secret.length > 0;
      } finally {
        Arrays.fill(secret, '\0');
      }
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static Reply unavailable(UUID requestId, boolean dispatched) {
    ObjectNode output = GatewayServer.error("STORE_UNAVAILABLE");
    output.put("requestId", requestId.toString());
    if (dispatched) output.put("chargedCredits", 1);
    else output.putNull("chargedCredits");
    output.put("replayed", false);
    return new Reply(503, output);
  }

  private static String fingerprint(String profileId, String rubric, byte[] image) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(profileId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(rubric.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return HexFormat.of().formatHex(digest.digest(image));
    } catch (Exception impossible) {
      throw new GatewayException(GatewayException.Code.INTERNAL_ERROR);
    }
  }

  private static void validateRubricCost(Rubric rubric) {
    var question = rubric.question();
    if (question.criteria().size() > MAX_CRITERIA) throw new IllegalArgumentException();
    long characters =
        (long) question.prompt().length()
            + question.standardAnswer().length()
            + rubric.rawRubric().length();
    int allowedScores = 0;
    for (var criterion : question.criteria()) {
      if (criterion.description().length() > MAX_CRITERION_RULE_CHARS)
        throw new IllegalArgumentException();
      characters += criterion.id().length() + criterion.description().length();
      allowedScores = Math.addExact(allowedScores, criterion.allowedScores().size());
    }
    if (allowedScores > MAX_ALLOWED_SCORES || characters > MAX_TOTAL_TEXT_CHARS)
      throw new IllegalArgumentException();
    byte[] canonical =
        new RubricJsonCodec().write(rubric).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try {
      if (canonical.length > MAX_RUBRIC_UTF8_BYTES) throw new IllegalArgumentException();
    } finally {
      Arrays.fill(canonical, (byte) 0);
    }
  }

  private static void validatePng(byte[] bytes) {
    if (bytes.length < 40
        || bytes.length > MAX_IMAGE_BYTES
        || !Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      throw new IllegalArgumentException();
    try (var input =
        new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReadersByFormatName("png");
      if (!readers.hasNext()) throw new IllegalArgumentException();
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS)
          throw new IllegalArgumentException();
        var decoded = reader.read(0);
        if (decoded == null) throw new IllegalArgumentException();
        decoded.flush();
      } finally {
        reader.dispose();
      }
    } catch (Exception invalid) {
      throw new IllegalArgumentException();
    }
  }
}
