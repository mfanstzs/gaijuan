package app.deskgrade.gateway;

import app.deskgrade.adapters.commercial.PublicModelProfileJsonCodec;
import app.deskgrade.adapters.persistence.JournalCommandDispatcher;
import app.deskgrade.adapters.persistence.JournalJson;
import app.deskgrade.adapters.persistence.MySqlSessionJournal;
import app.deskgrade.core.application.session.GradingRecords;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Loopback HTTP boundary with an explicitly configured single HTTPS reverse-proxy mode. */
public final class GatewayServer implements AutoCloseable {
  private static final int CONTROL_THREADS = 16;
  private static final int CONTROL_QUEUE_CAPACITY = 64;
  private static final int GRADE_ADMISSION_THREADS = 4;
  private static final int DEFAULT_GRADE_INGRESS_QUEUE_CAPACITY = 32;
  private static final int MAX_GRADE_INGRESS_QUEUE_CAPACITY = 64;
  private static final int ACCOUNT_GRADE_INGRESS_LIMIT = 2;
  private static final Duration DEFAULT_GRADE_BODY_READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration MAX_GRADE_BODY_READ_TIMEOUT = Duration.ofSeconds(60);
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final ThreadPoolExecutor gradeExecutor;
  private final ScheduledThreadPoolExecutor bodyDeadlines;
  private final GatewayStore store;
  private final GatewayModelService models;
  private final DataSource journalDataSource;
  private final LoginLimiter limiter;
  private final RequestLimiter requests;
  private final GatewayCapacity grading;
  private final GatewayProxyConfiguration proxy;
  private final Duration gradeBodyReadTimeout;
  private final Map<UUID, Integer> gradeIngressUsers = new HashMap<>();
  private final Set<HttpExchange> gradeExchanges =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Set<UUID> gradingUsers =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  public GatewayServer(int port, GatewayStore store, GatewayModelService models, Clock clock)
      throws IOException {
    this(port, store, models, clock, null, GatewayCapacity.Configuration.load());
  }

  public GatewayServer(
      int port,
      GatewayStore store,
      GatewayModelService models,
      Clock clock,
      DataSource journalDataSource)
      throws IOException {
    this(port, store, models, clock, journalDataSource, GatewayCapacity.Configuration.load());
  }

  GatewayServer(
      int port,
      GatewayStore store,
      GatewayModelService models,
      Clock clock,
      DataSource journalDataSource,
      GatewayCapacity.Configuration capacity)
      throws IOException {
    this(
        port,
        store,
        models,
        clock,
        journalDataSource,
        capacity,
        DEFAULT_GRADE_BODY_READ_TIMEOUT,
        DEFAULT_GRADE_INGRESS_QUEUE_CAPACITY);
  }

  GatewayServer(
      int port,
      GatewayStore store,
      GatewayModelService models,
      Clock clock,
      DataSource journalDataSource,
      GatewayCapacity.Configuration capacity,
      Duration gradeBodyReadTimeout,
      int gradeIngressQueueCapacity)
      throws IOException {
    this(
        port,
        store,
        models,
        clock,
        journalDataSource,
        capacity,
        gradeBodyReadTimeout,
        gradeIngressQueueCapacity,
        GatewayProxyConfiguration.load());
  }

