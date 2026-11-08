package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(45)
class GatewayCommercialAdminTest {
  @TempDir Path temp;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void packageCrudPersistsAndDeletedPackageKeepsHistoricalOrderName() throws Exception {
    Path database = temp.resolve("packages");
    UUID orderId = UUID.randomUUID();
    try (var store = new GatewayStore(database, CLOCK);
        var http = HttpClient.newHttpClient();
        var server = new GatewayServer(0, store, unavailable(store), CLOCK)) {
      var adminAccount = store.bootstrapAdmin("operator", GatewayFixtures.password());
      store.register("alice", GatewayFixtures.password());
      server.start();
      String admin = store.login("operator", GatewayFixtures.password()).token();
      String user = store.login("alice", GatewayFixtures.password()).token();

      assertEquals(
          3,
          call(http, server, "GET", "/v1/admin/packages", admin, null)
              .body()
              .path("packages")
              .size());
      String create =
          """
          {"id":"calls-250","name":"自定义 250 次","credits":250,"priceMinor":1999,"currency":"CNY","enabled":true}
          """;
      assertEquals(401, call(http, server, "POST", "/v1/admin/packages", null, create).status());
      assertEquals(403, call(http, server, "POST", "/v1/admin/packages", user, create).status());
      var created = call(http, server, "POST", "/v1/admin/packages", admin, create);
      assertEquals(201, created.status());
      assertEquals(1, created.body().path("revision").asLong());
      assertEquals(1999, created.body().path("priceMinor").asLong());

      String disabled =
          """
          {"expectedRevision":1,"name":"自定义 250 次","credits":250,"priceMinor":1999,"currency":"CNY","enabled":false}
          """;
      var updated = call(http, server, "PUT", "/v1/admin/packages/calls-250", admin, disabled);
      assertEquals(200, updated.status());
      assertEquals(2, updated.body().path("revision").asLong());
      assertFalse(updated.body().path("enabled").asBoolean());
      assertEquals(
          409,
          call(
                  http,
                  server,
                  "PUT",
                  "/v1/admin/packages/calls-250",
                  admin,
                  disabled.replace("自定义 250 次", "覆盖旧版本"))
              .status());

      String order = "{\"requestId\":\"" + orderId + "\",\"packageId\":\"calls-250\"}";
      assertEquals(400, call(http, server, "POST", "/v1/orders", user, order).status());
      String enabled =
          disabled
              .replace("\"expectedRevision\":1", "\"expectedRevision\":2")
              .replace("\"enabled\":false", "\"enabled\":true");
      assertEquals(
          3,
          call(http, server, "PUT", "/v1/admin/packages/calls-250", admin, enabled)
              .body()
              .path("revision")
              .asLong());
      var ordered = call(http, server, "POST", "/v1/orders", user, order);
      assertEquals(201, ordered.status());
      assertEquals("自定义 250 次", ordered.body().path("packageName").asText());

      assertEquals(
          204,
          call(
                  http,
                  server,
                  "DELETE",
                  "/v1/admin/packages/calls-250",
                  admin,
                  "{\"expectedRevision\":3}")
              .status());
      assertFalse(
          containsPackage(
              call(http, server, "GET", "/v1/packages", user, null).body(), "calls-250"));
      var history = call(http, server, "GET", "/v1/orders", user, null);
      assertEquals("自定义 250 次", history.body().path("orders").get(0).path("packageName").asText());
      var audits = store.adminAudit(adminAccount.id(), 1, 20, "PACKAGE");
      assertEquals(4, audits.total());
      assertEquals("calls-250", audits.items().getFirst().packageId());
      assertEquals("DELETE", audits.items().getFirst().reason());
      assertEquals(4L, audits.items().getFirst().revision());
      assertEquals("CREATE", audits.items().getLast().reason());
      assertEquals(1L, audits.items().getLast().revision());
    }

    try (var store = new GatewayStore(database, CLOCK);
        var http = HttpClient.newHttpClient();
        var server = new GatewayServer(0, store, unavailable(store), CLOCK)) {
      server.start();
      String admin = store.login("operator", GatewayFixtures.password()).token();
      String user = store.login("alice", GatewayFixtures.password()).token();
      assertFalse(
          containsPackage(
              call(http, server, "GET", "/v1/admin/packages", admin, null).body(), "calls-250"));
      assertEquals(
          "自定义 250 次",
          call(http, server, "GET", "/v1/orders", user, null)
              .body()
              .path("orders")
              .get(0)
              .path("packageName")
              .asText());
    }
  }

