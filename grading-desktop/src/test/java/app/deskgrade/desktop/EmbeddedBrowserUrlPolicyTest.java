package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddedBrowserUrlPolicyTest {
  @Test
  void acceptsHttpsAndOnlyTheExactLocalMockHttpUrl() {
    var https = EmbeddedBrowserUrlPolicy.parse("Example.COM/path?q=secret#part");
    assertEquals(URI.create("https://example.com/path?q=secret#part"), https.uri());
    assertEquals("https://example.com", https.origin());
    assertFalse(https.localMock());

    var local = EmbeddedBrowserUrlPolicy.parse("http://127.0.0.1:8766/");
    assertEquals(EmbeddedBrowserUrlPolicy.LOCAL_MOCK_URL, local.uri());
    assertEquals("http://127.0.0.1:8766", local.origin());
    assertTrue(local.localMock());

    for (String rejected :
        List.of(
            "http://example.com/",
            "http://localhost:8766/",
            "http://127.0.0.1:8765/",
            "http://127.0.0.1:8766/other",
            "http://127.0.0.1:8766/?mode=trusted",
            "http://2130706433:8766/"))
      assertThrows(IllegalArgumentException.class, () -> EmbeddedBrowserUrlPolicy.parse(rejected));
  }

  @Test
  void rejectsExecutableAmbiguousAndCredentialBearingUrlsBeforeLoad() {
    for (String rejected :
        List.of(
            "",
            "file:///C:/Windows/win.ini",
            "javascript:alert(1)",
            "data:text/html,test",
            "jar:https://example.com/a.jar!/x",
            "about:blank",
            "ftp://example.com/file",
            "https://user:password@example.com/",
            "https://good.example@evil.example/",
            "https://example.com:0/",
            "https://example.com:/",
            "https://[::1]:/",
            "https://example.com:99999/",
            "https://example.com/a b",
            "https:\\example.com",
            "\rhttps://example.com/",
            "https://example.com/\n"))
      assertThrows(IllegalArgumentException.class, () -> EmbeddedBrowserUrlPolicy.parse(rejected));

    String oversized = "https://example.com/" + "a".repeat(EmbeddedBrowserUrlPolicy.MAX_URL_LENGTH);
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> EmbeddedBrowserUrlPolicy.parse(oversized));
    assertFalse(failure.getMessage().contains("example.com"));

    String normalizedOversized =
        "example.com/" + "a".repeat(EmbeddedBrowserUrlPolicy.MAX_URL_LENGTH - 12);
    assertThrows(
        IllegalArgumentException.class, () -> EmbeddedBrowserUrlPolicy.parse(normalizedOversized));
  }

  @Test
  void navigationIsLockedToTheInitialOriginAndMockRoot() {
    var remote = EmbeddedBrowserUrlPolicy.parse("https://example.com/start");
    assertTrue(
        EmbeddedBrowserUrlPolicy.navigationAllowed(remote, "https://example.com/next?token=x"));
    assertFalse(
        EmbeddedBrowserUrlPolicy.navigationAllowed(remote, "https://login.example.net/redirect"));
    assertFalse(EmbeddedBrowserUrlPolicy.navigationAllowed(remote, "file:///tmp/escape"));

    var local = EmbeddedBrowserUrlPolicy.parse("http://127.0.0.1:8766/");
    assertTrue(EmbeddedBrowserUrlPolicy.navigationAllowed(local, "http://127.0.0.1:8766/"));
    assertFalse(EmbeddedBrowserUrlPolicy.navigationAllowed(local, "http://127.0.0.1:8766/other"));
    assertFalse(EmbeddedBrowserUrlPolicy.navigationAllowed(local, "https://127.0.0.1:8766/"));
  }
}
