package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProfileJsonCodec;
import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalModelStorageBoundaryTest {
  @TempDir Path directory;

  @Test
  void rejectedNewDraftOrUnreadableReplacementPreservesReadyProfilesAcrossRestart()
      throws Exception {
    var profiles = new ArrayList<>(LocalModelDefaults.profiles());
    ProviderProfile template = profiles.getFirst();
    for (int index = profiles.size(); index < ProfileJsonCodec.MAX_LOCAL_PROFILES; index++)
      profiles.add(copy(template, "draft_" + index, template.endpoint()));
    Path file = directory.resolve("settings/local-profiles.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, new ProfileJsonCodec().write(profiles));
    byte[] saved = Files.readAllBytes(file);
    var first = new DesktopWorkspace(directory);
    try (first) {
      assertEquals(256, first.initialize().get(10, TimeUnit.SECONDS).profiles().size());
      assertRejected(
          first,
          copy(template, "draft_over_limit", template.endpoint()),
          DesktopWorkspace.LocalProfileStorageException.Reason.TOO_MANY_PROFILES);
      assertRejected(
          first,
          copy(
              template,
              template.id(),
              URI.create("https://api.example/" + "a".repeat(1100000) + "/chat/completions")),
          DesktopWorkspace.LocalProfileStorageException.Reason.DOCUMENT_TOO_LARGE);
      assertRejected(
          first,
          copy(
              template,
              template.id(),
              URI.create("https://api.example/" + "b".repeat(2100) + "/chat/completions")),
          DesktopWorkspace.LocalProfileStorageException.Reason.INVALID_CONFIGURATION);
      assertArrayEquals(saved, Files.readAllBytes(file));
      assertEquals(256, first.profiles().size());
      assertEquals(
          template,
          first.profiles().stream()
              .filter(p -> p.id().equals(template.id()))
              .findFirst()
              .orElseThrow());
      assertTrue(first.ready());
      assertTrue(first.localOnlyRuntime());
    }
    first.awaitClosed().get(10, TimeUnit.SECONDS);
    var restarted = new DesktopWorkspace(directory);
    try (restarted) {
      assertEquals(256, restarted.initialize().get(10, TimeUnit.SECONDS).profiles().size());
      assertTrue(restarted.ready());
      assertTrue(restarted.localOnlyRuntime());
      assertArrayEquals(saved, Files.readAllBytes(file));
    }
    restarted.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  private static void assertRejected(
      DesktopWorkspace workspace,
      ProviderProfile profile,
      DesktopWorkspace.LocalProfileStorageException.Reason reason) {
    Exception failure =
        assertThrows(
            Exception.class, () -> workspace.saveProfile(profile).get(5, TimeUnit.SECONDS));
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    assertEquals(
        reason,
        assertInstanceOf(DesktopWorkspace.LocalProfileStorageException.class, cause).reason());
  }

  private static ProviderProfile copy(ProviderProfile source, String id, URI endpoint) {
    return new ProviderProfile(
        id,
        source.revision(),
        source.displayName(),
        endpoint,
        source.modelId(),
        source.credentialRef(),
        source.structuredMode(),
        source.parameters(),
        source.allowedParameters(),
        source.connectTimeout(),
        source.requestTimeout(),
        source.pricing(),
        null);
  }
}
