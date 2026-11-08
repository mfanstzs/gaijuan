package app.deskgrade.desktop;

import java.util.concurrent.CompletableFuture;

/** Pure generation fence: no queued show can enter an active capture scope. */
final class ScanCaptureFence {
  record Clear(long generation, boolean renderRequired, CompletableFuture<Void> cleanFrame) {}

  private long generation;
  private int captures;
  private boolean desired, possiblyVisible;
  private CompletableFuture<Void> cleanFrame = CompletableFuture.completedFuture(null);

  synchronized long showRequested() {
    desired = true;
    if (captures == 0) possiblyVisible = true;
    return ++generation;
  }

  synchronized boolean mayShow(long ticket) {
    return ticket == generation && desired && captures == 0;
  }

  synchronized boolean shouldHide() {
    return captures > 0 || !desired;
  }

  synchronized Clear clearRequested() {
    desired = false;
    ++generation;
    boolean render = possiblyVisible;
    possiblyVisible = false;
    if (render) cleanFrame = new CompletableFuture<>();
    return new Clear(generation, render, cleanFrame);
  }

  synchronized Clear beginCapture() {
    captures++;
    return clearRequested();
  }

  synchronized long endCapture() {
    if (captures < 1) throw new IllegalStateException("Unbalanced scan capture scope");
    captures--;
    if (captures == 0 && desired) {
      possiblyVisible = true;
      return ++generation;
    }
    return -1;
  }
}
