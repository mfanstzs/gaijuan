package app.deskgrade.gateway;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Bounded, fair admission for supplier model calls. */
final class GatewayCapacity {
  static final String CONCURRENCY_PROPERTY = "deskgrade.gateway.modelConcurrency";
  static final String QUEUE_CAPACITY_PROPERTY = "deskgrade.gateway.modelQueueCapacity";
  static final String QUEUE_WAIT_MILLIS_PROPERTY = "deskgrade.gateway.modelQueueWaitMillis";
  static final String CONCURRENCY_ENV = "DESKGRADE_MODEL_CONCURRENCY";
  static final String QUEUE_CAPACITY_ENV = "DESKGRADE_MODEL_QUEUE_CAPACITY";
  static final String QUEUE_WAIT_MILLIS_ENV = "DESKGRADE_MODEL_QUEUE_WAIT_MILLIS";

  static final int MAX_CONCURRENCY = 64;
  static final int MAX_QUEUE_CAPACITY = 128;
  static final int MAX_QUEUE_WAIT_MILLIS = 30_000;

  record Configuration(int concurrency, int queueCapacity, int queueWaitMillis) {
    Configuration {
      requireRange("modelConcurrency", concurrency, 1, MAX_CONCURRENCY);
      requireRange("modelQueueCapacity", queueCapacity, 0, MAX_QUEUE_CAPACITY);
      requireRange("modelQueueWaitMillis", queueWaitMillis, 1, MAX_QUEUE_WAIT_MILLIS);
    }

    static Configuration load() {
      return load(System::getProperty, System::getenv, Runtime.getRuntime().availableProcessors());
    }

    static Configuration load(
        Function<String, String> properties,
        Function<String, String> environment,
        int availableProcessors) {
      Objects.requireNonNull(properties);
      Objects.requireNonNull(environment);
      int defaultConcurrency = defaultConcurrency(availableProcessors);
      return new Configuration(
          read(
              properties,
              environment,
              CONCURRENCY_PROPERTY,
              CONCURRENCY_ENV,
              defaultConcurrency,
              1,
              MAX_CONCURRENCY),
          read(
              properties,
              environment,
              QUEUE_CAPACITY_PROPERTY,
              QUEUE_CAPACITY_ENV,
              defaultConcurrency,
              0,
              MAX_QUEUE_CAPACITY),
          read(
              properties,
              environment,
              QUEUE_WAIT_MILLIS_PROPERTY,
              QUEUE_WAIT_MILLIS_ENV,
              2_000,
              1,
              MAX_QUEUE_WAIT_MILLIS));
    }

    int gradeWorkerCount() {
      return concurrency + queueCapacity;
    }

    private static int defaultConcurrency(int processors) {
      int boundedProcessors = Math.max(1, processors);
      return (int) Math.min(MAX_CONCURRENCY, Math.max(16L, (long) boundedProcessors * 2L));
    }

    private static int read(
        Function<String, String> properties,
        Function<String, String> environment,
        String property,
        String env,
        int fallback,
        int minimum,
        int maximum) {
      String configured = properties.apply(property);
      String source = property;
      if (configured == null) {
        configured = environment.apply(env);
        source = env;
      }
      if (configured == null) return fallback;
      if (!configured.matches("0|[1-9][0-9]*"))
        throw new IllegalArgumentException(source + " must be a base-10 integer");
      int parsed;
      try {
        parsed = Integer.parseInt(configured);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException(source + " is outside the supported range", invalid);
      }
      requireRange(source, parsed, minimum, maximum);
      return parsed;
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
      if (value < minimum || value > maximum)
        throw new IllegalArgumentException(
            name + " must be between " + minimum + " and " + maximum);
    }
  }

  private final Configuration configuration;
  private final Semaphore permits;
  private final AtomicInteger queued = new AtomicInteger();

  GatewayCapacity(Configuration configuration) {
    this.configuration = Objects.requireNonNull(configuration);
    permits = new Semaphore(configuration.concurrency(), true);
  }

  Permit acquire() {
    try {
      // Timed tryAcquire, even with a zero timeout, honors a fair semaphore's existing queue.
      if (permits.tryAcquire(0, TimeUnit.MILLISECONDS)) return new Permit(this);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw overloaded();
    }
    reserveQueueSlot();
    try {
      if (!permits.tryAcquire(configuration.queueWaitMillis(), TimeUnit.MILLISECONDS))
        throw overloaded();
      return new Permit(this);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw overloaded();
    } finally {
      queued.decrementAndGet();
    }
  }

  Configuration configuration() {
    return configuration;
  }

  int availablePermits() {
    return permits.availablePermits();
  }

  int queuedRequests() {
    return queued.get();
  }

  boolean fair() {
    return permits.isFair();
  }

  private void reserveQueueSlot() {
    while (true) {
      int current = queued.get();
      if (current >= configuration.queueCapacity()) throw overloaded();
      if (queued.compareAndSet(current, current + 1)) return;
    }
  }

  private GatewayException overloaded() {
    long retryAfter = Math.max(1, (configuration.queueWaitMillis() + 999L) / 1_000L);
    return new GatewayException(GatewayException.Code.MODEL_CAPACITY_EXHAUSTED, retryAfter);
  }

  static final class Permit implements AutoCloseable {
    private final GatewayCapacity owner;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Permit(GatewayCapacity owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) owner.permits.release();
    }
  }
}
