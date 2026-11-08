package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedisSessionTokenStoreTest {
  private static final String HASH = "a".repeat(64);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void refusesNamespacesThatCouldTouchAnotherApplicationsKeys() {
    for (String namespace :
        new String[] {
          "",
          "other:",
          "deskgrade:*:",
          "deskgrade:{slot}:",
          "deskgrade:line\nbreak:",
          "deskgrade:missing-separator",
          "deskgrade:" + "x".repeat(160) + ":"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new RedisSessionTokenStore("127.0.0.1", 6379, null, namespace, CLOCK));
    }
  }

  @Test
  void rejectsRawBearerTokensAndNonexpiringSessionsBeforeContactingRedis() {
    try (var store = new RedisSessionTokenStore("127.0.0.1", 1, null, "deskgrade:test:", CLOCK)) {
      String token = "x".repeat(43);
      UUID user = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class, () -> store.find(token));
      assertThrows(IllegalArgumentException.class, () -> store.delete(token));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.put(token, user, CLOCK.instant().plusSeconds(1)));
      assertThrows(IllegalArgumentException.class, () -> store.put(HASH, user, CLOCK.instant()));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.put(HASH, user, CLOCK.instant().plus(Duration.ofDays(31))));
    }
  }

  @Test
  void unavailableRedisFailsClosedWithABoundedSanitizedError() throws Exception {
    int port;
    try (var reserved = new ServerSocket(0)) {
      port = reserved.getLocalPort();
    }
    try (var store =
        new RedisSessionTokenStore(
            "127.0.0.1", port, "synthetic-password-that-must-not-leak", "deskgrade:test:", CLOCK)) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> {
            var failure = assertThrows(GatewayException.class, store::verifyAvailable);
            assertEquals(GatewayException.Code.SERVICE_UNAVAILABLE, failure.code());
            assertEquals("SERVICE_UNAVAILABLE", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("synthetic-password"));
          });
    }
  }

  @Test
  void closedStoreCannotAuthorizeAndClosingTwiceIsSafe() {
    var store = new RedisSessionTokenStore("127.0.0.1", 1, null, "deskgrade:test:", CLOCK);
    store.close();
    assertDoesNotThrow(store::close);
    assertEquals(
        GatewayException.Code.SERVICE_UNAVAILABLE,
        assertThrows(GatewayException.class, () -> store.find(HASH)).code());
    assertEquals(
        GatewayException.Code.SERVICE_UNAVAILABLE,
        assertThrows(GatewayException.class, store::verifyAvailable).code());
  }
}
