package app.deskgrade.adapters.commercial;

import static app.deskgrade.adapters.commercial.CommercialJson.*;

import app.deskgrade.adapters.model.GradeResultJsonCodec;
import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Account-scoped gateway transport. Tokens stay private and are cached only via a secret store. */
public final class CommercialClient implements AutoCloseable {
  private final Object lock = new Object();
  private final URI base;
  private final Clock clock;
  private final Duration timeout;
  private final HttpClient http;
  private final ScheduledExecutorService watcher;
  private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();
  private long generation;
  private Session session;
  private boolean closed;

  public CommercialClient(URI baseUrl, Clock clock) {
    this(baseUrl, clock, Duration.ofSeconds(10), Duration.ofSeconds(45));
  }

  CommercialClient(URI baseUrl, Clock clock, Duration connectTimeout, Duration requestTimeout) {
    this.base = validateBase(baseUrl);
    this.clock = Objects.requireNonNull(clock);
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout == null
        || requestTimeout.isNegative()
        || requestTimeout.isZero()) throw new IllegalArgumentException("Invalid gateway timeout");
    timeout = requestTimeout;
    http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    watcher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "gateway-request-guard");
              thread.setDaemon(true);
              return thread;
            });
    watcher.scheduleWithFixedDelay(this::checkPending, 25, 25, TimeUnit.MILLISECONDS);
  }

  public CompletionStage<SessionView> register(String username, char[] password) {
    return authenticate("register", username, password);
  }

  public CompletionStage<SessionView> login(String username, char[] password) {
    return authenticate("login", username, password);
  }

  /** Call on a worker. No token is returned to UI code or exposed through SessionView. */
  public void saveSession(CommercialSessionCache cache) {
    synchronized (lock) {
      Session bound = requireSession();
      cache.save(base, bound.token, bound.view);
    }
  }

  /** Cached metadata never grants a session; a fresh server account response is mandatory. */
  public CompletionStage<Optional<SessionView>> restoreSession(CommercialSessionCache cache) {
    synchronized (lock) {
      if (closed) return failed(GatewayException.Code.CLIENT_CLOSED);
      revoke();
      var saved = cache.load(base, clock.instant());
      if (saved.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
      var cached = saved.orElseThrow();
      long attempt = generation;
      return exchange(
              "GET",
              "/v1/account",
              null,
              null,
              new String(cached.token),
              attempt,
              CancellationToken.none(),
              timeout,
              null,
              response -> {
                SessionView verified = account(response, cached.account.expiresAt());
                if (!verified.accountId().equals(cached.account.accountId())) throw invalid();
                session = new Session(cached.token.clone(), verified, attempt);
                return Optional.of(verified);
              })
          .whenComplete((ignored, failure) -> cached.close());
    }
  }

  private CompletionStage<SessionView> authenticate(
      String operation, String username, char[] password) {
    if (username == null
        || !username.matches("[A-Za-z0-9_.-]{3,32}")
        || password == null
        || password.length < 12
        || password.length > 128) return failed(GatewayException.Code.INVALID_REQUEST);
    // Serialize before returning: the caller may immediately erase its array.
    char[] copied = password.clone();
    byte[] body;
    try {
      ObjectNode json = MAPPER.createObjectNode();
      json.put("username", username);
      json.put("password", new String(copied));
      body = bytes(json);
    } finally {
      Arrays.fill(copied, '\0');
    }
    synchronized (lock) {
      if (closed) return failed(GatewayException.Code.CLIENT_CLOSED);
      revoke();
      long attempt = generation;
      return exchange(
          "POST",
          "/v1/auth/" + operation,
          body,
          null,
          null,
          attempt,
          CancellationToken.none(),
          timeout,
          null,
          response -> {
            fields(response, Set.of("token", "expiresAt", "account"));
            String token = text(response.get("token"), 16, 4096);
            if (!token.matches("[A-Za-z0-9._~-]+")) throw invalid();
            Instant expiry = instant(response.get("expiresAt"));
            if (!expiry.isAfter(clock.instant())) throw invalid();
            SessionView view = account(response.get("account"), expiry);
            session = new Session(token.toCharArray(), view, attempt);
            return view;
          });
    }
  }

  public Optional<SessionView> currentSession() {
    synchronized (lock) {
      expire();
      return Optional.ofNullable(session == null ? null : session.view);
    }
  }

  public boolean isAuthenticated() {
    return currentSession().isPresent();
  }

  public long sessionGeneration() {
    synchronized (lock) {
      expire();
      return generation;
    }
  }

  /**
   * Internal record transport, bound to the session that created the journal. Never retries writes.
   */
  CompletionStage<JsonNode> journalCommand(JsonNode command, long expectedGeneration) {
    synchronized (lock) {
      Session bound = requireSession();
      if (bound.generation != expectedGeneration)
        return failed(GatewayException.Code.SESSION_CHANGED);
      byte[] encoded = bytes(command);
      if (encoded.length > app.deskgrade.adapters.persistence.JournalJson.MAX_BYTES)
        return failed(GatewayException.Code.INVALID_REQUEST);
      return exchange(
          "POST",
          "/v1/journal",
          encoded,
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          null,
          "recordJson".equals(command.path("operation").asText())
              ? MAX_RECORD_RESPONSE
              : MAX_RESPONSE,
          response -> {
            fields(response, Set.of("result"));
            return response.get("result");
          });
    }
  }

  app.deskgrade.core.application.session.GradingRecords.Owner journalOwner(
      long expectedGeneration) {
    synchronized (lock) {
      Session bound = requireSession();
      if (bound.generation != expectedGeneration)
        throw new GatewayException(GatewayException.Code.SESSION_CHANGED);
      return new app.deskgrade.core.application.session.GradingRecords.Owner(
          base.toString(), bound.view.accountId());
    }
  }

  public boolean isCurrentSession(long expected) {
    synchronized (lock) {
      expire();
      return !closed && session != null && generation == expected;
    }
  }

  public CompletionStage<SessionView> me() {
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "GET",
          "/v1/account",
          null,
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          null,
          json -> {
            SessionView view = account(json, bound.view.expiresAt());
            if (!view.accountId().equals(bound.view.accountId())) throw invalid();
            bound.view = view;
            return view;
          });
    }
  }

  public CompletionStage<List<PublicModelProfile>> models() {
    synchronized (lock) {
      Session bound = requireSession();
      return fetchPolicy(bound, CancellationToken.none())
          .thenCompose(
              before ->
                  exchange(
                          "GET",
                          "/v1/models",
                          null,
                          bound,
                          null,
                          bound.generation,
                          CancellationToken.none(),
                          timeout,
                          null,
                          json -> {
                            List<PublicModelProfile> profiles =
                                new PublicModelProfileJsonCodec().read(json);
                            if (profiles.size() > 2
                                || profiles.stream().anyMatch(p -> !p.isVerified()))
                              throw invalid();
                            return List.copyOf(profiles);
                          })
                      .thenCompose(
                          profiles ->
                              fetchPolicy(bound, CancellationToken.none())
                                  .thenApply(
                                      after -> {
                                        if (!before.equals(after))
                                          throw new GatewayException(
                                              GatewayException.Code.MODEL_POLICY_CHANGED);
                                        if (!catalogMatches(after, profiles)) throw invalid();
                                        bound.profiles = profiles;
                                        bound.catalogPolicy = after;
                                        return after.revision() == 0
                                            ? List.of()
                                            : List.of(profiles.getFirst());
                                      })));
    }
  }

  /** Only the coherently loaded administrator assignment; there is no user selection policy. */
  public Optional<ModelPolicy> currentModelPolicy() {
    synchronized (lock) {
      expire();
      if (session == null
          || session.catalogPolicy == null
          || !session.catalogPolicy.equals(session.observedPolicy)) return Optional.empty();
      return Optional.of(session.catalogPolicy);
    }
  }

  private CompletionStage<ModelPolicy> fetchPolicy(Session bound, CancellationToken cancellation) {
    synchronized (lock) {
      return exchange(
          "GET",
          "/v1/model-policy",
          null,
          bound,
          null,
          bound.generation,
          cancellation,
          timeout,
          null,
          json -> {
            allowedFields(
                json,
                Set.of("revision", "profileId", "modelId"),
                Set.of("revision", "profileId", "modelId", "fallbackProfileId", "fallbackModelId"));
            long revision = nonnegative(json.get("revision"));
            ModelPolicy policy;
            if (revision == 0) {
              if (!json.get("profileId").isNull() || !json.get("modelId").isNull()) throw invalid();
              if (json.hasNonNull("fallbackProfileId") || json.hasNonNull("fallbackModelId"))
                throw invalid();
              policy = new ModelPolicy(0, null, null, null, null);
            } else
              policy =
                  new ModelPolicy(
                      revision,
                      identifier(json.get("profileId")),
                      text(json.get("modelId"), 1, 200),
                      optionalIdentifier(json.get("fallbackProfileId")),
                      optionalText(json.get("fallbackModelId"), 200));
            if ((policy.fallbackProfileId() == null) != (policy.fallbackModelId() == null)
                || policy.fallbackProfileId() != null
                    && (policy.fallbackProfileId().equals(policy.profileId())
                        || policy.fallbackModelId().equals(policy.modelId()))) throw invalid();
            if (bound.observedPolicy != null
                && (policy.revision() < bound.observedPolicy.revision()
                    || policy.revision() == bound.observedPolicy.revision()
                        && !policy.equals(bound.observedPolicy))) throw invalid();
            bound.observedPolicy = policy;
            return policy;
          });
    }
  }

  public CompletionStage<List<PackageView>> packages() {
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "GET",
          "/v1/packages",
          null,
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          null,
          json -> {
            fields(json, Set.of("paymentEnabled", "packages"));
            boolean enabled = bool(json.get("paymentEnabled"));
            List<PackageView> result = new ArrayList<>();
            for (JsonNode item : array(json.get("packages"), 100)) {
              fields(item, Set.of("id", "name", "credits", "priceMinor", "currency", "enabled"));
              result.add(
                  new PackageView(
                      identifier(item.get("id")),
                      text(item.get("name"), 1, 200),
                      nonnegative(item.get("credits")),
                      optionalLong(item.get("priceMinor")),
                      currency(item.get("currency")),
                      bool(item.get("enabled")),
                      enabled));
            }
            if (result.stream().map(PackageView::id).distinct().count() != result.size())
              throw invalid();
            return List.copyOf(result);
          });
    }
  }

  public CompletionStage<OrderView> createOrder(String packageId) {
    return createOrder(UUID.randomUUID(), packageId);
  }

  public CompletionStage<OrderView> createOrder(UUID requestId, String packageId) {
    Objects.requireNonNull(requestId);
    validateId(packageId);
    ObjectNode body =
        MAPPER
            .createObjectNode()
            .put("requestId", requestId.toString())
            .put("packageId", packageId);
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "POST",
          "/v1/orders",
          bytes(body),
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          null,
          json -> {
            OrderView result = order(json);
            if (!result.requestId().equals(requestId) || !result.packageId().equals(packageId))
              throw invalid();
            return result;
          });
    }
  }

  public CompletionStage<List<OrderView>> orders() {
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "GET",
          "/v1/orders",
          null,
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          null,
          json -> {
            fields(json, Set.of("orders"));
            List<OrderView> result = new ArrayList<>();
            for (JsonNode item : array(json.get("orders"), 100)) result.add(order(item));
            if (result.stream().map(OrderView::id).distinct().count() != result.size())
              throw invalid();
            return List.copyOf(result);
          });
    }
  }

  /** Pure receipt lookup; never starts or retries a model call. */
  public CompletionStage<GradeStatus> gradeStatus(UUID requestId) {
    Objects.requireNonNull(requestId);
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "GET",
          "/v1/grades/" + requestId,
          null,
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          timeout,
          requestId,
          json -> {
            allowedFields(
                json,
                Set.of("requestId", "state", "creditsRemaining", "chargedCredits", "replayed"),
                Set.of(
                    "requestId",
                    "state",
                    "creditsRemaining",
                    "chargedCredits",
                    "replayed",
                    "diagnosticCode"));
            matchingId(json.get("requestId"), requestId);
            GradeState state = GradeState.valueOf(text(json.get("state"), 1, 30));
            long credits = nonnegative(json.get("creditsRemaining"));
            int charged = charged(json.get("chargedCredits"));
            boolean replayed = bool(json.get("replayed"));
            String diagnostic = optionalDiagnostic(json.get("diagnosticCode"));
            if (!replayed) throw invalid();
            updateCredits(bound, credits);
            return new GradeStatus(requestId, state, credits, charged, true, diagnostic);
          });
    }
  }

  /**
   * Explicit billable action that asks the server-selected rubric model to create structured
   * scoring points. Callers must durably persist {@code requestId} before dispatch so an uncertain
   * outcome can be replayed without a second debit.
   */
  public CompletionStage<GeneratedRubric> generateRubric(
      UUID requestId, String subject, String referenceAnswer, BigDecimal maximumScore) {
    Objects.requireNonNull(requestId);
    String normalizedSubject = requiredInput(subject, 1, 100, true);
    String normalizedReference = requiredInput(referenceAnswer, 1, 64_000, false);
    BigDecimal normalizedMaximum = validMaximum(maximumScore);
    ObjectNode body =
        MAPPER
            .createObjectNode()
            .put("requestId", requestId.toString())
            .put("subject", normalizedSubject)
            .put("referenceAnswer", normalizedReference)
            .put("maxScore", normalizedMaximum);
    synchronized (lock) {
      Session bound = requireSession();
      return exchange(
          "POST",
          "/v1/rubrics/generate",
          bytes(body),
          bound,
          null,
          bound.generation,
          CancellationToken.none(),
          Duration.ofMinutes(3),
          requestId,
          json -> decodeGeneratedRubric(bound, requestId, normalizedMaximum, json));
    }
  }

  private GeneratedRubric decodeGeneratedRubric(
      Session bound, UUID requestId, BigDecimal maximumScore, JsonNode json) {
    fields(
        json,
        Set.of(
            "requestId",
            "scoreStep",
            "criteria",
            "completedAt",
            "usage",
            "creditsRemaining",
            "chargedCredits",
            "replayed"));
    matchingId(json.get("requestId"), requestId);
    BigDecimal scoreStep = positiveDecimal(json.get("scoreStep"), 8);
    JsonNode criteriaNode = json.get("criteria");
    if (criteriaNode == null
        || !criteriaNode.isArray()
        || criteriaNode.isEmpty()
        || criteriaNode.size() > 50) throw invalid();
    List<ScoringCriterion> criteria = new ArrayList<>();
    Set<String> ids = new java.util.HashSet<>();
    BigDecimal maxima = BigDecimal.ZERO;
    for (JsonNode criterion : criteriaNode) {
      fields(criterion, Set.of("id", "rule", "allowedScores"));
      String id = text(criterion.get("id"), 1, 100);
      if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,99}") || !ids.add(id)) throw invalid();
      String rule = text(criterion.get("rule"), 1, 4000).trim();
      JsonNode valuesNode = criterion.get("allowedScores");
      if (!valuesNode.isArray() || valuesNode.size() < 2 || valuesNode.size() > 101)
        throw invalid();
      List<BigDecimal> values = new ArrayList<>();
      BigDecimal previous = null;
      for (JsonNode value : valuesNode) {
        BigDecimal parsed = nonnegativeDecimal(value, 8);
        if (previous != null && parsed.compareTo(previous) <= 0) throw invalid();
        if (parsed.remainder(scoreStep).signum() != 0) throw invalid();
        values.add(parsed);
        previous = parsed;
      }
      if (values.getFirst().signum() != 0) throw invalid();
      maxima = maxima.add(values.getLast());
      try {
        criteria.add(new ScoringCriterion(id, rule, values));
      } catch (RuntimeException rejected) {
        throw invalid();
      }
    }
    if (maxima.compareTo(maximumScore) != 0) throw invalid();
    long credits = nonnegative(json.get("creditsRemaining"));
    if (charged(json.get("chargedCredits")) != 1) throw invalid();
    boolean replayed = bool(json.get("replayed"));
    Instant completedAt = instant(json.get("completedAt"));
    GradingResponse.Usage usage = usage(json.get("usage"));
    updateCredits(bound, credits);
    return new GeneratedRubric(
        requestId, scoreStep, criteria, completedAt, usage, credits, replayed);
  }

  /**
   * Revokes local permission immediately. Server revocation completion cannot affect a new login.
   */
  public CompletionStage<Void> logout() {
    synchronized (lock) {
      if (closed) return CompletableFuture.completedFuture(null);
      Session old = session;
      String authorization = old == null ? null : new String(old.token);
      revoke();
      if (old == null) return CompletableFuture.completedFuture(null);
      return exchange(
          "POST",
          "/v1/auth/logout",
          new byte[0],
          null,
          authorization,
          -1,
          CancellationToken.none(),
          timeout,
          null,
          ignored -> null);
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      revoke();
      for (Pending<?> item : pending) item.abort(GatewayException.Code.CLIENT_CLOSED);
    }
    watcher.shutdownNow();
    http.shutdownNow();
  }

  Binding bindProfile(String profileId) {
    validateId(profileId);
    synchronized (lock) {
      Session bound = requireSession();
      PublicModelProfile profile =
          bound.profiles.stream()
              .filter(p -> p.id().equals(profileId))
              .findFirst()
              .orElseThrow(() -> new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE));
      if (bound.catalogPolicy == null
          || !bound.catalogPolicy.equals(bound.observedPolicy)
          || !profileId.equals(bound.catalogPolicy.profileId()))
        throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
      PublicModelProfile fallback =
          bound.catalogPolicy.fallbackProfileId() == null
              ? null
              : bound.profiles.stream()
                  .filter(p -> p.id().equals(bound.catalogPolicy.fallbackProfileId()))
                  .findFirst()
                  .orElseThrow(() -> new GatewayException(GatewayException.Code.MODEL_UNAVAILABLE));
      return new Binding(bound, profile, fallback, bound.catalogPolicy);
    }
  }

  CompletionStage<GradingResponse> grade(
      Binding binding,
      Rubric rubric,
      GradingRequest request,
      byte[] png,
      CancellationToken cancellation) {
    return fetchPolicy(binding.session, cancellation)
        .thenCompose(
            policy -> {
              if (!policy.equals(binding.policy))
                return failed(GatewayException.Code.MODEL_POLICY_CHANGED);
              return dispatchGrade(
                      binding, binding.profile, null, rubric, request, png, cancellation)
                  .thenCompose(
                      response ->
                          fetchPolicy(binding.session, cancellation)
                              .thenApply(
                                  after -> {
                                    if (!after.equals(binding.policy))
                                      throw new GatewayException(
                                          GatewayException.Code.MODEL_POLICY_CHANGED,
                                          409,
                                          request.requestId(),
                                          null,
                                          1,
                                          false);
                                    return response;
                                  }));
            });
  }

  CompletionStage<GradingResponse> gradeFallback(
      Binding binding,
      UUID primaryRequestId,
      Rubric rubric,
      GradingRequest request,
      byte[] png,
      CancellationToken cancellation) {
    if (binding.fallbackProfile == null) return failed(GatewayException.Code.MODEL_UNAVAILABLE);
    if (!request
        .requestId()
        .equals(
            app.deskgrade.core.port.FallbackGradingProvider.fallbackRequestId(primaryRequestId)))
      return failed(GatewayException.Code.INVALID_REQUEST);
    return fetchPolicy(binding.session, cancellation)
        .thenCompose(
            policy -> {
              if (!policy.equals(binding.policy))
                return failed(GatewayException.Code.MODEL_POLICY_CHANGED);
              return dispatchGrade(
                      binding,
                      binding.fallbackProfile,
                      primaryRequestId,
                      rubric,
                      request,
                      png,
                      cancellation)
                  .thenCompose(
                      response ->
                          fetchPolicy(binding.session, cancellation)
                              .thenApply(
                                  after -> {
                                    if (!after.equals(binding.policy))
                                      throw new GatewayException(
                                          GatewayException.Code.MODEL_POLICY_CHANGED,
                                          409,
                                          request.requestId(),
                                          null,
                                          1,
                                          false);
                                    return response;
                                  }));
            });
  }

  private CompletionStage<GradingResponse> dispatchGrade(
      Binding binding,
      PublicModelProfile dispatchProfile,
      UUID fallbackOfRequestId,
      Rubric rubric,
      GradingRequest request,
      byte[] png,
      CancellationToken cancellation) {
    ObjectNode json =
        MAPPER
            .createObjectNode()
            .put("requestId", request.requestId().toString())
            .put("profileId", dispatchProfile.id())
            .put("policyRevision", binding.policy.revision());
    if (fallbackOfRequestId != null)
      json.put("fallbackOfRequestId", fallbackOfRequestId.toString());
    try {
      json.set("rubric", MAPPER.readTree(new RubricJsonCodec().write(rubric)));
    } catch (Exception failure) {
      return failed(GatewayException.Code.INVALID_REQUEST);
    }
    json.put("imageBase64", Base64.getEncoder().encodeToString(png));
    byte[] body = bytes(json);
    if (body.length > 16 * 1024 * 1024) return failed(GatewayException.Code.INVALID_REQUEST);
    synchronized (lock) {
      Session bound = binding.session;
      if (!binding.policy.equals(bound.observedPolicy))
        return failed(GatewayException.Code.MODEL_POLICY_CHANGED);
      Duration gradingTimeout = dispatchProfile.requestTimeout().plusSeconds(15);
      return exchange(
          "POST",
          "/v1/grade",
          body,
          bound,
          null,
          bound.generation,
          cancellation,
          gradingTimeout,
          request.requestId(),
          response -> {
            if (!binding.policy.equals(bound.observedPolicy))
              throw new GatewayException(GatewayException.Code.MODEL_POLICY_CHANGED);
            fields(
                response,
                Set.of(
                    "requestId",
                    "source",
                    "result",
                    "completedAt",
                    "elapsedMs",
                    "usage",
                    "creditsRemaining",
                    "chargedCredits",
                    "replayed"));
            matchingId(response.get("requestId"), request.requestId());
            GradingResponse.Source source =
                GradingResponse.Source.valueOf(text(response.get("source"), 1, 4));
            long credits = nonnegative(response.get("creditsRemaining"));
            int charge = charged(response.get("chargedCredits"));
            bool(response.get("replayed"));
            if (charge != 1) throw invalid();
            updateCredits(bound, credits);
            var result =
                new GradeResultJsonCodec()
                    .read(response.get("result").toString(), rubric.question());
            Long elapsed = optionalLong(response.get("elapsedMs"));
            if (elapsed != null && elapsed > Duration.ofDays(1).toMillis()) throw invalid();
            return new GradingResponse(
                request.requestId(),
                source,
                result,
                instant(response.get("completedAt")),
                usage(response.get("usage")),
                elapsed == null ? null : Duration.ofMillis(elapsed));
          });
    }
  }

  static final class Binding {
    private final Session session;
    private final PublicModelProfile profile;
    private final PublicModelProfile fallbackProfile;
    private final ModelPolicy policy;

    private Binding(
        Session session,
        PublicModelProfile profile,
        PublicModelProfile fallbackProfile,
        ModelPolicy policy) {
      this.session = session;
      this.profile = profile;
      this.fallbackProfile = fallbackProfile;
      this.policy = policy;
    }

    Optional<String> fallbackProfileId() {
      return Optional.ofNullable(fallbackProfile).map(PublicModelProfile::id);
    }
  }

  private <T> CompletionStage<T> exchange(
      String method,
      String path,
      byte[] body,
      Session bound,
      String detachedToken,
      long expectedGeneration,
      CancellationToken cancellation,
      Duration requestTimeout,
      UUID requestId,
      Function<JsonNode, T> decode) {
    return exchange(
        method,
        path,
        body,
        bound,
        detachedToken,
        expectedGeneration,
        cancellation,
        requestTimeout,
        requestId,
        MAX_RESPONSE,
        decode);
  }

  private <T> CompletionStage<T> exchange(
      String method,
      String path,
      byte[] body,
      Session bound,
      String detachedToken,
      long expectedGeneration,
      CancellationToken cancellation,
      Duration requestTimeout,
      UUID requestId,
      int maximumResponse,
      Function<JsonNode, T> decode) {
    // Callers hold lock, so token selection, epoch check and dispatch form one local decision.
    GatewayException.Code rejected = rejected(bound, expectedGeneration, cancellation);
    if (rejected != null) return failed(rejected);
    Pending<T> item = new Pending<>(bound, expectedGeneration, cancellation, requestTimeout);
    try {
      var builder =
          HttpRequest.newBuilder(base.resolve(path))
              .timeout(requestTimeout)
              .header("Accept", "application/json")
              .header("X-DeskGrade-Diagnostics", "1");
      String token = bound == null ? detachedToken : new String(bound.token);
      if (token != null) builder.header("Authorization", "Bearer " + token);
      if (body == null) builder.GET();
      else
        builder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
      pending.add(item);
      item.network = http.sendAsync(builder.build(), ignored -> new BoundedBody(maximumResponse));
      item.network.whenComplete(
          (response, failure) -> {
            synchronized (lock) {
              if (item.result.isDone()) return;
              GatewayException.Code invalidated = rejected(bound, expectedGeneration, cancellation);
              if (invalidated != null) {
                item.abort(invalidated);
                return;
              }
              try {
                if (failure != null) {
                  Throwable cause = failure;
                  for (int depth = 0;
                      depth < 10 && cause != null;
                      depth++, cause = cause.getCause()) {
                    if (cause instanceof GatewayException gateway) throw gateway;
                    if (cause instanceof HttpTimeoutException)
                      throw new GatewayException(GatewayException.Code.TIMEOUT);
                  }
                  throw new GatewayException(GatewayException.Code.NETWORK);
                }
                int status = response.statusCode();
                if (status == 204 && path.equals("/v1/auth/logout")) {
                  if (response.body().length != 0) throw invalid();
                  item.result.complete(null);
                  return;
                }
                if (status < 200 || status >= 300) {
                  throw error(response.body(), status, requestId, bound);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType
                    .toLowerCase(java.util.Locale.ROOT)
                    .matches("application/json(?:\\s*;.*)?")) throw invalid();
                item.result.complete(decode.apply(parse(response.body(), maximumResponse)));
              } catch (GatewayException failureSafe) {
                item.result.completeExceptionally(failureSafe);
              } catch (RuntimeException failureUnsafe) {
                item.result.completeExceptionally(invalid());
              } finally {
                pending.remove(item);
              }
            }
          });
    } catch (RuntimeException failure) {
      pending.remove(item);
      item.result.completeExceptionally(new GatewayException(GatewayException.Code.NETWORK));
    }
    item.result.whenComplete(
        (value, failure) -> {
          pending.remove(item);
          if (item.network != null && !item.network.isDone()) item.network.cancel(true);
        });
    return item.result;
  }

  private GatewayException error(byte[] body, int status, UUID requestId, Session bound) {
    // Redirects are never followed or interpreted as successful account/grade responses.
    if (status >= 300 && status < 400) return invalid();
    JsonNode json = parse(body);
    allowedFields(
        json,
        Set.of("error", "chargedCredits"),
        Set.of("error", "requestId", "creditsRemaining", "chargedCredits", "replayed"));
    allowedFields(
        json.get("error"), Set.of("code", "message"), Set.of("code", "message", "diagnosticCode"));
    text(json.get("error").get("message"), 0, 1000); // Validate, deliberately never display.
    String diagnostic = optionalDiagnostic(json.get("error").get("diagnosticCode"));
    GatewayException.Code code;
    try {
      code = GatewayException.Code.valueOf(text(json.get("error").get("code"), 1, 40));
    } catch (IllegalArgumentException invalidCode) {
      throw invalid();
    }
    if (!Set.of(
            GatewayException.Code.AUTH_REQUIRED,
            GatewayException.Code.INVALID_CREDENTIALS,
            GatewayException.Code.ACCOUNT_DISABLED,
            GatewayException.Code.RATE_LIMITED,
            GatewayException.Code.INVALID_REQUEST,
            GatewayException.Code.MODEL_UNAVAILABLE,
            GatewayException.Code.MODEL_POLICY_CHANGED,
            GatewayException.Code.FALLBACK_NOT_ELIGIBLE,
            GatewayException.Code.INSUFFICIENT_CREDITS,
            GatewayException.Code.IDEMPOTENCY_CONFLICT,
            GatewayException.Code.REQUEST_IN_PROGRESS,
            GatewayException.Code.DISPATCH_UNKNOWN,
            GatewayException.Code.MODEL_FAILED,
            GatewayException.Code.MODEL_RESULT_INVALID,
            GatewayException.Code.STORE_UNAVAILABLE,
            GatewayException.Code.DUPLICATE_SUBMISSION,
            GatewayException.Code.INTERNAL_ERROR,
            GatewayException.Code.FORBIDDEN,
            GatewayException.Code.NOT_FOUND)
        .contains(code)) throw invalid();
    UUID returnedId = json.has("requestId") ? uuid(json.get("requestId")) : null;
    if (returnedId != null && requestId != null && !returnedId.equals(requestId)) throw invalid();
    Long credits = optionalLong(json.get("creditsRemaining"));
    Integer charged =
        json.get("chargedCredits").isNull() ? null : charged(json.get("chargedCredits"));
    boolean replayed = json.has("replayed") && bool(json.get("replayed"));
    if (bound != null && credits != null) updateCredits(bound, credits);
    return new GatewayException(code, status, returnedId, credits, charged, replayed, diagnostic);
  }

  private static String optionalDiagnostic(JsonNode node) {
    if (node == null || node.isNull()) return null;
    String code = text(node, 1, 80);
    if (!GatewayException.validDiagnosticCode(code)) throw invalid();
    return code;
  }

  private void checkPending() {
    synchronized (lock) {
      for (Pending<?> item : pending) {
        GatewayException.Code code = rejected(item.bound, item.generation, item.cancellation);
        if (code == null && System.nanoTime() - item.started >= item.timeoutNanos)
          code = GatewayException.Code.TIMEOUT;
        if (code != null) item.abort(code);
      }
    }
  }

  private GatewayException.Code rejected(
      Session bound, long expected, CancellationToken cancellation) {
    if (closed) return GatewayException.Code.CLIENT_CLOSED;
    if (cancellation.isCancellationRequested()) return GatewayException.Code.CANCELLED;
    if (expected >= 0 && generation != expected) return GatewayException.Code.SESSION_CHANGED;
    if (bound != null && (session != bound || !bound.view.expiresAt().isAfter(clock.instant())))
      return GatewayException.Code.SESSION_CHANGED;
    return null;
  }

  private void expire() {
    if (session != null && !session.view.expiresAt().isAfter(clock.instant())) revoke();
  }

  private Session requireSession() {
    expire();
    if (closed) throw new GatewayException(GatewayException.Code.CLIENT_CLOSED);
    if (session == null) throw new GatewayException(GatewayException.Code.AUTH_REQUIRED);
    return session;
  }

  private void revoke() {
    generation++;
    if (session != null) Arrays.fill(session.token, '\0');
    session = null;
    for (Pending<?> item : pending)
      if (item.generation >= 0) item.abort(GatewayException.Code.SESSION_CHANGED);
  }

  private static void updateCredits(Session bound, long credits) {
    var old = bound.view;
    bound.view =
        new SessionView(old.accountId(), old.username(), old.role(), credits, old.expiresAt());
  }

  private static final class Session {
    private final char[] token;
    private final long generation;
    private SessionView view;
    private List<PublicModelProfile> profiles = List.of();
    private ModelPolicy observedPolicy, catalogPolicy;

    private Session(char[] token, SessionView view, long generation) {
      this.token = token;
      this.view = view;
      this.generation = generation;
    }

    @Override
    public String toString() {
      return "GatewaySession[redacted]";
    }
  }

  private final class Pending<T> {
    private final Session bound;
    private final long generation;
    private final CancellationToken cancellation;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private CompletableFuture<HttpResponse<byte[]>> network;

    private Pending(
        Session bound, long generation, CancellationToken cancellation, Duration timeout) {
      this.bound = bound;
      this.generation = generation;
      this.cancellation = cancellation;
      this.timeoutNanos = timeout.toNanos();
    }

    private void abort(GatewayException.Code code) {
      result.completeExceptionally(new GatewayException(code));
      if (network != null) network.cancel(true);
      pending.remove(this);
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;

    BoundedBody(int maximum) {
      this.maximum = maximum;
    }

    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(invalid());
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }

  private static URI validateBase(URI base) {
    if (base == null
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null
        || !(base.getRawPath().isEmpty() || base.getRawPath().equals("/"))
        || !("https".equals(base.getScheme())
            || ("http".equals(base.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost()))))
      throw new IllegalArgumentException("Gateway URL must be HTTPS or exact local HTTP origin");
    return base;
  }

  private static byte[] bytes(JsonNode value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (Exception failure) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  private static SessionView account(JsonNode json, Instant expiresAt) {
    fields(json, Set.of("id", "username", "role", "credits"));
    String role = text(json.get("role"), 1, 10);
    if (!Set.of("USER", "ADMIN").contains(role)) throw invalid();
    return new SessionView(
        identifier(json.get("id")),
        text(json.get("username"), 3, 32),
        role,
        nonnegative(json.get("credits")),
        expiresAt);
  }

  private static OrderView order(JsonNode json) {
    fields(
        json,
        Set.of(
            "id",
            "requestId",
            "packageId",
            "packageName",
            "credits",
            "priceMinor",
            "currency",
            "status",
            "paymentEnabled"));
    String status = text(json.get("status"), 1, 30);
    if (!Set.of("PENDING_PAYMENT", "PAID", "CANCELLED", "EXPIRED").contains(status))
      throw invalid();
    return new OrderView(
        identifier(json.get("id")),
        uuid(json.get("requestId")),
        identifier(json.get("packageId")),
        text(json.get("packageName"), 1, 200),
        nonnegative(json.get("credits")),
        optionalLong(json.get("priceMinor")),
        currency(json.get("currency")),
        status,
        bool(json.get("paymentEnabled")));
  }

  private static GradingResponse.Usage usage(JsonNode json) {
    if (json == null || json.isNull()) return null;
    allowedFields(json, Set.of(), Set.of("inputTokens", "outputTokens", "estimatedCost"));
    JsonNode cost = json.get("estimatedCost");
    if (cost != null && !cost.isNull() && (!cost.isNumber() || cost.decimalValue().signum() < 0))
      throw invalid();
    return new GradingResponse.Usage(
        optionalLong(json.get("inputTokens")),
        optionalLong(json.get("outputTokens")),
        cost == null || cost.isNull() ? null : cost.decimalValue());
  }

  private static String identifier(JsonNode node) {
    String id = text(node, 1, 100);
    if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw invalid();
    return id;
  }

  private static String optionalIdentifier(JsonNode node) {
    return node == null || node.isNull() ? null : identifier(node);
  }

  private static String optionalText(JsonNode node, int max) {
    return node == null || node.isNull() ? null : text(node, 1, max);
  }

  private static boolean catalogMatches(ModelPolicy policy, List<PublicModelProfile> profiles) {
    if (policy.revision() == 0) return profiles.isEmpty();
    if (profiles.isEmpty() || profiles.size() > 2) return false;
    PublicModelProfile primary = profiles.getFirst();
    if (!primary.verified()
        || primary.role() != PublicModelProfile.Role.PRIMARY
        || !policy.profileId().equals(primary.id())
        || !policy.modelId().equals(primary.modelId())) return false;
    if (policy.fallbackProfileId() == null) return profiles.size() == 1;
    if (profiles.size() != 2) return false;
    PublicModelProfile fallback = profiles.get(1);
    return fallback.verified()
        && fallback.role() == PublicModelProfile.Role.FALLBACK
        && policy.fallbackProfileId().equals(fallback.id())
        && policy.fallbackModelId().equals(fallback.modelId())
        && !primary.id().equals(fallback.id())
        && !primary.modelId().equals(fallback.modelId());
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
  }

  private static String currency(JsonNode node) {
    String value = text(node, 3, 3);
    if (!value.matches("[A-Z]{3}")) throw invalid();
    return value;
  }

  private static JsonNode array(JsonNode node, int limit) {
    if (node == null || !node.isArray() || node.size() > limit) throw invalid();
    return node;
  }

  private static UUID uuid(JsonNode node) {
    String value = text(node, 36, 36);
    UUID id = UUID.fromString(value);
    if (!id.toString().equalsIgnoreCase(value)) throw invalid();
    return id;
  }

  private static void matchingId(JsonNode node, UUID expected) {
    if (!uuid(node).equals(expected)) throw invalid();
  }

  private static Instant instant(JsonNode node) {
    return Instant.parse(text(node, 1, 50));
  }

  private static int charged(JsonNode node) {
    long value = nonnegative(node);
    if (value > 1) throw invalid();
    return (int) value;
  }

  private static String requiredInput(String value, int min, int max, boolean singleLine) {
    if (value == null) throw new IllegalArgumentException("Missing rubric generation input");
    String normalized = value.trim();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < min
        || length > max
        || normalized.chars().anyMatch(c -> c == 0)
        || singleLine && normalized.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid rubric generation input");
    return normalized;
  }

  private static BigDecimal validMaximum(BigDecimal value) {
    BigDecimal normalized = value == null ? null : value.stripTrailingZeros();
    if (normalized == null
        || normalized.signum() <= 0
        || normalized.compareTo(new BigDecimal("100")) > 0
        || normalized.scale() > 8) throw new IllegalArgumentException("Invalid maximum score");
    return normalized;
  }

  private static BigDecimal positiveDecimal(JsonNode node, int maximumScale) {
    BigDecimal value = nonnegativeDecimal(node, maximumScale);
    if (value.signum() <= 0) throw invalid();
    return value;
  }

  private static BigDecimal nonnegativeDecimal(JsonNode node, int maximumScale) {
    if (node == null || !node.isNumber()) throw invalid();
    BigDecimal value = node.decimalValue().stripTrailingZeros();
    if (value.signum() < 0 || value.scale() > maximumScale || value.precision() - value.scale() > 3)
      throw invalid();
    return value;
  }

  private static <T> CompletableFuture<T> failed(GatewayException.Code code) {
    return CompletableFuture.failedFuture(new GatewayException(code));
  }

  public record ModelPolicy(
      long revision,
      String profileId,
      String modelId,
      String fallbackProfileId,
      String fallbackModelId) {}

  public record SessionView(
      String accountId, String username, String role, long credits, Instant expiresAt) {
    public String displayName() {
      return username;
    }

    public long remainingCredits() {
      return credits;
    }
  }

  public record PackageView(
      String id,
      String name,
      long credits,
      Long priceMinor,
      String currency,
      boolean enabled,
      boolean paymentEnabled) {
    public BigDecimal price() {
      return priceMinor == null ? null : BigDecimal.valueOf(priceMinor, 2);
    }
  }

  public record OrderView(
      String id,
      UUID requestId,
      String packageId,
      String packageName,
      long credits,
      Long priceMinor,
      String currency,
      String status,
      boolean paymentEnabled) {
    public BigDecimal price() {
      return priceMinor == null ? null : BigDecimal.valueOf(priceMinor, 2);
    }
  }

  public record GradeStatus(
      UUID requestId,
      GradeState state,
      long creditsRemaining,
      int chargedCredits,
      boolean replayed,
      String diagnosticCode) {
    public GradeStatus(
        UUID requestId,
        GradeState state,
        long creditsRemaining,
        int chargedCredits,
        boolean replayed) {
      this(requestId, state, creditsRemaining, chargedCredits, replayed, null);
    }

    public GradeStatus {
      if (diagnosticCode != null && !GatewayException.validDiagnosticCode(diagnosticCode))
        throw new IllegalArgumentException("Invalid gateway diagnostic code");
    }
  }

  public record GeneratedRubric(
      UUID requestId,
      BigDecimal scoreStep,
      List<ScoringCriterion> criteria,
      Instant completedAt,
      GradingResponse.Usage usage,
      long creditsRemaining,
      boolean replayed) {
    public GeneratedRubric {
      criteria = List.copyOf(criteria);
    }
  }

  public enum GradeState {
    PREPARED,
    DISPATCHING,
    SUCCEEDED,
    FAILED,
    NOT_DISPATCHED,
    DISPATCH_UNKNOWN
  }
}
