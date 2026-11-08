package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

class GatewayAbuseProtectionTest {
  @Test
  void loginRegistrationAndHashBudgetsAreIndependentAndBounded() {
    var clock = new GatewayStoreTest.MutableClock();
    var limiter = new LoginLimiter(clock);
    for (int i = 0; i < LoginLimiter.REGISTRATION_ADDRESS_LIMIT; i++)
      limiter.acquireRegistrationAddress("127.0.0.1");
    var limited =
        assertThrows(GatewayException.class, () -> limiter.acquireRegistrationAddress("127.0.0.1"));
    assertEquals(GatewayException.Code.RATE_LIMITED, limited.code());
    assertTrue(limited.retryAfterSeconds() > 0);
    assertDoesNotThrow(() -> limiter.acquireLoginAddress("127.0.0.1"));

    var permits = new ArrayList<LoginLimiter.PasswordPermit>();
    try {
      for (int i = 0; i < LoginLimiter.PASSWORD_HASH_CONCURRENCY; i++)
        permits.add(limiter.acquirePasswordHash());
      assertEquals(
          GatewayException.Code.RATE_LIMITED,
          assertThrows(GatewayException.class, limiter::acquirePasswordHash).code());
      permits.remove(0).close();
      permits.add(limiter.acquirePasswordHash());
    } finally {
      permits.forEach(LoginLimiter.PasswordPermit::close);
    }
  }

  @Test
  void requestBudgetsAreBoundedPerCredentialAndPerAccount() {
    var clock = new GatewayStoreTest.MutableClock();
    var limiter = new RequestLimiter(clock);
    String token = token((byte) 1), otherToken = token((byte) 2);
    for (int i = 0; i < RequestLimiter.TOKEN_LIMIT; i++) limiter.acquireToken(token);
    var tokenLimit = assertThrows(GatewayException.class, () -> limiter.acquireToken(token));
    assertEquals(GatewayException.Code.RATE_LIMITED, tokenLimit.code());
    assertTrue(tokenLimit.retryAfterSeconds() > 0);
    assertDoesNotThrow(() -> limiter.acquireToken(otherToken));

    UUID user = UUID.randomUUID();
    for (int i = 0; i < RequestLimiter.USER_LIMIT; i++) limiter.acquireUser(user);
    assertEquals(
        GatewayException.Code.RATE_LIMITED,
        assertThrows(GatewayException.class, () -> limiter.acquireUser(user)).code());
    assertDoesNotThrow(() -> limiter.acquireUser(UUID.randomUUID()));
    clock.now = clock.now.plus(RequestLimiter.WINDOW);
    assertDoesNotThrow(() -> limiter.acquireToken(token));
    assertDoesNotThrow(() -> limiter.acquireUser(user));
  }

  @Test
  void newPasswordsUseFifteenCharacterPolicyButLegacyTwelveCharacterHashesStillVerify()
      throws Exception {
    assertThrows(
        GatewayException.class, () -> PasswordHasher.validate("abcdefghijkl".toCharArray()));
    for (String common :
        new String[] {"deskgrade123456", "password-1234567", "admin_1234567890", "123456123456123"})
      assertThrows(
          GatewayException.class, () -> PasswordHasher.validate(common.toCharArray()), common);
    assertDoesNotThrow(
        () -> PasswordHasher.validate("my password is a long private phrase".toCharArray()));

    char[] legacy = "twelve-chars".toCharArray();
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    PBEKeySpec spec = new PBEKeySpec(legacy, salt, PasswordHasher.ITERATIONS, 256);
    byte[] expected =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    try {
      assertTrue(PasswordHasher.verify(legacy, salt, expected, PasswordHasher.ITERATIONS));
    } finally {
      spec.clearPassword();
      Arrays.fill(legacy, '\0');
      Arrays.fill(salt, (byte) 0);
      Arrays.fill(expected, (byte) 0);
    }
  }

  @Test
  void jdkHttpServerLimitsAreExplicitAndTestable() {
    GatewayMain.configureHttpServerLimits();
    assertAll(
        () -> assertEquals("64", System.getProperty("sun.net.httpserver.maxReqHeaders")),
        () -> assertEquals("32768", System.getProperty("sun.net.httpserver.maxReqHeaderSize")),
        () -> assertEquals("256", System.getProperty("jdk.httpserver.maxConnections")),
        () -> assertEquals("30", System.getProperty("sun.net.httpserver.maxReqTime")),
        () -> assertEquals("660", System.getProperty("sun.net.httpserver.maxRspTime")),
        () -> assertEquals("30", System.getProperty("sun.net.httpserver.idleInterval")));
  }

  private static String token(byte marker) {
    byte[] bytes = new byte[32];
    bytes[bytes.length - 1] = marker;
    try {
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      assertEquals(43, token.length());
      return token;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }
}
