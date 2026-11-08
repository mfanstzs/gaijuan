package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageConfigurationTest {
  @TempDir Path temp;

  @Test
  void loadsExplicitMysqlAndRedisEndpointsWithoutReadingSecretsOrConnecting() throws Exception {
    assertNotNull(StorageConfiguration.load(write(valid())));
  }

  @Test
  void rejectsH2AndOtherImplicitLocalDatabaseUrls() throws Exception {
    for (String url :
        new String[] {"jdbc:h2:file:gateway", "jdbc:h2:mem:gateway", "jdbc:sqlite:gateway", ""}) {
      var root = valid();
      root.withObject("mysql").put("jdbcUrl", url);
      rejected(root);
    }
  }

  @Test
  void refusesCredentialsEmbeddedInEverySupportedMysqlUrlPosition() throws Exception {
    for (String url :
        new String[] {
          "jdbc:mysql://127.0.0.1:3306/deskgrade?password=synthetic-secret",
          "jdbc:mysql://127.0.0.1:3306/deskgrade?user=synthetic-user",
          "jdbc:mysql://synthetic-user:synthetic-secret@127.0.0.1:3306/deskgrade",
          "jdbc:mysql://address=(host=127.0.0.1)(user=synthetic-user)(password=synthetic-secret)/deskgrade",
          "jdbc:mysql://(host=127.0.0.1,user=synthetic-user,password=synthetic-secret)/deskgrade",
          "jdbc:mysql://127.0.0.1:3306/deskgrade?%70assword=synthetic-secret"
        }) {
      var root = valid();
      root.withObject("mysql").put("jdbcUrl", url);
      rejected(root);
    }
  }

  @Test
  void refusesNonbooleanTlsConfigurationRatherThanSilentlyDisablingTls() throws Exception {
    var text = valid();
    text.withObject("redis").put("ssl", "tru");
    rejected(text);
    var number = valid();
    number.withObject("redis").put("ssl", 1);
    rejected(number);
  }

  @Test
  void schemaVersionMustNotWrapAroundAnInteger() throws Exception {
    var root = valid();
    root.put("schemaVersion", new BigInteger("4294967297"));
    rejected(root);
  }

  @Test
  void refusesRelativeCredentialDirectoryAndInvalidSecretReferences() throws Exception {
    var relative = valid();
    relative.put("credentialsDirectory", "credentials");
    rejected(relative);
    var traversal = valid();
    traversal.withObject("mysql").put("passwordRef", "../another-app");
    rejected(traversal);
  }

  @Test
  void validatesRedisNamespacePortAndDatabaseBeforeOpeningServices() throws Exception {
    var namespace = valid();
    namespace.withObject("redis").put("namespace", "another-application:");
    rejected(namespace);
    var port = valid();
    port.withObject("redis").put("port", 65536);
    rejected(port);
    var database = valid();
    database.withObject("redis").put("database", -1);
    rejected(database);
  }

  @Test
  void malformedOrOversizedConfigurationErrorsNeverEchoSecrets() throws Exception {
    Path duplicate = temp.resolve("duplicate.json");
    Files.writeString(
        duplicate,
        "{\"secret\":\"synthetic-secret\",\"secret\":\"another\"}",
        StandardCharsets.UTF_8);
    safeFailure(duplicate);
    Path huge = temp.resolve("huge.json");
    Files.writeString(huge, " ".repeat(32769), StandardCharsets.UTF_8);
    safeFailure(huge);
    safeFailure(temp.resolve("missing.json"));
  }

  private ObjectNode valid() {
    var root = GatewayJson.MAPPER.createObjectNode();
    root.put("schemaVersion", 1);
    root.put("credentialsDirectory", temp.resolve("protected").toAbsolutePath().toString());
    var mysql = root.putObject("mysql");
    mysql.put(
        "jdbcUrl", "jdbc:mysql://127.0.0.1:3306/deskgrade?connectTimeout=5000&socketTimeout=15000");
    mysql.put("username", "deskgrade_app");
    mysql.put("passwordRef", "mysql-production");
    var redis = root.putObject("redis");
    redis.put("host", "127.0.0.1");
    redis.put("port", 6379);
    redis.put("database", 0);
    redis.put("ssl", false);
    redis.put("namespace", "deskgrade:gateway:");
    redis.putNull("username");
    redis.putNull("passwordRef");
    return root;
  }

  private Path write(ObjectNode root) throws Exception {
    Path path = temp.resolve(UUID.randomUUID() + ".json");
    GatewayJson.MAPPER.writeValue(path.toFile(), root);
    return path;
  }

  private void rejected(ObjectNode root) throws Exception {
    safeFailure(write(root));
  }

  private static void safeFailure(Path path) {
    var failure = assertThrows(GatewayException.class, () -> StorageConfiguration.load(path));
    assertEquals(GatewayException.Code.STORAGE_FAILURE, failure.code());
    assertEquals("STORAGE_FAILURE", failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic-secret"));
  }
}
