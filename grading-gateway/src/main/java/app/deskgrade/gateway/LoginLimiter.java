package app.deskgrade.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded authentication budgets protect password hashing without creating an unbounded cache. */
final class LoginLimiter {
  static final int LOGIN_ADDRESS_LIMIT = 60;
  static final int LOGIN_FAILURE_LIMIT = 8;
  static final int REGISTRATION_ADDRESS_LIMIT = 20;
  static final int PASSWORD_HASH_CONCURRENCY = 4;
  static final Duration WINDOW = Duration.ofMinutes(15);

  private static final int MAX_TRACKED_KEYS = 10_000;
  private static final int CLEANUP_INTERVAL = 128;

  private record Window(long start, int count) {}

  @FunctionalInterface
  interface PasswordPermit extends AutoCloseable {
    @Override
    void close();
  }

  private final Map<String, Window> loginAddresses = new HashMap<>();
  private final Map<String, Window> registrationAddresses = new HashMap<>();
  private final Map<String, Window> usernameFailures = new HashMap<>();
  private final Semaphore passwordHashes = new Semaphore(PASSWORD_HASH_CONCURRENCY, true);
  private final Clock clock;
  private long operations;

  LoginLimiter(Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  /** Counts every login request by network address and is called before reading its body. */
  synchronized void acquireLoginAddress(String address) {
    long now = clock.millis();
    maintain(now);
    increment(loginAddresses, address(address), LOGIN_ADDRESS_LIMIT, now);
  }

  /** Registration has an independent budget, so signup traffic cannot consume the login budget. */
  synchronized void acquireRegistrationAddress(String address) {
    long now = clock.millis();
    maintain(now);
    increment(registrationAddresses, address(address), REGISTRATION_ADDRESS_LIMIT, now);
  }

  /** Checks the account-wide failed-login window without charging successful logins to it. */
  synchronized void checkLoginUsername(String username) {
    long now = clock.millis();
    maintain(now);
    String key = usernameKey(username);
    Window window = usernameFailures.get(key);
    if (window == null) return;
    if (expired(window, now)) {
      usernameFailures.remove(key);
      return;
    }
    if (window.count() >= LOGIN_FAILURE_LIMIT) throw limited(window, now);
  }

  synchronized void loginFailed(String username) {
    long now = clock.millis();
    maintain(now);
    increment(usernameFailures, usernameKey(username), LOGIN_FAILURE_LIMIT, now);
  }

  /**
   * A verified password clears stale failures rather than locking out a legitimate repeat login.
   */
  synchronized void loginSucceeded(String username) {
    usernameFailures.remove(usernameKey(username));
  }

  /** Never queue expensive PBKDF work on every HTTP worker. */
  PasswordPermit acquirePasswordHash() {
    if (!passwordHashes.tryAcquire())
      throw new GatewayException(GatewayException.Code.RATE_LIMITED, 1);
    var released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) passwordHashes.release();
    };
  }

  private void increment(Map<String, Window> windows, String key, int limit, long now) {
    var window = windows.get(key);
    if (window != null && expired(window, now)) {
      windows.remove(key);
      window = null;
    }
    if (window != null && window.count() >= limit) throw limited(window, now);
    if (window == null) {
      ensureCapacity(windows, now);
      windows.put(key, new Window(now, 1));
    } else {
      windows.put(key, new Window(window.start(), window.count() + 1));
    }
  }

  private void ensureCapacity(Map<String, Window> windows, long now) {
    if (windows.size() < MAX_TRACKED_KEYS) return;
    // A full scan happens only at the hard bound. Routine cleanup is amortized below.
    windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    if (windows.size() >= MAX_TRACKED_KEYS)
      throw new GatewayException(GatewayException.Code.RATE_LIMITED, WINDOW.toSeconds());
  }

  private void maintain(long now) {
    if (++operations % CLEANUP_INTERVAL != 0) return;
    loginAddresses.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    registrationAddresses.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    usernameFailures.entrySet().removeIf(entry -> expired(entry.getValue(), now));
  }

  private static String address(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || value.chars().anyMatch(Character::isISOControl))
      throw new GatewayException(GatewayException.Code.RATE_LIMITED, WINDOW.toSeconds());
    return value;
  }

  private static String usernameKey(String username) {
    if (username == null || username.length() > 32)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return GatewayStore.hash(username.toLowerCase(Locale.ROOT));
  }

  private static boolean expired(Window window, long now) {
    long elapsed = now - window.start();
    return elapsed < 0 || elapsed >= WINDOW.toMillis();
  }

  private static GatewayException limited(Window window, long now) {
    long remaining = Math.max(1, WINDOW.toMillis() - Math.max(0, now - window.start()));
    long seconds = Math.max(1, (remaining + 999) / 1000);
    return new GatewayException(GatewayException.Code.RATE_LIMITED, seconds);
  }
}