  @Test
  void orderCreationLocksCatalogueAndReplaysHistoricalSnapshotAfterPackageDeletion() {
    try (var store = new GatewayStore(temp.resolve("atomic-orders"), CLOCK)) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.createPackage(admin.id(), "semester-50", "学期包 50 次", 50, 1280L, "CNY", true);
      UUID request = UUID.randomUUID();
      var created = store.createOrderFromPackage(user.id(), request, "semester-50");
      assertEquals("学期包 50 次", created.packageName());
      assertEquals("CNY", created.currency());

      store.updatePackage(admin.id(), "semester-50", 1, "新学期包 50 次", 50, 1380L, "CNY", true);
      store.deletePackage(admin.id(), "semester-50", 2);
      var replayed = store.createOrderFromPackage(user.id(), request, "semester-50");
      assertEquals(created, replayed);
      assertEquals("学期包 50 次", store.orders(user.id(), 0, 10).getFirst().packageName());
      assertCode(
          GatewayException.Code.INVALID_REQUEST,
          () -> store.createOrderFromPackage(user.id(), UUID.randomUUID(), "semester-50"));
      assertCode(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          () -> store.createOrderFromPackage(user.id(), request, "calls-100"));
    }
  }

  @Test
  void dashboardAggregatesOnlyPersistedBusinessRecords() throws Exception {
    try (var store = new GatewayStore(temp.resolve("dashboard"), CLOCK);
        var http = HttpClient.newHttpClient();
        var server = new GatewayServer(0, store, unavailable(store), CLOCK)) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 5, "verified allocation");
      UUID success = UUID.randomUUID();
      store.prepare(user.id(), success, "a".repeat(64), "production-model");
      store.beforeDispatch(user.id(), success);
      store.finish(user.id(), success, "{\"result\":\"ok\"}", null, false);
      UUID failure = UUID.randomUUID();
      store.prepare(user.id(), failure, "b".repeat(64), "production-model");
      store.beforeDispatch(user.id(), failure);
      store.finish(user.id(), failure, null, "MODEL_FAILED", false);
      UUID notDispatched = UUID.randomUUID();
      store.prepare(user.id(), notDispatched, "c".repeat(64), "production-model");
      store.finish(user.id(), notDispatched, null, "LOCAL_VALIDATION_FAILED", false);
      store.createOrder(user.id(), UUID.randomUUID(), "calls-100", 100, null);
      var persisted = store.adminDashboard(admin.id(), 7);
      assertEquals(2, persisted.modelCalls());
      assertEquals(1, persisted.unpricedOrders());
      assertNull(persisted.orderAmountMinor());
      server.start();
      String adminToken = store.login("operator", GatewayFixtures.password()).token();
      String userToken = store.login("alice", GatewayFixtures.password()).token();

      assertEquals(403, call(http, server, "GET", "/v1/admin/dashboard", userToken, null).status());
      var response = call(http, server, "GET", "/v1/admin/dashboard", adminToken, null);
      assertEquals(200, response.status());
      JsonNode body = response.body(),
          summary = body.path("summary"),
          today = body.path("daily").get(6);
      assertEquals("UTC", body.path("timezone").asText());
      assertEquals(7, body.path("periodDays").asInt());
      assertEquals(1, summary.path("totalUsers").asLong());
      assertEquals(3, summary.path("availableCredits").asLong());
      assertEquals(2, summary.path("modelCalls").asLong());
      assertEquals(1, summary.path("succeededCalls").asLong());
      assertEquals(1, summary.path("failedCalls").asLong());
      assertEquals(0, summary.path("unresolvedCalls").asLong());
      assertEquals(2, summary.path("chargedCredits").asLong());
      assertEquals(5, summary.path("creditsAdded").asLong());
      assertEquals(1, summary.path("orders").asLong());
      assertTrue(summary.path("orderAmountMinor").isNull());
      assertEquals(1, summary.path("unpricedOrders").asLong());
      assertEquals("2026-09-12", today.path("date").asText());
      assertEquals(1, today.path("registrations").asLong());
      assertEquals(2, today.path("modelCalls").asLong());
      assertEquals(1, body.path("models").size());
      assertEquals("production-model", body.path("models").get(0).path("modelId").asText());
      assertEquals(2, body.path("models").get(0).path("calls").asLong());
    }
  }

  private static boolean containsPackage(JsonNode response, String id) {
    for (JsonNode pack : response.path("packages"))
      if (id.equals(pack.path("id").asText())) return true;
    return false;
  }

  private static void assertCode(GatewayException.Code expected, Runnable operation) {
    assertEquals(expected, assertThrows(GatewayException.class, operation::run).code());
  }

  private static GatewayModelService unavailable(GatewayStore store) {
    return new GatewayModelService(
        store,
        List.of(),
        (profile, request, rubric, dispatch) -> {
          throw new AssertionError("No model dispatch expected");
        });
  }

  private record Wire(int status, JsonNode body) {}

  private static Wire call(
      HttpClient client,
      GatewayServer server,
      String method,
      String path,
      String token,
      String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode json = response.body().isEmpty() ? null : GatewayJson.MAPPER.readTree(response.body());
    return new Wire(response.statusCode(), json);
  }
}
