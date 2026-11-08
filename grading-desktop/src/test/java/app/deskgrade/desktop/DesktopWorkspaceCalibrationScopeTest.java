package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class DesktopWorkspaceCalibrationScopeTest {
  @Test
  void calibrationOwnerIsBoundToVerifiedAccountAndGatewayOrigin() {
    String first =
        DesktopWorkspace.calibrationOwnerKey("account-1", URI.create("https://EXAMPLE.test/api"));
    assertEquals(
        first,
        DesktopWorkspace.calibrationOwnerKey(
            "account-1", URI.create("https://example.test:443/another/path")));
    assertNotEquals(
        first,
        DesktopWorkspace.calibrationOwnerKey("account-2", URI.create("https://example.test/api")));
    assertNotEquals(
        first,
        DesktopWorkspace.calibrationOwnerKey("account-1", URI.create("https://other.test/api")));
    assertTrue(first.matches("[a-f0-9]{64}"));
  }
}
