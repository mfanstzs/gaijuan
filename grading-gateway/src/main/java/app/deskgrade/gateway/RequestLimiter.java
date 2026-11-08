package app.deskgrade.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small in-process admission layer for the validated single-gateway deployment. */
final class RequestLimiter {
  static final Duration WINDOW = Duration.ofMinutes(1);
  static final int GLOBAL_LIMIT = 1_200;
  static final int TOKEN_LIMIT = 300;
  static final int USER_LIMIT = 480;

  private static final int MAX_TRACKED_KEYS = 10_000;
  private static final int CLEANUP_INTERVAL = 256;

  private record Window(long start, int count) {}

  private final Clock clock;
  private final Map<String, Window> tokens = new HashMap<>();
  private final Map<UUID, Window> users = new HashMap<>();
  private Window global;
  private long operations;

  RequestLimiter(Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  synchronized void acquireGlobal() {
    long now = clock.millis();
    maintain(now);
    global = increment(global, GLOBAL_LIMIT, now);
  }

  /** Called before database/Redis authentication; only a digest is retained in the bounded map. */
  synchronized void acquireToken(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new GatewayException(GatewayException.Code.UNAUTHENTICATED);
    long now = clock.millis();
    maintain(now);
    String key = GatewayStore.hash(token);
    tokens.put(key, increment(tokens, key, TOKEN_LIMIT, now));
  }

  synchronized void acquireUser(UUID userId) {
    if (userId == null) throw new GatewayException(GatewayException.Code.UNAUTHENTICATED);
    long now = clock.millis();
    maintain(now);
    users.put(userId, increment(users, userId, USER_LIMIT, now));
  }

  private Window increment(Window window, int limit, long now) {
    if (window == null || expired(window, now)) return new Window(now, 1);
    if (window.count() >= limit) throw limited(window, now);
    return new Window(window.start(), window.count() + 1);
  }

  private <K> Window increment(Map<K, Window> windows, K key, int limit, long now) {
    Window window = windows.get(key);
    if (window != null && expired(window, now)) {
      windows.remove(key);
      window = null;
    }
    if (window == null) ensureCapacity(windows, now);
    return increment(window, limit, now);
  }

  private <K> void ensureCapacity(Map<K, Window> windows, long now) {
    if (windows.size() < MAX_TRACKED_KEYS) return;
    windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    if (windows.size() >= MAX_TRACKED_KEYS)
      throw new GatewayException(GatewayException.Code.RATE_LIMITED, WINDOW.toSeconds());
  }

  private void maintain(long now) {
    if (++operations % CLEANUP_INTERVAL != 0) return;
    tokens.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    users.entrySet().removeIf(entry -> expired(entry.getValue(), now));
  }

  private static boolean expired(Window window, long now) {
    long elapsed = now - window.start();
    return elapsed < 0 || elapsed >= WINDOW.toMillis();
  }

  private static GatewayException limited(Window window, long now) {
    long remaining = Math.max(1, WINDOW.toMillis() - Math.max(0, now - window.start()));
    return new GatewayException(
        GatewayException.Code.RATE_LIMITED, Math.max(1, (remaining + 999) / 1000));
  }
}
