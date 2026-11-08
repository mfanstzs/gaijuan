package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalDesktopWorkspaceTest {
  @TempDir Path directory;

  @Test
  void localStartupIgnoresCloudAccessAndIsolatesOldDatabaseWithoutGrantingInput() throws Exception {
    Files.createDirectories(directory.resolve("settings"));
    Files.createDirectories(directory.resolve("database"));
    byte[] oldAccess = "{invalid old cloud settings".getBytes(StandardCharsets.UTF_8);
    byte[] oldDatabase =
        "old database must not be opened or migrated".getBytes(StandardCharsets.UTF_8);
    Files.write(directory.resolve("settings/access.json"), oldAccess);
    Files.writeString(
        directory.resolve("settings/profiles.json"), "invalid old developer profiles");
    Files.write(directory.resolve("database/deskgrade.mv.db"), oldDatabase);
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      var loaded = workspace.initialize().get(10, TimeUnit.SECONDS);
      assertTrue(workspace.localOnlyRuntime());
      assertFalse(workspace.commercialMode());
      assertFalse(workspace.developerMode());
      assertEquals("", workspace.gatewayAddress());
      assertNull(workspace.accountView().account());
      assertEquals(3, loaded.profiles().size());
      assertTrue(loaded.profiles().stream().noneMatch(ProviderProfile::isVerified));
      assertTrue(loaded.profiles().stream().noneMatch(ProviderProfile::isStructuredVerified));
      assertFalse(workspace.generationModelAvailable());
      assertFalse(workspace.hotkeysAvailable());
      assertNull(workspace.binding());
      assertNull(workspace.status());
      char[] password = "synthetic-account-password".toCharArray();
      assertThrows(
          Exception.class,
          () -> workspace.authenticate("unused", password, true).get(5, TimeUnit.SECONDS));
      for (char character : password) assertEquals('\0', character);
      assertThrows(
          Exception.class, () -> workspace.setCommercialMode(true).get(5, TimeUnit.SECONDS));
      assertThrows(
          Exception.class,
          () -> workspace.saveGatewayAddress("https://invalid.example").get(5, TimeUnit.SECONDS));
      assertEquals(
          0,
          workspace
              .recordsSource()
              .query(GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .total());
      assertTrue(Files.exists(directory.resolve("database/local-deskgrade.mv.db")));
      assertArrayEquals(oldAccess, Files.readAllBytes(directory.resolve("settings/access.json")));
      assertArrayEquals(
          oldDatabase, Files.readAllBytes(directory.resolve("database/deskgrade.mv.db")));
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void independentRolesSurviveRestartAndNoModelCallQuotaIsConfigured() throws Exception {
    var first = new DesktopWorkspace(directory);
    DesktopWorkspace.LocalModelSelection expected;
    try (first) {
      first.initialize().get(10, TimeUnit.SECONDS);
      var initial = first.localModelSelection();
      expected =
          first
              .saveLocalModelSelection(initial.primaryId(), null, initial.rubricId())
              .get(5, TimeUnit.SECONDS);
      assertEquals("", expected.fallbackId());
      assertThrows(
          Exception.class,
          () ->
              first
                  .saveLocalModelSelection(
                      initial.primaryId(), initial.primaryId(), initial.rubricId())
                  .get(5, TimeUnit.SECONDS));
      assertThrows(
          Exception.class,
          () ->
              first
                  .saveLocalModelSelection("missing", null, initial.rubricId())
                  .get(5, TimeUnit.SECONDS));
      var method =
          DesktopWorkspace.class.getDeclaredMethod(
              "liveSyntheticSettings", DesktopWorkspace.ModelSelection.class, int.class, int.class);
      method.setAccessible(true);
      var settings =
          (SessionSettings)
              method.invoke(
                  first, DesktopWorkspace.ModelSelection.of(first.profiles().getFirst()), 20, 1);
      assertEquals(20, settings.maxSubmissions());
      assertEquals(
          0, settings.maxCalls(), "User task stop count is not an account/model-call quota");
      assertTrue(settings.configurationJson().contains("LOCAL"));
      assertFalse(settings.configurationJson().contains("serviceAddress"));
    }
    first.awaitClosed().get(10, TimeUnit.SECONDS);
    var second = new DesktopWorkspace(directory);
    try (second) {
      second.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(expected, second.localModelSelection());
      assertTrue(second.localOnlyRuntime());
    }
    second.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void qwenDefaultsDoNotOverwriteAnExistingExplicitOpenAiRoleOrVerification() throws Exception {
    var template = LocalModelDefaults.profiles().getFirst();
    var legacy =
        new ProviderProfile(
            "zz_openai_gpt_6_luna_v1",
            1,
            "Previously selected rule generator",
            URI.create(LocalModelSetup.Vendor.OPENAI.endpoint),
            "gpt-6-luna",
            "openai_luna",
            template.structuredMode(),
            Map.of(),
            Set.of(),
            template.connectTimeout(),
            template.requestTimeout(),
            null,
            null);
    legacy =
        legacy.withVerification(
            new ProviderProfile.Verification(legacy.configDigest(), Instant.EPOCH, false, true));
    DesktopWorkspace.LocalModelSelection expected;
    var first = new DesktopWorkspace(directory);
    try (first) {
      first.initialize().get(10, TimeUnit.SECONDS);
      first.saveProfile(legacy).get(5, TimeUnit.SECONDS);
      var original = first.localModelSelection();
      expected =
          first
              .saveLocalModelSelection(original.primaryId(), original.fallbackId(), legacy.id())
              .get(5, TimeUnit.SECONDS);
    }
    first.awaitClosed().get(10, TimeUnit.SECONDS);
    var second = new DesktopWorkspace(directory);
    try (second) {
      second.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(expected, second.localModelSelection());
      var actual =
          second.profiles().stream()
              .filter(p -> p.id().equals(expected.rubricId()))
              .findFirst()
              .orElseThrow();
      assertEquals(legacy, actual);
      assertTrue(actual.isStructuredVerified());
      assertFalse(actual.isVerified());
      assertFalse(
          second.generationModelAvailable(),
          "No real or synthetic credential is created by this test");
    }
    second.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void savingOrForgettingSharedKeyInvalidatesEveryDependentModelAndNeverWritesPlaintext()
      throws Exception {
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      workspace.initialize().get(10, TimeUnit.SECONDS);
      var selected = workspace.profiles().getFirst();
      String selectedId = selected.id();
      for (ProviderProfile candidate : List.copyOf(workspace.profiles()))
        workspace
            .saveProfile(
                candidate.withVerification(
                    new ProviderProfile.Verification(
                        candidate.configDigest(), Instant.EPOCH, true, true)))
            .get(5, TimeUnit.SECONDS);
      selected =
          workspace.profiles().stream()
              .filter(p -> p.id().equals(selectedId))
              .findFirst()
              .orElseThrow();
      String key = "synthetic-fixture-never-package-this";
      char[] secret = key.toCharArray();
      var changed = workspace.saveCredential(selected, secret).get(5, TimeUnit.SECONDS);
      assertTrue(workspace.hasCredential(changed).get(5, TimeUnit.SECONDS));
      for (char character : secret) assertEquals('\0', character);
      assertTrue(
          workspace.profiles().stream()
              .filter(p -> p.credentialRef().equals(changed.credentialRef()))
              .noneMatch(ProviderProfile::isVerified));
      assertTrue(
          workspace.profiles().stream()
              .filter(p -> !p.credentialRef().equals(changed.credentialRef()))
              .allMatch(ProviderProfile::isVerified));
      byte[] encrypted =
          Files.readAllBytes(
              directory.resolve("credentials").resolve(changed.credentialRef() + ".dpapi"));
      assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains(key));
      assertFalse(
          Files.readString(directory.resolve("settings/local-profiles.json")).contains(key));
      var forgotten = workspace.forgetCredential(changed).get(5, TimeUnit.SECONDS);
      assertFalse(workspace.hasCredential(forgotten).get(5, TimeUnit.SECONDS));
      assertTrue(
          workspace.profiles().stream()
              .filter(p -> p.credentialRef().equals(forgotten.credentialRef()))
              .noneMatch(ProviderProfile::isStructuredVerified));
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }
}
