package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScanCaptureFenceTest {
  @Test
  void queuedShowIsRevokedBeforeCaptureAndOldRequestNeverRestores() {
    var fence = new ScanCaptureFence();
    long queued = fence.showRequested();
    assertTrue(fence.mayShow(queued));
    var clear = fence.beginCapture();
    assertTrue(clear.renderRequired());
    assertFalse(clear.cleanFrame().isDone());
    assertFalse(fence.mayShow(queued));
    clear.cleanFrame().complete(null);
    assertEquals(-1, fence.endCapture());
    assertFalse(fence.mayShow(queued));
  }

  @Test
  void newShowCannotEnterAnyNestedCaptureAndOnlyRestoresAfterAllClose() {
    var fence = new ScanCaptureFence();
    fence.beginCapture();
    fence.beginCapture();
    long queued = fence.showRequested();
    assertFalse(fence.mayShow(queued));
    assertEquals(-1, fence.endCapture());
    long release = fence.endCapture();
    assertTrue(release > queued);
    assertFalse(fence.mayShow(queued));
    assertTrue(fence.mayShow(release));
    assertThrows(IllegalStateException.class, fence::endCapture);
  }

  @Test
  void repeatedUndecoratedCapturesReuseCompletedFenceWithoutRenderDelay() {
    var fence = new ScanCaptureFence();
    var first = fence.beginCapture();
    assertFalse(first.renderRequired());
    assertTrue(first.cleanFrame().isDone());
    fence.endCapture();
    var second = fence.beginCapture();
    assertFalse(second.renderRequired());
    assertSame(first.cleanFrame(), second.cleanFrame());
    fence.endCapture();
  }

  @Test
  void pendingStopHideIsStillAwaitedByCaptureAndOldCompletionCannotCleanNewShow() {
    var fence = new ScanCaptureFence();
    fence.showRequested();
    var stop = fence.clearRequested();
    var capture = fence.beginCapture();
    assertSame(stop.cleanFrame(), capture.cleanFrame());
    assertFalse(capture.cleanFrame().isDone());
    stop.cleanFrame().complete(null);
    fence.endCapture();
    long next = fence.showRequested();
    assertTrue(fence.mayShow(next));
    var newCapture = fence.beginCapture();
    assertNotSame(stop.cleanFrame(), newCapture.cleanFrame());
    assertFalse(newCapture.cleanFrame().isDone());
    assertFalse(fence.mayShow(next));
    fence.endCapture();
  }
}
