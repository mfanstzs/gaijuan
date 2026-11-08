package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.core.domain.desktop.WindowIdentity;
import java.time.Instant;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class ManagedWebViewTest {
  @Test
  void ordinaryWebsitesOpenMaximizedAndResizableWithScreenBoundedRestoreSize() {
    var target = EmbeddedBrowserUrlPolicy.parse("https://example.test/grading");
    var workArea = new Rectangle2D(0, 0, 1920, 1040);
    var layout = ManagedWebView.windowLayout(target, workArea);
    assertTrue(layout.maximized());
    assertTrue(layout.resizable());
    assertEquals(1440, layout.width());
    assertEquals(956.8, layout.height(), .001);
    assertTrue(layout.width() > 900);
    assertTrue(layout.height() > 760);
    assertTrue(layout.x() >= workArea.getMinX());
    assertTrue(layout.y() >= workArea.getMinY());
    assertTrue(layout.x() + layout.width() <= workArea.getMaxX());
    assertTrue(layout.y() + layout.height() <= workArea.getMaxY());
  }

  @Test
  void smallAndNegativeOriginScreensDoNotPlaceRestoreBoundsOffScreen() {
    var target = EmbeddedBrowserUrlPolicy.parse("https://example.test/grading");
    for (var workArea :
        new Rectangle2D[] {
          new Rectangle2D(0, 0, 1024, 600), new Rectangle2D(-1600, -120, 1600, 860)
        }) {
      var layout = ManagedWebView.windowLayout(target, workArea);
      assertTrue(layout.x() >= workArea.getMinX());
      assertTrue(layout.y() >= workArea.getMinY());
      assertTrue(layout.x() + layout.width() <= workArea.getMaxX());
      assertTrue(layout.y() + layout.height() <= workArea.getMaxY());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ManagedWebView.windowLayout(target, new Rectangle2D(0, 0, 0, 600)));
  }

  @Test
  void localMockKeepsItsProtocolSizeWhileCloudMockUsesTheLargeOrdinaryWindow() {
    var workArea = new Rectangle2D(0, 0, 1920, 1040);
    var local =
        ManagedWebView.windowLayout(
            EmbeddedBrowserUrlPolicy.parse("http://127.0.0.1:8766/"), workArea);
    assertEquals(900, local.width());
    assertEquals(760, local.height());
    assertFalse(local.resizable());
    assertFalse(local.maximized());
    var cloud =
        ManagedWebView.windowLayout(
            EmbeddedBrowserUrlPolicy.parse("https://example.test/mock-grading/test/"), workArea);
    assertTrue(cloud.resizable());
    assertTrue(cloud.maximized());
  }

  @Test
  void windowTitleCarriesTheUniqueLaunchIdentityForEveryTarget() {
    var remote = EmbeddedBrowserUrlPolicy.parse("https://example.test/grading");
    var local = EmbeddedBrowserUrlPolicy.parse("http://127.0.0.1:8766/");

    String first = ManagedWebView.windowTitle(remote, "launch-one");
    String second = ManagedWebView.windowTitle(remote, "launch-two");

    assertEquals(ManagedWebView.UNTRUSTED_TITLE_PREFIX + "launch-one", first);
    assertEquals(
        ManagedWebView.TRUSTED_TITLE_PREFIX + "local-launch",
        ManagedWebView.windowTitle(local, "local-launch"));
    assertNotEquals(first, second);
    assertThrows(IllegalArgumentException.class, () -> ManagedWebView.windowTitle(remote, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManagedWebView.Session("launch-one", remote, "unrelated title"));
  }

  @Test
  void sessionAcceptsOnlyItsExactWindowFromTheCurrentProcess() {
    long currentProcess = ProcessHandle.current().pid();
    long otherProcess = currentProcess == 1 ? 2 : 1;
    var target = EmbeddedBrowserUrlPolicy.parse("https://example.test/grading");
    String title = ManagedWebView.windowTitle(target, "launch-one");
    var session = new ManagedWebView.Session("launch-one", target, title);
    Instant startedAt = Instant.parse("2026-09-13T00:00:00Z");

    var exact =
        new WindowsWindowService.WindowChoice(
            new WindowIdentity(101, currentProcess, startedAt), title, "GlassWndClass");
    var wrongTitle =
        new WindowsWindowService.WindowChoice(
            new WindowIdentity(101, currentProcess, startedAt), title + "-stale", "GlassWndClass");
    var wrongProcess =
        new WindowsWindowService.WindowChoice(
            new WindowIdentity(101, otherProcess, startedAt), title, "GlassWndClass");

    assertTrue(session.matchesCurrentProcessWindow(exact));
    assertFalse(session.matchesCurrentProcessWindow(wrongTitle));
    assertFalse(session.matchesCurrentProcessWindow(wrongProcess));
    assertFalse(session.matchesCurrentProcessWindow(null));
  }

  @Test
  void lifecycleKeepsSameOriginNavigationAndRevokesUnsafeDestinationsOrLoadFailure() {
    var target = EmbeddedBrowserUrlPolicy.parse("https://example.test/grading");

    assertFalse(ManagedWebView.revokesForNavigation(target, "https://example.test/next"));
    assertTrue(ManagedWebView.revokesForNavigation(target, "https://attacker.test/next"));
    assertFalse(ManagedWebView.revokesForLoadState(Worker.State.SCHEDULED));
    assertFalse(ManagedWebView.revokesForLoadState(Worker.State.CANCELLED));
    assertFalse(ManagedWebView.revokesForLoadState(Worker.State.SUCCEEDED));
    assertTrue(ManagedWebView.revokesForLoadState(Worker.State.FAILED));
  }
}
