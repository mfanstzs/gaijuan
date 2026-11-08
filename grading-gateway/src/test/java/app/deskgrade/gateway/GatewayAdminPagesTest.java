package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(45)
class GatewayAdminPagesTest {
  @TempDir Path temp;

  @Test
  void usersAreSearchedAndPagedByServerWithBoundariesAndProtectedDetail() throws Exception {
    try (var store = new GatewayStore(temp.resolve("pages"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      store.bootstrapAdmin("operator", GatewayFixtures.password());
      var ids = new ArrayList<UUID>();
      for (int i = 0; i < 7; i++)
        ids.add(store.register("group_" + i, GatewayFixtures.password()).id());
      store.register("group-other", GatewayFixtures.password());
      var models = unavailable(store);
      try (var server = new GatewayServer(0, store, models, Clock.systemUTC())) {
        server.start();
        String admin = store.login("operator", GatewayFixtures.password()).token();
        String user = store.login("group_0", GatewayFixtures.password()).token();
        var seen = new HashSet<String>();
        for (int page = 1; page <= 3; page++) {
          var response =
              get(client, server, "/v1/admin/users?page=" + page + "&pageSize=3&q=GROUP_", admin);
          assertEquals(200, response.status());
          assertEquals(7, response.body().path("total").asInt());
          assertEquals(3, response.body().path("totalPages").asInt());
          assertEquals(page, response.body().path("page").asInt());
          assertEquals(3, response.body().path("pageSize").asInt());
          assertEquals(page == 3 ? 1 : 3, response.body().path("users").size());
          for (var item : response.body().path("users")) {
            assertTrue(seen.add(item.path("id").asText()), "Pages must not overlap");
            assertTrue(
                item.path("username").asText().startsWith("group_"),
                "underscore is literal, not SQL wildcard");
            assertTrue(item.has("createdAt"));
          }
        }
        assertEquals(new HashSet<>(ids.stream().map(UUID::toString).toList()), seen);
        var empty = get(client, server, "/v1/admin/users?page=4&pageSize=3&q=group_", admin);
        assertEquals(200, empty.status());
        assertEquals(0, empty.body().path("users").size());
        assertEquals(7, empty.body().path("total").asInt());
        var noMatch = get(client, server, "/v1/admin/users?q=absent", admin);
        assertEquals(0, noMatch.body().path("total").asInt());
        assertEquals(0, noMatch.body().path("totalPages").asInt());
        for (String query :
            List.of(
                "page=0",
                "page=-1",
                "pageSize=0",
                "pageSize=101",
                "page=9999999999999999",
                "page=1000001&pageSize=100",
                "page=1&page=2",
                "page=1&unknown=x",
                "q=%25",
                "q=" + "a".repeat(33))) {
          assertEquals(400, get(client, server, "/v1/admin/users?" + query, admin).status(), query);
        }
        String detail = "/v1/admin/users/" + ids.getFirst();
        assertEquals(401, get(client, server, detail, null).status());
        assertEquals(403, get(client, server, detail, user).status());
        assertEquals(200, get(client, server, detail, admin).status());
        assertEquals(
            "group_0", get(client, server, detail, admin).body().path("username").asText());
        assertEquals(
            404, get(client, server, "/v1/admin/users/" + UUID.randomUUID(), admin).status());
        store.logout(admin);
        assertEquals(
            401, get(client, server, "/v1/admin/users?page=1&pageSize=20", admin).status());
      }
    }
  }

  @Test
  void existingDataHasPagedLedgerOrdersAndCombinedAdminAuditAfterReopen() throws Exception {
    Path database = temp.resolve("existing");
    UUID adminId, userId, otherId;
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      adminId = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      userId = store.register("alice", GatewayFixtures.password()).id();
      otherId = store.register("bob", GatewayFixtures.password()).id();
      for (int i = 0; i < 5; i++)
        store.adjust(adminId, UUID.randomUUID(), userId, 2, "synthetic allocation " + i);
      store.adjust(adminId, UUID.randomUUID(), otherId, 1, "separate user");
      for (int i = 0; i < 5; i++)
        store.createOrder(userId, UUID.randomUUID(), "calls-100", 100, null);
      store.createOrder(otherId, UUID.randomUUID(), "calls-500", 500, null);
      GatewayFixtures.activate(store, adminId, GatewayFixtures.profile());
      UUID request = UUID.randomUUID();
      store.prepare(userId, request, "a".repeat(64), "synthetic-model");
      store.beforeDispatch(userId, request);
      store.finish(userId, request, null, "MODEL_FAILED", false);
    }
    try (var store = new GatewayStore(database, Clock.systemUTC());
        var client = HttpClient.newHttpClient();
        var server = new GatewayServer(0, store, unavailable(store), Clock.systemUTC())) {
      server.start();
      String admin = store.login("operator", GatewayFixtures.password()).token();
      String user = store.login("alice", GatewayFixtures.password()).token();
      assertEquals(9, store.account(userId).balance());
      assertEquals(1, store.modelPolicy().revision());
      String ledger = "/v1/admin/ledger?userId=" + userId + "&pageSize=2&page=";
      var first = get(client, server, ledger + "1", admin);
      var next = get(client, server, ledger + "2", admin);
      assertEquals(200, first.status());
      assertEquals(6, first.body().path("total").asInt());
      assertEquals(2, first.body().path("entries").size());
      assertEquals(2, next.body().path("entries").size());
      assertNotEquals(
          first.body().path("entries").get(0).path("id"),
          next.body().path("entries").get(0).path("id"));
      for (var entry : first.body().path("entries")) {
        assertEquals(userId.toString(), entry.path("userId").asText());
        assertEquals("alice", entry.path("username").asText());
      }
      assertEquals(
          5,
          get(client, server, ledger + "1&kind=ADMIN_ADJUSTMENT", admin)
              .body()
              .path("total")
              .asInt());
      var requests = get(client, server, ledger + "1&kind=MODEL_DISPATCH", admin);
      assertEquals(1, requests.body().path("total").asInt());
      assertEquals(-1, requests.body().path("entries").get(0).path("delta").asInt());
      var orders = get(client, server, "/v1/admin/orders?page=2&pageSize=2&q=ALICE", admin);
      assertEquals(200, orders.status());
      assertEquals(5, orders.body().path("total").asInt());
      assertEquals(2, orders.body().path("orders").size());
      assertFalse(orders.body().path("orders").get(0).path("paymentEnabled").asBoolean());
      assertEquals("PENDING_PAYMENT", orders.body().path("orders").get(0).path("status").asText());
      assertEquals(
          1,
          get(client, server, "/v1/admin/orders?userId=" + otherId, admin)
              .body()
              .path("total")
              .asInt());
      var audit = get(client, server, "/v1/admin/audit?page=2&pageSize=3", admin);
      assertEquals(200, audit.status());
      assertEquals(7, audit.body().path("total").asInt());
      assertEquals(3, audit.body().path("entries").size());
      assertEquals(
          6,
          get(client, server, "/v1/admin/audit?type=CREDITS", admin).body().path("total").asInt());
      var policies = get(client, server, "/v1/admin/audit?type=MODEL_POLICY", admin);
      assertEquals(1, policies.body().path("total").asInt());
      assertEquals(
          "operator", policies.body().path("entries").get(0).path("adminUsername").asText());
      assertEquals(1, policies.body().path("entries").get(0).path("revision").asInt());
      assertTrue(policies.body().path("entries").get(0).path("targetUserId").isNull());
      assertEquals(
          3, get(client, server, "/v1/admin/packages", admin).body().path("packages").size());
      for (String path :
          List.of(
              "/v1/admin/ledger", "/v1/admin/orders", "/v1/admin/packages", "/v1/admin/audit")) {
        assertEquals(403, get(client, server, path, user).status());
        assertEquals(401, get(client, server, path, null).status());
      }
      assertEquals(400, get(client, server, "/v1/admin/audit?type=UNKNOWN", admin).status());
      assertEquals(400, get(client, server, "/v1/admin/ledger?kind=UNKNOWN", admin).status());
      assertEquals(
          400, get(client, server, "/v1/admin/orders?page=1&pageSize=101", admin).status());
      assertEquals(9, store.account(userId).balance());
      assertEquals(1, store.account(otherId).balance());
    }
  }

  @Test
  void exhaustedCreditsReturnStableRemainingAndChargeWithoutAnotherSupplierCall() throws Exception {
    try (var store = new GatewayStore(temp.resolve("zero"), Clock.systemUTC());
        var client = HttpClient.newHttpClient()) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 1, "single synthetic request");
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (p, r, q, hook) -> {
                hook.run();
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new ProviderException(ProviderException.Failure.AUTHENTICATION));
              });
      try (var server = new GatewayServer(0, store, service, Clock.systemUTC())) {
        server.start();
        String token = store.login("alice", GatewayFixtures.password()).token();
        var paid = GatewayFixtures.publicBody(UUID.randomUUID());
        var exhausted = GatewayFixtures.publicBody(UUID.randomUUID());
        var first = post(client, server, "/v1/grade", token, paid.toString());
        assertEquals(502, first.status());
        assertEquals(1, first.body().path("chargedCredits").asInt());
        assertEquals(0, first.body().path("creditsRemaining").asInt());
        var denied = post(client, server, "/v1/grade", token, exhausted.toString());
        assertEquals(402, denied.status());
        assertEquals("INSUFFICIENT_CREDITS", denied.body().path("error").path("code").asText());
        assertEquals(exhausted.path("requestId"), denied.body().path("requestId"));
        assertTrue(denied.body().has("creditsRemaining"));
        assertEquals(0, denied.body().path("creditsRemaining").asLong());
        assertEquals(0, denied.body().path("chargedCredits").asInt());
        assertFalse(denied.body().path("replayed").asBoolean());
        var replay = post(client, server, "/v1/grade", token, exhausted.toString());
        assertEquals(402, replay.status());
        assertFalse(replay.body().path("replayed").asBoolean());
        assertEquals(0, replay.body().path("chargedCredits").asInt());
        assertNull(
            store.findRequest(user.id(), UUID.fromString(exhausted.path("requestId").asText())));
        assertEquals(
            1,
            post(client, server, "/v1/grade", token, paid.toString())
                .body()
                .path("chargedCredits")
                .asInt());
        assertEquals(1, calls.get());
        store.logout(token);
        assertEquals(401, post(client, server, "/v1/grade", token, paid.toString()).status());
        assertEquals(1, calls.get());
      }
    }
  }

  private static GatewayModelService unavailable(GatewayStore store) {
    return new GatewayModelService(
        store,
        List.of(),
        (p, r, q, h) -> {
          throw new AssertionError("No supplier dispatch");
        });
  }

  private record Wire(int status, JsonNode body) {}

  private static Wire get(HttpClient client, GatewayServer server, String path, String token)
      throws Exception {
    return call(client, server, path, token, null);
  }

  private static Wire post(
      HttpClient client, GatewayServer server, String path, String token, String body)
      throws Exception {
    return call(client, server, path, token, body);
  }

  private static Wire call(
      HttpClient client, GatewayServer server, String path, String token, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Wire(response.statusCode(), GatewayJson.MAPPER.readTree(response.body()));
  }
}
