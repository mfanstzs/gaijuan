package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.RubricJsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewaySchemaMigrationTest {
  @TempDir Path temp;

  @Test
  void versionOneDatabaseMigratesWithoutChangingPaidReceiptsBalancesOrAudit() throws Exception {
    Path database = temp.resolve("legacy");
    UUID admin, user, requestId = UUID.randomUUID();
    var legacy = GatewayFixtures.body(requestId);
    legacy.remove("policyRevision");
    byte[] image = Base64.getDecoder().decode(legacy.path("imageBase64").asText());
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(GatewayFixtures.profile().id().getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(
        new RubricJsonCodec().write(GatewayFixtures.rubric()).getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    String fingerprint = HexFormat.of().formatHex(digest.digest(image));
    var original = GatewayJson.MAPPER.createObjectNode();
    var gradingRequest =
        new app.deskgrade.core.domain.GradingRequest(
            requestId, GatewayFixtures.rubric().question(), image);
    var response = GatewayFixtures.response(gradingRequest);
    original.put("requestId", requestId.toString());
    original.put("source", response.source().name());
    original.set(
        "result", GatewayJson.parse(GatewayJson.MAPPER.writeValueAsBytes(response.result())));
    original.put("completedAt", response.completedAt().toString());
    original.put("elapsedMs", response.elapsed().toMillis());
    original.putNull("usage");
    List<GatewayStore.LedgerEntry> before;
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 3, "legacy synthetic allocation");
      store.prepare(user, requestId, fingerprint, GatewayFixtures.profile().modelId());
      store.beforeDispatch(user, requestId);
      store.finish(user, requestId, original.toString(), null, false);
      before = store.ledger(admin, user, 100);
    }
    // Reconstruct the exact C1 schema shape, with a paid C1 receipt and no C2 policy metadata.
    String jdbc =
        "jdbc:h2:file:"
            + database.toAbsolutePath().toString().replace('\\', '/')
            + ";IFEXISTS=TRUE;WRITE_DELAY=0";
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement()) {
      sql.execute("ALTER TABLE grade_requests DROP COLUMN profile_id");
      sql.execute("ALTER TABLE grade_requests DROP COLUMN policy_revision");
      sql.execute("DROP TABLE model_policy_audit");
      sql.execute("DROP TABLE gateway_model_policy");
      sql.execute("DROP TABLE rubric_model_policy_audit");
      sql.execute("DROP TABLE gateway_rubric_model_policy");
      sql.execute("DROP TABLE rubric_generation_requests");
      sql.execute("DROP TABLE package_audit");
      sql.execute("DROP TABLE credit_packages");
      sql.execute("ALTER TABLE orders DROP COLUMN package_name");
      sql.execute("ALTER TABLE orders DROP COLUMN currency");
      sql.execute("ALTER TABLE grade_requests DROP COLUMN request_json");
      sql.execute("ALTER TABLE grade_requests DROP COLUMN raw_model_output");
      sql.execute("ALTER TABLE grade_requests DROP COLUMN completed_at");
      sql.execute("DROP INDEX IF EXISTS accounts_registration_dashboard");
      sql.execute("DROP INDEX IF EXISTS quota_events_dashboard");
      sql.execute("DROP INDEX IF EXISTS orders_dashboard");
      sql.execute("DROP INDEX IF EXISTS grade_calls_global_page");
      sql.execute("DROP INDEX IF EXISTS grade_calls_user_page");
      sql.executeUpdate("UPDATE gateway_schema SET version=1 WHERE id=1");
    }
    assertTrue(GatewayStore.adminPresent(database));
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement();
        var rs = sql.executeQuery("SELECT version FROM gateway_schema WHERE id=1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1), "admin-status must not migrate the C1 database");
    }
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals(new GatewayStore.ModelPolicy(0, null, null), store.modelPolicy());
      assertEquals(before, store.ledger(admin, user, 100));
      assertEquals(2, store.account(user).balance());
      var receipt = store.request(user, requestId);
      assertNull(receipt.profileId());
      assertNull(receipt.policyRevision());
      assertEquals("SUCCEEDED", receipt.state());
      assertEquals(original.toString(), receipt.responseJson());
      var calls = new AtomicInteger();
      var service =
          new GatewayModelService(
              store,
              List.of(),
              (p, r, q, h) -> {
                calls.incrementAndGet();
                throw new AssertionError();
              });
      var replay = service.grade(user, legacy);
      assertEquals(200, replay.status());
      assertTrue(replay.body().path("replayed").asBoolean());
      assertEquals(1, replay.body().path("chargedCredits").asInt());
      assertEquals(original.path("result"), replay.body().path("result"));
      assertEquals(original.path("completedAt"), replay.body().path("completedAt"));
      assertEquals(0, calls.get());
      assertEquals(2, store.account(user).balance());
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(
                  GatewayException.class,
                  () -> service.grade(user, legacy.deepCopy().put("policyRevision", 1)))
              .code());
      var newLegacy = legacy.deepCopy().put("requestId", UUID.randomUUID().toString());
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(GatewayException.class, () -> service.grade(user, newLegacy)).code());
    }
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement();
        var rs = sql.executeQuery("SELECT version FROM gateway_schema WHERE id=1")) {
      assertTrue(rs.next());
      assertEquals(6, rs.getInt(1));
    }
  }

  @Test
  void versionTwoDatabaseUpgradesAdditivelyWithoutChangingExistingData() throws Exception {
    Path database = temp.resolve("version-two");
    UUID admin;
    UUID user;
    long balance;
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      admin = store.bootstrapAdmin("operator", GatewayFixtures.password()).id();
      user = store.register("alice", GatewayFixtures.password()).id();
      store.adjust(admin, UUID.randomUUID(), user, 7, "v2 synthetic balance");
      GatewayFixtures.activate(store, admin, GatewayFixtures.profile());
      balance = store.account(user).balance();
    }
    String jdbc =
        "jdbc:h2:file:"
            + database.toAbsolutePath().toString().replace('\\', '/')
            + ";IFEXISTS=TRUE;WRITE_DELAY=0";
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE rubric_model_policy_audit");
      sql.execute("DROP TABLE gateway_rubric_model_policy");
      sql.execute("DROP TABLE rubric_generation_requests");
      sql.execute("DROP TABLE package_audit");
      sql.execute("DROP TABLE credit_packages");
      sql.execute("ALTER TABLE orders DROP COLUMN package_name");
      sql.execute("ALTER TABLE orders DROP COLUMN currency");
      for (String table : List.of("grade_requests"))
        for (String column : List.of("request_json", "raw_model_output", "completed_at"))
          sql.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
      sql.execute("DROP INDEX IF EXISTS accounts_registration_dashboard");
      sql.execute("DROP INDEX IF EXISTS quota_events_dashboard");
      sql.execute("DROP INDEX IF EXISTS orders_dashboard");
      sql.execute("DROP INDEX IF EXISTS grade_calls_global_page");
      sql.execute("DROP INDEX IF EXISTS grade_calls_user_page");
      sql.executeUpdate("UPDATE gateway_schema SET version=2 WHERE id=1");
    }
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      assertEquals(balance, store.account(user).balance());
      assertEquals(1, store.modelPolicy().revision());
      assertEquals(new GatewayStore.ModelPolicy(0, null, null), store.rubricModelPolicy());
      assertTrue(store.rubricModelPolicyAudit(admin).isEmpty());
    }
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement();
        var rs = sql.executeQuery("SELECT version FROM gateway_schema WHERE id=1")) {
      assertTrue(rs.next());
      assertEquals(6, rs.getInt(1));
    }
  }

  @Test
  void versionFiveOrdersGainImmutableCatalogueSnapshotsBeforeSchemaVersionMoves() throws Exception {
    Path database = temp.resolve("version-five");
    UUID user;
    UUID orderRequest = UUID.randomUUID();
    try (var store = new GatewayStore(database, Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      user = store.register("alice", GatewayFixtures.password()).id();
      store.createPackage(admin.id(), "history-20", "历史二十次", 20, 880L, "CNY", true);
      store.createOrderFromPackage(user, orderRequest, "history-20");
    }
    String jdbc =
        "jdbc:h2:file:"
            + database.toAbsolutePath().toString().replace('\\', '/')
            + ";IFEXISTS=TRUE;WRITE_DELAY=0";
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE package_audit");
      sql.execute("ALTER TABLE orders DROP COLUMN package_name");
      sql.execute("ALTER TABLE orders DROP COLUMN currency");
      sql.execute("DROP INDEX IF EXISTS accounts_registration_dashboard");
      sql.execute("DROP INDEX IF EXISTS quota_events_dashboard");
      sql.execute("DROP INDEX IF EXISTS orders_dashboard");
      sql.executeUpdate("UPDATE gateway_schema SET version=5 WHERE id=1");
    }

    try (var upgraded = new GatewayStore(database, Clock.systemUTC())) {
      var order = upgraded.orders(user, 0, 10).getFirst();
      assertEquals(orderRequest, order.requestId());
      assertEquals("历史二十次", order.packageName());
      assertEquals("CNY", order.currency());
    }
    try (var db = DriverManager.getConnection(jdbc, "sa", "");
        var query = db.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rows = query.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(6, rows.getInt(1));
    }
  }
}
