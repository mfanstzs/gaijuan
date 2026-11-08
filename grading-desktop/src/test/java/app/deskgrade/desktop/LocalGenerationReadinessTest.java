package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.model.ProviderProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Readiness checks only: no DPAPI decryption, native UI, provider calls or student data. */
class LocalGenerationReadinessTest {
  @TempDir Path directory;

  @Test
  void activeTextRoleExplainsMissingRequirementsAndIsIndependentOfAnUnassignedDraft()
      throws Exception {
    var workspace = new DesktopWorkspace(directory);
    assertFalse(workspace.generationModelAvailable());
    assertTrue(workspace.generationModelUnavailableReason().contains("初始化"));
    try (workspace) {
      workspace.initialize().get(10, TimeUnit.SECONDS);
      ProviderProfile generation =
          workspace.profiles().stream()
              .filter(profile -> profile.id().equals(workspace.localModelSelection().rubricId()))
              .findFirst()
              .orElseThrow();
      assertTrue(workspace.generationModelUnavailableReason().contains("Key"));
      Path cache = directory.resolve("credentials").resolve(generation.credentialRef() + ".dpapi");
      Files.createDirectories(cache.getParent());
      // Deliberately not a real encrypted key: this API only reports file presence, never reads it.
      Files.write(cache, new byte[] {1});
      assertTrue(workspace.generationModelUnavailableReason().contains("尚未验证"));
      var verified =
          generation.withVerification(
              new ProviderProfile.Verification(
                  generation.configDigest(), Instant.EPOCH, false, true));
      workspace.saveProfile(verified).get(5, TimeUnit.SECONDS);
      assertFalse(verified.isVerified(), "A rule-generation model need not have vision capability");
      assertTrue(workspace.generationModelAvailable());
      assertEquals("", workspace.generationModelUnavailableReason());
      ProviderProfile draft =
          LocalModelSetup.savedOrIsolatedDraft(
              verified,
              LocalModelSetup.Purpose.FALLBACK,
              "Unassigned synthetic draft",
              verified.endpoint(),
              "synthetic-alternate-model",
              verified.structuredMode(),
              false,
              java.util.UUID.randomUUID());
      workspace.saveProfile(draft).get(5, TimeUnit.SECONDS);
      assertFalse(draft.isStructuredVerified());
      assertTrue(
          workspace.generationModelAvailable(),
          "Unassigned drafts must not gate the active text role");
      assertEquals(verified.id(), workspace.localModelSelection().rubricId());
      var stale =
          new ProviderProfile(
              verified.id(),
              verified.revision() + 1,
              verified.displayName(),
              verified.endpoint(),
              verified.modelId(),
              verified.credentialRef(),
              verified.structuredMode(),
              verified.parameters(),
              verified.allowedParameters(),
              verified.connectTimeout(),
              verified.requestTimeout(),
              verified.pricing(),
              verified.verification());
      workspace.saveProfile(stale).get(5, TimeUnit.SECONDS);
      assertFalse(workspace.generationModelAvailable());
      assertTrue(workspace.generationModelUnavailableReason().contains("配置已更改"));
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
    assertTrue(workspace.generationModelUnavailableReason().contains("关闭"));
  }
}
