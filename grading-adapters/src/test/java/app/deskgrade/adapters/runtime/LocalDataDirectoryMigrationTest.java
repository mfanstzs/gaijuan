package app.deskgrade.adapters.runtime;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.ProfileJsonCodec;
import app.deskgrade.core.domain.model.ProviderProfile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataDirectoryMigrationTest {
  @TempDir Path directory;

  @Test
  void defaultRootDoesNotDependOnLocalAppDataOrWorkingDirectory() {
    assertEquals(
        directory.resolve(".deskgrade/data"),
        LocalDataDirectoryMigration.defaultDirectory(directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> LocalDataDirectoryMigration.defaultDirectory(Path.of("relative")));
  }

  @Test
  void upgradeCopiesOnlyLocalDataAtomicallyAndKeepsOriginalsAndEncryptedCredential()
      throws Exception {
    Path source = directory.resolve("legacy"), target = directory.resolve("home/.deskgrade/data");
    Files.createDirectories(source.resolve("database"));
    Files.createDirectories(source.resolve("settings"));
    Files.createDirectories(source.resolve("credentials"));
    Files.createDirectories(source.resolve("records"));
    Files.writeString(source.resolve("database/local-deskgrade.mv.db"), "synthetic-local-ledger");
    Files.writeString(
        source.resolve("database/deskgrade.mv.db"), "do-not-import-developer-or-cloud-history");
    Files.writeString(
        source.resolve("settings/local-profiles.json"),
        new ProfileJsonCodec().write(List.of(profile())));
    Files.writeString(
        source.resolve("settings/local-model-selection.json"), "synthetic-local-roles");
    Files.writeString(source.resolve("settings/rubric.json"), "synthetic-rubric");
    Files.writeString(source.resolve("settings/rubric-subject.txt"), "synthetic-subject");
    Files.writeString(source.resolve("settings/access.json"), "never-import-login-state");
    Files.writeString(
        source.resolve("settings/manual-calibration.json"), "never-rebind-old-position");
    byte[] encrypted = new byte[] {7, 12, 1, 4};
    Files.write(source.resolve("credentials/local-key.dpapi"), encrypted);
    Files.writeString(
        source.resolve("credentials/commercial-session.dpapi"), "never-import-account-session");
    String history = "{\"event\":\"SYNTHETIC\"}\n";
    Files.writeString(source.resolve("records/grading-2026-10-21-000001.jsonl"), history);

    assertEquals(
        LocalDataDirectoryMigration.Result.COPIED,
        LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, source));
    assertEquals(
        "synthetic-local-ledger",
        Files.readString(target.resolve("database/local-deskgrade.mv.db")));
    assertArrayEquals(encrypted, Files.readAllBytes(target.resolve("credentials/local-key.dpapi")));
    assertEquals(
        history, Files.readString(target.resolve("records/grading-2026-10-21-000001.jsonl")));
    assertEquals(
        history, Files.readString(source.resolve("records/grading-2026-10-21-000001.jsonl")));
    assertTrue(Files.exists(source.resolve("database/local-deskgrade.mv.db")));
    assertFalse(Files.exists(target.resolve("database/deskgrade.mv.db")));
    assertFalse(Files.exists(target.resolve("settings/access.json")));
    assertFalse(Files.exists(target.resolve("settings/manual-calibration.json")));
    assertFalse(Files.exists(target.resolve("credentials/commercial-session.dpapi")));
    assertTrue(
        Files.readString(target.resolve("local-storage-migration.json"))
            .contains("sourceRetained"));
  }

  @Test
  void alreadyInitializedDestinationAndCredentialsAreNeverOverwritten() throws Exception {
    Path source = directory.resolve("legacy"), target = directory.resolve("home/.deskgrade/data");
    Files.createDirectories(source.resolve("database"));
    Files.createDirectories(target.resolve("database"));
    Files.createDirectories(target.resolve("credentials"));
    Files.writeString(source.resolve("database/local-deskgrade.mv.db"), "other-history");
    Files.writeString(target.resolve("database/local-deskgrade.mv.db"), "active-history");
    Files.writeString(target.resolve("credentials/active.dpapi"), "already-encrypted-cache");
    assertEquals(
        LocalDataDirectoryMigration.Result.ALREADY_INITIALIZED,
        LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, source));
    assertEquals(
        "active-history", Files.readString(target.resolve("database/local-deskgrade.mv.db")));
    assertEquals(
        "already-encrypted-cache", Files.readString(target.resolve("credentials/active.dpapi")));
    assertFalse(Files.exists(target.resolve("local-storage-migration.json")));
  }

  @Test
  void noLegacyDataIsANormalFreshInstallAndNeverCreatesAnEmptyDestination() throws Exception {
    Path target = directory.resolve("home/.deskgrade/data");
    assertEquals(
        LocalDataDirectoryMigration.Result.NO_LEGACY_DATA,
        LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, directory.resolve("missing")));
    assertFalse(Files.exists(target));
  }

  @Test
  void lockedLegacyAppPreventsCopyAndLeavesSourceUntouched() throws Exception {
    Path source = directory.resolve("legacy"), target = directory.resolve("home/.deskgrade/data");
    Files.createDirectories(source.resolve("database"));
    Files.writeString(source.resolve("database/local-deskgrade.mv.db"), "preserved-history");
    try (var held = SingleInstanceGuard.tryAcquire(source).orElseThrow()) {
      assertThrows(
          java.io.IOException.class,
          () -> LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, source));
      assertFalse(Files.exists(target));
      assertEquals(
          "preserved-history", Files.readString(source.resolve("database/local-deskgrade.mv.db")));
    }
  }

  @Test
  void unexpectedEmptyDestinationDoesNotSilentlySkipAnExistingHistory() throws Exception {
    Path source = directory.resolve("legacy"), target = directory.resolve("home/.deskgrade/data");
    Files.createDirectories(source.resolve("database"));
    Files.createDirectories(target);
    Files.writeString(source.resolve("database/local-deskgrade.mv.db"), "preserved-history");
    assertThrows(
        java.io.IOException.class,
        () -> LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, source));
    assertFalse(Files.exists(target.resolve("database/local-deskgrade.mv.db")));
  }

  @Test
  void invalidModelConfigurationCannotPublishPartialDatabase() throws Exception {
    Path source = directory.resolve("legacy"), target = directory.resolve("home/.deskgrade/data");
    Files.createDirectories(source.resolve("database"));
    Files.createDirectories(source.resolve("settings"));
    Files.writeString(source.resolve("database/local-deskgrade.mv.db"), "preserved-history");
    Files.writeString(source.resolve("settings/local-profiles.json"), "invalid-profile-document");
    assertThrows(
        java.io.IOException.class,
        () -> LocalDataDirectoryMigration.copyVisibleLegacyOnce(target, source));
    assertFalse(Files.exists(target));
  }

  private static ProviderProfile profile() {
    return new ProviderProfile(
        "local-profile",
        1,
        "Synthetic",
        URI.create("https://synthetic.invalid/chat/completions"),
        "synthetic-model",
        "local-key",
        ProviderProfile.StructuredMode.JSON_OBJECT,
        Map.of(),
        Set.of(),
        Duration.ofSeconds(10),
        Duration.ofSeconds(120),
        null,
        null);
  }
}
