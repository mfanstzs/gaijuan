package app.deskgrade.gateway;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2 cost follows the OWASP SHA-256 minimum; no lower-cost production/test override. */
final class PasswordHasher {
  static final int ITERATIONS = 600_000;
  static final int MIN_NEW_PASSWORD_LENGTH = 15;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[][] COMMON_BASES =
      new char[][] {
        "deskgrade".toCharArray(),
        "password".toCharArray(),
        "admin".toCharArray(),
        "123456".toCharArray()
      };

  record Encoded(byte[] salt, byte[] hash, int iterations) {
    @Override
    public String toString() {
      return "Encoded[redacted]";
    }
  }

  static Encoded create(char[] password) {
    validate(password);
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return new Encoded(salt, derive(password, salt, ITERATIONS), ITERATIONS);
  }

  static boolean verify(char[] password, byte[] salt, byte[] expected, int iterations) {
    if (password == null
        || password.length < 12
        || password.length > 128
        || iterations < ITERATIONS
        || iterations > 2_000_000) return false;
    byte[] actual = derive(password, salt, iterations);
    try {
      return MessageDigest.isEqual(expected, actual);
    } finally {
      Arrays.fill(actual, (byte) 0);
    }
  }

  static void validate(char[] password) {
    if (password == null
        || password.length < MIN_NEW_PASSWORD_LENGTH
        || password.length > 128
        || commonDerived(password)) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  /** Reject only full-string derivatives; passphrases containing an ordinary word stay valid. */
  private static boolean commonDerived(char[] password) {
    char[] compact = new char[password.length];
    int length = 0;
    try {
      for (char value : password) {
        if (Character.isWhitespace(value) || ". _@!#$%^&*+-=".indexOf(value) >= 0) continue;
        compact[length++] = Character.toLowerCase(value);
      }
      int position = 0;
      boolean matchedBase = false;
      while (position < length) {
        char[] matched = null;
        for (char[] base : COMMON_BASES) {
          if (startsWith(compact, length, position, base)) {
            matched = base;
            break;
          }
        }
        if (matched != null) {
          position += matched.length;
          matchedBase = true;
          continue;
        }
        if (matchedBase) {
          for (int i = position; i < length; i++) if (!Character.isDigit(compact[i])) return false;
          return true;
        }
        return false;
      }
      return matchedBase;
    } finally {
      Arrays.fill(compact, '\0');
    }
  }

  private static boolean startsWith(char[] value, int length, int offset, char[] expected) {
    if (offset + expected.length > length) return false;
    for (int i = 0; i < expected.length; i++) {
      if (value[offset + i] != expected[i]) return false;
    }
    return true;
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (Exception unavailable) {
      throw new GatewayException(GatewayException.Code.SERVICE_UNAVAILABLE);
    } finally {
      spec.clearPassword();
    }
  }
}
