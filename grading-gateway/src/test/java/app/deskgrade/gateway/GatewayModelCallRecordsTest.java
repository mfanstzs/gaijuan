package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayModelCallRecordsTest {
  @TempDir Path directory;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
  static final String RESULT =
      "{\"result\":{\"studentText\":\"合成学生答案\",\"criteria\":[{\"criterionId\":\"c1\",\"awarded\":1,\"evidence\":\"合成依据\",\"reason\":\"匹配规则\"}]}}";

  @Test
  void stableCombinedPaginationAndExactAccountLookupKeepKindsAndOwnersDistinct() throws Exception {
    try (var fixture = new Fixture(directory.resolve("pages"))) {
      UUID sameId = UUID.randomUUID();
      prepare(fixture.store, fixture.alice.id(), sameId, "GRADING");
      prepare(fixture.store, fixture.alice.id(), sameId, "RUBRIC_GENERATION");
      prepare(fixture.store, fixture.bob.id(), sameId, "GRADING");
      var all = fixture.store.adminModelCalls(fixture.admin.id(), null, null, 1, 2);
      assertEquals(3, all.total());
      assertEquals(2, all.totalPages());
      var next = fixture.store.adminModelCalls(fixture.admin.id(), null, null, 2, 2);
      var identities = new HashSet<String>();
      for (var row : all.items()) identities.add(row.kind() + row.userId() + row.requestId());
      for (var row : next.items())
        assertTrue(identities.add(row.kind() + row.userId() + row.requestId()));
      assertEquals(3, identities.size());
      assertEquals(all, fixture.store.adminModelCalls(fixture.admin.id(), null, null, 1, 2));
      assertEquals(
          2, fixture.store.adminModelCalls(fixture.admin.id(), null, "ALICE", 1, 20).total());
      assertEquals(
          0, fixture.store.adminModelCalls(fixture.admin.id(), null, "ali", 1, 20).total());
      assertEquals(
          0,
          fixture
              .store
              .adminModelCalls(fixture.admin.id(), fixture.bob.id(), "alice", 1, 20)
              .total());
      assertEquals(2, fixture.store.modelCalls(fixture.alice.id(), 1, 20).total());
      assertCode(
          GatewayException.Code.FORBIDDEN,
          () -> fixture.store.adminModelCalls(fixture.alice.id(), null, null, 1, 20));
      assertCode(
          GatewayException.Code.NOT_FOUND,
          () -> fixture.store.modelCallDetail(fixture.bob.id(), sameId, "RUBRIC_GENERATION"));
      assertFalse(
          fixture
              .store
              .prepare(
                  fixture.alice.id(),
                  sameId,
                  "a".repeat(64),
                  GatewayFixtures.profile().modelId(),
                  GatewayFixtures.profile().id(),
                  1L)
              .created());
      assertEquals(3, fixture.store.adminModelCalls(fixture.admin.id(), null, null, 1, 20).total());
    }
  }

  @Test
  void immutableActualSnapshotsRetainMalformedOutputsAndRejectLateOrUnsafeWrites()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("snapshots"))) {
      for (String kind : List.of("GRADING", "RUBRIC_GENERATION")) {
        UUID id = UUID.randomUUID();
        prepare(fixture.store, fixture.alice.id(), id, kind);
        String snapshot = snapshot(kind, "真实system text", "标准答案及规则");
        fixture.store.recordModelRequest(fixture.alice.id(), id, kind, snapshot);
        fixture.store.recordModelRequest(fixture.alice.id(), id, kind, snapshot);
        assertCode(
            GatewayException.Code.IDEMPOTENCY_CONFLICT,
            () ->
                fixture.store.recordModelRequest(
                    fixture.alice.id(), id, kind, snapshot(kind, "changed", "changed")));
        assertCode(
            GatewayException.Code.NOT_FOUND,
            () -> fixture.store.recordModelRequest(fixture.bob.id(), id, kind, snapshot));
        assertCode(
            GatewayException.Code.REQUEST_IN_PROGRESS,
            () -> fixture.store.recordModelOutput(fixture.alice.id(), id, kind, "premature"));
        dispatch(fixture.store, fixture.alice.id(), id, kind);
        fixture.store.recordModelOutput(fixture.alice.id(), id, kind, "{invalid model JSON");
        finish(fixture.store, fixture.alice.id(), id, kind, null, "INVALID_MODEL_JSON");
        var detail = fixture.store.modelCallDetail(fixture.alice.id(), id, kind);
        assertEquals(snapshot, detail.requestJson());
        assertEquals("{invalid model JSON", detail.modelOutput());
        assertNull(detail.responseJson());
        assertEquals("FAILED", detail.call().state());
        assertEquals(1, detail.call().chargedCredits());
        assertEquals(CLOCK.instant(), detail.call().completedAt());
        fixture.store.recordModelOutput(fixture.alice.id(), id, kind, "{invalid model JSON");
        assertCode(
            GatewayException.Code.IDEMPOTENCY_CONFLICT,
            () -> fixture.store.recordModelOutput(fixture.alice.id(), id, kind, "late success"));
      }
      UUID unsafe = UUID.randomUUID();
      prepare(fixture.store, fixture.alice.id(), unsafe, "GRADING");
      var json =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              GatewayJson.MAPPER.readTree(snapshot("GRADING", "text", "text"));
      json.put("authorization", "synthetic forbidden header");
      assertCode(
          GatewayException.Code.INVALID_REQUEST,
          () ->
              fixture.store.recordModelRequest(
                  fixture.alice.id(), unsafe, "GRADING", json.toString()));
      json.remove("authorization");
      json.put("image_url", "synthetic forbidden image body");
      assertCode(
          GatewayException.Code.INVALID_REQUEST,
          () ->
              fixture.store.recordModelRequest(
                  fixture.alice.id(), unsafe, "GRADING", json.toString()));
      finish(fixture.store, fixture.alice.id(), unsafe, "GRADING", null, "CANCELLED");
      assertCode(
          GatewayException.Code.REQUEST_IN_PROGRESS,
          () ->
              fixture.store.recordModelRequest(
                  fixture.alice.id(), unsafe, "GRADING", snapshot("GRADING", "late", "late")));
    }
  }

  @Test
  void failureDiagnosticMergesWithoutChangingOriginalSnapshotsAndRemainsImmutable()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("failure-snapshot"))) {
      var failure =
          new GatewayModelFailure(
              GatewayModelFailure.Phase.AFTER_DISPATCH, "NETWORK", "MODEL_DNS_FAILURE", null);
      for (String kind : List.of("GRADING", "RUBRIC_GENERATION")) {
        UUID id = UUID.randomUUID();
        prepare(fixture.store, fixture.alice.id(), id, kind);
        String snapshot = snapshot(kind, "original system", "original answer and rules");
        fixture.store.recordModelRequest(fixture.alice.id(), id, kind, snapshot);
        assertCode(
            GatewayException.Code.REQUEST_IN_PROGRESS,
            () -> fixture.store.recordModelFailure(fixture.alice.id(), id, kind, failure));
        dispatch(fixture.store, fixture.alice.id(), id, kind);
        assertCode(
            GatewayException.Code.NOT_FOUND,
            () -> fixture.store.recordModelFailure(fixture.bob.id(), id, kind, failure));
        fixture.store.recordModelFailure(fixture.alice.id(), id, kind, failure);
        fixture.store.recordModelFailure(fixture.alice.id(), id, kind, failure);
        var saved =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                GatewayJson.MAPPER.readTree(
                    fixture.store.modelCallDetail(fixture.alice.id(), id, kind).requestJson());
        assertEquals(failure, GatewayModelFailure.read(saved.remove("failureDiagnostic")));
        assertEquals(GatewayJson.MAPPER.readTree(snapshot), saved);
        assertEquals(failure, fixture.store.modelFailure(fixture.alice.id(), id, kind));
        finish(fixture.store, fixture.alice.id(), id, kind, null, "MODEL_FAILED");
        fixture.store.recordModelFailure(fixture.alice.id(), id, kind, failure);
        assertCode(
            GatewayException.Code.IDEMPOTENCY_CONFLICT,
            () ->
                fixture.store.recordModelFailure(
                    fixture.alice.id(),
                    id,
                    kind,
                    new GatewayModelFailure(
                        GatewayModelFailure.Phase.AFTER_DISPATCH,
                        "TIMEOUT",
                        "MODEL_CONNECT_TIMEOUT",
                        null)));
      }
      UUID unsafe = UUID.randomUUID();
      prepare(fixture.store, fixture.alice.id(), unsafe, "GRADING");
      var injected =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              GatewayJson.MAPPER.readTree(snapshot("GRADING", "text", "text"));
      injected.set("failureDiagnostic", failure.toJson());
      assertCode(
          GatewayException.Code.INVALID_REQUEST,
          () ->
              fixture.store.recordModelRequest(
                  fixture.alice.id(), unsafe, "GRADING", injected.toString()));
      assertNull(fixture.store.modelFailure(fixture.alice.id(), unsafe, "GRADING"));
    }
  }

  @Test
  void failureWithoutPreparedSnapshotRecordsOnlyMetadataAndNeverInventsPrompt() throws Exception {
    try (var fixture = new Fixture(directory.resolve("metadata-only"))) {
      UUID id = UUID.randomUUID();
      prepare(fixture.store, fixture.alice.id(), id, "GRADING");
      var failure =
          new GatewayModelFailure(
              GatewayModelFailure.Phase.BEFORE_DISPATCH,
              "MISSING_CREDENTIAL",
              "MODEL_MISSING_CREDENTIAL",
              null);
      fixture.store.recordModelFailure(fixture.alice.id(), id, "GRADING", failure);
      var saved =
          GatewayJson.MAPPER.readTree(
              fixture.store.modelCallDetail(fixture.alice.id(), id, "GRADING").requestJson());
      assertEquals("NOT_RECORDED", saved.path("requestSnapshotStatus").asText());
      assertFalse(saved.has("providerRequest"));
      assertFalse(saved.has("question"));
      assertEquals(failure, fixture.store.modelFailure(fixture.alice.id(), id, "GRADING"));
      assertEquals(100, fixture.store.account(fixture.alice.id()).balance());
    }
  }

  @Test
  void v3UpgradeLeavesLegacyReceiptsIntactAndCreatesBothPagingIndexes() throws Exception {
    Path base = directory.resolve("old-v3");
    UUID owner, request = UUID.randomUUID();
    try (var fixture = new Fixture(base)) {
      owner = fixture.alice.id();
      prepare(fixture.store, owner, request, "GRADING");
      dispatch(fixture.store, owner, request, "GRADING");
      finish(fixture.store, owner, request, "GRADING", RESULT, null);
    }
    try (var db = DriverManager.getConnection("jdbc:h2:file:" + base, "sa", "");
        var sql = db.createStatement()) {
      for (String table : List.of("grade_requests", "rubric_generation_requests")) {
        for (String column : List.of("request_json", "raw_model_output", "completed_at"))
          sql.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        String prefix = table.equals("grade_requests") ? "grade_calls" : "rubric_calls";
        sql.execute("DROP INDEX IF EXISTS " + prefix + "_global_page");
        sql.execute("DROP INDEX IF EXISTS " + prefix + "_user_page");
      }
      sql.execute("DROP TABLE package_audit");
      sql.execute("DROP TABLE credit_packages");
      sql.execute("ALTER TABLE orders DROP COLUMN package_name");
      sql.execute("ALTER TABLE orders DROP COLUMN currency");
      sql.execute("DROP INDEX IF EXISTS accounts_registration_dashboard");
      sql.execute("DROP INDEX IF EXISTS quota_events_dashboard");
      sql.execute("DROP INDEX IF EXISTS orders_dashboard");
      sql.executeUpdate("UPDATE gateway_schema SET version=3 WHERE id=1");
    }
    try (var upgraded = new GatewayStore(base, CLOCK)) {
      var detail = upgraded.modelCallDetail(owner, request, "GRADING");
      assertEquals(RESULT, detail.responseJson());
      assertNull(detail.requestJson());
      assertNull(detail.modelOutput());
      assertNull(detail.call().completedAt());
      assertEquals("SUCCEEDED", detail.call().state());
      assertEquals(1, detail.call().chargedCredits());
    }
    try (var db = DriverManager.getConnection("jdbc:h2:file:" + base, "sa", "")) {
      for (String table : List.of("GRADE_REQUESTS", "RUBRIC_GENERATION_REQUESTS")) {
        var names = new HashSet<String>();
        try (var indexes =
            db.getMetaData().getIndexInfo(db.getCatalog(), "PUBLIC", table, false, false)) {
          while (indexes.next())
            if (indexes.getString("INDEX_NAME") != null) names.add(indexes.getString("INDEX_NAME"));
        }
        String prefix = table.startsWith("GRADE_") ? "GRADE_CALLS" : "RUBRIC_CALLS";
        assertTrue(names.contains(prefix + "_GLOBAL_PAGE"));
        assertTrue(names.contains(prefix + "_USER_PAGE"));
      }
    }
  }

  @Test
  void httpPaginationAndDetailsEnforceTokenOwnershipAndRejectUnknownQueryFields() throws Exception {
    try (var fixture = new Fixture(directory.resolve("http"));
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                fixture.store,
                new GatewayModelService(
                    fixture.store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                CLOCK)) {
      UUID id = UUID.randomUUID();
      prepare(fixture.store, fixture.alice.id(), id, "GRADING");
      fixture.store.recordModelRequest(
          fixture.alice.id(), id, "GRADING", snapshot("GRADING", "system", "user"));
      dispatch(fixture.store, fixture.alice.id(), id, "GRADING");
      fixture.store.recordModelOutput(fixture.alice.id(), id, "GRADING", "{bad json");
      fixture.store.recordModelFailure(
          fixture.alice.id(),
          id,
          "GRADING",
          new GatewayModelFailure(
              GatewayModelFailure.Phase.AFTER_DISPATCH, "HTTP_ERROR", "MODEL_HTTP_ERROR", 418));
      finish(fixture.store, fixture.alice.id(), id, "GRADING", null, "INVALID_MODEL_JSON");
      String admin = fixture.store.login("operator", GatewayFixtures.password()).token();
      String alice = fixture.store.login("alice", GatewayFixtures.password()).token();
      String bob = fixture.store.login("bravo", GatewayFixtures.password()).token();
      server.start();
      var page =
          get(client, server, "/v1/admin/model-calls?username=alice&page=1&pageSize=20", admin);
      assertEquals(200, page.status());
      assertEquals(1, page.json().path("total").asInt());
      assertFalse(page.json().path("calls").get(0).has("request"));
      assertEquals(404, get(client, server, "/v1/model-calls", alice).status());
      assertEquals(
          404, get(client, server, "/v1/model-calls/" + id + "?kind=GRADING", alice).status());
      assertEquals(404, get(client, server, "/v1/model-calls", bob).status());
      assertEquals(
          404, get(client, server, "/v1/model-calls?userId=" + fixture.alice.id(), bob).status());
      assertEquals(403, get(client, server, "/v1/admin/model-calls", bob).status());
      var detail =
          get(
              client,
              server,
              "/v1/admin/model-calls/" + id + "?kind=GRADING&userId=" + fixture.alice.id(),
              admin);
      assertEquals(200, detail.status());
      assertEquals("{bad json", detail.json().path("modelOutput").asText());
      assertEquals("RECORDED", detail.json().path("requestSnapshotStatus").asText());
      assertEquals("RECORDED", detail.json().path("failureDiagnosticStatus").asText());
      assertEquals(
          "MODEL_HTTP_ERROR",
          detail.json().path("failureDiagnostic").path("diagnosticCode").asText());
      assertEquals(418, detail.json().path("failureDiagnostic").path("upstreamStatus").asInt());
      assertFalse(detail.json().path("request").has("failureDiagnostic"));
      assertEquals(
          400,
          get(client, server, "/v1/admin/model-calls/" + id + "?kind=GRADING", admin).status());
      assertEquals(400, get(client, server, "/v1/admin/model-calls?page=0", admin).status());
    }
  }

  @Test
  void adminDetailsDistinguishLegacyAndMalformedDiagnosticsWithoutDisplayingUnsafeFields()
      throws Exception {
    Path base = directory.resolve("invalid-diagnostics");
    try (var fixture = new Fixture(base);
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                fixture.store,
                new GatewayModelService(
                    fixture.store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                CLOCK)) {
      UUID legacy = UUID.randomUUID(),
          corrupt = UUID.randomUUID(),
          metadataOnly = UUID.randomUUID();
      for (UUID id : List.of(legacy, corrupt, metadataOnly))
        prepare(fixture.store, fixture.alice.id(), id, "GRADING");
      fixture.store.recordModelRequest(
          fixture.alice.id(), corrupt, "GRADING", snapshot("GRADING", "system", "user"));
      var invalid =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              GatewayJson.MAPPER.readTree(
                  fixture
                      .store
                      .modelCallDetail(fixture.alice.id(), corrupt, "GRADING")
                      .requestJson());
      invalid.putObject("failureDiagnostic").put("exception", "SYNTHETIC_PRIVATE_EXCEPTION");
      try (var db = DriverManager.getConnection("jdbc:h2:file:" + base, "sa", "");
          var update =
              db.prepareStatement(
                  "UPDATE grade_requests SET request_json=? WHERE user_id=? AND request_id=?")) {
        update.setString(1, invalid.toString());
        update.setString(2, fixture.alice.id().toString());
        update.setString(3, corrupt.toString());
        assertEquals(1, update.executeUpdate());
      }
      assertCode(
          GatewayException.Code.STORAGE_FAILURE,
          () -> fixture.store.modelFailure(fixture.alice.id(), corrupt, "GRADING"));
      fixture.store.recordModelFailure(
          fixture.alice.id(),
          metadataOnly,
          "GRADING",
          new GatewayModelFailure(
              GatewayModelFailure.Phase.BEFORE_DISPATCH,
              "MISSING_CREDENTIAL",
              "MODEL_MISSING_CREDENTIAL",
              null));
      String admin = fixture.store.login("operator", GatewayFixtures.password()).token();
      server.start();
      String path = "/v1/admin/model-calls/";
      String query = "?kind=GRADING&userId=" + fixture.alice.id();
      var old = get(client, server, path + legacy + query, admin);
      assertEquals("NOT_RECORDED_LEGACY", old.json().path("failureDiagnosticStatus").asText());
      assertTrue(old.json().path("failureDiagnostic").isNull());
      var bad = get(client, server, path + corrupt + query, admin);
      assertEquals(200, bad.status());
      assertEquals("INVALID_RECORDED_DATA", bad.json().path("failureDiagnosticStatus").asText());
      assertTrue(bad.json().path("failureDiagnostic").isNull());
      assertFalse(bad.json().toString().contains("SYNTHETIC_PRIVATE_EXCEPTION"));
      var noPrompt = get(client, server, path + metadataOnly + query, admin);
      assertEquals("NOT_RECORDED", noPrompt.json().path("requestSnapshotStatus").asText());
      assertTrue(noPrompt.json().path("request").isNull());
      assertEquals("RECORDED", noPrompt.json().path("failureDiagnosticStatus").asText());
    }
  }

  static String snapshot(String kind, String system, String user) {
    var root = GatewayJson.MAPPER.createObjectNode().put("schemaVersion", 1).put("kind", kind);
    root.putObject("question")
        .put("id", "q1")
        .put("prompt", "1+1?")
        .put("standardAnswer", "2")
        .put("maxScore", 1);
    var provider =
        root.putObject("providerRequest").put("model", GatewayFixtures.profile().modelId());
    var messages = provider.putArray("messages");
    messages
        .addObject()
        .put("role", "system")
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", system);
    var content = messages.addObject().put("role", "user").putArray("content");
    content.addObject().put("type", "text").put("text", user);
    content.addObject().put("type", "image").put("sha256", "a".repeat(64)).put("bytes", 123);
    return root.toString();
  }

  static void prepare(GatewayStore store, UUID owner, UUID id, String kind) {
    var p = GatewayFixtures.profile();
    if (kind.equals("GRADING")) store.prepare(owner, id, "a".repeat(64), p.modelId(), p.id(), 1L);
    else store.prepareRubricGeneration(owner, id, "a".repeat(64), p.modelId(), p.id(), 1L);
  }

  static void dispatch(GatewayStore store, UUID owner, UUID id, String kind) {
    if (kind.equals("GRADING")) store.beforeDispatch(owner, id);
    else store.beforeRubricGenerationDispatch(owner, id, null);
  }

  static void finish(
      GatewayStore store, UUID owner, UUID id, String kind, String result, String error) {
    if (kind.equals("GRADING")) store.finish(owner, id, result, error, false);
    else store.finishRubricGeneration(owner, id, result, error, false);
  }

  private static void assertCode(GatewayException.Code expected, Runnable operation) {
    assertEquals(expected, assertThrows(GatewayException.class, operation::run).code());
  }

  private record Reply(int status, JsonNode json) {}

  private static Reply get(HttpClient client, GatewayServer server, String path, String token)
      throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), GatewayJson.MAPPER.readTree(response.body()));
  }

  private static final class Fixture implements AutoCloseable {
    final GatewayStore store;
    final GatewayStore.Account admin, alice, bob;

    Fixture(Path base) {
      store = new GatewayStore(base, CLOCK);
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      alice = store.register("alice", GatewayFixtures.password());
      bob = store.register("bravo", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), alice.id(), 100, "synthetic test credits");
      store.adjust(admin.id(), UUID.randomUUID(), bob.id(), 100, "synthetic test credits");
      var profile = GatewayFixtures.profile();
      GatewayFixtures.activate(store, admin.id(), profile);
      store.selectRubricModelPolicy(
          admin.id(),
          UUID.randomUUID(),
          profile.id(),
          0,
          "synthetic generator policy",
          id -> profile.modelId());
    }

    @Override
    public void close() {
      store.close();
    }
  }
}
