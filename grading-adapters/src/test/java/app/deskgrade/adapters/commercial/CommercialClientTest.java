package app.deskgrade.adapters.commercial;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CommercialClientTest {
  private static final String SYNTHETIC_TOKEN = "synthetic-session-token-no-provider-key";
  private static final String PASSWORD = "synthetic-password-123";
  private static final Clock CLOCK = Clock.systemUTC();

  @Test
  void cachedTokenRequiresFreshAccountVerificationBeforeItCanAuthorizeAnything() throws Exception {
    var secrets = new SessionSecrets();
    var cache = new CommercialSessionCache(secrets);
    try (var server = new Fixture();
        var first = server.client()) {
      await(first.login("alice", PASSWORD.toCharArray()));
      first.saveSession(cache);
      assertTrue(secrets.present());
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      server.override =
          req -> {
            if (req.path().equals("/v1/account")) {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
              }
            }
            return null;
          };
      try (var restored = server.client()) {
        var pending = restored.restoreSession(cache);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(restored.isAuthenticated());
        assertThrows(GatewayException.class, restored::models);
        release.countDown();
        assertEquals("alice", await(pending).orElseThrow().username());
        assertTrue(restored.isAuthenticated());
        assertEquals(0, server.gradeCalls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void logoutDuringCachedSessionVerificationDiscardsLateAccount() throws Exception {
    var cache = new CommercialSessionCache(new SessionSecrets());
    try (var server = new Fixture();
        var first = server.client()) {
      await(first.login("alice", PASSWORD.toCharArray()));
      first.saveSession(cache);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      server.override =
          req -> {
            if (req.path().equals("/v1/account")) {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
              }
            }
            return null;
          };
      try (var restored = server.client()) {
        var pending = restored.restoreSession(cache);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        await(restored.logout());
        release.countDown();
        assertThrows(Exception.class, () -> await(pending));
        assertFalse(restored.isAuthenticated());
      } finally {
        release.countDown();
      }
    }
  }

  private static final class SessionSecrets implements app.deskgrade.core.port.SecretStore {
    private char[] value;

    public synchronized void put(String id, char[] secret) {
      value = secret.clone();
    }

    public synchronized java.util.Optional<char[]> read(String id) {
      return value == null ? java.util.Optional.empty() : java.util.Optional.of(value.clone());
    }

    public synchronized void delete(String id) {
      if (value != null) Arrays.fill(value, '\0');
      value = null;
    }

    synchronized boolean present() {
      return value != null;
    }
  }

  @Test
  void rejectsNonHttpsRemoteAliasesCredentialsPathQueryAndFragment() {
    for (String value :
        List.of(
            "http://example.com",
            "http://127.0.0.2",
            "http://localhost.evil.test",
            "http://127.1",
            "http://2130706433",
            "https://user:secret@example.com",
            "https://example.com?key=x",
            "https://example.com#secret",
            "https://example.com/v1",
            "https://example.com/%2e%2e/")) {
      assertThrows(
          IllegalArgumentException.class, () -> new CommercialClient(URI.create(value), CLOCK));
    }
    for (String value :
        List.of(
            "https://example.com",
            "http://localhost:1234/",
            "http://127.0.0.1:1234",
            "http://[::1]:1234")) {
      try (var ignored = new CommercialClient(URI.create(value), CLOCK)) {
        assertNotNull(ignored);
      }
    }
  }

  @Test
  void accountPackagesOrdersHaveNoTokenOrInventedPaymentAndPasswordCanBeErasedImmediately()
      throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      char[] password = PASSWORD.toCharArray();
      var login = client.register("alice", password);
      Arrays.fill(password, '\0');
      var session = await(login);
      assertEquals("alice", session.username());
      assertEquals(10, session.credits());
      assertFalse(session.toString().contains(SYNTHETIC_TOKEN));
      assertFalse(client.toString().contains(SYNTHETIC_TOKEN));
      assertTrue(client.isCurrentSession(client.sessionGeneration()));
      assertEquals(PASSWORD, server.requests.getFirst().json().path("password").asText());
      assertEquals(10, await(client.me()).credits());
      var packages = await(client.packages());
      assertEquals(1, packages.size());
      assertNull(packages.getFirst().price());
      assertFalse(packages.getFirst().paymentEnabled());
      UUID requestId = UUID.randomUUID();
      var order = await(client.createOrder(requestId, "pack100"));
      assertEquals(requestId, order.requestId());
      assertEquals("PENDING_PAYMENT", order.status());
      assertNull(order.price());
      assertFalse(order.paymentEnabled());
      assertEquals(List.of(order), await(client.orders()));
      assertEquals(10, client.currentSession().orElseThrow().credits());
      await(client.logout());
      assertFalse(client.isAuthenticated());
      assertEquals(0, server.requests.getLast().body().length);
      assertThrows(GatewayException.class, client::me);
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void rubricGenerationSendsOnlyRequiredInputsAndAcceptsExactStructuredPoints() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      UUID requestId = UUID.randomUUID();
      var generated =
          await(client.generateRubric(requestId, "历史", "这几种形势，都利于我方坚守。", new BigDecimal("2")));
      assertEquals(requestId, generated.requestId());
      assertEquals(new BigDecimal("1"), generated.scoreStep());
      assertEquals(
          List.of("P1", "P2"), generated.criteria().stream().map(ScoringCriterion::id).toList());
      assertEquals(9, generated.creditsRemaining());
      Request request =
          server.requests.stream()
              .filter(item -> item.path().equals("/v1/rubrics/generate"))
              .findFirst()
              .orElseThrow();
      assertEquals(
          Set.of("requestId", "subject", "referenceAnswer", "maxScore"),
          fieldNames(request.json()));
      assertEquals("历史", request.json().path("subject").asText());
      assertFalse(request.json().has("profileId"));
      assertFalse(request.json().has("prompt"));
    }
  }

  @Test
  void rubricSubjectRejectsEveryIsoControlBeforeDispatchWhileReferenceMayContainNewlines()
      throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      long before =
          server.requests.stream()
              .filter(item -> item.path().equals("/v1/rubrics/generate"))
              .count();
      for (String subject : List.of("历史\n材料题", "历史\t材料题", "历史\u0085材料题"))
        assertThrows(
            IllegalArgumentException.class,
            () -> client.generateRubric(UUID.randomUUID(), subject, "标准答案", new BigDecimal("2")));
      assertEquals(
          before,
          server.requests.stream()
              .filter(item -> item.path().equals("/v1/rubrics/generate"))
              .count());

      var generated =
          await(client.generateRubric(UUID.randomUUID(), "历史", "第一行\n第二行", new BigDecimal("2")));
      assertEquals(2, generated.criteria().size());
      Request sent =
          server.requests.stream()
              .filter(item -> item.path().equals("/v1/rubrics/generate"))
              .reduce((first, second) -> second)
              .orElseThrow();
      assertEquals("第一行\n第二行", sent.json().path("referenceAnswer").asText());
    }
  }

  @Test
  void onlyServerReturnedVerifiedModelsCanBindAndSourceUsageRemainTruthful() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      assertCode(
          GatewayException.Code.MODEL_UNAVAILABLE,
          assertThrows(
              GatewayException.class,
              () -> new GatewayGradingProvider(client, rubric(), "fixture-model")));
      assertTrue(await(client.models()).getFirst().isVerified());
      try (var provider = new GatewayGradingProvider(client, rubric(), "fixture-model")) {
        UUID id = UUID.randomUUID();
        var result = await(provider.grade(request(id), CancellationToken.none()));
        assertEquals(id, result.requestId());
        assertEquals(GradingResponse.Source.MOCK, result.source());
        assertEquals(GradeResult.Status.SCORED, result.result().status());
        assertNull(result.usage());
        assertEquals(Duration.ofMillis(20), result.elapsed());
        assertEquals(9, client.currentSession().orElseThrow().credits());
        Request sent =
            server.requests.stream()
                .filter(r -> r.path().equals("/v1/grade"))
                .findFirst()
                .orElseThrow();
        assertEquals(
            Set.of("requestId", "profileId", "policyRevision", "rubric", "imageBase64"),
            fieldNames(sent.json()));
        assertEquals(1, sent.json().path("policyRevision").asLong());
        assertEquals("fixture-model", sent.json().path("profileId").asText());
        assertTrue(
            Arrays.equals(
                png(),
                java.util.Base64.getDecoder().decode(sent.json().path("imageBase64").asText())));
        assertFalse(new String(sent.body(), StandardCharsets.UTF_8).contains("credentialRef"));
        assertEquals(1, server.gradeCalls.get());
      }
    }
  }

  @Test
  void alternateModelIsServerBoundHiddenFromUserSelectionAndCarriesPrimaryReceipt()
      throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      ProviderProfile alternate = profile("alternate-model", "alternate-vision", true);
      server.override =
          req ->
              switch (req.path()) {
                case "/v1/model-policy" ->
                    Reply.ok(
                        "{\"revision\":1,\"profileId\":\"fixture-model\",\"modelId\":\"fixture-vision\","
                            + "\"fallbackProfileId\":\"alternate-model\",\"fallbackModelId\":\"alternate-vision\"}");
                case "/v1/models" ->
                    Reply.ok(
                        catalog(
                            publicProfile(profile(true), PublicModelProfile.Role.PRIMARY),
                            publicProfile(alternate, PublicModelProfile.Role.FALLBACK)));
                default -> null;
              };
      await(client.login("alice", PASSWORD.toCharArray()));
      assertEquals(
          List.of(publicProfile(profile(true), PublicModelProfile.Role.PRIMARY)),
          await(client.models()));
      try (var provider = new GatewayGradingProvider(client, rubric(), "fixture-model")) {
        assertEquals(List.of("alternate-model"), provider.fallbackProfileId().stream().toList());
        UUID primaryId = UUID.randomUUID();
        UUID fallbackId = FallbackGradingProvider.fallbackRequestId(primaryId);
        var response =
            await(provider.gradeFallback(primaryId, request(fallbackId), CancellationToken.none()));
        assertEquals(fallbackId, response.requestId());
        Request sent =
            server.requests.stream()
                .filter(r -> r.path().equals("/v1/grade"))
                .findFirst()
                .orElseThrow();
        assertEquals(
            Set.of(
                "requestId",
                "profileId",
                "policyRevision",
                "fallbackOfRequestId",
                "rubric",
                "imageBase64"),
            fieldNames(sent.json()));
        assertEquals(primaryId.toString(), sent.json().path("fallbackOfRequestId").asText());
        assertEquals("alternate-model", sent.json().path("profileId").asText());
      }
    }
  }

  @Test
  void unverifiedOrTamperedCatalogCannotCreateProvider() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      server.override =
          req ->
              req.path().equals("/v1/models")
                  ? Reply.ok(
                      catalog(publicProfile(profile(false), PublicModelProfile.Role.PRIMARY)))
                  : null;
      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.models()));
      assertCode(
          GatewayException.Code.MODEL_UNAVAILABLE,
          assertThrows(
              GatewayException.class,
              () -> new GatewayGradingProvider(client, rubric(), "fixture-model")));
      server.override =
          req ->
              req.path().equals("/v1/models")
                  ? Reply.ok(
                      catalog(publicProfile(profile(true), PublicModelProfile.Role.PRIMARY))
                          .replace("fixture-vision", "other-vision"))
                  : null;
      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.models()));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void publicModelCatalogRejectsUnknownOrMissingFields() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      ObjectNode unknown =
          (ObjectNode)
              CommercialJson.MAPPER.readTree(
                  catalog(publicProfile(profile(true), PublicModelProfile.Role.PRIMARY)));
      ((ObjectNode) unknown.path("models").get(0))
          .put("endpoint", "https://supplier.example/v1/chat/completions");
      server.override =
          req -> req.path().equals("/v1/models") ? Reply.ok(unknown.toString()) : null;
      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.models()));

      ObjectNode missing =
          (ObjectNode)
              CommercialJson.MAPPER.readTree(
                  catalog(publicProfile(profile(true), PublicModelProfile.Role.PRIMARY)));
      ((ObjectNode) missing.path("models").get(0)).remove("requestTimeoutMs");
      server.override =
          req -> req.path().equals("/v1/models") ? Reply.ok(missing.toString()) : null;
      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.models()));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void administratorPolicyChangeBeforeRequestStopsOldProviderWithoutDispatch() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      assertEquals(1, client.currentModelPolicy().orElseThrow().revision());
      assertThrows(
          GatewayException.class,
          () -> new GatewayGradingProvider(client, rubric(), "user-choice"));
      server.policyRevision = 2;
      assertCode(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none())));
      assertEquals(0, server.gradeCalls.get());
      assertTrue(
          client.currentModelPolicy().isEmpty(),
          "Observed new policy cannot authorize stale catalog");
      await(client.models());
      assertEquals(2, client.currentModelPolicy().orElseThrow().revision());
      assertCode(
          GatewayException.Code.MODEL_POLICY_CHANGED,
          failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none())));
      assertEquals(
          0,
          server.gradeCalls.get(),
          "Refreshing must not make an old provider borrow the new assignment");
      try (var fresh = new GatewayGradingProvider(client, rubric(), "fixture-model")) {
        await(fresh.grade(request(UUID.randomUUID()), CancellationToken.none()));
        assertEquals(1, server.gradeCalls.get());
        assertEquals(
            2,
            server.requests.stream()
                .filter(r -> r.path().equals("/v1/grade"))
                .findFirst()
                .orElseThrow()
                .json()
                .path("policyRevision")
                .asLong());
      }
    }
  }

  @Test
  void policyChangedWhileModelWasRunningRejectsLateScoreAndRetainsCharge() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      server.override =
          req -> {
            if (!req.path().equals("/v1/grade")) return null;
            server.policyRevision = 2;
            return server.gradeReply(req);
          };
      var error = failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
      assertCode(GatewayException.Code.MODEL_POLICY_CHANGED, error);
      assertEquals(1, error.chargedCredits());
      assertEquals(1, server.gradeCalls.get());
    }
  }

  @Test
  void policyChangingDuringCatalogReadNeverInstallsMixedAssignment() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      server.override =
          req -> {
            if (req.path().equals("/v1/models")) server.policyRevision = 2;
            return null;
          };
      assertCode(GatewayException.Code.MODEL_POLICY_CHANGED, failure(client.models()));
      assertTrue(client.currentModelPolicy().isEmpty());
      assertThrows(
          GatewayException.class,
          () -> new GatewayGradingProvider(client, rubric(), "fixture-model"));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void unassignedPolicyLoadsNoModelAndCannotBindAnyProvider() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      server.override =
          req ->
              switch (req.path()) {
                case "/v1/model-policy" ->
                    Reply.ok("{\"revision\":0,\"profileId\":null,\"modelId\":null}");
                case "/v1/models" -> Reply.ok(catalog());
                default -> null;
              };
      assertTrue(await(client.models()).isEmpty());
      assertEquals(0, client.currentModelPolicy().orElseThrow().revision());
      assertThrows(
          GatewayException.class,
          () -> new GatewayGradingProvider(client, rubric(), "fixture-model"));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void logoutRejectsLateGradeAndOldProviderNeverUsesNewAccountToken() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      var provider = provider(server, client);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      server.override =
          req -> {
            if (!req.path().equals("/v1/grade")) return null;
            entered.countDown();
            boundedWait(release);
            return server.gradeReply(req);
          };
      var pending = provider.grade(request(UUID.randomUUID()), CancellationToken.none());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      long oldGeneration = client.sessionGeneration();
      var logout = client.logout();
      assertFalse(client.isAuthenticated());
      assertFalse(client.isCurrentSession(oldGeneration));
      assertCode(GatewayException.Code.SESSION_CHANGED, failure(pending));
      await(client.login("bravo", PASSWORD.toCharArray()));
      await(client.models());
      assertCode(
          GatewayException.Code.SESSION_CHANGED,
          failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none())));
      release.countDown();
      await(logout);
      assertEquals("bravo", client.currentSession().orElseThrow().username());
      assertEquals(1, server.gradeCalls.get());
      provider.close();
    }
  }

  @Test
  void newerLoginWinsOverOlderLateLoginWithoutExposingSession() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      server.override =
          req -> {
            if (req.path().equals("/v1/auth/login")
                && req.json().path("username").asText().equals("alice")) {
              entered.countDown();
              boundedWait(release);
              return server.auth(req);
            }
            return null;
          };
      var first = client.login("alice", PASSWORD.toCharArray());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var second = await(client.login("bravo", PASSWORD.toCharArray()));
      assertEquals("bravo", second.username());
      assertCode(GatewayException.Code.SESSION_CHANGED, failure(first));
      release.countDown();
      assertEquals("bravo", client.currentSession().orElseThrow().username());
    }
  }

  @Test
  void rejectionCodesHideRawBodyPreserveUnknownChargeAndNeverRetry() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      for (var code :
          List.of(
              "AUTH_REQUIRED",
              "FORBIDDEN",
              "NOT_FOUND",
              "INSUFFICIENT_CREDITS",
              "RATE_LIMITED",
              "MODEL_FAILED",
              "STORE_UNAVAILABLE",
              "DISPATCH_UNKNOWN")) {
        int status =
            switch (code) {
              case "AUTH_REQUIRED" -> 401;
              case "FORBIDDEN" -> 403;
              case "NOT_FOUND" -> 404;
              case "INSUFFICIENT_CREDITS" -> 402;
              case "RATE_LIMITED" -> 429;
              default -> 503;
            };
        server.override =
            req ->
                req.path().equals("/v1/grade")
                    ? new Reply(
                        status,
                        "{\"error\":{\"code\":\""
                            + code
                            + "\",\"message\":\"RAW_SECRET_MUST_NOT_ESCAPE\"},"
                            + "\"requestId\":\""
                            + req.json().path("requestId").asText()
                            + "\","
                            + "\"creditsRemaining\":3,\"chargedCredits\":null,\"replayed\":false}",
                        null)
                    : null;
        GatewayException failure =
            failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
        assertEquals(code, failure.code().name());
        assertEquals("GATEWAY_" + code, failure.diagnosticCode());
        assertNull(failure.chargedCredits());
        assertEquals(3L, failure.creditsRemaining());
        assertFalse(failure.toString().contains("RAW_SECRET"));
        assertNull(failure.getCause());
      }
      assertEquals(8, server.gradeCalls.get());
    }
  }

  @Test
  void upstreamDiagnosticsArePreservedWithoutChangingUnknownDispatchOrAutomaticRetry()
      throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      for (String diagnostic :
          List.of(
              "MODEL_CONNECT_TIMEOUT",
              "MODEL_REQUEST_TIMEOUT",
              "MODEL_DNS_FAILURE",
              "MODEL_TLS_HANDSHAKE_FAILED",
              "GATEWAY_WAIT_TIMEOUT")) {
        server.override =
            req ->
                req.path().equals("/v1/grade")
                    ? new Reply(
                        503,
                        "{\"error\":{\"code\":\"DISPATCH_UNKNOWN\",\"message\":\"RAW_SECRET_MUST_NOT_ESCAPE\",\"diagnosticCode\":\""
                            + diagnostic
                            + "\"},"
                            + "\"requestId\":\""
                            + req.json().path("requestId").asText()
                            + "\",\"creditsRemaining\":9,\"chargedCredits\":1,\"replayed\":false}",
                        null)
                    : null;
        var failure = failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
        assertEquals(GatewayException.Code.DISPATCH_UNKNOWN, failure.code());
        assertEquals("1", server.requests.getLast().diagnostics());
        assertEquals(diagnostic, failure.diagnosticCode());
        assertEquals(1, failure.chargedCredits());
        assertFalse(failure.fallbackEligible());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("RAW_SECRET"));
      }
      assertEquals(5, server.gradeCalls.get());
    }
  }

  @Test
  void forgedOrNonStringUpstreamDiagnosticsAreRejectedInsteadOfDisplayed() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      for (String diagnostic :
          List.of(
              "\"MODEL_UNKNOWN_BUT_REGEX_SAFE\"",
              "\"GATEWAY_FORGED\"",
              "\"secret-private-answer\"",
              "123",
              "{}")) {
        server.override =
            req ->
                req.path().equals("/v1/grade")
                    ? new Reply(
                        503,
                        "{\"error\":{\"code\":\"DISPATCH_UNKNOWN\",\"message\":\"Unavailable\",\"diagnosticCode\":"
                            + diagnostic
                            + "},\"chargedCredits\":1}",
                        null)
                    : null;
        var failure = failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
        assertCode(GatewayException.Code.INVALID_RESPONSE, failure);
        assertEquals("GATEWAY_INVALID_RESPONSE", failure.diagnosticCode());
        assertFalse(failure.toString().contains("private-answer"));
      }
      assertEquals(5, server.gradeCalls.get());
    }
  }

  @Test
  void validatesRequestIdExactScoreContractUsageAndUnableWithoutDefaultZero() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      server.override =
          req -> {
            if (!req.path().equals("/v1/grade")) return null;
            ObjectNode json = server.gradeJson(req);
            json.put("source", "LIVE");
            var usage = json.putObject("usage");
            usage.put("inputTokens", 12);
            usage.putNull("estimatedCost");
            return Reply.ok(json.toString());
          };
      var result = await(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
      assertEquals(GradingResponse.Source.LIVE, result.source());
      assertEquals(12L, result.usage().inputTokens());
      assertNull(result.usage().outputTokens());
      assertNull(result.usage().estimatedCost());
      List<java.util.function.Consumer<ObjectNode>> changes =
          List.of(
              json -> json.put("requestId", UUID.randomUUID().toString()),
              json -> json.put("source", "MODEL_SAYS_LIVE"),
              json -> json.put("chargedCredits", 0),
              json -> json.put("unknown", true),
              json -> ((ObjectNode) json.path("result")).put("total", 1),
              json ->
                  ((ObjectNode) json.path("result").path("criteria").get(0)).put("awarded", 0.5),
              json -> json.putObject("usage").put("inputTokens", -1));
      for (var change : changes) {
        server.override =
            req -> {
              if (!req.path().equals("/v1/grade")) return null;
              var json = server.gradeJson(req);
              change.accept(json);
              return Reply.ok(json.toString());
            };
        assertCode(
            GatewayException.Code.INVALID_RESPONSE,
            failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none())));
      }
      server.override =
          req -> {
            if (!req.path().equals("/v1/grade")) return null;
            var json = server.gradeJson(req);
            var grade = (ObjectNode) json.path("result");
            grade.put("status", "UNABLE").put("readability", "UNREADABLE").putNull("studentText");
            ((ObjectNode) grade.path("criteria").get(0)).putNull("awarded");
            grade.putArray("issues").add("合成图片无法辨认");
            return Reply.ok(json.toString());
          };
      var unable = await(provider.grade(request(UUID.randomUUID()), CancellationToken.none()));
      assertEquals(GradeResult.Status.UNABLE, unable.result().status());
      assertNull(unable.result().criteria().getFirst().awarded());
    }
  }

  @Test
  void rejectsInvalidOversizeTruncatedAndOversizedDimensionPngBeforeAnyHttp() throws Exception {
    try (var server = new Fixture();
        var client = server.client();
        var provider = provider(server, client)) {
      byte[] valid = png();
      byte[] corrupt = valid.clone();
      corrupt[20] ^= 1;
      byte[] largeDimension = valid.clone();
      java.nio.ByteBuffer.wrap(largeDimension, 16, 8).putInt(100_000).putInt(100_000);
      var crc = new java.util.zip.CRC32();
      crc.update(largeDimension, 12, 17);
      java.nio.ByteBuffer.wrap(largeDimension, 29, 4).putInt((int) crc.getValue());
      for (byte[] image :
          List.of(
              new byte[] {1},
              new byte[10 * 1024 * 1024 + 1],
              corrupt,
              Arrays.copyOf(valid, valid.length - 1),
              largeDimension,
              Arrays.copyOf(valid, valid.length + 1))) {
        assertCode(
            GatewayException.Code.INVALID_REQUEST,
            failure(
                provider.grade(
                    new GradingRequest(UUID.randomUUID(), rubric().question(), image),
                    CancellationToken.none())));
      }
      var other =
          new QuestionDefinition(
              "different", 1, "2+2", "4", BigDecimal.ONE, rubric().question().criteria());
      assertCode(
          GatewayException.Code.INVALID_REQUEST,
          failure(
              provider.grade(
                  new GradingRequest(UUID.randomUUID(), other, valid), CancellationToken.none())));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void pureStatusLookupDoesNotPostGradeAndPreservesDispatchUnknown() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      UUID id = UUID.randomUUID();
      server.override =
          req ->
              req.path().equals("/v1/grades/" + id)
                  ? Reply.ok(
                      "{\"requestId\":\""
                          + id
                          + "\",\"state\":\"DISPATCH_UNKNOWN\",\"creditsRemaining\":9,\"chargedCredits\":1,\"replayed\":true}")
                  : null;
      var receipt = await(client.gradeStatus(id));
      assertEquals(CommercialClient.GradeState.DISPATCH_UNKNOWN, receipt.state());
      assertNull(receipt.diagnosticCode());
      assertEquals(1, receipt.chargedCredits());
      assertEquals("GET", server.requests.getLast().method());
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void statusLookupAcceptsAllowlistedDiagnosticButRejectsForgedCode() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      UUID id = UUID.randomUUID();
      for (String diagnostic :
          List.of("MODEL_REQUEST_TIMEOUT", "GATEWAY_STORAGE_FAILURE", "GATEWAY_UNEXPECTED_ERROR")) {
        server.override =
            req ->
                req.path().equals("/v1/grades/" + id)
                    ? Reply.ok(
                        "{\"requestId\":\""
                            + id
                            + "\",\"state\":\"DISPATCH_UNKNOWN\",\"creditsRemaining\":9,\"chargedCredits\":1,\"replayed\":true,\"diagnosticCode\":\""
                            + diagnostic
                            + "\"}")
                    : null;
        assertEquals(diagnostic, await(client.gradeStatus(id)).diagnosticCode());
      }
      server.override =
          req ->
              req.path().equals("/v1/grades/" + id)
                  ? Reply.ok(
                      "{\"requestId\":\""
                          + id
                          + "\",\"state\":\"DISPATCH_UNKNOWN\",\"creditsRemaining\":9,\"chargedCredits\":1,\"replayed\":true,\"diagnosticCode\":\"MODEL_ARBITRARY_TEXT\"}")
                  : null;
      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.gradeStatus(id)));
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void rejectsDuplicateKeysTruncatedJsonUnknownFieldsAndResponseOverOneMiB() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      for (String body :
          List.of(
              "{\"id\":\"a\",\"id\":\"b\"}",
              "{",
              "{} {}",
              "{\"id\":\"a\",\"username\":\"alice\",\"role\":\"USER\",\"credits\":10,\"token\":\"leak\"}",
              " ".repeat(CommercialJson.MAX_RESPONSE + 1))) {
        server.override = req -> req.path().equals("/v1/account") ? Reply.ok(body) : null;
        assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.me()));
      }
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void recordJsonReadsARecordAboveOneMiBThroughTheAuthenticatedJournalDecoder() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      String studentText = "x".repeat(CommercialJson.MAX_RESPONSE + 32) + "学生回答末尾";
      String recordJson =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("schemaVersion", 2)
              .put("studentText", studentText)
              .put("javaValidatedTotal", 5)
              .toString();
      String reply = CommercialJson.MAPPER.createObjectNode().put("result", recordJson).toString();
      assertTrue(reply.getBytes(StandardCharsets.UTF_8).length > CommercialJson.MAX_RESPONSE);
      assertTrue(
          reply.getBytes(StandardCharsets.UTF_8).length < CommercialJson.MAX_RECORD_RESPONSE);
      server.override = req -> req.path().equals("/v1/journal") ? Reply.ok(reply) : null;
      var command =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("operation", "recordJson")
              .put("attemptId", UUID.randomUUID().toString());

      JsonNode returned = await(client.journalCommand(command, client.sessionGeneration()));

      assertTrue(returned.isTextual());
      assertEquals(recordJson, returned.textValue());
      assertEquals(
          studentText,
          CommercialJson.MAPPER.readTree(returned.textValue()).path("studentText").textValue());
      assertEquals("POST", server.requests.getLast().method());
      assertEquals(
          "Bearer " + SYNTHETIC_TOKEN + "-alice", server.requests.getLast().authorization());
      assertEquals("recordJson", server.requests.getLast().json().path("operation").textValue());
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void ordinaryApiAndOtherJournalOperationsStillRejectValidJsonAboveOneMiB() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      String account = server.account("alice").toString();
      String oversizedAccount =
          account
              + " "
                  .repeat(
                      CommercialJson.MAX_RESPONSE
                          - account.getBytes(StandardCharsets.UTF_8).length
                          + 1);
      String oversizedJournal =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("result", "x".repeat(CommercialJson.MAX_RESPONSE))
              .toString();
      server.override =
          req -> {
            if (req.path().equals("/v1/account")) return Reply.ok(oversizedAccount);
            if (req.path().equals("/v1/journal")) return Reply.ok(oversizedJournal);
            return null;
          };

      assertCode(GatewayException.Code.INVALID_RESPONSE, failure(client.me()));
      var command = CommercialJson.MAPPER.createObjectNode().put("operation", "recentRecords");
      assertCode(
          GatewayException.Code.INVALID_RESPONSE,
          failure(client.journalCommand(command, client.sessionGeneration())));
      assertTrue(client.isAuthenticated());
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void recordJsonAllowsExactlyEightMiBButRejectsOneExtraResponseByte() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      await(client.login("alice", PASSWORD.toCharArray()));
      String emptyReply = CommercialJson.MAPPER.createObjectNode().put("result", "").toString();
      String contents =
          "x"
              .repeat(
                  CommercialJson.MAX_RECORD_RESPONSE
                      - emptyReply.getBytes(StandardCharsets.UTF_8).length);
      String boundaryReply =
          CommercialJson.MAPPER.createObjectNode().put("result", contents).toString();
      assertEquals(
          CommercialJson.MAX_RECORD_RESPONSE,
          boundaryReply.getBytes(StandardCharsets.UTF_8).length);
      server.override = req -> req.path().equals("/v1/journal") ? Reply.ok(boundaryReply) : null;
      var command = CommercialJson.MAPPER.createObjectNode().put("operation", "recordJson");

      assertEquals(
          contents, await(client.journalCommand(command, client.sessionGeneration())).textValue());

      server.override =
          req -> req.path().equals("/v1/journal") ? Reply.ok(boundaryReply + " ") : null;
      assertCode(
          GatewayException.Code.INVALID_RESPONSE,
          failure(client.journalCommand(command, client.sessionGeneration())));
      assertTrue(client.isAuthenticated());
      assertEquals(0, server.gradeCalls.get());
    }
  }

  @Test
  void redirectsAreNeverFollowedAndInvalidLoginResponseDoesNotCreateSession() throws Exception {
    try (var server = new Fixture();
        var destination = new Fixture();
        var client = server.client()) {
      server.override =
          req -> new Reply(307, "", destination.origin().resolve("/stolen").toString());
      assertCode(
          GatewayException.Code.INVALID_RESPONSE,
          failure(client.login("alice", PASSWORD.toCharArray())));
      assertFalse(client.isAuthenticated());
      assertEquals(0, destination.requests.size());
      server.override =
          req -> Reply.ok(server.auth(req).body().replace(SYNTHETIC_TOKEN, "bad\\r\\nheader"));
      assertCode(
          GatewayException.Code.INVALID_RESPONSE,
          failure(client.login("alice", PASSWORD.toCharArray())));
      assertFalse(client.isAuthenticated());
    }
  }

  @Test
  void cancellationTimeoutAndCloseHaveBoundedCompletionWithoutRetry() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      try (var provider = provider(server, client)) {
        assertCode(
            GatewayException.Code.CANCELLED,
            failure(provider.grade(request(UUID.randomUUID()), () -> true)));
        assertEquals(0, server.gradeCalls.get());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.override =
            req -> {
              if (!req.path().equals("/v1/grade")) return null;
              entered.countDown();
              boundedWait(release);
              return server.gradeReply(req);
            };
        AtomicBoolean cancelled = new AtomicBoolean();
        var grade = provider.grade(request(UUID.randomUUID()), cancelled::get);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        cancelled.set(true);
        assertCode(GatewayException.Code.CANCELLED, failure(grade));
        release.countDown();
      }
      server.override = null;
      try (var shortClient =
          new CommercialClient(
              server.origin(), CLOCK, Duration.ofSeconds(1), Duration.ofMillis(500))) {
        await(shortClient.login("alice", PASSWORD.toCharArray()));
        CountDownLatch release = new CountDownLatch(1);
        server.override =
            req -> {
              if (!req.path().equals("/v1/account")) return null;
              boundedWait(release);
              return Reply.ok(server.account("alice").toString());
            };
        assertCode(GatewayException.Code.TIMEOUT, failure(shortClient.me()));
        release.countDown();
      }
      assertEquals(1, server.gradeCalls.get());
    }
  }

  @Test
  void closingProviderCancelsOnlyItsRequestAndClosingClientRevokesSession() throws Exception {
    try (var server = new Fixture();
        var client = server.client()) {
      var provider = provider(server, client);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      server.override =
          req -> {
            if (!req.path().equals("/v1/grade")) return null;
            entered.countDown();
            boundedWait(release);
            return server.gradeReply(req);
          };
      var pending = provider.grade(request(UUID.randomUUID()), CancellationToken.none());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      provider.close();
      assertCode(GatewayException.Code.CANCELLED, failure(pending));
      assertTrue(client.isAuthenticated());
      assertCode(
          GatewayException.Code.CANCELLED,
          failure(provider.grade(request(UUID.randomUUID()), CancellationToken.none())));
      release.countDown();
      client.close();
      assertFalse(client.isAuthenticated());
      assertCode(
          GatewayException.Code.CLIENT_CLOSED,
          failure(client.login("bravo", PASSWORD.toCharArray())));
      assertEquals(1, server.gradeCalls.get());
    }
  }

  private static GatewayGradingProvider provider(Fixture ignored, CommercialClient client)
      throws Exception {
    await(client.login("alice", PASSWORD.toCharArray()));
    await(client.models());
    return new GatewayGradingProvider(client, rubric(), "fixture-model");
  }

  private static <T> T await(CompletionStage<T> future) throws Exception {
    return future.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static GatewayException failure(CompletionStage<?> future) throws Exception {
    var failure = assertThrows(ExecutionException.class, () -> await(future));
    return assertInstanceOf(GatewayException.class, failure.getCause());
  }

  private static void assertCode(GatewayException.Code expected, GatewayException failure) {
    assertEquals(expected, failure.code());
  }

  private static void boundedWait(CountDownLatch latch) {
    try {
      latch.await(4, TimeUnit.SECONDS);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static Set<String> fieldNames(JsonNode json) {
    Set<String> result = new java.util.HashSet<>();
    json.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private static Rubric rubric() {
    return new Rubric(
        new QuestionDefinition(
            "sum",
            1,
            "7+5=?",
            "12",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "answer", "正确答案1分", List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "12得1分；其他得0分。",
        BigDecimal.ONE,
        false,
        false);
  }

  private static ProviderProfile profile(boolean verified) {
    return profile("fixture-model", "fixture-vision", verified);
  }

  private static ProviderProfile profile(String id, String modelId, boolean verified) {
    var profile =
        new ProviderProfile(
            id,
            1,
            "Synthetic fixture only",
            URI.create("http://127.0.0.1/compatible-mode/v1/chat/completions"),
            modelId,
            "server-only",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            Map.of(),
            Set.of(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            null,
            null);
    return verified
        ? profile.withVerification(
            new ProviderProfile.Verification(
                profile.configDigest(), Instant.parse("2026-09-10T00:00:00Z"), true, true))
        : profile;
  }

  private static PublicModelProfile publicProfile(
      ProviderProfile profile, PublicModelProfile.Role role) {
    return new PublicModelProfile(
        profile.id(),
        profile.revision(),
        profile.displayName(),
        profile.modelId(),
        profile.requestTimeout(),
        profile.isVerified(),
        role);
  }

  private static String catalog(PublicModelProfile... profiles) {
    return new PublicModelProfileJsonCodec().write(List.of(profiles)).toString();
  }

  private static GradingRequest request(UUID id) throws Exception {
    return new GradingRequest(id, rubric().question(), png());
  }

  private static byte[] png() throws Exception {
    var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    image.flush();
    return output.toByteArray();
  }

  private record Request(
      String method, String path, byte[] body, String authorization, String diagnostics) {
    JsonNode json() {
      return CommercialJson.parse(body);
    }
  }

  private record Reply(int status, String body, String location) {
    static Reply ok(String body) {
      return new Reply(200, body, null);
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newCachedThreadPool(
            r -> {
              var thread = new Thread(r, "synthetic-gateway-fixture");
              thread.setDaemon(true);
              return thread;
            });
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger gradeCalls = new AtomicInteger();
    private volatile Function<Request, Reply> override;
    private volatile ObjectNode savedOrder;
    private volatile long policyRevision = 1;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::handle);
      server.start();
    }

    URI origin() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    CommercialClient client() {
      return new CommercialClient(origin(), CLOCK);
    }

    private void handle(HttpExchange exchange) throws IOException {
      try {
        byte[] body = exchange.getRequestBody().readNBytes(16 * 1024 * 1024 + 1);
        Request request =
            new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                body,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-DeskGrade-Diagnostics"));
        if (requests.size() >= 100) throw new IOException("Fixture request limit exceeded");
        requests.add(request);
        if (request.path().equals("/v1/grade")) gradeCalls.incrementAndGet();
        var selectedOverride = override;
        Reply reply = selectedOverride == null ? null : selectedOverride.apply(request);
        if (reply == null) reply = defaultReply(request);
        if (reply.location() != null)
          exchange.getResponseHeaders().set("Location", reply.location());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] result = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : result.length);
        if (reply.status() != 204) exchange.getResponseBody().write(result);
      } finally {
        exchange.close();
      }
    }

    private Reply defaultReply(Request req) {
      return switch (req.path()) {
        case "/v1/auth/login", "/v1/auth/register" -> auth(req);
        case "/v1/auth/logout" -> new Reply(204, "", null);
        case "/v1/account" -> Reply.ok(account("alice").toString());
        case "/v1/model-policy" ->
            Reply.ok(
                "{\"revision\":"
                    + policyRevision
                    + ",\"profileId\":\"fixture-model\",\"modelId\":\"fixture-vision\"}");
        case "/v1/models" ->
            Reply.ok(catalog(publicProfile(profile(true), PublicModelProfile.Role.PRIMARY)));
        case "/v1/packages" ->
            Reply.ok(
                "{\"paymentEnabled\":false,\"packages\":[{\"id\":\"pack100\",\"name\":\"100次\",\"credits\":100,\"priceMinor\":null,\"currency\":\"CNY\",\"enabled\":true}]}");
        case "/v1/orders" -> {
          if (req.method().equals("POST")) {
            savedOrder =
                CommercialJson.MAPPER
                    .createObjectNode()
                    .put("id", "order-one")
                    .put("requestId", req.json().path("requestId").asText())
                    .put("packageId", "pack100")
                    .put("packageName", "100次")
                    .put("credits", 100)
                    .putNull("priceMinor")
                    .put("currency", "CNY")
                    .put("status", "PENDING_PAYMENT")
                    .put("paymentEnabled", false);
            yield Reply.ok(savedOrder.toString());
          }
          ObjectNode root = CommercialJson.MAPPER.createObjectNode();
          var list = root.putArray("orders");
          if (savedOrder != null) list.add(savedOrder);
          yield Reply.ok(root.toString());
        }
        case "/v1/grade" -> gradeReply(req);
        case "/v1/rubrics/generate" -> rubricReply(req);
        default ->
            new Reply(
                404,
                "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Unavailable\"},\"chargedCredits\":0}",
                null);
      };
    }

    private Reply auth(Request req) {
      String username = req.json().path("username").asText();
      ObjectNode json =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("token", SYNTHETIC_TOKEN + "-" + username)
              .put("expiresAt", Instant.now().plusSeconds(3600).toString());
      json.set("account", account(username));
      return Reply.ok(json.toString());
    }

    private ObjectNode account(String username) {
      return CommercialJson.MAPPER
          .createObjectNode()
          .put("id", "account-" + username)
          .put("username", username)
          .put("role", "USER")
          .put("credits", 10);
    }

    private Reply gradeReply(Request req) {
      return Reply.ok(gradeJson(req).toString());
    }

    private Reply rubricReply(Request req) {
      ObjectNode root =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("requestId", req.json().path("requestId").asText())
              .put("scoreStep", 1)
              .put("completedAt", Instant.now().toString())
              .putNull("usage")
              .put("creditsRemaining", 9)
              .put("chargedCredits", 1)
              .put("replayed", false);
      var criteria = root.putArray("criteria");
      for (int index = 1; index <= 2; index++) {
        var criterion =
            criteria.addObject().put("id", "P" + index).put("rule", "评分点 " + index + "，符合得1分，否则0分");
        criterion.putArray("allowedScores").add(0).add(1);
      }
      return Reply.ok(root.toString());
    }

    private ObjectNode gradeJson(Request req) {
      var root =
          CommercialJson.MAPPER
              .createObjectNode()
              .put("requestId", req.json().path("requestId").asText())
              .put("source", "MOCK")
              .put("completedAt", Instant.now().toString())
              .put("elapsedMs", 20)
              .putNull("usage")
              .put("creditsRemaining", 9)
              .put("chargedCredits", 1)
              .put("replayed", false);
      var grade =
          root.putObject("result")
              .put("status", "SCORED")
              .put("readability", "CLEAR")
              .put("studentText", "12");
      grade
          .putArray("criteria")
          .addObject()
          .put("criterionId", "answer")
          .put("awarded", 1)
          .put("evidence", "12")
          .put("reason", "合成结果匹配");
      grade.putArray("issues");
      return root;
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
      try {
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        fail(stopped);
      }
    }
  }
}
