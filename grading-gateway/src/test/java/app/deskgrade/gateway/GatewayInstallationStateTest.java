package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayInstallationStateTest {
  @TempDir Path temp;

  @Test
  void onlyADatabaseWithNoApplicationTablesIsANewInstallation() throws Exception {
    try (var database = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
      assertDoesNotThrow(() -> GatewayStore.requireNewDatabase(database));
      try (var create = database.createStatement()) {
        create.execute(
            "CREATE TABLE gateway_storage_state(id INT PRIMARY KEY,migration_state VARCHAR(16))");
        create.execute("INSERT INTO gateway_storage_state VALUES(1,'PENDING')");
      }
      assertThrows(SQLException.class, () -> GatewayStore.requireNewDatabase(database));
    }
  }

  @Test
  void pendingMigrationDoesNotBecomeAReadyInstallationEvenIfAnAdminWasCopied() throws Exception {
    Path source = temp.resolve("pending");
    setup(source, true);
    try (var connection = writable(source)) {
      state(connection, "PENDING");
      assertThrows(SQLException.class, () -> GatewayStore.requireCompleteInstallation(connection));
    }
  }

  @Test
  void readyMarkerWithoutAnAdministratorCannotTriggerNormalStartup() throws Exception {
    Path source = temp.resolve("noadmin");
    setup(source, false);
    try (var connection = writable(source)) {
      state(connection, "READY");
      assertThrows(SQLException.class, () -> GatewayStore.requireCompleteInstallation(connection));
    }
  }

  @Test
  void verifiedReadyInstallationIsAcceptedWithoutCreatingOrChangingAnAccount() throws Exception {
    Path source = temp.resolve("ready");
    setup(source, true);
    try (var connection = writable(source)) {
      state(connection, "READY");
      assertDoesNotThrow(() -> GatewayStore.requireCompleteInstallation(connection));
      try (var query = connection.prepareStatement("SELECT COUNT(*),SUM(balance) FROM accounts");
          var result = query.executeQuery()) {
        assertTrue(result.next());
        assertEquals(1, result.getLong(1));
        assertEquals(0, result.getLong(2));
      }
    }
  }

  private static void setup(Path source, boolean administrator) {
    try (var store = new GatewayStore(source, Clock.systemUTC())) {
      if (administrator) store.bootstrapAdmin("operator", GatewayFixtures.password());
    }
  }

  private static Connection writable(Path source) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:h2:file:" + source.toAbsolutePath().toString().replace('\\', '/') + ";IFEXISTS=TRUE",
        "sa",
        "");
  }

  private static void state(Connection connection, String value) throws SQLException {
    try (var create = connection.createStatement()) {
      create.execute(
          "CREATE TABLE gateway_storage_state(id INT PRIMARY KEY,migration_state VARCHAR(16))");
    }
    try (var insert =
        connection.prepareStatement("INSERT INTO gateway_storage_state VALUES(1,?)")) {
      insert.setString(1, value);
      insert.executeUpdate();
    }
  }
}
