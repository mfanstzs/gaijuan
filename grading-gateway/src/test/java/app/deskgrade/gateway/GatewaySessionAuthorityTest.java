package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Relational session authority is tested independently from a live Redis installation. */
class GatewaySessionAuthorityTest {
  @TempDir Path temp;

  @Test
  void cacheMissRebuildsOnlyAfterRedisIsAvailableAndSqlSessionIsValid() {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    try (var store = new GatewayStore(temp.resolve("cache-miss"), clock, cache)) {
      var user = store.register("alice", GatewayFixtures.password());
      var login = store.login("alice", GatewayFixtures.password());
      String digest = GatewayStore.hash(login.token());
      assertEquals(user.id(), cache.values.get(digest).userId());
      assertFalse(cache.values.containsKey(login.token()));
      cache.values.clear();
      cache.available = false;
      assertEquals(
          GatewayException.Code.SERVICE_UNAVAILABLE,
          assertThrows(GatewayException.class, () -> store.authenticate(login.token())).code());
      assertTrue(cache.values.isEmpty());
      cache.available = true;
      assertEquals(user.id(), store.authenticate(login.token()).id());
      assertEquals(login.expiresAt(), cache.values.get(digest).expiresAt());
    }
  }

  @Test
  void logoutCommitsSqlRevocationEvenWhenDeletingRedisFailsAndOldSnapshotReturns() {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    Path db = temp.resolve("revoke");
    String token;
    UUID userId;
    try (var store = new GatewayStore(db, clock, cache)) {
      var user = store.register("alice", GatewayFixtures.password());
      userId = user.id();
      token = store.login("alice", GatewayFixtures.password()).token();
      cache.failDelete = true;
      assertEquals(
          GatewayException.Code.SERVICE_UNAVAILABLE,
          assertThrows(GatewayException.class, () -> store.logout(token)).code());
      assertTrue(cache.values.containsKey(GatewayStore.hash(token)));
      cache.failDelete = false;
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> store.authenticate(token)).code());
    }
    try (var restarted = new GatewayStore(db, clock, cache)) {
      assertEquals(0, restarted.account(userId).balance());
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> restarted.authenticate(token)).code());
    }
  }

  @Test
  void redisFailureBeforeDispatchCannotDebitOrChangePreparedRequest() {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    try (var store = new GatewayStore(temp.resolve("dispatch-cache"), clock, cache)) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 2, "synthetic budget");
      String token = store.login("alice", GatewayFixtures.password()).token();
      UUID request = UUID.randomUUID();
      store.prepare(user.id(), request, "a".repeat(64), "synthetic-model");
      cache.available = false;
      assertEquals(
          GatewayException.Code.SERVICE_UNAVAILABLE,
          assertThrows(
                  GatewayException.class, () -> store.beforeDispatch(user.id(), request, token))
              .code());
      assertEquals(2, store.account(user.id()).balance());
      assertFalse(store.request(user.id(), request).charged());
      assertEquals("PREPARED", store.request(user.id(), request).state());
      cache.available = true;
      store.beforeDispatch(user.id(), request, token);
      assertEquals(1, store.account(user.id()).balance());
      assertTrue(store.request(user.id(), request).charged());
    }
  }

  @Test
  void expiredSqlSessionCannotBeRevivedByLongerLivedRedisEntry() {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    try (var store = new GatewayStore(temp.resolve("expired"), clock, cache)) {
      var user = store.register("alice", GatewayFixtures.password());
      var login = store.login("alice", GatewayFixtures.password());
      cache.values.put(
          GatewayStore.hash(login.token()),
          new SessionTokenStore.Session(user.id(), login.expiresAt().plusSeconds(3600)));
      clock.now = login.expiresAt();
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> store.authenticate(login.token())).code());
    }
  }

  @Test
  void failedCacheInsertionRollsBackDurableLoginWithoutReturningToken() throws Exception {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    Path db = temp.resolve("login-rollback");
    try (var store = new GatewayStore(db, clock, cache)) {
      store.register("alice", GatewayFixtures.password());
      cache.failPut = true;
      assertEquals(
          GatewayException.Code.SERVICE_UNAVAILABLE,
          assertThrows(
                  GatewayException.class, () -> store.login("alice", GatewayFixtures.password()))
              .code());
      try (var jdbc =
              DriverManager.getConnection(
                  "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/'), "sa", "");
          var query = jdbc.prepareStatement("SELECT COUNT(*) FROM sessions");
          var rs = query.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong(1));
      }
    }
  }

  @Test
  void adminRevocationCommitsAllTargetSessionsBeforeCacheFailureAndLeavesOthersAlone() {
    var clock = new GatewayStoreTest.MutableClock();
    var cache = new FakeCache();
    try (var store = new GatewayStore(temp.resolve("admin-revoke"), clock, cache)) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var target = store.register("alice", GatewayFixtures.password());
      var other = store.register("bob", GatewayFixtures.password());
      String first = store.login("alice", GatewayFixtures.password()).token();
      String second = store.login("alice", GatewayFixtures.password()).token();
      String unaffected = store.login("bob", GatewayFixtures.password()).token();

      assertEquals(
          GatewayException.Code.FORBIDDEN,
          assertThrows(GatewayException.class, () -> store.revokeSessions(other.id(), target.id()))
              .code());
      assertEquals(target.id(), store.authenticate(first).id());

      cache.failRevoke = true;
      assertEquals(
          GatewayException.Code.SERVICE_UNAVAILABLE,
          assertThrows(GatewayException.class, () -> store.revokeSessions(admin.id(), target.id()))
              .code());
      cache.failRevoke = false;
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> store.authenticate(first)).code());
      assertEquals(
          GatewayException.Code.UNAUTHENTICATED,
          assertThrows(GatewayException.class, () -> store.authenticate(second)).code());
      assertEquals(other.id(), store.authenticate(unaffected).id());
    }
  }

  static final class FakeCache implements SessionTokenStore {
    final Map<String, Session> values = new HashMap<>();
    boolean available = true, failPut, failDelete, failRevoke;

    public void put(String hash, UUID userId, Instant expiresAt) {
      verifyAvailable();
      if (failPut) throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
      values.put(hash, new Session(userId, expiresAt));
    }

    public Optional<Session> find(String hash) {
      verifyAvailable();
      return Optional.ofNullable(values.get(hash));
    }

    public void delete(String hash) {
      verifyAvailable();
      if (failDelete) throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
      values.remove(hash);
    }

    public void revokeUser(UUID userId) {
      verifyAvailable();
      if (failRevoke) throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
      values.values().removeIf(v -> v.userId().equals(userId));
    }

    public void verifyAvailable() {
      if (!available) throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
    }

    public void close() {}
  }
}