  GatewayServer(
      int port,
      GatewayStore store,
      GatewayModelService models,
      Clock clock,
      DataSource journalDataSource,
      GatewayCapacity.Configuration capacity,
      Duration gradeBodyReadTimeout,
      int gradeIngressQueueCapacity,
      GatewayProxyConfiguration proxy)
      throws IOException {
    if (port < 0 || port > 65535) throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    if (gradeBodyReadTimeout == null
        || gradeBodyReadTimeout.compareTo(Duration.ofMillis(1)) < 0
        || gradeBodyReadTimeout.compareTo(MAX_GRADE_BODY_READ_TIMEOUT) > 0)
      throw new IllegalArgumentException("grade body read timeout is outside the supported range");
    if (gradeIngressQueueCapacity < 0
        || gradeIngressQueueCapacity > MAX_GRADE_INGRESS_QUEUE_CAPACITY)
      throw new IllegalArgumentException(
          "grade ingress queue capacity is outside the supported range");
    this.store = store;
    this.models = models;
    this.journalDataSource = journalDataSource;
    this.limiter = new LoginLimiter(clock);
    this.requests = new RequestLimiter(clock);
    this.grading = new GatewayCapacity(capacity);
    this.proxy = java.util.Objects.requireNonNull(proxy);
    this.gradeBodyReadTimeout = gradeBodyReadTimeout;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
    executor =
        new ThreadPoolExecutor(
            CONTROL_THREADS,
            CONTROL_THREADS,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY),
            Thread.ofPlatform().name("gateway-control-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    BlockingQueue<Runnable> gradeQueue =
        gradeIngressQueueCapacity == 0
            ? new SynchronousQueue<>()
            : new ArrayBlockingQueue<>(gradeIngressQueueCapacity);
    int gradeWorkerCount = capacity.gradeWorkerCount() + GRADE_ADMISSION_THREADS;
    gradeExecutor =
        new ThreadPoolExecutor(
            gradeWorkerCount,
            gradeWorkerCount,
            0,
            TimeUnit.MILLISECONDS,
            gradeQueue,
            Thread.ofPlatform().name("gateway-grade-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    bodyDeadlines =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon(true).name("gateway-body-deadline-", 0).factory());
    bodyDeadlines.setRemoveOnCancelPolicy(true);
    server.setExecutor(executor);
    server.createContext("/v1/grade", this::dispatchGrade);
    server.createContext("/v1/rubrics/generate", this::dispatchGrade);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  int queuedGradeRequests() {
    return grading.queuedRequests();
  }

  int queuedGradeIngressRequests() {
    return gradeExecutor.getQueue().size();
  }

  int activeGradeIngressRequests(UUID userId) {
    synchronized (gradeIngressUsers) {
      return gradeIngressUsers.getOrDefault(userId, 0);
    }
  }

  private void dispatchGrade(HttpExchange exchange) {
    if (!isPaidModelPath(exchange.getRequestURI().getPath())) {
      handle(exchange);
      return;
    }
    gradeExchanges.add(exchange);
    try {
      if (closed.get()) throw new RejectedExecutionException();
      gradeExecutor.execute(
          () -> {
            try {
              handle(exchange);
            } finally {
              gradeExchanges.remove(exchange);
            }
          });
    } catch (RejectedExecutionException saturated) {
      gradeExchanges.remove(exchange);
      rejectGradeIngress(exchange);
    }
  }

  private static void rejectGradeIngress(HttpExchange exchange) {
    try (exchange) {
      secureHeaders(exchange);
      exchange.getResponseHeaders().set("Retry-After", "1");
      var response = error("RATE_LIMITED");
      // Admission occurred before authentication and receipt lookup, so an old charged request
      // with the same unknown body cannot be ruled out.
      response.putNull("chargedCredits");
      send(exchange, 429, response);
    } catch (IOException ignored) {
      /* A saturated or disconnected peer cannot retain a gateway worker. */
    }
  }

  private void handle(HttpExchange exchange) {
    try (exchange) {
      secureHeaders(exchange);
      try {
        requests.acquireGlobal();
        String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
        String address =
            proxy.validate(
                exchange.getRequestHeaders(),
                exchange.getRemoteAddress().getAddress(),
                port(),
                method,
                path);
        boolean packageMutation =
            List.of("PUT", "DELETE").contains(method) && path.startsWith("/v1/admin/packages/");
        if (!List.of("GET", "POST").contains(method) && !packageMutation) {
          send(exchange, 405, error("INVALID_REQUEST"));
          return;
        }
        if (method.equals("GET")
            && List.of("/admin", "/admin/", "/admin/app.js", "/admin/style.css").contains(path)) {
          staticAdmin(exchange, path);
          return;
        }
        if (method.equals("GET")
            && path.equals("/health")
            && exchange.getRequestURI().getRawQuery() == null) {
          var health = GatewayJson.MAPPER.createObjectNode();
          store.verifyAvailable();
          health.put("service", "deskgrade-gateway");
          health.put("apiVersion", 2);
          send(exchange, 200, health);
          return;
        }
        if (!path.startsWith("/v1/")
            || exchange.getRequestURI().getRawQuery() != null
                && !List.of(
                        "/v1/admin/users",
                        "/v1/admin/ledger",
                        "/v1/admin/orders",
                        "/v1/admin/audit",
                        "/v1/admin/model-calls")
                    .contains(path)
                && !path.startsWith("/v1/admin/model-calls/"))
          throw new GatewayException(GatewayException.Code.NOT_FOUND);
        if (method.equals("POST")
            && (path.equals("/v1/auth/login") || path.equals("/v1/auth/register"))) {
          boolean registration = path.endsWith("register");
          if (registration) limiter.acquireRegistrationAddress(address);
          else limiter.acquireLoginAddress(address);
          JsonNode body = body(exchange, 4096);
          GatewayJson.fields(body, "username", "password");
          String username = GatewayJson.string(body, "username", 1, 32);
          // Registration may authenticate an already-created account, so it must not bypass the
          // per-username credential-stuffing budget.
          limiter.checkLoginUsername(username);
          char[] password = GatewayJson.string(body, "password", 1, 128).toCharArray();
          try {
            try (var ignored = limiter.acquirePasswordHash()) {
              if (registration) {
                try {
                  store.register(username, password);
                } catch (GatewayException unavailable) {
                  // Registration is deliberately idempotent for the same credentials. Never
                  // expose whether a username was already present: the following password check
                  // has the same public success/failure shape as an ordinary authentication.
                  if (unavailable.code() != GatewayException.Code.USERNAME_UNAVAILABLE)
                    throw unavailable;
                }
              }
              GatewayStore.Login login;
              try {
                login = store.login(username, password);
              } catch (GatewayException invalid) {
                if (invalid.code() == GatewayException.Code.UNAUTHENTICATED) {
                  limiter.loginFailed(username);
                  throw new GatewayException(GatewayException.Code.INVALID_CREDENTIALS);
                }
                throw invalid;
              }
              limiter.loginSucceeded(username);
              var result = GatewayJson.MAPPER.createObjectNode();
              result.put("token", login.token());
              result.put("expiresAt", login.expiresAt().toString());
              result.set("account", account(login.account()));
              send(exchange, registration ? 201 : 200, result);
            }
          } finally {
            Arrays.fill(password, '\0');
          }
          return;
        }
        String token = bearer(exchange);
        boolean logout = method.equals("POST") && path.equals("/v1/auth/logout");
        if (!logout) requests.acquireToken(token);
        var current = store.authenticate(token);
        if (!logout) requests.acquireUser(current.id());
        if (method.equals("POST") && path.equals("/v1/journal")) {
          if (journalDataSource == null)
            throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
          var command = body(exchange, JournalJson.MAX_BYTES);
          var owner =
              new GradingRecords.Owner(proxy.journalOrigin(port()), current.id().toString());
          String clientScope =
              java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(token.getBytes(StandardCharsets.UTF_8)));
          try (var journal =
              new MySqlSessionJournal(journalDataSource.getConnection(), owner, clientScope)) {
            var journalReply = JournalCommandDispatcher.execute(journal, owner, command);
            send(
                exchange,
                200,
                GatewayCallRecordEnricher.enrich(store, current.id(), command, journalReply));
          } catch (IllegalArgumentException invalid) {
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          } catch (app.deskgrade.core.error.DeskGradeException rejected) {
            if (rejected.code() == app.deskgrade.core.error.ErrorCode.DUPLICATE_SUBMISSION)
              throw new GatewayException(GatewayException.Code.DUPLICATE_SUBMISSION);
            throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
          } catch (java.sql.SQLException unavailable) {
            throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
          }
          return;
        }
        if (method.equals("POST") && path.equals("/v1/auth/logout")) {
          requireEmpty(exchange);
          store.logout(token);
          exchange.sendResponseHeaders(204, -1);
          return;
        }
        if (method.equals("GET") && path.equals("/v1/account")) {
          send(exchange, 200, account(current));
          return;
        }
        if (method.equals("GET") && path.equals("/v1/model-policy")) {
          send(exchange, 200, models.publicPolicyJson());
          return;
        }
        if (path.startsWith("/v1/admin/") && !current.role().equals("ADMIN"))
          throw new GatewayException(GatewayException.Code.FORBIDDEN);
        if (method.equals("GET") && path.equals("/v1/admin/models")) {
          send(exchange, 200, models.adminModels(current.id()));
          return;
        }
        if (path.equals("/v1/admin/model-policy")) {
          if (method.equals("GET")) {
            send(exchange, 200, GatewayModelService.policyJson(models.adminPolicy(current.id())));
          } else {
            var request = body(exchange, 8192);
            GatewayJson.fields(request, "requestId", "profileId", "expectedRevision", "reason");
            var revision = request.get("expectedRevision");
            if (!revision.isIntegralNumber()
                || !revision.canConvertToLong()
                || revision.longValue() < 0)
              throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
            var changed =
                models.selectPolicy(
                    current.id(),
                    GatewayJson.uuid(request, "requestId"),
                    GatewayJson.string(request, "profileId", 1, 100),
                    revision.longValue(),
                    GatewayJson.string(request, "reason", 1, 500));
            var result = GatewayJson.MAPPER.createObjectNode();
            result.set("policy", GatewayModelService.policyJson(changed.policy()));
            result.put("auditEntryId", changed.auditEntryId().toString());
            result.put("replayed", changed.replayed());
            send(exchange, 200, result);
          }
          return;
        }
        if (path.equals("/v1/admin/rubric-model-policy")) {
          if (method.equals("GET")) {
            send(
                exchange,
                200,
                GatewayModelService.rubricPolicyJson(models.adminRubricPolicy(current.id())));
          } else {
            var request = body(exchange, 8192);
            GatewayJson.fields(request, "requestId", "profileId", "expectedRevision", "reason");
            var revision = request.get("expectedRevision");
            if (!revision.isIntegralNumber()
                || !revision.canConvertToLong()
                || revision.longValue() < 0)
              throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
            var changed =
                models.selectRubricPolicy(
                    current.id(),
                    GatewayJson.uuid(request, "requestId"),
                    GatewayJson.string(request, "profileId", 1, 100),
                    revision.longValue(),
                    GatewayJson.string(request, "reason", 1, 500));
            var result = GatewayJson.MAPPER.createObjectNode();
            result.set("policy", GatewayModelService.rubricPolicyJson(changed.policy()));
            result.put("auditEntryId", changed.auditEntryId().toString());
            result.put("replayed", changed.replayed());
            send(exchange, 200, result);
          }
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/model-policy/audit")) {
          var result = GatewayJson.MAPPER.createObjectNode();
          var entries = result.putArray("entries");
          for (var entry : store.modelPolicyAudit(current.id())) {
            var row = entries.addObject();
            row.put("id", entry.id().toString());
            row.put("adminId", entry.adminId().toString());
            row.put("previousProfileId", entry.previousProfileId());
            row.put("profileId", entry.profileId());
            row.put("previousModelId", entry.previousModelId());
            row.put("modelId", entry.modelId());
            row.put("previousRevision", entry.previousRevision());
            row.put("revision", entry.revision());
            row.put("reason", entry.reason());
            row.put("createdAt", entry.createdAt().toString());
          }
          send(exchange, 200, result);
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/rubric-model-policy/audit")) {
          var result = GatewayJson.MAPPER.createObjectNode();
          var entries = result.putArray("entries");
          for (var entry : store.rubricModelPolicyAudit(current.id())) {
            var row = entries.addObject();
            row.put("id", entry.id().toString());
            row.put("adminId", entry.adminId().toString());
            row.put("previousProfileId", entry.previousProfileId());
            row.put("profileId", entry.profileId());
            row.put("previousModelId", entry.previousModelId());
            row.put("modelId", entry.modelId());
            row.put("previousRevision", entry.previousRevision());
            row.put("revision", entry.revision());
            row.put("reason", entry.reason());
            row.put("createdAt", entry.createdAt().toString());
          }
          send(exchange, 200, result);
          return;
        }
        if (method.equals("GET") && path.equals("/v1/models")) {
          send(exchange, 200, new PublicModelProfileJsonCodec().write(models.publicProfiles()));
          return;
        }
        if (method.equals("GET") && path.equals("/v1/packages")) {
          var result = GatewayJson.MAPPER.createObjectNode();
          result.put("paymentEnabled", false);
          result.set(
              "packages",
              GatewayJson.MAPPER.valueToTree(
                  store.publicPackages().stream().map(GatewayServer::publicPackageJson).toList()));
          send(exchange, 200, result);
          return;
        }
        if (path.equals("/v1/orders")) {
          if (method.equals("POST")) {
            var body = body(exchange, 4096);
            GatewayJson.fields(body, "requestId", "packageId");
            var id = GatewayJson.uuid(body, "requestId");
            var packageId = GatewayJson.string(body, "packageId", 1, 64);
            send(exchange, 201, order(store.createOrderFromPackage(current.id(), id, packageId)));
          } else {
            var result = GatewayJson.MAPPER.createObjectNode();
            var list = result.putArray("orders");
            store.orders(current.id(), 0, 100).forEach(o -> list.add(order(o)));
            send(exchange, 200, result);
          }
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/model-calls")) {
          var parameters = parameters(exchange, "page", "pageSize", "userId", "username");
          var paging = paging(parameters);
          var calls =
              store.adminModelCalls(
                  current.id(),
                  optionalUuid(parameters.get("userId")),
                  parameters.get("username"),
                  paging.page(),
                  paging.size());
          send(exchange, 200, page(calls, "calls", GatewayServer::modelCall));
          return;
        }
        if (method.equals("GET") && path.startsWith("/v1/admin/model-calls/")) {
          var parameters = parameters(exchange, "kind", "userId");
          var requestId = uuid(path.substring("/v1/admin/model-calls/".length()));
          String kind = parameters.get("kind");
          var detail =
              store.adminModelCallDetail(
                  current.id(), requiredUuid(parameters.get("userId")), requestId, kind);
          send(exchange, 200, modelCallDetail(detail));
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/users")) {
          var parameters = parameters(exchange, "page", "pageSize", "q");
          var paging = paging(parameters);
          var users =
              store.userPage(
                  current.id(), paging.page(), paging.size(), parameters.getOrDefault("q", ""));
          send(exchange, 200, page(users, "users", GatewayServer::adminAccount));
          return;
        }
        if (method.equals("GET") && path.startsWith("/v1/admin/users/")) {
          var id = uuid(path.substring("/v1/admin/users/".length()));
          send(exchange, 200, adminAccount(store.adminAccount(current.id(), id)));
          return;
        }
        if (method.equals("POST")
            && path.startsWith("/v1/admin/users/")
            && path.endsWith("/revoke-sessions")) {
          String raw =
              path.substring(
                  "/v1/admin/users/".length(), path.length() - "/revoke-sessions".length());
          if (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
          var id = uuid(raw);
          requireEmpty(exchange);
          store.revokeSessions(current.id(), id);
          exchange.sendResponseHeaders(204, -1);
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/orders")) {
          var parameters = parameters(exchange, "page", "pageSize", "q", "userId");
          var paging = paging(parameters);
          UUID userId = parameters.containsKey("userId") ? uuid(parameters.get("userId")) : null;
          var orders =
              store.adminOrders(
                  current.id(),
                  userId,
                  paging.page(),
                  paging.size(),
                  parameters.getOrDefault("q", ""));
          send(
              exchange,
              200,
              page(
                  orders,
                  "orders",
                  item -> {
                    var row = order(item.order());
                    row.put("userId", item.order().userId().toString());
                    row.put("username", item.username());
                    row.put("createdAt", item.order().createdAt().toString());
                    return row;
                  }));
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/dashboard")) {
          send(exchange, 200, dashboard(store.adminDashboard(current.id(), 7)));
          return;
        }
        if (path.equals("/v1/admin/packages")) {
          if (method.equals("POST")) {
            var request = body(exchange, 8192);
            GatewayJson.fields(
                request, "id", "name", "credits", "priceMinor", "currency", "enabled");
            var created =
                store.createPackage(
                    current.id(),
                    GatewayJson.string(request, "id", 1, 64),
                    GatewayJson.string(request, "name", 1, 80),
                    longField(request, "credits", 1, 1_000_000_000L),
                    nullableLongField(request, "priceMinor", 0, 100_000_000_000L),
                    GatewayJson.string(request, "currency", 3, 3),
                    booleanField(request, "enabled"));
            send(exchange, 201, packageJson(created));
            return;
          }
          if (!method.equals("GET")) throw new GatewayException(GatewayException.Code.NOT_FOUND);
          var result = GatewayJson.MAPPER.createObjectNode();
          result.put("paymentEnabled", false);
          result.set(
              "packages",
              GatewayJson.MAPPER.valueToTree(
                  store.adminPackages(current.id()).stream()
                      .map(GatewayServer::packageJson)
                      .toList()));
          send(exchange, 200, result);
          return;
        }
        if (path.startsWith("/v1/admin/packages/")) {
          String packageId = path.substring("/v1/admin/packages/".length());
          if (packageId.isEmpty() || packageId.contains("/"))
            throw new GatewayException(GatewayException.Code.NOT_FOUND);
          if (method.equals("PUT")) {
            var request = body(exchange, 8192);
            GatewayJson.fields(
                request,
                "expectedRevision",
                "name",
                "credits",
                "priceMinor",
                "currency",
                "enabled");
            send(
                exchange,
                200,
                packageJson(
                    store.updatePackage(
                        current.id(),
                        packageId,
                        longField(request, "expectedRevision", 1, Long.MAX_VALUE - 1),
                        GatewayJson.string(request, "name", 1, 80),
                        longField(request, "credits", 1, 1_000_000_000L),
                        nullableLongField(request, "priceMinor", 0, 100_000_000_000L),
                        GatewayJson.string(request, "currency", 3, 3),
                        booleanField(request, "enabled"))));
            return;
          }
          if (method.equals("DELETE")) {
            var request = body(exchange, 8192);
            GatewayJson.fields(request, "expectedRevision");
            store.deletePackage(
                current.id(),
                packageId,
                longField(request, "expectedRevision", 1, Long.MAX_VALUE - 1));
            exchange.sendResponseHeaders(204, -1);
            return;
          }
          throw new GatewayException(GatewayException.Code.NOT_FOUND);
        }
        if (method.equals("GET") && path.equals("/v1/admin/audit")) {
          var parameters = parameters(exchange, "page", "pageSize", "type");
          var paging = paging(parameters);
          var audit =
              store.adminAudit(
                  current.id(),
                  paging.page(),
                  paging.size(),
                  parameters.getOrDefault("type", "ALL"));
          send(
              exchange,
              200,
              page(
                  audit,
                  "entries",
                  entry -> {
                    var row = GatewayJson.MAPPER.createObjectNode();
                    row.put("id", entry.id().toString());
                    row.put("adminId", entry.adminId().toString());
                    row.put("adminUsername", entry.adminUsername());
                    row.put("kind", entry.kind());
                    row.put("packageId", entry.packageId());
                    row.put(
                        "targetUserId",
                        entry.targetUserId() == null ? null : entry.targetUserId().toString());
                    row.put("targetUsername", entry.targetUsername());
                    row.put("delta", entry.delta());
                    row.put("balanceAfter", entry.balanceAfter());
                    row.put("profileId", entry.profileId());
                    row.put("modelId", entry.modelId());
                    row.put("previousModelId", entry.previousModelId());
                    row.put("previousRevision", entry.previousRevision());
                    row.put("revision", entry.revision());
                    row.put("reason", entry.reason());
                    row.put("createdAt", entry.createdAt().toString());
                    return row;
                  }));
          return;
        }
        if (method.equals("POST") && path.equals("/v1/admin/credits")) {
          var body = body(exchange, 8192);
          GatewayJson.fields(body, "requestId", "targetUserId", "delta", "reason");
          var id = GatewayJson.uuid(body, "requestId");
          var userId = GatewayJson.uuid(body, "targetUserId");
          var delta = body.get("delta");
          if (!delta.isIntegralNumber() || !delta.canConvertToLong())
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          // Existence and mutation are serialized by this monitor, preserving the response replay
          // flag.
          ObjectNode result;
          synchronized (store) {
            boolean replayed = store.adjustmentExists(current.id(), id);
            var adjusted =
                store.adjust(
                    current.id(),
                    id,
                    userId,
                    delta.longValue(),
                    GatewayJson.string(body, "reason", 1, 500));
            result = GatewayJson.MAPPER.createObjectNode();
            result.set("account", account(store.account(userId)));
            result.put("ledgerEntryId", adjusted.id().toString());
            result.put("replayed", replayed);
          }
          send(exchange, 200, result);
          return;
        }
        if (method.equals("GET") && path.equals("/v1/admin/ledger")) {
          var parameters = parameters(exchange, "userId", "page", "pageSize", "kind");
          var paging = paging(parameters);
          UUID userId = parameters.containsKey("userId") ? uuid(parameters.get("userId")) : null;
          var ledger =
              store.ledgerPage(
                  current.id(),
                  userId,
                  paging.page(),
                  paging.size(),
                  parameters.getOrDefault("kind", "ALL"));
          send(
              exchange,
              200,
              page(
                  ledger,
                  "entries",
                  item -> ledger(item.entry()).put("username", item.username())));
          return;
        }
        if (method.equals("GET") && path.startsWith("/v1/grades/")) {
          String raw = path.substring("/v1/grades/".length());
          if (!raw.matches("[0-9a-fA-F-]{36}"))
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          UUID id;
          try {
            id = UUID.fromString(raw);
          } catch (Exception invalid) {
            throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
          }
          sendModelReply(exchange, 200, models.status(current.id(), id));
          return;
        }
        if (method.equals("POST") && path.equals("/v1/rubrics/generate")) {
          try (var ingress = acquireGradeIngress(current.id())) {
            JsonNode request;
            var deadline = startGradeBodyDeadline(exchange);
            try (deadline) {
              request = body(exchange, GatewayModelService.MAX_RUBRIC_GENERATION_REQUEST_BYTES);
            }
            if (deadline.expired())
              throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
            UUID requestId = GatewayJson.uuid(request, "requestId");
            boolean userAcquired = false;
            GatewayCapacity.Permit capacityPermit = null;
            try {
              if (store.findRubricGenerationRequest(current.id(), requestId) == null) {
                userAcquired = gradingUsers.add(current.id());
                if (!userAcquired)
                  throw new GatewayException(GatewayException.Code.ACCOUNT_GRADE_IN_PROGRESS);
                capacityPermit = grading.acquire();
              }
              try {
                var reply = models.generateRubric(current.id(), request, token);
                sendModelReply(exchange, reply.status(), reply.body());
              } catch (GatewayException failure) {
                if (failure.code() != GatewayException.Code.STORAGE_FAILURE
                    && failure.code() != GatewayException.Code.INTERNAL_ERROR) throw failure;
                sendUnknown(exchange, requestId, "STORE_UNAVAILABLE");
              } catch (RuntimeException | LinkageError unknown) {
                sendUnknown(exchange, requestId, "DISPATCH_UNKNOWN");
              }
            } finally {
              if (capacityPermit != null) capacityPermit.close();
              if (userAcquired) gradingUsers.remove(current.id());
            }
          }
          return;
        }
        if (method.equals("POST") && path.equals("/v1/grade")) {
          try (var ingress = acquireGradeIngress(current.id())) {
            JsonNode request;
            var deadline = startGradeBodyDeadline(exchange);
            try (deadline) {
              request = body(exchange, GatewayModelService.MAX_REQUEST_BYTES);
            }
            if (deadline.expired())
              throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
            UUID requestId = GatewayJson.uuid(request, "requestId");
            boolean userAcquired = false;
            GatewayCapacity.Permit capacityPermit = null;
            try {
              // Existing immutable requests must reach fingerprint/replay handling before model
              // admission. Only a new request competes for this user's supplier dispatch slot.
              if (store.findRequest(current.id(), requestId) == null) {
                userAcquired = gradingUsers.add(current.id());
                if (!userAcquired)
                  throw new GatewayException(GatewayException.Code.ACCOUNT_GRADE_IN_PROGRESS);
                capacityPermit = grading.acquire();
              }
              try {
                var reply = models.gradePublic(current.id(), request, token);
                sendModelReply(exchange, reply.status(), reply.body());
              } catch (GatewayException failure) {
                if (failure.code() != GatewayException.Code.STORAGE_FAILURE
                    && failure.code() != GatewayException.Code.INTERNAL_ERROR) throw failure;
                sendUnknown(exchange, requestId, "STORE_UNAVAILABLE");
              } catch (RuntimeException | LinkageError unknown) {
                sendUnknown(exchange, requestId, "DISPATCH_UNKNOWN");
              }
            } finally {
              if (capacityPermit != null) capacityPermit.close();
              if (userAcquired) gradingUsers.remove(current.id());
            }
          }
          return;
        }
        throw new GatewayException(GatewayException.Code.NOT_FOUND);
      } catch (GatewayException failure) {
        if (failure.code() == GatewayException.Code.RATE_LIMITED
            || failure.code() == GatewayException.Code.MODEL_CAPACITY_EXHAUSTED) {
          exchange
              .getResponseHeaders()
              .set("Retry-After", Long.toString(Math.max(1, failure.retryAfterSeconds())));
        }
        String code = publicCode(failure.code());
        var response = error(code);
        if (isPaidModelPath(exchange.getRequestURI().getPath())
            && List.of(
                    GatewayException.Code.STORAGE_FAILURE,
                    GatewayException.Code.RATE_LIMITED,
                    GatewayException.Code.GRADE_INGRESS_BUSY,
                    GatewayException.Code.INTERNAL_ERROR)
                .contains(failure.code())) {
          // A failure before receipt lookup cannot classify a previously charged request ID.
          response.putNull("chargedCredits");
        }
        send(exchange, httpStatus(failure.code()), response);
      } catch (Exception | LinkageError failure) {
        var response = error("INTERNAL_ERROR");
        if (isPaidModelPath(exchange.getRequestURI().getPath())) response.putNull("chargedCredits");
        send(exchange, 500, response);
      }
    } catch (IOException ignored) {
      /* Client disconnect does not refund or resubmit a dispatched request. */
    }
  }

  static ObjectNode error(String code) {
    var node = GatewayJson.MAPPER.createObjectNode();
    var error = node.putObject("error");
    error.put("code", code);
    error.put("message", code);
    node.put("chargedCredits", 0);
    return node;
  }

  private static boolean isPaidModelPath(String path) {
    return path.equals("/v1/grade") || path.equals("/v1/rubrics/generate");
  }

  private static ObjectNode account(GatewayStore.Account account) {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("id", account.id().toString());
    node.put("username", account.username());
    node.put("role", account.role());
    node.put("credits", account.balance());
    return node;
  }

  private static ObjectNode adminAccount(GatewayStore.Account value) {
    return account(value).put("enabled", true).put("createdAt", value.createdAt().toString());
  }

  private static ObjectNode ledger(GatewayStore.LedgerEntry entry) {
    var row = GatewayJson.MAPPER.createObjectNode();
    row.put("id", entry.id().toString());
    row.put("userId", entry.userId().toString());
    row.put("kind", entry.kind());
    row.put("referenceId", entry.referenceId().toString());
    row.put("delta", entry.delta());
    row.put("balanceBefore", entry.balanceBefore());
    row.put("balanceAfter", entry.balanceAfter());
    row.put("createdAt", entry.createdAt().toString());
    row.put("reason", entry.reason());
    row.put("adminId", entry.adminId() == null ? null : entry.adminId().toString());
    return row;
  }

  private static <T> ObjectNode page(
      GatewayStore.Page<T> page, String field, java.util.function.Function<T, ObjectNode> encode) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("page", page.page());
    result.put("pageSize", page.pageSize());
    result.put("total", page.total());
    result.put("totalPages", page.totalPages());
    var items = result.putArray(field);
    for (var item : page.items()) items.add(encode.apply(item));
    return result;
  }

  private record Paging(int page, int size) {}

  private static UUID optionalUuid(String value) {
    return value == null || value.isBlank() ? null : uuid(value);
  }

  private static UUID requiredUuid(String value) {
    UUID id = optionalUuid(value);
    if (id == null) throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return id;
  }

  private static ObjectNode modelCall(GatewayStore.ModelCall call) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("kind", call.kind());
    result.put("requestId", call.requestId().toString());
    result.put("userId", call.userId().toString());
    result.put("username", call.username());
    result.put("modelId", call.modelId());
    result.put("profileId", call.profileId());
    result.put("policyRevision", call.policyRevision());
    result.put("state", call.state());
    result.put("chargedCredits", call.chargedCredits());
    result.put("errorCode", call.errorCode());
    result.put("createdAt", call.createdAt().toString());
    result.put("completedAt", call.completedAt() == null ? null : call.completedAt().toString());
    result.put("requestSnapshotAvailable", call.requestSnapshotAvailable());
    result.put("responseAvailable", call.responseAvailable());
    result.put("modelOutputAvailable", call.modelOutputAvailable());
    return result;
  }

  private static ObjectNode modelCallDetail(GatewayStore.ModelCallDetail detail) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("schemaVersion", 1);
    result.set("call", modelCall(detail.call()));
    try {
      var request =
          detail.requestJson() == null ? null : GatewayJson.MAPPER.readTree(detail.requestJson());
      result.put("failureDiagnosticStatus", "NOT_RECORDED_LEGACY");
      result.putNull("failureDiagnostic");
      if (request != null && request.isObject()) {
        try {
          var failure = GatewayModelFailure.read(request.get("failureDiagnostic"));
          if (failure != null) {
            result.put("failureDiagnosticStatus", "RECORDED");
            result.set("failureDiagnostic", failure.toJson());
          }
        } catch (IllegalArgumentException invalid) {
          result.put("failureDiagnosticStatus", "INVALID_RECORDED_DATA");
        }
        ((ObjectNode) request).remove("failureDiagnostic");
      }
      boolean requestRecorded = request != null && request.path("providerRequest").isObject();
      result.put(
          "requestSnapshotStatus",
          requestRecorded
              ? "RECORDED"
              : detail.requestJson() == null ? "NOT_RECORDED_LEGACY" : "NOT_RECORDED");
      result.set("request", requestRecorded ? request : GatewayJson.MAPPER.nullNode());
      result.set(
          "response",
          detail.responseJson() == null
              ? GatewayJson.MAPPER.nullNode()
              : GatewayJson.MAPPER.readTree(detail.responseJson()));
    } catch (IOException invalid) {
      throw new GatewayException(GatewayException.Code.STORAGE_FAILURE);
    }
    result.put("modelOutput", detail.modelOutput());
    return result;
  }

  private static Paging paging(java.util.Map<String, String> parameters) {
    int page = positive(parameters.getOrDefault("page", "1"));
    int size = positive(parameters.getOrDefault("pageSize", "20"));
    if (size > 100 || (long) (page - 1) * size > 1_000_000)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return new Paging(page, size);
  }

  private static int positive(String value) {
    if (!value.matches("[1-9][0-9]{0,6}"))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return Integer.parseInt(value);
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException invalid) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  private static java.util.Map<String, String> parameters(
      HttpExchange exchange, String... allowed) {
    var values = new java.util.HashMap<String, String>();
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) return values;
    if (raw.length() > 2048 || raw.isEmpty())
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    var names = java.util.Set.of(allowed);
    try {
      for (String pair : raw.split("&", -1)) {
        int delimiter = pair.indexOf('=');
        if (delimiter < 1) throw new IllegalArgumentException();
        String key =
            java.net.URLDecoder.decode(pair.substring(0, delimiter), StandardCharsets.UTF_8);
        String value =
            java.net.URLDecoder.decode(pair.substring(delimiter + 1), StandardCharsets.UTF_8);
        if (!names.contains(key) || values.putIfAbsent(key, value) != null)
          throw new IllegalArgumentException();
      }
      return values;
    } catch (IllegalArgumentException invalid) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  private static ObjectNode order(GatewayStore.Order order) {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("id", order.id().toString());
    node.put("requestId", order.requestId().toString());
    node.put("packageId", order.packageId());
    node.put("packageName", order.packageName());
    node.put("credits", order.units());
    if (order.priceCents() == null) node.putNull("priceMinor");
    else node.put("priceMinor", order.priceCents());
    node.put("currency", order.currency());
    node.put("status", order.status());
    node.put("paymentEnabled", false);
    return node;
  }

  private static ObjectNode packageJson(GatewayStore.CreditPackage pack) {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("id", pack.id());
    node.put("name", pack.name());
    node.put("credits", pack.credits());
    if (pack.priceMinor() == null) node.putNull("priceMinor");
    else node.put("priceMinor", pack.priceMinor());
    node.put("currency", pack.currency());
    node.put("enabled", pack.enabled());
    node.put("revision", pack.revision());
    node.put("createdAt", pack.createdAt().toString());
    node.put("updatedAt", pack.updatedAt().toString());
    return node;
  }

  private static ObjectNode publicPackageJson(GatewayStore.CreditPackage pack) {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("id", pack.id());
    node.put("name", pack.name());
    node.put("credits", pack.credits());
    if (pack.priceMinor() == null) node.putNull("priceMinor");
    else node.put("priceMinor", pack.priceMinor());
    node.put("currency", pack.currency());
    node.put("enabled", pack.enabled());
    return node;
  }

  private static ObjectNode dashboard(GatewayStore.Dashboard snapshot) {
    var result = GatewayJson.MAPPER.createObjectNode();
    result.put("generatedAt", snapshot.generatedAt().toString());
    result.put("timezone", "UTC");
    result.put("periodDays", snapshot.days());
    var summary = result.putObject("summary");
    summary.put("totalUsers", snapshot.totalUsers());
    summary.put("availableCredits", snapshot.availableCredits());
    summary.put("modelCalls", snapshot.modelCalls());
    summary.put("succeededCalls", snapshot.succeededCalls());
    summary.put("failedCalls", snapshot.failedCalls());
    summary.put("unresolvedCalls", snapshot.unresolvedCalls());
    summary.put("chargedCredits", snapshot.chargedCredits());
    summary.put("creditsAdded", snapshot.creditsAdded());
    summary.put("orders", snapshot.orders());
    if (snapshot.orderAmountMinor() == null) summary.putNull("orderAmountMinor");
    else summary.put("orderAmountMinor", snapshot.orderAmountMinor());
    summary.put("unpricedOrders", snapshot.unpricedOrders());
    var daily = result.putArray("daily");
    for (var value : snapshot.daily()) {
      var row = daily.addObject();
      row.put("date", value.date().toString());
      row.put("registrations", value.registrations());
      row.put("modelCalls", value.modelCalls());
      row.put("succeededCalls", value.succeededCalls());
      row.put("failedCalls", value.failedCalls());
      row.put("unresolvedCalls", value.unresolvedCalls());
      row.put("chargedCredits", value.chargedCredits());
      row.put("creditsAdded", value.creditsAdded());
      row.put("orders", value.orders());
      if (value.orderAmountMinor() == null) row.putNull("orderAmountMinor");
      else row.put("orderAmountMinor", value.orderAmountMinor());
      row.put("unpricedOrders", value.unpricedOrders());
    }
    var models = result.putArray("models");
    for (var value : snapshot.models()) {
      var row = models.addObject();
      row.put("modelId", value.modelId());
      row.put("calls", value.calls());
      row.put("succeededCalls", value.succeededCalls());
      row.put("failedCalls", value.failedCalls());
      row.put("unresolvedCalls", value.unresolvedCalls());
      row.put("chargedCredits", value.chargedCredits());
    }
    return result;
  }

  private static long longField(JsonNode node, String field, long minimum, long maximum) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    long number = value.longValue();
    if (number < minimum || number > maximum)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return number;
  }

