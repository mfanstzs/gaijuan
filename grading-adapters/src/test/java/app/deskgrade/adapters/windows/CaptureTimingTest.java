package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CaptureTimingTest {
  @Test
  void recordsAllExistingPhasesWithoutClockWallTimeOrSensitiveFields() {
    var now = new AtomicLong(100);
    var collector = new CaptureTimingCollector(now::get);
    for (var phase : CaptureTiming.Phase.values()) {
      collector.begin(phase);
      now.addAndGet(13);
      collector.end();
    }
    var timing = collector.finish(true);
    assertTrue(timing.successful());
    assertEquals(13L * CaptureTiming.Phase.values().length, timing.elapsedNanos());
    assertEquals(CaptureTiming.Phase.values().length, timing.phaseNanos().size());
    assertTrue(timing.phaseNanos().values().stream().allMatch(nanos -> nanos == 13));
    assertEquals(
        List.of("elapsedNanos", "successful", "phaseNanos"),
        Arrays.stream(CaptureTiming.class.getRecordComponents())
            .map(component -> component.getName())
            .toList());
  }

  @Test
  void failedBoundaryPrepareHasOnlyItsElapsedPartialPhase() {
    var now = new AtomicLong(10);
    var collector = new CaptureTimingCollector(now::get);
    collector.begin(CaptureTiming.Phase.BOUNDARY_PREPARE);
    now.addAndGet(17);
    var timing = collector.finish(false);
    assertFalse(timing.successful());
    assertEquals(17, timing.elapsedNanos());
    assertEquals(java.util.Map.of(CaptureTiming.Phase.BOUNDARY_PREPARE, 17L), timing.phaseNanos());
  }

  @Test
  void partialCaptureFailureAndItsOriginalScopeCloseAreMeasuredWithoutInventingLaterStages() {
    var now = new AtomicLong();
    var collector = new CaptureTimingCollector(now::get);
    collector.begin(CaptureTiming.Phase.RAW_CAPTURE);
    now.addAndGet(41);
    // Beginning original cleanup finalizes the open failing phase; no retry/capture is added.
    collector.begin(CaptureTiming.Phase.BOUNDARY_CLOSE);
    now.addAndGet(5);
    collector.end();
    var timing = collector.finish(false);
    assertFalse(timing.successful());
    assertEquals(46, timing.elapsedNanos());
    assertEquals(41L, timing.phaseNanos().get(CaptureTiming.Phase.RAW_CAPTURE));
    assertEquals(5L, timing.phaseNanos().get(CaptureTiming.Phase.BOUNDARY_CLOSE));
    assertFalse(timing.phaseNanos().containsKey(CaptureTiming.Phase.PNG_ENCODE));
    assertFalse(timing.phaseNanos().containsKey(CaptureTiming.Phase.DIGEST_AND_RESULT));
  }

  @Test
  void summaryIsAnImmutableBoundedCopyAndCannotBecomeAnImageOrExceptionContainer() {
    var phases = new EnumMap<CaptureTiming.Phase, Long>(CaptureTiming.Phase.class);
    phases.put(CaptureTiming.Phase.PNG_ENCODE, 8L);
    var timing = new CaptureTiming(10, true, phases);
    phases.clear();
    assertEquals(8L, timing.phaseNanos().get(CaptureTiming.Phase.PNG_ENCODE));
    assertThrows(UnsupportedOperationException.class, () -> timing.phaseNanos().clear());
    assertThrows(IllegalArgumentException.class, () -> new CaptureTiming(-1, true, phases));
    phases.put(CaptureTiming.Phase.SAFETY_AFTER, -1L);
    assertThrows(IllegalArgumentException.class, () -> new CaptureTiming(10, false, phases));
  }

  @Test
  void runtimeObserverFailureNeverReplacesTheOriginalCaptureFailure() {
    var original = new IllegalStateException("Synthetic original capture failure");
    var timing = new CaptureTiming(4, false, java.util.Map.of());
    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> {
              try {
                throw original;
              } finally {
                WindowRegionCapture.publishTiming(
                    summary -> {
                      throw new IllegalArgumentException("Synthetic observer failure");
                    },
                    timing);
              }
            });
    assertSame(original, actual);
    assertEquals(0, actual.getSuppressed().length);
  }

  @Test
  void unavailableOptionalObserverDependencyNeverChangesAnAlreadySuccessfulCapture() {
    var timing = new CaptureTiming(4, true, java.util.Map.of());
    assertDoesNotThrow(
        () ->
            WindowRegionCapture.publishTiming(
                summary -> {
                  throw new NoClassDefFoundError("Synthetic missing optional timing consumer");
                },
                timing));
    assertDoesNotThrow(
        () ->
            WindowRegionCapture.publishTiming(
                WindowRegionCapture.CaptureTimingObserver.NONE, timing));
  }

  @Test
  void observerRunsExactlyOnceOnTheOriginalCaptureCallerThread() {
    var caller = Thread.currentThread();
    var received = new java.util.ArrayList<CaptureTiming>();
    var timing = new CaptureTiming(4, true, java.util.Map.of());
    WindowRegionCapture.publishTiming(
        summary -> {
          assertSame(caller, Thread.currentThread());
          received.add(summary);
        },
        timing);
    assertEquals(List.of(timing), received);
  }
}
