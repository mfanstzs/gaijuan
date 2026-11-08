package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.commercial.CommercialClient;
import app.deskgrade.adapters.commercial.PublicModelProfile;
import app.deskgrade.core.application.session.SessionSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Settings serialization only: no initialize, network request, saved account, or desktop input. */
@Timeout(10)
class DesktopWorkspaceSessionSettingsTest {
  @TempDir Path directory;

  @Test
  void resumeGenerationIncreasesForTheSameServerProfileWithoutRewritingItsAuditRevision()
      throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    try (workspace;
        var client = new CommercialClient(URI.create("http://127.0.0.1:9"), Clock.systemUTC())) {
      PublicModelProfile profile =
          new PublicModelProfile(
              "fixture_server_model",
              37,
              "Synthetic server profile",
              "fixture-vision",
              Duration.ofSeconds(5),
              true,
              PublicModelProfile.Role.PRIMARY);
      var account =
          new CommercialClient.SessionView(
              "fixture-account", "fixture-user", "USER", 50, Instant.now().plusSeconds(300));
      // Inject an in-memory fixture without authentication or even a loopback HTTP exchange.
      Class<?> sessionClass = Class.forName(CommercialClient.class.getName() + "$Session");
      var constructor =
          sessionClass.getDeclaredConstructor(
              char[].class, CommercialClient.SessionView.class, long.class);
      constructor.setAccessible(true);
      Object session =
          constructor.newInstance("synthetic-not-a-real-token".toCharArray(), account, 0L);
      var policy =
          new CommercialClient.ModelPolicy(
              12, profile.id(), profile.modelId(), "fixture_fallback", "fixture-vision-plus");
      field(sessionClass, "observedPolicy").set(session, policy);
      field(sessionClass, "catalogPolicy").set(session, policy);
      field(CommercialClient.class, "session").set(client, session);
      field(DesktopWorkspace.class, "commercialClient").set(workspace, client);
      field(DesktopWorkspace.class, "gatewayAddress")
          .set(workspace, URI.create("http://127.0.0.1:9"));
      field(DesktopWorkspace.class, "accountView")
          .set(
              workspace,
              new DesktopWorkspace.AccountView(account, List.of(profile), List.of(), List.of()));
      field(DesktopWorkspace.class, "accountPolicyRevision").setLong(workspace, 12L);

      var settingsMethod =
          DesktopWorkspace.class.getDeclaredMethod(
              "liveSyntheticSettings", DesktopWorkspace.ModelSelection.class, int.class, int.class);
      settingsMethod.setAccessible(true);
      var selection = DesktopWorkspace.ModelSelection.of(profile);
      var generation = (AtomicLong) field(DesktopWorkspace.class, "revision").get(workspace);
      generation.set(4);
      var initial = (SessionSettings) settingsMethod.invoke(workspace, selection, 20, 0);
      // This is the exact revision increment performed by resumeSynthetic before new provider
      // setup.
      generation.incrementAndGet();
      var resumed = (SessionSettings) settingsMethod.invoke(workspace, selection, 20, 0);
      generation.incrementAndGet();
      var resumedAgain = (SessionSettings) settingsMethod.invoke(workspace, selection, 20, 0);

      assertEquals(4, initial.modelRevision());
      assertEquals(5, resumed.modelRevision());
      assertEquals(6, resumedAgain.modelRevision());
      assertEquals(initial.modelProfileId(), resumed.modelProfileId());
      assertEquals(initial.question(), resumed.question());
      assertEquals(5, resumed.platformRevision());
      assertEquals(37, profile.revision());
      for (var settings : List.of(initial, resumed, resumedAgain)) {
        var snapshot = new ObjectMapper().readTree(settings.configurationJson());
        assertEquals("COMMERCIAL", snapshot.path("access").path("mode").asText());
        assertEquals("SERVER_MANAGED", snapshot.path("access").path("routing").asText());
        assertEquals("SERVER_MANAGED", snapshot.path("profile").path("selection").asText());
        assertFalse(snapshot.path("profile").has("serverProfileId"));
        assertFalse(snapshot.path("profile").has("modelId"));
        assertFalse(snapshot.path("profile").has("fallbackProfileId"));
        assertFalse(snapshot.path("access").has("gateway"));
        assertFalse(snapshot.path("access").has("accountId"));
        assertTrue(snapshot.path("access").path("ownerScope").asText().matches("[0-9a-f]{64}"));
        assertFalse(settings.configurationJson().contains("synthetic-not-a-real-token"));
        assertFalse(settings.configurationJson().contains("fixture-vision"));
      }

      var calibration = syntheticCalibration();
      var binding = syntheticBinding();
      field(DesktopWorkspace.class, "manualCalibration").set(workspace, calibration);
      field(DesktopWorkspace.class, "binding").set(workspace, binding);
      Class<?> bindingClass = Class.forName(DesktopWorkspace.class.getName() + "$ManualBinding");
      var bindingConstructor =
          bindingClass.getDeclaredConstructor(
              app.deskgrade.core.domain.desktop.TargetBinding.class,
              app.deskgrade.core.domain.desktop.ManualPlatformCalibration.class,
              String.class,
              long.class,
              long.class,
              long.class);
      bindingConstructor.setAccessible(true);
      field(DesktopWorkspace.class, "manualBinding")
          .set(
              workspace,
              bindingConstructor.newInstance(
                  binding, calibration, "manual-site-v1:" + "b".repeat(64), 0L, 0L, 0L));

      var bound = (SessionSettings) settingsMethod.invoke(workspace, selection, 20, 0);
      var boundSnapshot = new ObjectMapper().readTree(bound.configurationJson());
      assertEquals("MANUAL_COORDINATES", boundSnapshot.path("platform").path("mode").asText());
      assertFalse(boundSnapshot.path("platform").path("coordinatesPersisted").asBoolean(true));
      assertTrue(boundSnapshot.path("binding").path("bound").asBoolean());
      assertFalse(boundSnapshot.path("binding").path("coordinatesPersisted").asBoolean(true));
      for (String secret :
          List.of(
              "calibrationId",
              "answerRegion",
              "scoreRegion",
              "submitRegion",
              "windowBounds",
              "Synthetic target",
              "manual-site-v1:")) {
        assertFalse(bound.configurationJson().contains(secret), secret);
      }
      assertNull(workspace.status());
      assertNotNull(workspace.binding());
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static app.deskgrade.core.domain.desktop.ManualPlatformCalibration
      syntheticCalibration() {
    return new app.deskgrade.core.domain.desktop.ManualPlatformCalibration(
        "run-secret",
        1,
        "本次手工框选",
        new app.deskgrade.core.domain.desktop.ManualPlatformCalibration.WindowLayout(
            1000, 700, 96, "SyntheticWindow"),
        null,
        new app.deskgrade.core.domain.desktop.LogicalRect(20, 100, 500, 300),
        new app.deskgrade.core.domain.desktop.LogicalRect(600, 500, 100, 40),
        new app.deskgrade.core.domain.desktop.LogicalRect(750, 500, 100, 40),
        null,
        null,
        app.deskgrade.core.domain.desktop.ManualPlatformCalibration.NavigationMode
            .AUTO_AFTER_SUBMIT,
        Instant.parse("2026-09-13T00:00:00Z"));
  }

  private static app.deskgrade.core.domain.desktop.TargetBinding syntheticBinding() {
    var bounds = new app.deskgrade.core.domain.desktop.PixelRect(0, 0, 1000, 700);
    var window =
        new app.deskgrade.core.domain.desktop.WindowSnapshot(
            new app.deskgrade.core.domain.desktop.WindowIdentity(
                12345L, 456L, Instant.parse("2026-09-13T00:00:00Z")),
            "Synthetic target",
            "SyntheticWindow",
            bounds,
            bounds,
            new app.deskgrade.core.domain.desktop.DisplaySnapshot("test", bounds, bounds, 1, 1),
            96,
            true,
            true,
            false,
            false,
            true);
    return new app.deskgrade.core.domain.desktop.TargetBinding(
        window,
        new app.deskgrade.core.domain.desktop.LogicalRect(20, 100, 500, 300),
        new app.deskgrade.core.domain.desktop.PixelRect(20, 100, 500, 300));
  }
}
