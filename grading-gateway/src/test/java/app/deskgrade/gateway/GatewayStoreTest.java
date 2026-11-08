package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayStoreTest {
  @TempDir Path temp;

  @Test
  void credentialsAreSaltedAndTokensAreOnlyHashedExpiredAndRevocable() throws Exception {
    var clock = new MutableClock();
    Path database = temp.resolve("auth");
    try (var store = new GatewayStore(database, clock)) {
      var first = store.register("Alice", GatewayFixtures.password());
      var second = store.register("bob", GatewayFixtures.password());
      assertEquals(0, first.balance());
      assertEquals("alice", first.username());
      assertEquals("USER", first.role());
      var login = store.login("ALICE", GatewayFixtures.password());
      assertEquals(first.id(), store.authenticate(login.token()).id());
      assertFalse(login.toString().contains(login.token()));
      try (var connection =
              DriverManager.getConnection(
                  "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/'),
                  "sa",
                  "");
          var statement = connection.createStatement()) {
        try (var rs =
            statement.executeQuery(
                "SELECT salt,password_hash,iterations FROM accounts ORDER BY username")) {
          assertTrue(rs.next());
          byte[] salt = rs.getBytes(1), hash = rs.getBytes(2);
          assertTrue(rs.getInt(3) >= 600000);
          assertTrue(rs.next());
          assertFalse(java.util.Arrays.equals(salt, rs.getBytes(1)));
          assertFalse(java.util.Arrays.equals(hash, rs.getBytes(2)));
        }
        try (var rs = statement.executeQuery("SELECT token_hash FROM sessions")) {
          assertTrue(rs.next());
          assertEquals(GatewayStore.hash(login.token()), rs.getString(1));
          assertNotEquals(login.token(), rs.getString(1));
        }
      }
      store.logout(login.token());
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> store.authenticate(login.token())).code());
      var later = store.login(second.username(), GatewayFixtures.password());
      clock.now = clock.now.plusSeconds(12 * 3600);
      assertThrows(GatewayException.class, () -> store.authenticate(later.token()));
      assertThrows(
          GatewayException.class,
          () -> store.login("alice", "wrong synthetic password".toCharArray()));
    }
  }

  @Test
  void adminAdjustmentIsAtomicIdempotentAuditedAndCannotOverdraw() {
    try (var store = new GatewayStore(temp.resolve("credits"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("student", GatewayFixtures.password());
      UUID id = UUID.randomUUID();
      assertEquals(
          GatewayException.Code.FORBIDDEN,
          assertThrows(
                  GatewayException.class,
                  () -> store.adjust(user.id(), id, user.id(), 5, "synthetic adjustment"))
              .code());
      var first = store.adjust(admin.id(), id, user.id(), 5, "synthetic adjustment");
      assertEquals(first, store.adjust(admin.id(), id, user.id(), 5, "synthetic adjustment"));
      assertEquals(5, store.account(user.id()).balance());
      assertEquals(
          GatewayException.Code.IDEMPOTENCY_CONFLICT,
          assertThrows(
                  GatewayException.class,
                  () -> store.adjust(admin.id(), id, user.id(), 6, "synthetic adjustment"))
              .code());
      assertThrows(
          GatewayException.class,
          () -> store.adjust(admin.id(), UUID.randomUUID(), user.id(), -6, "cannot overdraw"));
      assertEquals(5, store.account(user.id()).balance());
      var ledger = store.ledger(admin.id(), user.id(), 100);
      assertEquals(1, ledger.size());
      assertEquals(0, ledger.getFirst().balanceBefore());
      assertEquals(5, ledger.getFirst().balanceAfter());
      assertEquals(id, ledger.getFirst().id());
      assertThrows(
          GatewayException.class,
          () -> store.bootstrapAdmin("anotheradmin", GatewayFixtures.password()));
    }
  }

  @Test
  void pendingOrdersNeverCreditAndStayScopedToTheirUser() {
    try (var store = new GatewayStore(temp.resolve("orders"), Clock.systemUTC())) {
      var one = store.register("alice", GatewayFixtures.password());
      var two = store.register("bob", GatewayFixtures.password());
      UUID id = UUID.randomUUID();
      var order = store.createOrder(one.id(), id, "calls-100", 100, null);
      assertEquals("PENDING_PAYMENT", order.status());
      assertNull(order.priceCents());
      assertEquals(order, store.createOrder(one.id(), id, "calls-100", 100, null));
      assertThrows(
          GatewayException.class, () -> store.createOrder(one.id(), id, "calls-500", 500, null));
      assertTrue(store.orders(two.id(), 0, 100).isEmpty());
      assertEquals(0, store.account(one.id()).balance());
    }
  }

  @Test
  void concurrentDistinctRequestsSpendOnlyAvailableCredits() throws Exception {
    try (var store = new GatewayStore(temp.resolve("concurrent"), Clock.systemUTC());
        var workers = Executors.newFixedThreadPool(8)) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 3, "synthetic budget");
      var tasks = new java.util.ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 12; i++) {
        UUID id = UUID.randomUUID();
        store.prepare(user.id(), id, "a".repeat(64), "model");
        tasks.add(
            () -> {
              try {
                store.beforeDispatch(user.id(), id);
                return true;
              } catch (GatewayException insufficient) {
                assertEquals(GatewayException.Code.INSUFFICIENT_QUOTA, insufficient.code());
                return false;
              }
            });
      }
      int charged = 0;
      for (var task : workers.invokeAll(tasks)) if (task.get()) charged++;
      assertEquals(3, charged);
      assertEquals(0, store.account(user.id()).balance());
      assertEquals(
          3,
          store.ledger(admin.id(), user.id(), 100).stream()
              .filter(e -> e.kind().equals("MODEL_DISPATCH"))
              .count());
    }
  }

  @Test
  void restartDistinguishesPreparedFromDispatchUncertaintyWithoutRefund() {
    Path db = temp.resolve("recovery");
    UUID userId, prepared = UUID.randomUUID(), dispatch = UUID.randomUUID();
    try (var store = new GatewayStore(db, Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      userId = user.id();
      store.adjust(admin.id(), UUID.randomUUID(), userId, 2, "synthetic budget");
      store.prepare(userId, prepared, "a".repeat(64), "model");
      store.prepare(userId, dispatch, "b".repeat(64), "model");
      store.beforeDispatch(userId, dispatch);
      assertThrows(GatewayException.class, () -> new GatewayStore(db, Clock.systemUTC()));
    }
    try (var reopened = new GatewayStore(db, Clock.systemUTC())) {
      assertEquals("NOT_DISPATCHED", reopened.request(userId, prepared).state());
      assertFalse(reopened.request(userId, prepared).charged());
      assertEquals("UNKNOWN", reopened.request(userId, dispatch).state());
      assertTrue(reopened.request(userId, dispatch).charged());
      assertEquals(1, reopened.account(userId).balance());
      assertFalse(reopened.prepare(userId, dispatch, "b".repeat(64), "model").created());
      assertThrows(GatewayException.class, () -> reopened.beforeDispatch(userId, dispatch));
    }
  }

  @Test
  void profileIdentifiersCannotBeRepurposedAndLoginBudgetsExpire() {
    var clock = new MutableClock();
    try (var store = new GatewayStore(temp.resolve("profiles"), clock)) {
      store.bindProfile("paid-v1", "a".repeat(64));
      store.bindProfile("paid-v1", "a".repeat(64));
      assertThrows(GatewayException.class, () -> store.bindProfile("paid-v1", "b".repeat(64)));
    }
    var limiter = new LoginLimiter(clock);
    for (int i = 0; i < LoginLimiter.LOGIN_FAILURE_LIMIT; i++) {
      limiter.acquireLoginAddress("127.0.0.1");
      limiter.checkLoginUsername("synthetic");
      limiter.loginFailed("synthetic");
    }
    assertThrows(GatewayException.class, () -> limiter.checkLoginUsername("synthetic"));
    limiter.loginSucceeded("synthetic");
    assertDoesNotThrow(() -> limiter.checkLoginUsername("synthetic"));
    clock.now = clock.now.plusSeconds(15 * 60);
    assertDoesNotThrow(() -> limiter.acquireLoginAddress("127.0.0.1"));
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-09-10T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
