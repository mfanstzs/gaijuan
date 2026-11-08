package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.ScoringCriterion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayServerTest {
  @TempDir Path temp;

  record Wire(int status, JsonNode body, HttpResponse<String> response) {}

  @Test
  void registrationZeroBalanceAdminAuthorizationPendingOrderAndLogoutOverHttp() throws Exception {
    try (var store = new GatewayStore(temp.resolve("http"), Clock.systemUTC());
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                store,
                new GatewayModelService(
                    store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                Clock.systemUTC())) {
      var operator = store.bootstrapAdmin("operator", GatewayFixtures.password());
      GatewayFixtures.activate(store, operator.id(), GatewayFixtures.profile());
      server.start();
      var registration =
          call(
              client,
              server,
              "POST",
              "/v1/auth/register",
              null,
              "{\"username\":\"alice\",\"password\":\"synthetic test password 42\"}");
      assertEquals(201, registration.status());
      String token = registration.body().path("token").asText(),
          userId = registration.body().path("account").path("id").asText();
      assertEquals(0, registration.body().path("account").path("credits").asLong());
      assertEquals(401, call(client, server, "GET", "/v1/account", null, null).status());
      assertEquals(403, call(client, server, "GET", "/v1/admin/users", token, null).status());
      var admin =
          call(
                  client,
                  server,
                  "POST",
                  "/v1/auth/login",
                  null,
                  "{\"username\":\"operator\",\"password\":\"synthetic test password 42\"}")
              .body()
              .path("token")
              .asText();
      String adjust =
          "{\"requestId\":\""
              + UUID.randomUUID()
              + "\",\"targetUserId\":\""
              + userId
              + "\",\"delta\":3,\"reason\":\"synthetic allocation\"}";
      assertEquals(403, call(client, server, "POST", "/v1/admin/credits", token, adjust).status());
      var added = call(client, server, "POST", "/v1/admin/credits", admin, adjust);
      assertEquals(200, added.status());
      assertFalse(added.body().path("replayed").asBoolean());
      assertTrue(
          call(client, server, "POST", "/v1/admin/credits", admin, adjust)
              .body()
              .path("replayed")
              .asBoolean());
      var ledger = call(client, server, "GET", "/v1/admin/ledger?userId=" + userId, admin, null);
      assertEquals(1, ledger.body().path("entries").size());
      assertEquals(
          "synthetic allocation", ledger.body().path("entries").get(0).path("reason").asText());
      assertEquals(0, ledger.body().path("entries").get(0).path("balanceBefore").asInt());
      var packages = call(client, server, "GET", "/v1/packages", token, null);
      assertFalse(packages.body().path("paymentEnabled").asBoolean());
      assertTrue(packages.body().path("packages").get(0).path("priceMinor").isNull());
      String orderBody =
          "{\"requestId\":\"" + UUID.randomUUID() + "\",\"packageId\":\"calls-100\"}";
      var order = call(client, server, "POST", "/v1/orders", token, orderBody);
      assertEquals("PENDING_PAYMENT", order.body().path("status").asText());
      assertEquals(
          order.body(), call(client, server, "POST", "/v1/orders", token, orderBody).body());
      assertEquals(
          3,
          call(client, server, "GET", "/v1/account", token, null).body().path("credits").asInt());
      var second =
          call(
                  client,
                  server,
                  "POST",
                  "/v1/auth/register",
                  null,
                  "{\"username\":\"bob\",\"password\":\"synthetic test password 42\"}")
              .body()
              .path("token")
              .asText();
      assertEquals(
          0, call(client, server, "GET", "/v1/orders", second, null).body().path("orders").size());
      var grade =
          call(
              client,
              server,
              "POST",
              "/v1/grade",
              token,
              GatewayFixtures.publicBody(UUID.randomUUID()).toString());
      assertEquals(503, grade.status());
      assertEquals(0, grade.body().path("chargedCredits").asInt());
      assertEquals(204, call(client, server, "POST", "/v1/auth/logout", token, null).status());
      assertEquals(401, call(client, server, "GET", "/v1/account", token, null).status());
    }
  }

  @Test
  void repeatedRegistrationNeverRevealsWhetherTheUsernameAlreadyExists() throws Exception {
    try (var store = new GatewayStore(temp.resolve("registration-enumeration"), Clock.systemUTC());
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                store,
                new GatewayModelService(
                    store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                Clock.systemUTC())) {
      server.start();
      String credentials = "{\"username\":\"alice\",\"password\":\"synthetic test password 42\"}";
      var created = call(client, server, "POST", "/v1/auth/register", null, credentials);
      var repeated = call(client, server, "POST", "/v1/auth/register", null, credentials);

      assertEquals(201, created.status());
      assertEquals(201, repeated.status());
      assertEquals(
          created.body().path("account").path("id").asText(),
          repeated.body().path("account").path("id").asText());
      assertNotEquals(
          created.body().path("token").asText(), repeated.body().path("token").asText());

      String wrong = "{\"username\":\"alice\",\"password\":\"different synthetic password 77\"}";
      var rejectedRegistration = call(client, server, "POST", "/v1/auth/register", null, wrong);
      var rejectedLogin = call(client, server, "POST", "/v1/auth/login", null, wrong);
      assertEquals(401, rejectedRegistration.status());
      assertEquals(
          "INVALID_CREDENTIALS", rejectedRegistration.body().path("error").path("code").asText());
      assertEquals(rejectedLogin.status(), rejectedRegistration.status());
      assertEquals(rejectedLogin.body(), rejectedRegistration.body());
      assertFalse(rejectedRegistration.response().body().contains("alice"));
      assertFalse(rejectedRegistration.response().body().contains("USERNAME_UNAVAILABLE"));
    }
  }

  @Test
  void strictJsonOriginAndAdminResourcesHaveNoPublicCorsOrUnsafeDefaults() throws Exception {
    try (var store = new GatewayStore(temp.resolve("strict"), Clock.systemUTC());
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                store,
                new GatewayModelService(
                    store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                Clock.systemUTC())) {
      server.start();
      assertEquals(
          400,
          call(
                  client,
                  server,
                  "POST",
                  "/v1/auth/register",
                  null,
                  "{\"username\":\"a\",\"username\":\"alice\",\"password\":\"synthetic test password 42\"}")
              .status());
      assertEquals(
          400,
          call(
                  client,
                  server,
                  "POST",
                  "/v1/auth/register",
                  null,
                  "{\"username\":\"alice\",\"password\":\"synthetic test password 42\",\"role\":\"ADMIN\"}")
              .status());
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.port() + "/v1/auth/register"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Origin", "https://evil.invalid")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"username\":\"alice\",\"password\":\"synthetic test password 42\"}"))
              .build();
      var denied = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(403, denied.statusCode());
      assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      var html =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/admin"))
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, html.statusCode());
      assertEquals("no-store", html.headers().firstValue("Cache-Control").orElseThrow());
      assertEquals("nosniff", html.headers().firstValue("X-Content-Type-Options").orElseThrow());
      assertEquals("DENY", html.headers().firstValue("X-Frame-Options").orElseThrow());
      assertEquals(
          "same-origin", html.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
      assertTrue(
          html.headers().firstValue("Permissions-Policy").orElseThrow().contains("camera=()"));
      assertTrue(
          html.headers()
              .firstValue("Content-Security-Policy")
              .orElseThrow()
              .contains("frame-ancestors 'none'"));
      assertEquals(
          400, call(client, server, "POST", "/v1/auth/register", null, " ".repeat(4097)).status());
    }
  }

  @Test
  void httpSuccessAndCrossUserStatusUseOnlyOwnRequestAndOneDebit() throws Exception {
    try (var store = new GatewayStore(temp.resolve("grade"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var user = store.register("alice", GatewayFixtures.password());
      store.register("bob", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 1, "synthetic budget");
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(GatewayFixtures.response(q));
              });
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        String token = store.login("alice", GatewayFixtures.password()).token(),
            other = store.login("bob", GatewayFixtures.password()).token();
        UUID id = UUID.randomUUID();
        String body = GatewayFixtures.publicBody(id).toString();
        var grade = call(client, server, "POST", "/v1/grade", token, body);
        assertEquals(200, grade.status());
        assertEquals(9, grade.body().size());
        assertEquals(1, grade.body().path("chargedCredits").asInt());
        assertEquals(0, grade.body().path("creditsRemaining").asInt());
        assertTrue(
            call(client, server, "POST", "/v1/grade", token, body)
                .body()
                .path("replayed")
                .asBoolean());
        assertEquals(1, calls.get());
        assertEquals(404, call(client, server, "GET", "/v1/grades/" + id, other, null).status());
        assertEquals(
            "SUCCEEDED",
            call(client, server, "GET", "/v1/grades/" + id, token, null)
                .body()
                .path("state")
                .asText());
      }
    }
  }

  @Test
  void rubricGeneratorPolicyIsAdminOnlyAndGenerationIsAuthenticatedIdempotentAndBillable()
      throws Exception {
    try (var store = new GatewayStore(temp.resolve("rubric-http"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      var profile = GatewayFixtures.profile();
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "rubric generation budget");
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(profile),
              (p, r, q, hook) -> {
                throw new AssertionError("grading engine must not run");
              },
              (p, subject, reference, maximum, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    new GatewayModelService.GeneratedRubric(
                        BigDecimal.ONE,
                        List.of(
                            new ScoringCriterion(
                                "P1", "合成评分点", List.of(BigDecimal.ZERO, new BigDecimal("4")))),
                        null,
                        Duration.ZERO));
              });
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        String adminToken = store.login("operator", GatewayFixtures.password()).token();
        String userToken = store.login("alice", GatewayFixtures.password()).token();
        String policy =
            "{\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"profileId\":\""
                + profile.id()
                + "\",\"expectedRevision\":0,\"reason\":\"synthetic generator selection\"}";
        assertEquals(
            403,
            call(client, server, "POST", "/v1/admin/rubric-model-policy", userToken, policy)
                .status());
        var selected =
            call(client, server, "POST", "/v1/admin/rubric-model-policy", adminToken, policy);
        assertEquals(200, selected.status());
        assertEquals(1, selected.body().path("policy").path("revision").asInt());
        var directory = call(client, server, "GET", "/v1/admin/models", adminToken, null);
        assertEquals(
            profile.id(), directory.body().path("rubricPolicy").path("profileId").asText());
        assertTrue(directory.body().path("models").get(0).path("rubricActive").asBoolean());

        UUID requestId = UUID.randomUUID();
        String request =
            "{\"requestId\":\""
                + requestId
                + "\",\"subject\":\"历史\",\"referenceAnswer\":\"标准答案\",\"maxScore\":4}";
        assertEquals(
            401, call(client, server, "POST", "/v1/rubrics/generate", null, request).status());
        var generated = call(client, server, "POST", "/v1/rubrics/generate", userToken, request);
        assertEquals(200, generated.status());
        assertEquals(1, generated.body().path("chargedCredits").asInt());
        assertEquals(1, generated.body().path("creditsRemaining").asInt());
        assertFalse(generated.body().path("replayed").asBoolean());
        assertTrue(
            call(client, server, "POST", "/v1/rubrics/generate", userToken, request)
                .body()
                .path("replayed")
                .asBoolean());
        assertEquals(1, calls.get());
        String changed = request.replace("标准答案", "不同答案");
        assertEquals(
            409, call(client, server, "POST", "/v1/rubrics/generate", userToken, changed).status());
      }
    }
  }

  @Test
  void diagnosticsOptInKeepsReleasedClientGradeRubricAndStatusJsonCompatible() throws Exception {
    try (var store = new GatewayStore(temp.resolve("diagnostics-wire"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 3, "synthetic diagnostics contract");
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var calls = new AtomicInteger();
      var models =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new ProviderException(
                        ProviderException.Failure.NETWORK,
                        null,
                        ProviderException.Diagnostic.MODEL_DNS_FAILURE));
              },
              (p, subject, reference, maximum, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new ProviderException(
                        ProviderException.Failure.TIMEOUT,
                        null,
                        ProviderException.Diagnostic.MODEL_REQUEST_TIMEOUT));
              });
      models.selectRubricPolicy(
          admin.id(),
          UUID.randomUUID(),
          GatewayFixtures.profile().id(),
          0,
          "synthetic diagnostic generator");
      String userToken = store.login("alice", GatewayFixtures.password()).token();
      String adminToken = store.login("operator", GatewayFixtures.password()).token();
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        UUID gradeId = UUID.randomUUID(), rubricId = UUID.randomUUID();
        String gradeBody = GatewayFixtures.publicBody(gradeId).toString();
        String rubricBody =
            "{\"requestId\":\""
                + rubricId
                + "\",\"subject\":\"语文\",\"referenceAnswer\":\"标准答案\",\"maxScore\":4}";
        for (var request :
            List.of(
                new String[] {"/v1/grade", gradeBody, "MODEL_DNS_FAILURE"},
                new String[] {"/v1/rubrics/generate", rubricBody, "MODEL_REQUEST_TIMEOUT"})) {
          var first = call(client, server, "POST", request[0], userToken, request[1]);
          assertFalse(first.body().path("error").has("diagnosticCode"));
          var modern = call(client, server, "POST", request[0], userToken, request[1], "1");
          assertEquals(request[2], modern.body().path("error").path("diagnosticCode").asText());
          var legacyShape = (ObjectNode) modern.body().deepCopy();
          ((ObjectNode) legacyShape.path("error")).remove("diagnosticCode");
          for (String[] version :
              List.of(
                  new String[] {},
                  new String[] {"0"},
                  new String[] {"2"},
                  new String[] {"1", "1"},
                  new String[] {"1,1"})) {
            var legacy = call(client, server, "POST", request[0], userToken, request[1], version);
            assertEquals(modern.status(), legacy.status());
            assertEquals(legacyShape, legacy.body());
          }
        }
        String statusPath = "/v1/grades/" + gradeId;
        var modernStatus = call(client, server, "GET", statusPath, userToken, null, "1");
        assertEquals("MODEL_DNS_FAILURE", modernStatus.body().path("diagnosticCode").asText());
        var legacyStatus = (ObjectNode) modernStatus.body().deepCopy();
        legacyStatus.remove("diagnosticCode");
        assertEquals(legacyStatus, call(client, server, "GET", statusPath, userToken, null).body());
        var adminDetail =
            call(
                client,
                server,
                "GET",
                "/v1/admin/model-calls/" + gradeId + "?kind=GRADING&userId=" + user.id(),
                adminToken,
                null);
        assertEquals(
            "MODEL_DNS_FAILURE",
            adminDetail.body().path("failureDiagnostic").path("diagnosticCode").asText());
        assertEquals(2, calls.get());
        assertEquals(1, store.account(user.id()).balance());
        assertEquals(
            "MODEL_DNS_FAILURE",
            store.modelFailure(user.id(), gradeId, "GRADING").diagnosticCode());
      }
    }
  }

  @Test
  void adminCanRevokeOnlyTheTargetUsersSessionsOverHttp() throws Exception {
    try (var store = new GatewayStore(temp.resolve("session-revoke-http"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var target = store.register("alice", GatewayFixtures.password());
      var other = store.register("bob", GatewayFixtures.password());
      String adminToken = store.login("operator", GatewayFixtures.password()).token();
      String targetToken = store.login("alice", GatewayFixtures.password()).token();
      String otherToken = store.login("bob", GatewayFixtures.password()).token();
      var models =
          new GatewayModelService(
              store,
              List.of(),
              (p, r, q, h) -> {
                throw new AssertionError();
              });
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        String route = "/v1/admin/users/" + target.id() + "/revoke-sessions";
        assertEquals(403, call(client, server, "POST", route, otherToken, null).status());
        assertEquals(200, call(client, server, "GET", "/v1/account", targetToken, null).status());
        assertEquals(204, call(client, server, "POST", route, adminToken, null).status());
        assertEquals(401, call(client, server, "GET", "/v1/account", targetToken, null).status());
        assertEquals(200, call(client, server, "GET", "/v1/account", otherToken, null).status());
        assertEquals(admin.id(), store.authenticate(adminToken).id());
      }
    }
  }

  private static Wire call(
      HttpClient client,
      GatewayServer server,
      String method,
      String path,
      String token,
      String body,
      String... diagnosticsVersions)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (String version : diagnosticsVersions) builder.header("X-DeskGrade-Diagnostics", version);
    if (method.equals("POST")) {
      if (body != null) builder.header("Content-Type", "application/json");
      builder.POST(
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
    } else builder.GET();
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return new Wire(
        response.statusCode(),
        response.body().isEmpty() ? null : GatewayJson.MAPPER.readTree(response.body()),
        response);
  }
}
