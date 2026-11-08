package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GatewayCapacityTest {
  @Test
  void defaultIsCpuAwareAtLeastSixteenAndNeverTheLegacyFour() {
    var singleCpu = configuration(Map.of(), Map.of(), 1);
    var twelveCpus = configuration(Map.of(), Map.of(), 12);
    var excessiveCpuCount = configuration(Map.of(), Map.of(), Integer.MAX_VALUE);

    assertAll(
        () -> assertEquals(16, singleCpu.concurrency()),
        () -> assertNotEquals(4, singleCpu.concurrency()),
        () -> assertEquals(24, twelveCpus.concurrency()),
        () -> assertEquals(GatewayCapacity.MAX_CONCURRENCY, excessiveCpuCount.concurrency()),
        () -> assertEquals(singleCpu.concurrency(), singleCpu.queueCapacity()));
  }

  @Test
  void systemPropertyPrecedesEnvironmentAndEveryValueIsStrictlyBounded() {
    var configured =
        configuration(
            Map.of(
                GatewayCapacity.CONCURRENCY_PROPERTY,
                "20",
                GatewayCapacity.QUEUE_CAPACITY_PROPERTY,
                "7",
                GatewayCapacity.QUEUE_WAIT_MILLIS_PROPERTY,
                "1250"),
            Map.of(
                GatewayCapacity.CONCURRENCY_ENV,
                "30",
                GatewayCapacity.QUEUE_CAPACITY_ENV,
                "8",
                GatewayCapacity.QUEUE_WAIT_MILLIS_ENV,
                "1500"),
            1);
    assertEquals(new GatewayCapacity.Configuration(20, 7, 1250), configured);

    for (var invalid :
        new String[] {"", " 16", "16 ", "+16", "-1", "1.0", "01", "999999999999999"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> configuration(Map.of(GatewayCapacity.CONCURRENCY_PROPERTY, invalid), Map.of(), 1),
          invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            configuration(
                Map.of(
                    GatewayCapacity.CONCURRENCY_PROPERTY,
                    Integer.toString(GatewayCapacity.MAX_CONCURRENCY + 1)),
                Map.of(),
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            configuration(
                Map.of(
                    GatewayCapacity.QUEUE_CAPACITY_PROPERTY,
                    Integer.toString(GatewayCapacity.MAX_QUEUE_CAPACITY + 1)),
                Map.of(),
                1));
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration(Map.of(GatewayCapacity.QUEUE_WAIT_MILLIS_PROPERTY, "0"), Map.of(), 1));
  }

  @Test
  @Timeout(10)
  void moreThanFourDistinctWorkersCanEnterAtTheSameTime() throws Exception {
    var capacity = new GatewayCapacity(new GatewayCapacity.Configuration(6, 0, 100));
    var entered = new CountDownLatch(6);
    var release = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(6)) {
      var futures =
          java.util.stream.IntStream.range(0, 6)
              .mapToObj(
                  ignored ->
                      workers.submit(
                          () -> {
                            try (var permit = capacity.acquire()) {
                              entered.countDown();
                              assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            return null;
                          }))
              .toList();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(0, capacity.availablePermits());
      release.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    assertEquals(6, capacity.availablePermits());
    assertTrue(capacity.fair());
  }

  @Test
  @Timeout(10)
  void waitingQueueIsBoundedAndReturnsRetryAfterWhenFull() throws Exception {
    var capacity = new GatewayCapacity(new GatewayCapacity.Configuration(1, 1, 2_000));
    try (var first = capacity.acquire();
        var worker = Executors.newSingleThreadExecutor()) {
      var waiting = worker.submit(capacity::acquire);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (capacity.queuedRequests() != 1 && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(1, capacity.queuedRequests());
      var rejected = assertThrows(GatewayException.class, capacity::acquire);
      assertEquals(GatewayException.Code.MODEL_CAPACITY_EXHAUSTED, rejected.code());
      assertEquals(2, rejected.retryAfterSeconds());
      first.close();
      try (var queuedPermit = waiting.get(3, TimeUnit.SECONDS)) {
        assertEquals(0, capacity.availablePermits());
      }
    }
    assertEquals(1, capacity.availablePermits());
    assertEquals(0, capacity.queuedRequests());
  }

  @Test
  void permitIsIdempotentAndExceptionsCannotLeakCapacity() {
    var capacity = new GatewayCapacity(new GatewayCapacity.Configuration(1, 0, 100));
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var ignored = capacity.acquire()) {
            throw new IllegalStateException("synthetic failure");
          }
        });
    assertEquals(1, capacity.availablePermits());
    var permit = capacity.acquire();
    permit.close();
    permit.close();
    assertEquals(1, capacity.availablePermits());
  }

  @Test
  void timedOutWaiterLeavesTheBoundedQueueAndCapacityCanBeReused() {
    var capacity = new GatewayCapacity(new GatewayCapacity.Configuration(1, 1, 25));
    try (var first = capacity.acquire()) {
      var rejected = assertThrows(GatewayException.class, capacity::acquire);
      assertEquals(GatewayException.Code.MODEL_CAPACITY_EXHAUSTED, rejected.code());
      assertEquals(0, capacity.queuedRequests());
    }
    try (var reused = capacity.acquire()) {
      assertEquals(0, capacity.availablePermits());
    }
    assertEquals(1, capacity.availablePermits());
  }

  private static GatewayCapacity.Configuration configuration(
      Map<String, String> properties, Map<String, String> environment, int processors) {
    return GatewayCapacity.Configuration.load(properties::get, environment::get, processors);
  }
}