  private static Long nullableLongField(JsonNode node, String field, long minimum, long maximum) {
    JsonNode value = node.get(field);
    return value != null && value.isNull() ? null : longField(node, field, minimum, maximum);
  }

  private static boolean booleanField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean())
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return value.booleanValue();
  }

  private static String bearer(HttpExchange exchange) {
    var values = exchange.getRequestHeaders().get("Authorization");
    if (values == null || values.size() != 1 || !values.getFirst().startsWith("Bearer "))
      throw new GatewayException(GatewayException.Code.UNAUTHENTICATED);
    return values.getFirst().substring(7);
  }

  private GradeIngressPermit acquireGradeIngress(UUID userId) {
    synchronized (gradeIngressUsers) {
      int current = gradeIngressUsers.getOrDefault(userId, 0);
      if (current >= ACCOUNT_GRADE_INGRESS_LIMIT)
        throw new GatewayException(GatewayException.Code.GRADE_INGRESS_BUSY);
      gradeIngressUsers.put(userId, current + 1);
    }
    return new GradeIngressPermit(userId);
  }

  private BodyReadDeadline startGradeBodyDeadline(HttpExchange exchange) {
    var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var expired = new java.util.concurrent.atomic.AtomicBoolean();
    ScheduledFuture<?> future =
        bodyDeadlines.schedule(
            () -> {
              if (armed.compareAndSet(true, false)) {
                expired.set(true);
                exchange.close();
              }
            },
            gradeBodyReadTimeout.toMillis(),
            TimeUnit.MILLISECONDS);
    return new BodyReadDeadline(future, armed, expired);
  }

  private static JsonNode body(HttpExchange exchange, int limit) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null
        || !type.toLowerCase(java.util.Locale.ROOT)
            .matches("application/json(?:;\\s*charset=utf-8)?"))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length) < 0 || Long.parseLong(length) > limit)
          throw new IllegalArgumentException();
      } catch (Exception invalid) {
        throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
      }
    }
    byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
    if (bytes.length > limit) throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return GatewayJson.parse(bytes);
  }

  private static void requireEmpty(HttpExchange exchange) throws IOException {
    if (exchange.getRequestBody().read() != -1)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
  }

  private static void secureHeaders(HttpExchange exchange) {
    var h = exchange.getResponseHeaders();
    h.set("Cache-Control", "no-store");
    h.set("X-Content-Type-Options", "nosniff");
    h.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'");
    h.set("X-Frame-Options", "DENY");
    h.set("Cross-Origin-Resource-Policy", "same-origin");
    h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    h.set("Referrer-Policy", "no-referrer");
  }

  private static void staticAdmin(HttpExchange exchange, String path) throws IOException {
    String resource =
        path.equals("/admin") || path.equals("/admin/")
            ? "index.html"
            : path.substring("/admin/".length());
    try (var input = GatewayServer.class.getResourceAsStream("/admin/" + resource)) {
      if (input == null) throw new GatewayException(GatewayException.Code.NOT_FOUND);
      byte[] bytes = input.readNBytes(256 * 1024 + 1);
      if (bytes.length > 256 * 1024)
        throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
      exchange
          .getResponseHeaders()
          .set(
              "Content-Type",
              resource.endsWith(".js")
                  ? "text/javascript; charset=utf-8"
                  : resource.endsWith(".css")
                      ? "text/css; charset=utf-8"
                      : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static void send(HttpExchange exchange, int status, JsonNode node) throws IOException {
    byte[] bytes = GatewayJson.MAPPER.writeValueAsBytes(node);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /** Released clients reject unknown JSON fields, so diagnostics require explicit opt-in. */
  private static void sendModelReply(HttpExchange exchange, int status, ObjectNode node)
      throws IOException {
    var versions = exchange.getRequestHeaders().get("X-DeskGrade-Diagnostics");
    if (versions == null || versions.size() != 1 || !"1".equals(versions.getFirst())) {
      node = node.deepCopy();
      node.remove("diagnosticCode");
      if (node.get("error") instanceof ObjectNode error) error.remove("diagnosticCode");
    }
    send(exchange, status, node);
  }

  private static void sendUnknown(HttpExchange exchange, UUID requestId, String code)
      throws IOException {
    var uncertain = error(code);
    uncertain.put("requestId", requestId.toString());
    uncertain.putNull("chargedCredits");
    uncertain.put("replayed", false);
    send(exchange, 503, uncertain);
  }

  private static String publicCode(GatewayException.Code code) {
    return switch (code) {
      case UNAUTHENTICATED -> "AUTH_REQUIRED";
      // USERNAME_UNAVAILABLE is an internal storage decision. Keep the defensive mapping even
      // though the registration route consumes it before a response is written.
      case USERNAME_UNAVAILABLE -> "INVALID_CREDENTIALS";
      case INVALID_REQUEST -> "INVALID_REQUEST";
      case INSUFFICIENT_QUOTA -> "INSUFFICIENT_CREDITS";
      case REQUEST_UNKNOWN -> "DISPATCH_UNKNOWN";
      case STORAGE_FAILURE -> "STORE_UNAVAILABLE";
      case SERVICE_UNAVAILABLE -> "MODEL_UNAVAILABLE";
      case MODEL_CAPACITY_EXHAUSTED -> "RATE_LIMITED";
      case GRADE_INGRESS_BUSY, ACCOUNT_GRADE_IN_PROGRESS -> "REQUEST_IN_PROGRESS";
      default -> code.name();
    };
  }

  private static int httpStatus(GatewayException.Code code) {
    return switch (code) {
      case UNAUTHENTICATED, INVALID_CREDENTIALS, USERNAME_UNAVAILABLE -> 401;
      case FORBIDDEN -> 403;
      case NOT_FOUND -> 404;
      case RATE_LIMITED, MODEL_CAPACITY_EXHAUSTED -> 429;
      case INSUFFICIENT_QUOTA -> 402;
      case IDEMPOTENCY_CONFLICT,
              DUPLICATE_SUBMISSION,
              REQUEST_IN_PROGRESS,
              REQUEST_UNKNOWN,
              MODEL_POLICY_CHANGED,
              GRADE_INGRESS_BUSY,
              ACCOUNT_GRADE_IN_PROGRESS ->
          409;
      case STORAGE_FAILURE, SERVICE_UNAVAILABLE, MODEL_UNAVAILABLE -> 503;
      case INTERNAL_ERROR -> 500;
      default -> 400;
    };
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    server.stop(1);
    gradeExchanges.forEach(HttpExchange::close);
    gradeExchanges.clear();
    bodyDeadlines.shutdownNow();
    gradeExecutor.shutdownNow();
    executor.shutdownNow();
    try {
      gradeExecutor.awaitTermination(5, TimeUnit.SECONDS);
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private final class GradeIngressPermit implements AutoCloseable {
    private final UUID userId;
    private boolean released;

    private GradeIngressPermit(UUID userId) {
      this.userId = userId;
    }

    @Override
    public void close() {
      synchronized (gradeIngressUsers) {
        if (released) return;
        released = true;
        int remaining = gradeIngressUsers.getOrDefault(userId, 0) - 1;
        if (remaining <= 0) gradeIngressUsers.remove(userId);
        else gradeIngressUsers.put(userId, remaining);
      }
    }
  }

  private static final class BodyReadDeadline implements AutoCloseable {
    private final ScheduledFuture<?> future;
    private final java.util.concurrent.atomic.AtomicBoolean armed;
    private final java.util.concurrent.atomic.AtomicBoolean expired;

    private BodyReadDeadline(
        ScheduledFuture<?> future,
        java.util.concurrent.atomic.AtomicBoolean armed,
        java.util.concurrent.atomic.AtomicBoolean expired) {
      this.future = future;
      this.armed = armed;
      this.expired = expired;
    }

    private boolean expired() {
      return expired.get();
    }

    @Override
    public void close() {
      if (armed.compareAndSet(true, false)) future.cancel(false);
    }
  }
}
