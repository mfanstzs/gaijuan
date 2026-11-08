package app.deskgrade.gateway;

import static app.deskgrade.gateway.GatewayException.Code.*;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Transactional account ledger. Production uses MySQL with one active gateway; the H2 constructor
 * remains only for isolated tests and reading/migrating legacy local data.
 */
public final class GatewayStore implements AutoCloseable {
  public record Account(UUID id, String username, String role, long balance, Instant createdAt) {}

  public record Login(String token, Instant expiresAt, Account account) {
    @Override
    public String toString() {
      return "Login[token=redacted]";
    }
  }

  public record Adjustment(
      UUID id,
      UUID adminId,
      UUID userId,
      long delta,
      String reason,
      long balanceAfter,
      Instant createdAt) {}

  public record Order(
      UUID id,
      UUID userId,
      UUID requestId,
      String packageId,
      long units,
      Long priceCents,
      String status,
      Instant createdAt,
      String packageName,
      String currency) {}

  /** A durable commercial package. Deleted rows remain as tombstones for historical orders. */
  public record CreditPackage(
      String id,
      String name,
      long credits,
      Long priceMinor,
      String currency,
      boolean enabled,
      long revision,
      Instant createdAt,
      Instant updatedAt) {}

  public record Request(
      UUID userId,
      UUID requestId,
      String fingerprint,
      String modelId,
      String state,
      boolean charged,
      String responseJson,
      String errorCode,
      Instant createdAt,
      String profileId,
      Long policyRevision) {}

  public record Prepared(Request request, boolean created) {}

  public record ModelPolicy(long revision, String profileId, String modelId) {}

  public record PolicyAudit(
      UUID id,
      UUID adminId,
      String previousProfileId,
      String profileId,
      String previousModelId,
      String modelId,
      long previousRevision,
      long revision,
      String reason,
      Instant createdAt) {}

  public record PolicyChange(ModelPolicy policy, UUID auditEntryId, boolean replayed) {}

  public record Page<T>(List<T> items, int page, int pageSize, long total, long totalPages) {}

  public record ModelCall(
      String kind,
      UUID requestId,
      UUID userId,
      String username,
      String modelId,
      String profileId,
      Long policyRevision,
      String state,
      int chargedCredits,
      String errorCode,
      Instant createdAt,
      Instant completedAt,
      boolean requestSnapshotAvailable,
      boolean responseAvailable,
      boolean modelOutputAvailable) {}

  public record ModelCallDetail(
      ModelCall call, String requestJson, String responseJson, String modelOutput) {}

  public record NamedLedger(LedgerEntry entry, String username) {}

  public record NamedOrder(Order order, String username) {}

  public record DashboardDay(
      LocalDate date,
      long registrations,
      long modelCalls,
      long succeededCalls,
      long failedCalls,
      long unresolvedCalls,
      long chargedCredits,
      long creditsAdded,
      long orders,
      Long orderAmountMinor,
      long unpricedOrders) {}

  public record ModelUsage(
      String modelId,
      long calls,
      long succeededCalls,
      long failedCalls,
      long unresolvedCalls,
      long chargedCredits) {}

  public record Dashboard(
      Instant generatedAt,
      int days,
      long totalUsers,
      long availableCredits,
      long modelCalls,
      long succeededCalls,
      long failedCalls,
      long unresolvedCalls,
      long chargedCredits,
      long creditsAdded,
      long orders,
      Long orderAmountMinor,
      long unpricedOrders,
      List<DashboardDay> daily,
      List<ModelUsage> models) {}

  public record AuditEntry(
      UUID id,
      UUID adminId,
      String adminUsername,
      String kind,
      String packageId,
      UUID targetUserId,
      String targetUsername,
      Long delta,
      Long balanceAfter,
      String profileId,
      String modelId,
      String previousModelId,
      Long previousRevision,
      Long revision,
      String reason,
      Instant createdAt) {}

  public record LedgerEntry(
      UUID id,
      UUID userId,
      String kind,
      UUID referenceId,
      long delta,
      long balanceBefore,
      long balanceAfter,
      Instant createdAt,
      String reason,
      UUID adminId) {}

  private record PasswordRow(Account account, byte[] salt, byte[] hash, int iterations) {}

  private record CreditPackageRow(CreditPackage value, Long deletedAt) {}

  private Connection connection;
  private final DataSource dataSource;
  private final SessionTokenStore sessionTokens;
  private final FileChannel lockChannel;
  private final FileLock processLock;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final PasswordHasher.Encoded dummy =
      PasswordHasher.create("synthetic nonexistent account".toCharArray());
  private boolean closed;

  static final List<CreditPackage> DEFAULT_CREDIT_PACKAGES =
      List.of(
          new CreditPackage(
              "calls-100", "100 次", 100, null, "CNY", true, 1, Instant.EPOCH, Instant.EPOCH),
          new CreditPackage(
              "calls-500", "500 次", 500, null, "CNY", true, 1, Instant.EPOCH, Instant.EPOCH),
          new CreditPackage(
              "calls-1000", "1000 次", 1000, null, "CNY", true, 1, Instant.EPOCH, Instant.EPOCH));

  /** Read-only bootstrap discovery. Does not create a database, migrate it, or recover requests. */
  public static boolean adminPresent(Path database) {
    Path absolute = database.toAbsolutePath().normalize();
    if (absolute.toString().contains(";")) throw new GatewayException(INVALID_REQUEST);
    if (!Files.exists(absolute.resolveSibling(absolute.getFileName() + ".mv.db"))) return false;
    try (var readOnly =
            DriverManager.getConnection(
                "jdbc:h2:file:"
                    + absolute.toString().replace('\\', '/')
                    + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0;AUTO_SERVER=FALSE",
                "sa",
                "");
        var query = readOnly.prepareStatement("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'");
        var rs = query.executeQuery()) {
      rs.next();
      return rs.getLong(1) > 0;
    } catch (Exception failure) {
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  public GatewayStore(Path database, Clock clock) {
    this(database, clock, null);
  }

  /** Isolated tests can exercise cache failure/recovery against the legacy relational dialect. */
  GatewayStore(Path database, Clock clock, SessionTokenStore sessionTokens) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.dataSource = null;
    this.sessionTokens = sessionTokens;
    Connection opened = null;
    FileChannel locking = null;
    FileLock acquired = null;
    try {
      Path absolute = database.toAbsolutePath().normalize();
      if (absolute.toString().contains(";")) throw new IllegalArgumentException();
      Files.createDirectories(absolute.getParent());
      locking =
          FileChannel.open(
              absolute.resolveSibling(absolute.getFileName() + ".gateway.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE);
      acquired = locking.tryLock();
      if (acquired == null) throw new IllegalStateException();
      opened =
          DriverManager.getConnection(
              "jdbc:h2:file:"
                  + absolute.toString().replace('\\', '/')
                  + ";WRITE_DELAY=0;LOCK_TIMEOUT=5000;AUTO_SERVER=FALSE",
              "sa",
              "");
      connection = opened;
      lockChannel = locking;
      processLock = acquired;
      connection.setAutoCommit(false);
      initialize();
    } catch (Exception failure) {
      try {
        if (opened != null) opened.close();
      } catch (Exception ignored) {
      }
      try {
        if (acquired != null) acquired.close();
      } catch (Exception ignored) {
      }
      try {
        if (locking != null) locking.close();
      } catch (Exception ignored) {
      }
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  /**
   * Opens an explicitly initialized, migrated MySQL database. Never creates an empty replacement.
   */
  public GatewayStore(DataSource dataSource, Clock clock, SessionTokenStore sessionTokens) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.dataSource = java.util.Objects.requireNonNull(dataSource);
    this.sessionTokens = java.util.Objects.requireNonNull(sessionTokens);
    this.lockChannel = null;
    this.processLock = null;
    try {
      sessionTokens.verifyAvailable();
      connection = openMySql(dataSource, true);
      recoverInterruptedRequests(connection);
      seedDefaultPackages(connection, clock.millis());
      connection.commit();
    } catch (Exception failure) {
      closeQuietly(connection);
      if (failure instanceof GatewayException safe) throw safe;
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  /** Explicit migration/setup entry point; does not create the database or mark it ready. */
  public static void initializeMySql(DataSource source) {
    Connection opened = null;
    try {
      opened = openMySql(source, false);
      initializeMySqlSchema(opened);
      opened.commit();
    } catch (Exception failure) {
      throw new GatewayException(STORAGE_FAILURE);
    } finally {
      closeQuietly(opened);
    }
  }

  private static void initializeMySqlSchema(Connection opened) throws Exception {
    executeSqlResource(opened, "/db/mysql/V001__gateway.sql");
    try (var statement = opened.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO gateway_schema(id,version) SELECT 1,3 WHERE NOT EXISTS(SELECT 1 FROM gateway_schema WHERE id=1)");
      statement.executeUpdate(
          "INSERT INTO gateway_storage_state(id,migration_state) SELECT 1,'PENDING' WHERE NOT EXISTS(SELECT 1 FROM gateway_storage_state WHERE id=1)");
      statement.executeUpdate(
          "INSERT INTO gateway_model_policy(id,revision,profile_id,model_id) SELECT 1,0,NULL,NULL WHERE NOT EXISTS(SELECT 1 FROM gateway_model_policy WHERE id=1)");
      statement.executeUpdate(
          "INSERT INTO gateway_rubric_model_policy(id,revision,profile_id,model_id) SELECT 1,0,NULL,NULL WHERE NOT EXISTS(SELECT 1 FROM gateway_rubric_model_policy WHERE id=1)");
      statement.executeUpdate("UPDATE gateway_schema SET version=3 WHERE id=1 AND version=2");
      upgradeMySqlV3ToV4(opened);
      upgradeMySqlV4ToV5(opened);
      upgradeMySqlV5ToV6(opened);
      verifySchemaVersion(opened);
    }
  }

  private static void upgradeMySqlV2ToV3(Connection opened) throws SQLException {
    int version;
    try (var query = opened.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException();
      version = rows.getInt(1);
    }
    if (version == 3 || version == 4 || version == 5 || version == 6) return;
    if (version != 2) throw new SQLException();
    // MySQL DDL can commit implicitly. Every statement is additive and idempotent, and the version
    // moves last so a stopped upgrade is safely completed on the next launch.
    executeSqlResource(opened, "/db/mysql/V003__rubric_generation.sql");
    try (var statement = opened.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO gateway_rubric_model_policy(id,revision,profile_id,model_id) SELECT 1,0,NULL,NULL WHERE NOT EXISTS(SELECT 1 FROM gateway_rubric_model_policy WHERE id=1)");
      if (statement.executeUpdate("UPDATE gateway_schema SET version=3 WHERE id=1 AND version=2")
          != 1) throw new SQLException();
    }
  }

  private static void executeSqlResource(Connection opened, String resourceName)
      throws SQLException {
    try (var resource = GatewayStore.class.getResourceAsStream(resourceName)) {
      if (resource == null) throw new SQLException();
      try (var statement = opened.createStatement()) {
        for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
          if (!sql.isBlank()) statement.execute(sql.trim());
        }
      }
    } catch (java.io.IOException invalidResource) {
      throw new SQLException();
    }
  }

  private static void upgradeMySqlV3ToV4(Connection opened) throws SQLException {
    int version;
    try (var query = opened.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException();
      version = rows.getInt(1);
    }
    if (version == 4 || version == 5 || version == 6) return;
    if (version != 3) throw new SQLException();
    ensureModelCallRecordColumns(opened, true);
    try (var statement = opened.createStatement()) {
      if (statement.executeUpdate("UPDATE gateway_schema SET version=4 WHERE id=1 AND version=3")
          != 1) throw new SQLException();
    }
  }

  /** Additive and restartable: package rows are seeded only after the database is READY. */
  private static void upgradeMySqlV4ToV5(Connection opened) throws SQLException {
    int version;
    try (var query = opened.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException();
      version = rows.getInt(1);
    }
    if (version == 5 || version == 6) return;
    if (version != 4) throw new SQLException();
    executeSqlResource(opened, "/db/mysql/V005__credit_packages.sql");
    try (var statement = opened.createStatement()) {
      if (statement.executeUpdate("UPDATE gateway_schema SET version=5 WHERE id=1 AND version=4")
          != 1) throw new SQLException();
    }
  }

  /**
   * Adds immutable order catalogue snapshots, package mutation audit and global dashboard indexes.
   * Every DDL/data step is repeatable and the version moves last because MySQL may commit DDL.
   */
  private static void upgradeMySqlV5ToV6(Connection opened) throws SQLException {
    int version;
    try (var query = opened.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new SQLException();
      version = rows.getInt(1);
    }
    if (version == 6) return;
    if (version != 5) throw new SQLException();
    executeSqlResource(opened, "/db/mysql/V006__commercial_integrity.sql");
    ensureCommercialIntegritySchema(opened, true);
    try (var statement = opened.createStatement()) {
      if (statement.executeUpdate("UPDATE gateway_schema SET version=6 WHERE id=1 AND version=5")
          != 1) throw new SQLException();
    }
  }

  /**
   * Additive, restartable V4 migration; DDL never rewrites existing receipts or invents prompts.
   */
  private static void ensureModelCallRecordColumns(Connection opened, boolean mysql)
      throws SQLException {
    for (String table : List.of("grade_requests", "rubric_generation_requests")) {
      String metadataTable = mysql ? table : table.toUpperCase(java.util.Locale.ROOT);
      for (String column : List.of("request_json", "raw_model_output", "completed_at")) {
        boolean exists;
        try (var columns =
            opened
                .getMetaData()
                .getColumns(
                    opened.getCatalog(),
                    opened.getSchema(),
                    metadataTable,
                    mysql ? column : column.toUpperCase(java.util.Locale.ROOT))) {
          exists = columns.next();
        }
        if (!exists)
          try (var statement = opened.createStatement()) {
            statement.execute(
                "ALTER TABLE "
                    + table
                    + " ADD COLUMN "
                    + column
                    + " "
                    + (column.equals("completed_at") ? "BIGINT" : mysql ? "LONGTEXT" : "CLOB"));
          }
      }
      var indexes = new java.util.HashSet<String>();
      try (var rows =
          opened
              .getMetaData()
              .getIndexInfo(opened.getCatalog(), opened.getSchema(), metadataTable, false, false)) {
        while (rows.next())
          if (rows.getString("INDEX_NAME") != null)
            indexes.add(rows.getString("INDEX_NAME").toLowerCase(java.util.Locale.ROOT));
      }
      String prefix = table.equals("grade_requests") ? "grade_calls" : "rubric_calls";
      for (String suffix : List.of("global_page", "user_page")) {
        String name = prefix + "_" + suffix;
        if (!indexes.contains(name))
          try (var statement = opened.createStatement()) {
            statement.execute(
                "CREATE INDEX "
                    + name
                    + " ON "
                    + table
                    + (suffix.equals("user_page")
                        ? "(user_id,created_at,request_id)"
                        : "(created_at,request_id,user_id)"));
          }
      }
    }
  }

  private static void ensureCommercialIntegritySchema(Connection opened, boolean mysql)
      throws SQLException {
    String table = mysql ? "orders" : "ORDERS";
    for (String column : List.of("package_name", "currency")) {
      if (!columnExists(opened, table, mysql ? column : column.toUpperCase(Locale.ROOT))) {
        try (var statement = opened.createStatement()) {
          statement.execute(
              "ALTER TABLE orders ADD COLUMN "
                  + column
                  + (column.equals("package_name") ? " VARCHAR(80)" : " CHAR(3)"));
        }
      }
    }
    backfillOrderPackageSnapshots(opened);
    try (var statement = opened.createStatement()) {
      if (mysql) {
        statement.execute("ALTER TABLE orders MODIFY COLUMN package_name VARCHAR(80) NOT NULL");
        statement.execute("ALTER TABLE orders MODIFY COLUMN currency CHAR(3) NOT NULL");
      } else {
        statement.execute("ALTER TABLE orders ALTER COLUMN package_name SET NOT NULL");
        statement.execute("ALTER TABLE orders ALTER COLUMN currency SET NOT NULL");
      }
    }
    ensureIndex(
        opened,
        mysql ? "accounts" : "ACCOUNTS",
        "accounts_registration_dashboard",
        "accounts(role,created_at,id)");
    ensureIndex(
        opened,
        mysql ? "quota_events" : "QUOTA_EVENTS",
        "quota_events_dashboard",
        "quota_events(created_at,id)");
    ensureIndex(opened, mysql ? "orders" : "ORDERS", "orders_dashboard", "orders(created_at,id)");
  }

  private static boolean columnExists(Connection opened, String table, String column)
      throws SQLException {
    try (var rows =
        opened.getMetaData().getColumns(opened.getCatalog(), opened.getSchema(), table, column)) {
      return rows.next();
    }
  }

  private static void ensureIndex(
      Connection opened, String metadataTable, String indexName, String indexedExpression)
      throws SQLException {
    boolean exists = false;
    try (var rows =
        opened
            .getMetaData()
            .getIndexInfo(opened.getCatalog(), opened.getSchema(), metadataTable, false, false)) {
      while (rows.next()) {
        String found = rows.getString("INDEX_NAME");
        if (found != null && found.equalsIgnoreCase(indexName)) {
          exists = true;
          break;
        }
      }
    }
    if (!exists)
      try (var statement = opened.createStatement()) {
        statement.execute("CREATE INDEX " + indexName + " ON " + indexedExpression);
      }
  }

  private static void backfillOrderPackageSnapshots(Connection opened) throws SQLException {
    var missing = new ArrayList<String[]>();
    try (var read =
            opened.prepareStatement(
                "SELECT o.id,o.package_id,p.name,p.currency FROM orders o LEFT JOIN credit_packages p ON p.id=o.package_id WHERE o.package_name IS NULL OR o.currency IS NULL");
        var rows = read.executeQuery()) {
      while (rows.next()) {
        String packageId = rows.getString("package_id");
        String name = rows.getString("name");
        String currency = rows.getString("currency");
        missing.add(
            new String[] {
              rows.getString("id"),
              name == null ? packageId : name,
              currency == null ? "CNY" : currency
            });
      }
    }
    try (var update =
        opened.prepareStatement(
            "UPDATE orders SET package_name=?,currency=? WHERE id=? AND (package_name IS NULL OR currency IS NULL)")) {
      for (String[] value : missing) {
        update.setString(1, value[1]);
        update.setString(2, value[2]);
        update.setString(3, value[0]);
        if (update.executeUpdate() != 1) throw new SQLException();
      }
    }
    try (var query =
            opened.prepareStatement(
                "SELECT COUNT(*) FROM orders WHERE package_name IS NULL OR currency IS NULL");
        var rows = query.executeQuery()) {
      if (!rows.next() || rows.getLong(1) != 0) throw new SQLException();
    }
  }

  private static void seedDefaultPackages(Connection opened, long now) throws SQLException {
    try (var count = opened.prepareStatement("SELECT COUNT(*) FROM credit_packages");
        var rows = count.executeQuery()) {
      if (!rows.next() || rows.getLong(1) != 0) return;
    }
    try (var insert =
        opened.prepareStatement(
            "INSERT INTO credit_packages(id,name,credits,price_minor,currency,enabled,revision,created_at,updated_at,deleted_at) VALUES(?,?,?,?,?,?,1,?,?,NULL)")) {
      for (CreditPackage pack : DEFAULT_CREDIT_PACKAGES) {
        insert.setString(1, pack.id());
        insert.setString(2, pack.name());
        insert.setLong(3, pack.credits());
        insert.setObject(4, pack.priceMinor());
        insert.setString(5, pack.currency());
        insert.setBoolean(6, pack.enabled());
        insert.setLong(7, now);
        insert.setLong(8, now);
        if (insert.executeUpdate() != 1) throw new SQLException();
      }
    }
  }

  /**
   * Explicit first installation only. An initialized PENDING database is never a new installation.
   */
  public static void assertNewMySqlDatabase(DataSource source) {
    try (var connection = source.getConnection()) {
      if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
          || connection.getCatalog() == null
          || connection.getCatalog().isBlank()) throw new SQLException();
      requireNewDatabase(connection);
    } catch (Exception failure) {
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  static void requireNewDatabase(Connection connection) throws SQLException {
    try (var tables =
        connection
            .getMetaData()
            .getTables(
                connection.getCatalog(),
                connection.getSchema(),
                "%",
                new String[] {"TABLE", "VIEW"})) {
      if (tables.next()) throw new SQLException();
    }
  }

  /**
   * The emptiness check, schema setup and first account stay under the same MySQL ownership lock.
   */
  public static void bootstrapNewMySql(
      DataSource source, String username, byte[] salt, byte[] passwordHash, int iterations) {
    String normalized = normalizeUsername(username);
    if (salt == null
        || salt.length != 16
        || passwordHash == null
        || passwordHash.length != 32
        || iterations < 600000) throw new GatewayException(INVALID_REQUEST);
    Connection opened = null;
    try {
      opened = openMySql(source, false);
      requireNewDatabase(opened);
      initializeMySqlSchema(opened);
      try (var insert =
          opened.prepareStatement(
              "INSERT INTO accounts(id,username,role,balance,salt,password_hash,iterations,created_at) VALUES(?,?,'ADMIN',0,?,?,?,?)")) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, normalized);
        insert.setBytes(3, salt);
        insert.setBytes(4, passwordHash);
        insert.setInt(5, iterations);
        insert.setLong(6, System.currentTimeMillis());
        if (insert.executeUpdate() != 1) throw new SQLException();
      }
      markMySqlReady(opened, "explicit-new-install");
      opened.commit();
    } catch (Exception failure) {
      throw new GatewayException(STORAGE_FAILURE);
    } finally {
      closeQuietly(opened);
    }
  }

  /** Read-only normal-launch gate: failed imports and missing administrators are errors. */
  public static void verifyMySqlInstallation(DataSource source) {
    Connection opened = null;
    try {
      opened = openMySql(source, true);
      requireCompleteInstallation(opened);
    } catch (Exception failure) {
      throw new GatewayException(STORAGE_FAILURE);
    } finally {
      closeQuietly(opened);
    }
  }

  static void requireCompleteInstallation(Connection connection) throws SQLException {
    verifyMySqlReady(connection);
    try (var query =
            connection.prepareStatement("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'");
        var rows = query.executeQuery()) {
      if (!rows.next() || rows.getLong(1) < 1) throw new SQLException();
    }
  }

  /** Called only after an explicit import/bootstrap has been verified by its migration tool. */
  public static void markMySqlReady(DataSource source, String migrationSource) {
    withMySqlMigration(
        source,
        opened -> {
          markMySqlReady(opened, migrationSource);
          return null;
        });
  }

  /** Marks readiness in the caller's import transaction; never commits a partial import. */
  public static void markMySqlReady(Connection opened, String migrationSource) throws SQLException {
    if (migrationSource == null || !migrationSource.matches("[A-Za-z0-9._:-]{1,200}"))
      throw new GatewayException(INVALID_REQUEST);
    verifySchemaVersion(opened);
    try (var query = opened.prepareStatement("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'");
        var rs = query.executeQuery()) {
      rs.next();
      if (rs.getLong(1) < 1) throw new SQLException();
    }
    try (var update =
        opened.prepareStatement(
            "UPDATE gateway_storage_state SET migration_state='READY',migration_source=?,ready_at=? WHERE id=1 AND migration_state='PENDING'")) {
      update.setString(1, migrationSource);
      update.setLong(2, System.currentTimeMillis());
      if (update.executeUpdate() == 0) verifyMySqlReady(opened);
    }
  }

  @FunctionalInterface
  public interface MySqlMigrationWork<T> {
    T run(Connection connection) throws Exception;
  }

  /** Holds the same database lock as the gateway and atomically commits one explicit import. */
  public static <T> T withMySqlMigration(DataSource source, MySqlMigrationWork<T> work) {
    Connection opened = null;
    try {
      opened = openMySql(source, false);
      T result = work.run(opened);
      opened.commit();
      return result;
    } catch (Exception failure) {
      if (failure instanceof GatewayException safe) throw safe;
      throw new GatewayException(STORAGE_FAILURE);
    } finally {
      closeQuietly(opened);
    }
  }

  public static boolean adminPresent(DataSource source) {
    try (Connection opened = source.getConnection();
        var query = opened.prepareStatement("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'");
        var rs = query.executeQuery()) {
      rs.next();
      return rs.getLong(1) > 0;
    } catch (SQLException failure) {
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  private static Connection openMySql(DataSource source, boolean requireReady) throws SQLException {
    Connection opened = source.getConnection();
    try {
      if (!"MySQL".equals(opened.getMetaData().getDatabaseProductName())
          || opened.getCatalog() == null
          || opened.getCatalog().isBlank()) throw new SQLException();
      opened.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      opened.setAutoCommit(false);
      try (var query = opened.prepareStatement("SELECT GET_LOCK(?,0)")) {
        query.setString(1, mySqlLockName(opened));
        try (var rs = query.executeQuery()) {
          if (!rs.next() || rs.getInt(1) != 1) throw new SQLException();
        }
      }
      if (requireReady) {
        upgradeMySqlV2ToV3(opened);
        upgradeMySqlV3ToV4(opened);
        upgradeMySqlV4ToV5(opened);
        upgradeMySqlV5ToV6(opened);
        verifyMySqlReady(opened);
      }
      opened.commit();
      return opened;
    } catch (SQLException failure) {
      closeQuietly(opened);
      throw failure;
    }
  }

  private static String mySqlLockName(Connection opened) throws SQLException {
    return "deskgrade.gateway." + hash(opened.getCatalog()).substring(0, 40);
  }

  private static void verifySchemaVersion(Connection opened) throws SQLException {
    try (var query = opened.prepareStatement("SELECT version FROM gateway_schema WHERE id=1");
        var rs = query.executeQuery()) {
      if (!rs.next() || rs.getInt(1) != 6) throw new SQLException();
    }
  }

  private static void verifyMySqlReady(Connection opened) throws SQLException {
    verifySchemaVersion(opened);
    try (var query =
            opened.prepareStatement(
                "SELECT migration_state FROM gateway_storage_state WHERE id=1");
        var rs = query.executeQuery()) {
      if (!rs.next() || !"READY".equals(rs.getString(1))) throw new SQLException();
    }
  }

  private static void recoverInterruptedRequests(Connection opened) throws SQLException {
    try (var statement = opened.createStatement()) {
      statement.executeUpdate(
          "UPDATE grade_requests SET state='NOT_DISPATCHED', error_code='RESTART_BEFORE_DISPATCH' WHERE state='PREPARED'");
      statement.executeUpdate(
          "UPDATE grade_requests SET state='UNKNOWN', error_code='DISPATCH_OUTCOME_UNKNOWN' WHERE state='DISPATCHING'");
      statement.executeUpdate(
          "UPDATE rubric_generation_requests SET state='NOT_DISPATCHED', error_code='RESTART_BEFORE_DISPATCH' WHERE state='PREPARED'");
      statement.executeUpdate(
          "UPDATE rubric_generation_requests SET state='UNKNOWN', error_code='DISPATCH_OUTCOME_UNKNOWN' WHERE state='DISPATCHING'");
    }
  }

  private static void closeQuietly(Connection opened) {
    if (opened == null) return;
    try {
      opened.rollback();
    } catch (SQLException ignored) {
    }
    try {
      releaseMySqlLock(opened);
    } catch (SQLException ignored) {
    }
    try {
      opened.close();
    } catch (SQLException ignored) {
    }
  }

  private static void releaseMySqlLock(Connection opened) throws SQLException {
    if (!"MySQL".equals(opened.getMetaData().getDatabaseProductName())
        || opened.getCatalog() == null) return;
    try (var query = opened.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      query.setString(1, mySqlLockName(opened));
      try (var ignored = query.executeQuery()) {}
    }
  }

  private void ensureConnection() throws SQLException {
    if (dataSource == null) return;
    boolean usable = false;
    try {
      if (connection != null && !connection.isClosed() && connection.isValid(2)) {
        try (var query = connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
          query.setString(1, mySqlLockName(connection));
          try (var rs = query.executeQuery()) {
            usable = rs.next() && rs.getBoolean(1);
          }
        }
      }
    } catch (SQLException disconnected) {
      /* Reconnect before the next transaction, never replay it. */
    }
    if (!usable) {
      closeQuietly(connection);
      connection = null;
      connection = openMySql(dataSource, true);
      // This process may still own an in-flight model request. Recovery runs only on construction.
    }
  }

  private void initialize() throws SQLException {
    String[] ddl = {
      "CREATE TABLE IF NOT EXISTS gateway_schema (id INT PRIMARY KEY CHECK (id=1), version INT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS gateway_profiles (id VARCHAR(100) PRIMARY KEY, digest CHAR(64) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS accounts (id VARCHAR(36) PRIMARY KEY, username VARCHAR(32) UNIQUE NOT NULL, role VARCHAR(8) NOT NULL CHECK(role IN ('USER','ADMIN')), balance BIGINT NOT NULL CHECK(balance>=0), salt VARBINARY(16) NOT NULL, password_hash VARBINARY(32) NOT NULL, iterations INT NOT NULL CHECK(iterations>=600000), created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS sessions (token_hash CHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), expires_at BIGINT NOT NULL)",
      "CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at)",
      "CREATE TABLE IF NOT EXISTS adjustments (id VARCHAR(36) PRIMARY KEY, admin_id VARCHAR(36) NOT NULL REFERENCES accounts(id), user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), delta BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, balance_after BIGINT NOT NULL CHECK(balance_after>=0), created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS quota_events (id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), kind VARCHAR(24) NOT NULL, reference_id VARCHAR(36) NOT NULL, delta BIGINT NOT NULL, balance_after BIGINT NOT NULL CHECK(balance_after>=0), created_at BIGINT NOT NULL, UNIQUE(user_id,kind,reference_id))",
      "CREATE TABLE IF NOT EXISTS orders (id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), request_id VARCHAR(36) NOT NULL, package_id VARCHAR(64) NOT NULL, units BIGINT NOT NULL CHECK(units>0), price_cents BIGINT, status VARCHAR(24) NOT NULL CHECK(status='PENDING_PAYMENT'), created_at BIGINT NOT NULL, package_name VARCHAR(80), currency CHAR(3), UNIQUE(user_id,request_id))",
      "CREATE TABLE IF NOT EXISTS grade_requests (user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), request_id VARCHAR(36) NOT NULL, fingerprint CHAR(64) NOT NULL, model_id VARCHAR(200) NOT NULL, state VARCHAR(24) NOT NULL CHECK(state IN ('PREPARED','DISPATCHING','SUCCEEDED','FAILED','NOT_DISPATCHED','UNKNOWN')), charged BOOLEAN NOT NULL, response_json CLOB, error_code VARCHAR(64), created_at BIGINT NOT NULL, PRIMARY KEY(user_id,request_id))",
      "CREATE TABLE IF NOT EXISTS rubric_generation_requests (user_id VARCHAR(36) NOT NULL REFERENCES accounts(id), request_id VARCHAR(36) NOT NULL, fingerprint CHAR(64) NOT NULL, model_id VARCHAR(200) NOT NULL, state VARCHAR(24) NOT NULL CHECK(state IN ('PREPARED','DISPATCHING','SUCCEEDED','FAILED','NOT_DISPATCHED','UNKNOWN')), charged BOOLEAN NOT NULL, response_json CLOB, error_code VARCHAR(64), created_at BIGINT NOT NULL, profile_id VARCHAR(100) NOT NULL, policy_revision BIGINT NOT NULL, PRIMARY KEY(user_id,request_id))"
    };
    try (var statement = connection.createStatement()) {
      for (String sql : ddl) statement.execute(sql);
      try (var rs = statement.executeQuery("SELECT version FROM gateway_schema WHERE id=1")) {
        if (rs.next()) {
          if (rs.getInt(1) < 1 || rs.getInt(1) > 6) throw new SQLException();
        } else {
          try (var insert = connection.prepareStatement("INSERT INTO gateway_schema VALUES(1,1)")) {
            insert.executeUpdate();
          }
        }
      }
      // Version 1 receipts keep nullable policy metadata so they remain replayable verbatim.
      statement.execute(
          "ALTER TABLE grade_requests ADD COLUMN IF NOT EXISTS profile_id VARCHAR(100)");
      statement.execute(
          "ALTER TABLE grade_requests ADD COLUMN IF NOT EXISTS policy_revision BIGINT");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS gateway_model_policy (id INT PRIMARY KEY CHECK(id=1), revision BIGINT NOT NULL CHECK(revision>=0), profile_id VARCHAR(100), model_id VARCHAR(200), CHECK((revision=0 AND profile_id IS NULL AND model_id IS NULL) OR (revision>0 AND profile_id IS NOT NULL AND model_id IS NOT NULL)))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS model_policy_audit (id VARCHAR(36) PRIMARY KEY, admin_id VARCHAR(36) NOT NULL REFERENCES accounts(id), previous_profile_id VARCHAR(100), profile_id VARCHAR(100) NOT NULL, previous_model_id VARCHAR(200), model_id VARCHAR(200) NOT NULL, previous_revision BIGINT NOT NULL, revision BIGINT NOT NULL UNIQUE, reason VARCHAR(500) NOT NULL, created_at BIGINT NOT NULL)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS gateway_rubric_model_policy (id INT PRIMARY KEY CHECK(id=1), revision BIGINT NOT NULL CHECK(revision>=0), profile_id VARCHAR(100), model_id VARCHAR(200), CHECK((revision=0 AND profile_id IS NULL AND model_id IS NULL) OR (revision>0 AND profile_id IS NOT NULL AND model_id IS NOT NULL)))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS rubric_model_policy_audit (id VARCHAR(36) PRIMARY KEY, admin_id VARCHAR(36) NOT NULL REFERENCES accounts(id), previous_profile_id VARCHAR(100), profile_id VARCHAR(100) NOT NULL, previous_model_id VARCHAR(200), model_id VARCHAR(200) NOT NULL, previous_revision BIGINT NOT NULL, revision BIGINT NOT NULL UNIQUE, reason VARCHAR(500) NOT NULL, created_at BIGINT NOT NULL)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS credit_packages (id VARCHAR(64) PRIMARY KEY, name VARCHAR(80) NOT NULL, credits BIGINT NOT NULL CHECK(credits>0), price_minor BIGINT CHECK(price_minor IS NULL OR price_minor>=0), currency CHAR(3) NOT NULL, enabled BOOLEAN NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted_at BIGINT)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS package_audit (id VARCHAR(36) PRIMARY KEY, admin_id VARCHAR(36) NOT NULL REFERENCES accounts(id), package_id VARCHAR(64) NOT NULL, action VARCHAR(8) NOT NULL CHECK(action IN ('CREATE','UPDATE','DELETE')), previous_revision BIGINT, revision BIGINT NOT NULL, before_json CLOB, after_json CLOB, created_at BIGINT NOT NULL, UNIQUE(package_id,revision))");
      statement.executeUpdate(
          "INSERT INTO gateway_model_policy(id,revision,profile_id,model_id) SELECT 1,0,NULL,NULL WHERE NOT EXISTS(SELECT 1 FROM gateway_model_policy WHERE id=1)");
      statement.executeUpdate(
          "INSERT INTO gateway_rubric_model_policy(id,revision,profile_id,model_id) SELECT 1,0,NULL,NULL WHERE NOT EXISTS(SELECT 1 FROM gateway_rubric_model_policy WHERE id=1)");
      ensureModelCallRecordColumns(connection, false);
      seedDefaultPackages(connection, clock.millis());
      ensureCommercialIntegritySchema(connection, false);
      statement.executeUpdate("UPDATE gateway_schema SET version=6 WHERE id=1");
      // Additive indexes preserve C1/C2 account and billing data without another schema rewrite.
      statement.execute(
          "CREATE INDEX IF NOT EXISTS quota_user_page ON quota_events(user_id,created_at,id)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS orders_user_page ON orders(user_id,created_at,id)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS adjustments_page ON adjustments(created_at,id)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS package_audit_page ON package_audit(created_at,id)");
      // A prepared request has not crossed the dispatch callback. Dispatch uncertainty never
      // retries.
      statement.executeUpdate(
          "UPDATE grade_requests SET state='NOT_DISPATCHED', error_code='RESTART_BEFORE_DISPATCH' WHERE state='PREPARED'");
      statement.executeUpdate(
          "UPDATE grade_requests SET state='UNKNOWN', error_code='DISPATCH_OUTCOME_UNKNOWN' WHERE state='DISPATCHING'");
      statement.executeUpdate(
          "UPDATE rubric_generation_requests SET state='NOT_DISPATCHED', error_code='RESTART_BEFORE_DISPATCH' WHERE state='PREPARED'");
      statement.executeUpdate(
          "UPDATE rubric_generation_requests SET state='UNKNOWN', error_code='DISPATCH_OUTCOME_UNKNOWN' WHERE state='DISPATCHING'");
    }
    connection.commit();
  }

  public Account register(String username, char[] password) {
    return createAccount(username, password, "USER", false);
  }

  /** CLI only. Bootstrap cannot silently promote an existing user or replace an existing admin. */
  public Account bootstrapAdmin(String username, char[] password) {
    return createAccount(username, password, "ADMIN", true);
  }

  private Account createAccount(String username, char[] password, String role, boolean bootstrap) {
    String normalized = normalizeUsername(username);
    var encoded = PasswordHasher.create(password);
    try {
      return transaction(
          () -> {
            lockSchemaRow();
            if (bootstrap) {
              try (var query =
                      connection.prepareStatement(
                          "SELECT COUNT(*) FROM accounts WHERE role='ADMIN'");
                  var rs = query.executeQuery()) {
                rs.next();
                if (rs.getLong(1) != 0) throw new GatewayException(FORBIDDEN);
              }
            }
            if (passwordRow(normalized) != null) throw new GatewayException(USERNAME_UNAVAILABLE);
            UUID id = UUID.randomUUID();
            Instant now = Instant.ofEpochMilli(clock.millis());
            try (var insert =
                connection.prepareStatement(
                    "INSERT INTO accounts(id,username,role,balance,salt,password_hash,iterations,created_at) VALUES(?,?,?,0,?,?,?,?)")) {
              insert.setString(1, id.toString());
              insert.setString(2, normalized);
              insert.setString(3, role);
              insert.setBytes(4, encoded.salt());
              insert.setBytes(5, encoded.hash());
              insert.setInt(6, encoded.iterations());
              insert.setLong(7, now.toEpochMilli());
              insert.executeUpdate();
            }
            return new Account(id, normalized, role, 0, now);
          });
    } finally {
      Arrays.fill(encoded.hash(), (byte) 0);
      Arrays.fill(encoded.salt(), (byte) 0);
    }
  }

  public Login login(String username, char[] password) {
    String normalized;
    try {
      normalized = normalizeUsername(username);
    } catch (GatewayException invalid) {
      normalized = "";
    }
    final String lookup = normalized;
    PasswordRow row = transaction(() -> passwordRow(lookup));
    boolean valid =
        PasswordHasher.verify(
            password,
            row == null ? dummy.salt() : row.salt(),
            row == null ? dummy.hash() : row.hash(),
            row == null ? dummy.iterations() : row.iterations());
    if (!valid || row == null) throw new GatewayException(UNAUTHENTICATED);
    byte[] tokenBytes = new byte[32];
    random.nextBytes(tokenBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    Arrays.fill(tokenBytes, (byte) 0);
    return transaction(
        () -> {
          Instant expires = Instant.ofEpochMilli(clock.millis()).plus(Duration.ofHours(12));
          try (var cleanup =
              connection.prepareStatement("DELETE FROM sessions WHERE expires_at<=?")) {
            cleanup.setLong(1, clock.millis());
            cleanup.executeUpdate();
          }
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)")) {
            insert.setString(1, hash(token));
            insert.setString(2, row.account().id().toString());
            insert.setLong(3, expires.toEpochMilli());
            insert.executeUpdate();
          }
          Account current = accountInternal(row.account().id());
          if (sessionTokens != null) sessionTokens.put(hash(token), current.id(), expires);
          return new Login(token, expires, current);
        });
  }

  public Account authenticate(String token) {
    validateToken(token);
    return transaction(() -> accountInternal(sessionUser(token)));
  }

  public void logout(String token) {
    validateToken(token);
    transaction(
        () -> {
          try (var delete =
              connection.prepareStatement("DELETE FROM sessions WHERE token_hash=?")) {
            delete.setString(1, hash(token));
            delete.executeUpdate();
          }
          return null;
        });
    // MySQL revocation must stay committed even if deleting a stale Redis entry fails.
    if (sessionTokens != null) sessionTokens.delete(hash(token));
  }

  /**
   * Revokes every durable session before advancing the optional cache generation. Holding the store
   * monitor prevents a concurrent login from being caught between those two operations.
   */
  public synchronized void revokeSessions(UUID adminId, UUID userId) {
    if (adminId == null || userId == null) throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          requireAdmin(adminId);
          accountForUpdate(userId);
          try (var delete = connection.prepareStatement("DELETE FROM sessions WHERE user_id=?")) {
            delete.setString(1, userId.toString());
            delete.executeUpdate();
          }
          return null;
        });
    // The SQL deletion is already committed. A cache outage is surfaced and therefore fail closed.
    if (sessionTokens != null) sessionTokens.revokeUser(userId);
  }

  private UUID sessionUser(String token) throws SQLException {
    validateToken(token);
    String tokenHash = hash(token);
    var cached =
        sessionTokens == null
            ? java.util.Optional.<SessionTokenStore.Session>empty()
            : sessionTokens.find(tokenHash);
    try (var query =
        connection.prepareStatement(
            "SELECT user_id,expires_at FROM sessions WHERE token_hash=? AND expires_at>?")) {
      query.setString(1, tokenHash);
      query.setLong(2, clock.millis());
      try (var rs = query.executeQuery()) {
        if (!rs.next()) throw new GatewayException(UNAUTHENTICATED);
        UUID user = UUID.fromString(rs.getString(1));
        Instant expiry = Instant.ofEpochMilli(rs.getLong(2));
        // Redis must be available; a healthy miss can rebuild only a durable, unrevoked session.
        if (sessionTokens != null
            && (cached.isEmpty()
                || !cached.get().userId().equals(user)
                || !cached.get().expiresAt().equals(expiry)
                || !cached.get().expiresAt().isAfter(clock.instant()))) {
          sessionTokens.put(tokenHash, user, expiry);
        }
        return user;
      }
    }
  }

  public Account account(UUID userId) {
    return transaction(() -> accountInternal(userId));
  }

  /** Dependency readiness only; reconnects if needed, without replaying or recovering requests. */
  public void verifyAvailable() {
    transaction(
        () -> {
          try (var query = connection.prepareStatement("SELECT 1");
              var rs = query.executeQuery()) {
            if (!rs.next() || rs.getInt(1) != 1) throw new SQLException();
          }
          if (sessionTokens != null) sessionTokens.verifyAvailable();
          return null;
        });
  }

  public Account adminAccount(UUID adminId, UUID userId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          return accountInternal(userId);
        });
  }

  /** Public catalogue includes disabled entries for an honest UI, but never deleted tombstones. */
  public List<CreditPackage> publicPackages() {
    return transaction(() -> packageList(false, null));
  }

  public List<CreditPackage> adminPackages(UUID adminId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          return packageList(false, null);
        });
  }

  List<CreditPackage> packageHistory() {
    return transaction(() -> packageList(true, null));
  }

  public CreditPackage packageForOrder(String packageId) {
    String id = validatePackageId(packageId);
    return transaction(
        () -> {
          CreditPackageRow row = packageRow(id, false);
          if (row == null || row.deletedAt() != null) throw new GatewayException(INVALID_REQUEST);
          return row.value();
        });
  }

  public CreditPackage createPackage(
      UUID adminId,
      String packageId,
      String name,
      long credits,
      Long priceMinor,
      String currency,
      boolean enabled) {
    String id = validatePackageId(packageId);
    String normalizedName = validatePackageName(name);
    validatePackageValues(credits, priceMinor, currency);
    return transaction(
        () -> {
          requireAdmin(adminId);
          CreditPackageRow prior = packageRow(id, true);
          if (prior != null) {
            if (prior.deletedAt() == null
                && samePackage(
                    prior.value(), normalizedName, credits, priceMinor, currency, enabled))
              return prior.value();
            throw new GatewayException(IDEMPOTENCY_CONFLICT);
          }
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO credit_packages(id,name,credits,price_minor,currency,enabled,revision,created_at,updated_at,deleted_at) VALUES(?,?,?,?,?,?,1,?,?,NULL)")) {
            insert.setString(1, id);
            insert.setString(2, normalizedName);
            insert.setLong(3, credits);
            setNullableLong(insert, 4, priceMinor);
            insert.setString(5, currency);
            insert.setBoolean(6, enabled);
            insert.setLong(7, now.toEpochMilli());
            insert.setLong(8, now.toEpochMilli());
            if (insert.executeUpdate() != 1) throw new GatewayException(STORAGE_FAILURE);
          }
          var created =
              new CreditPackage(
                  id, normalizedName, credits, priceMinor, currency, enabled, 1, now, now);
          auditPackage(adminId, "CREATE", null, created, now);
          return created;
        });
  }

  public CreditPackage updatePackage(
      UUID adminId,
      String packageId,
      long expectedRevision,
      String name,
      long credits,
      Long priceMinor,
      String currency,
      boolean enabled) {
    String id = validatePackageId(packageId);
    String normalizedName = validatePackageName(name);
    validatePackageValues(credits, priceMinor, currency);
    if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE)
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          CreditPackageRow row = packageRow(id, true);
          if (row == null || row.deletedAt() != null) throw new GatewayException(NOT_FOUND);
          CreditPackage prior = row.value();
          if (prior.revision() != expectedRevision) {
            if (prior.revision() == expectedRevision + 1
                && samePackage(prior, normalizedName, credits, priceMinor, currency, enabled))
              return prior;
            throw new GatewayException(IDEMPOTENCY_CONFLICT);
          }
          long revision = Math.addExact(expectedRevision, 1);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var update =
              connection.prepareStatement(
                  "UPDATE credit_packages SET name=?,credits=?,price_minor=?,currency=?,enabled=?,revision=?,updated_at=? WHERE id=? AND deleted_at IS NULL AND revision=?")) {
            update.setString(1, normalizedName);
            update.setLong(2, credits);
            setNullableLong(update, 3, priceMinor);
            update.setString(4, currency);
            update.setBoolean(5, enabled);
            update.setLong(6, revision);
            update.setLong(7, now.toEpochMilli());
            update.setString(8, id);
            update.setLong(9, expectedRevision);
            if (update.executeUpdate() != 1) throw new GatewayException(IDEMPOTENCY_CONFLICT);
          }
          var changed =
              new CreditPackage(
                  id,
                  normalizedName,
                  credits,
                  priceMinor,
                  currency,
                  enabled,
                  revision,
                  prior.createdAt(),
                  now);
          auditPackage(adminId, "UPDATE", prior, changed, now);
          return changed;
        });
  }

  /** Soft deletion keeps package names resolvable for historical order records. */
  public void deletePackage(UUID adminId, String packageId, long expectedRevision) {
    String id = validatePackageId(packageId);
    if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE)
      throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          requireAdmin(adminId);
          CreditPackageRow row = packageRow(id, true);
          if (row == null) throw new GatewayException(NOT_FOUND);
          if (row.deletedAt() != null) {
            if (row.value().revision() == expectedRevision + 1) return null;
            throw new GatewayException(NOT_FOUND);
          }
          if (row.value().revision() != expectedRevision)
            throw new GatewayException(IDEMPOTENCY_CONFLICT);
          long now = clock.millis();
          try (var update =
              connection.prepareStatement(
                  "UPDATE credit_packages SET enabled=FALSE,revision=?,updated_at=?,deleted_at=? WHERE id=? AND revision=? AND deleted_at IS NULL")) {
            update.setLong(1, Math.addExact(expectedRevision, 1));
            update.setLong(2, now);
            update.setLong(3, now);
            update.setString(4, id);
            update.setLong(5, expectedRevision);
            if (update.executeUpdate() != 1) throw new GatewayException(IDEMPOTENCY_CONFLICT);
          }
          var deleted =
              new CreditPackage(
                  row.value().id(),
                  row.value().name(),
                  row.value().credits(),
                  row.value().priceMinor(),
                  row.value().currency(),
                  false,
                  Math.addExact(expectedRevision, 1),
                  row.value().createdAt(),
                  Instant.ofEpochMilli(now));
          auditPackage(adminId, "DELETE", row.value(), deleted, deleted.updatedAt());
          return null;
        });
  }

  private void auditPackage(
      UUID adminId, String action, CreditPackage before, CreditPackage after, Instant createdAt)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO package_audit(id,admin_id,package_id,action,previous_revision,revision,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
      insert.setString(1, UUID.randomUUID().toString());
      insert.setString(2, adminId.toString());
      insert.setString(3, after.id());
      insert.setString(4, action);
      setNullableLong(insert, 5, before == null ? null : before.revision());
      insert.setLong(6, after.revision());
      insert.setString(7, before == null ? null : packageSnapshot(before));
      insert.setString(8, packageSnapshot(after));
      insert.setLong(9, createdAt.toEpochMilli());
      if (insert.executeUpdate() != 1) throw new SQLException();
    }
  }

  private static String packageSnapshot(CreditPackage value) {
    var snapshot = GatewayJson.MAPPER.createObjectNode();
    snapshot.put("id", value.id());
    snapshot.put("name", value.name());
    snapshot.put("credits", value.credits());
    if (value.priceMinor() == null) snapshot.putNull("priceMinor");
    else snapshot.put("priceMinor", value.priceMinor());
    snapshot.put("currency", value.currency());
    snapshot.put("enabled", value.enabled());
    snapshot.put("revision", value.revision());
    snapshot.put("createdAt", value.createdAt().toString());
    snapshot.put("updatedAt", value.updatedAt().toString());
    return snapshot.toString();
  }

  private List<CreditPackage> packageList(boolean includeDeleted, String id) throws SQLException {
    String where =
        (includeDeleted ? "" : " WHERE deleted_at IS NULL")
            + (id == null ? "" : (includeDeleted ? " WHERE" : " AND") + " id=?");
    var result = new ArrayList<CreditPackage>();
    try (var query =
        connection.prepareStatement(
            "SELECT id,name,credits,price_minor,currency,enabled,revision,created_at,updated_at,deleted_at FROM credit_packages"
                + where
                + " ORDER BY created_at,id")) {
      if (id != null) query.setString(1, id);
      try (var rows = query.executeQuery()) {
        while (rows.next()) result.add(readPackageRow(rows).value());
      }
    }
    return List.copyOf(result);
  }

  private CreditPackageRow packageRow(String id, boolean forUpdate) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT id,name,credits,price_minor,currency,enabled,revision,created_at,updated_at,deleted_at FROM credit_packages WHERE id=?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        return rows.next() ? readPackageRow(rows) : null;
      }
    }
  }

  private static CreditPackageRow readPackageRow(ResultSet rows) throws SQLException {
    Long price = rows.getObject("price_minor", Long.class);
    Long deleted = rows.getObject("deleted_at", Long.class);
    return new CreditPackageRow(
        new CreditPackage(
            rows.getString("id"),
            rows.getString("name"),
            rows.getLong("credits"),
            price,
            rows.getString("currency"),
            rows.getBoolean("enabled"),
            rows.getLong("revision"),
            Instant.ofEpochMilli(rows.getLong("created_at")),
            Instant.ofEpochMilli(rows.getLong("updated_at"))),
        deleted);
  }

  private static String validatePackageId(String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,63}"))
      throw new GatewayException(INVALID_REQUEST);
    return value;
  }

  private static String validatePackageName(String value) {
    if (value == null) throw new GatewayException(INVALID_REQUEST);
    String normalized = value.trim();
    if (normalized.isEmpty()
        || normalized.length() > 80
        || normalized.codePoints().anyMatch(Character::isISOControl))
      throw new GatewayException(INVALID_REQUEST);
    return normalized;
  }

  private static void validatePackageValues(long credits, Long priceMinor, String currency) {
    if (credits < 1
        || credits > 1_000_000_000L
        || priceMinor != null && (priceMinor < 0 || priceMinor > 100_000_000_000L)
        || !"CNY".equals(currency)) throw new GatewayException(INVALID_REQUEST);
  }

  private static boolean samePackage(
      CreditPackage prior,
      String name,
      long credits,
      Long priceMinor,
      String currency,
      boolean enabled) {
    return prior.name().equals(name)
        && prior.credits() == credits
        && java.util.Objects.equals(prior.priceMinor(), priceMinor)
        && prior.currency().equals(currency)
        && prior.enabled() == enabled;
  }

  private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) statement.setNull(index, Types.BIGINT);
    else statement.setLong(index, value);
  }

  /**
   * Produces a bounded UTC analytics snapshot from durable business tables. No counters are held in
   * process memory, so a gateway restart cannot reset or invent dashboard data.
   */
  public Dashboard adminDashboard(UUID adminId, int days) {
    if (days < 1 || days > 31) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          Instant generatedAt = clock.instant();
          LocalDate today = LocalDate.ofInstant(generatedAt, ZoneOffset.UTC);
          LocalDate first = today.minusDays(days - 1L);
          long start = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
          long end = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
          long[][] values = new long[days][10];
          readDailyRegistrations(start, end, values);
          readDailyModelCalls(start, end, values);
          readDailyCreditsAdded(start, end, values);
          readDailyOrders(start, end, values);

          long totalUsers;
          long availableCredits;
          try (var query =
                  connection.prepareStatement(
                      "SELECT COUNT(*),COALESCE(SUM(balance),0) FROM accounts WHERE role='USER'");
              var rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException();
            totalUsers = rows.getLong(1);
            availableCredits = rows.getLong(2);
          }

          var daily = new ArrayList<DashboardDay>(days);
          long calls = 0,
              succeeded = 0,
              failed = 0,
              unresolved = 0,
              charged = 0,
              added = 0,
              orders = 0,
              knownOrderAmount = 0,
              unpricedOrders = 0;
          for (int day = 0; day < days; day++) {
            long[] value = values[day];
            daily.add(
                new DashboardDay(
                    first.plusDays(day),
                    value[0],
                    value[1],
                    value[2],
                    value[3],
                    value[4],
                    value[5],
                    value[6],
                    value[7],
                    value[9] == 0 ? value[8] : null,
                    value[9]));
            calls = Math.addExact(calls, value[1]);
            succeeded = Math.addExact(succeeded, value[2]);
            failed = Math.addExact(failed, value[3]);
            unresolved = Math.addExact(unresolved, value[4]);
            charged = Math.addExact(charged, value[5]);
            added = Math.addExact(added, value[6]);
            orders = Math.addExact(orders, value[7]);
            knownOrderAmount = Math.addExact(knownOrderAmount, value[8]);
            unpricedOrders = Math.addExact(unpricedOrders, value[9]);
          }
          return new Dashboard(
              generatedAt,
              days,
              totalUsers,
              availableCredits,
              calls,
              succeeded,
              failed,
              unresolved,
              charged,
              added,
              orders,
              unpricedOrders == 0 ? knownOrderAmount : null,
              unpricedOrders,
              List.copyOf(daily),
              readModelUsage(start, end));
        });
  }

  private void readDailyRegistrations(long start, long end, long[][] values) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT FLOOR((created_at-?)/86400000) AS day_index,COUNT(*) AS registrations FROM accounts WHERE role='USER' AND created_at>=? AND created_at<? GROUP BY day_index")) {
      query.setLong(1, start);
      query.setLong(2, start);
      query.setLong(3, end);
      try (var rows = query.executeQuery()) {
        while (rows.next()) values[dashboardDay(rows, values.length)][0] = rows.getLong(2);
      }
    }
  }

  private void readDailyModelCalls(long start, long end, long[][] values) throws SQLException {
    String sql =
        "SELECT FLOOR((created_at-?)/86400000) AS day_index,COUNT(*) AS calls,"
            + "SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
            + "SUM(CASE WHEN state IN ('FAILED','UNKNOWN') THEN 1 ELSE 0 END) AS failed,"
            + "SUM(CASE WHEN state='DISPATCHING' THEN 1 ELSE 0 END) AS unresolved,"
            + "SUM(CASE WHEN charged THEN 1 ELSE 0 END) AS charged "
            + "FROM (SELECT created_at,state,charged FROM grade_requests WHERE created_at>=? AND created_at<? "
            + "UNION ALL SELECT created_at,state,charged FROM rubric_generation_requests WHERE created_at>=? AND created_at<?) model_calls "
            + "WHERE charged=TRUE "
            + "GROUP BY day_index";
    try (var query = connection.prepareStatement(sql)) {
      query.setLong(1, start);
      query.setLong(2, start);
      query.setLong(3, end);
      query.setLong(4, start);
      query.setLong(5, end);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          long[] value = values[dashboardDay(rows, values.length)];
          value[1] = rows.getLong("calls");
          value[2] = rows.getLong("succeeded");
          value[3] = rows.getLong("failed");
          value[4] = rows.getLong("unresolved");
          value[5] = rows.getLong("charged");
        }
      }
    }
  }

  private void readDailyCreditsAdded(long start, long end, long[][] values) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT FLOOR((created_at-?)/86400000) AS day_index,COALESCE(SUM(delta),0) AS credits_added FROM quota_events WHERE delta>0 AND created_at>=? AND created_at<? GROUP BY day_index")) {
      query.setLong(1, start);
      query.setLong(2, start);
      query.setLong(3, end);
      try (var rows = query.executeQuery()) {
        while (rows.next()) values[dashboardDay(rows, values.length)][6] = rows.getLong(2);
      }
    }
  }

  private void readDailyOrders(long start, long end, long[][] values) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT FLOOR((created_at-?)/86400000) AS day_index,COUNT(*) AS orders,COALESCE(SUM(price_cents),0) AS order_amount,SUM(CASE WHEN price_cents IS NULL THEN 1 ELSE 0 END) AS unpriced_orders FROM orders WHERE created_at>=? AND created_at<? GROUP BY day_index")) {
      query.setLong(1, start);
      query.setLong(2, start);
      query.setLong(3, end);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          long[] value = values[dashboardDay(rows, values.length)];
          value[7] = rows.getLong("orders");
          value[8] = rows.getLong("order_amount");
          value[9] = rows.getLong("unpriced_orders");
        }
      }
    }
  }

  private List<ModelUsage> readModelUsage(long start, long end) throws SQLException {
    String sql =
        "SELECT model_id,COUNT(*) AS calls,"
            + "SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
            + "SUM(CASE WHEN state IN ('FAILED','UNKNOWN') THEN 1 ELSE 0 END) AS failed,"
            + "SUM(CASE WHEN state='DISPATCHING' THEN 1 ELSE 0 END) AS unresolved,"
            + "SUM(CASE WHEN charged THEN 1 ELSE 0 END) AS charged "
            + "FROM (SELECT model_id,state,charged FROM grade_requests WHERE created_at>=? AND created_at<? "
            + "UNION ALL SELECT model_id,state,charged FROM rubric_generation_requests WHERE created_at>=? AND created_at<?) model_calls "
            + "WHERE charged=TRUE "
            + "GROUP BY model_id ORDER BY calls DESC,model_id LIMIT 10";
    var result = new ArrayList<ModelUsage>();
    try (var query = connection.prepareStatement(sql)) {
      query.setLong(1, start);
      query.setLong(2, end);
      query.setLong(3, start);
      query.setLong(4, end);
      try (var rows = query.executeQuery()) {
        while (rows.next())
          result.add(
              new ModelUsage(
                  rows.getString("model_id"),
                  rows.getLong("calls"),
                  rows.getLong("succeeded"),
                  rows.getLong("failed"),
                  rows.getLong("unresolved"),
                  rows.getLong("charged")));
      }
    }
    return List.copyOf(result);
  }

  private static int dashboardDay(ResultSet rows, int days) throws SQLException {
    int day = rows.getInt("day_index");
    if (day < 0 || day >= days) throw new SQLException();
    return day;
  }

  public void recordModelRequest(UUID userId, UUID requestId, String kind, String requestJson) {
    modelCallTable(kind);
    if (requestJson == null || requestJson.getBytes(StandardCharsets.UTF_8).length > 4 * 1_048_576)
      throw new GatewayException(INVALID_REQUEST);
    try {
      var snapshot = GatewayJson.MAPPER.readTree(requestJson);
      if (snapshot == null
          || !snapshot.isObject()
          || snapshot.path("schemaVersion").asInt() != 1
          || !kind.equals(snapshot.path("kind").asText())
          || !snapshot.path("providerRequest").isObject()
          || snapshot.has("failureDiagnostic")) throw new IllegalArgumentException();
      rejectImageDataOrSecrets(snapshot);
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new GatewayException(INVALID_REQUEST);
    }
    recordModelSnapshot(userId, requestId, kind, "request_json", requestJson, "PREPARED");
  }

  public void recordModelOutput(UUID userId, UUID requestId, String kind, String outputText) {
    if (outputText == null) throw new GatewayException(INVALID_REQUEST);
    try {
      if (GatewayJson.MAPPER.writeValueAsBytes(outputText).length > 1_048_576
          || outputText.matches("(?is).*data:image/[^;\\s]+;base64,.*"))
        throw new GatewayException(INVALID_REQUEST);
    } catch (java.io.IOException invalid) {
      throw new GatewayException(INVALID_REQUEST);
    }
    recordModelSnapshot(userId, requestId, kind, "raw_model_output", outputText, "DISPATCHING");
  }

  /** Merge server-owned failure facts without replacing the actual request or answer metadata. */
  void recordModelFailure(UUID userId, UUID requestId, String kind, GatewayModelFailure failure) {
    String table = modelCallTable(kind);
    if (userId == null || requestId == null || failure == null)
      throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          accountInternal(userId);
          com.fasterxml.jackson.databind.node.ObjectNode snapshot;
          try (var query =
              connection.prepareStatement(
                  "SELECT state,charged,request_json FROM "
                      + table
                      + " WHERE user_id=? AND request_id=? FOR UPDATE")) {
            query.setString(1, userId.toString());
            query.setString(2, requestId.toString());
            try (var row = query.executeQuery()) {
              if (!row.next()) throw new GatewayException(NOT_FOUND);
              String old = row.getString("request_json");
              if (old == null) {
                snapshot = GatewayJson.MAPPER.createObjectNode();
                snapshot.put("schemaVersion", 1);
                snapshot.put("kind", kind);
                snapshot.put("requestSnapshotStatus", "NOT_RECORDED");
              } else {
                try {
                  var parsed = GatewayJson.MAPPER.readTree(old);
                  if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
                      || object.path("schemaVersion").asInt() != 1
                      || !kind.equals(object.path("kind").asText()))
                    throw new GatewayException(STORAGE_FAILURE);
                  snapshot = object;
                } catch (java.io.IOException invalid) {
                  throw new GatewayException(STORAGE_FAILURE);
                }
              }
              GatewayModelFailure existing =
                  GatewayModelFailure.read(snapshot.get("failureDiagnostic"));
              if (existing != null) {
                if (!existing.equals(failure)) throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return null;
              }
              boolean dispatched = row.getBoolean("charged");
              if (!(dispatched ? "DISPATCHING" : "PREPARED").equals(row.getString("state"))
                  || dispatched != (failure.phase() == GatewayModelFailure.Phase.AFTER_DISPATCH))
                throw new GatewayException(REQUEST_IN_PROGRESS);
            }
          }
          snapshot.set("failureDiagnostic", failure.toJson());
          try (var update =
              connection.prepareStatement(
                  "UPDATE " + table + " SET request_json=? WHERE user_id=? AND request_id=?")) {
            update.setString(1, snapshot.toString());
            update.setString(2, userId.toString());
            update.setString(3, requestId.toString());
            if (update.executeUpdate() != 1) throw new GatewayException(STORAGE_FAILURE);
          }
          return null;
        });
  }

  GatewayModelFailure modelFailure(UUID userId, UUID requestId, String kind) {
    String json = modelCallDetail(userId, requestId, kind).requestJson();
    if (json == null) return null;
    try {
      var snapshot = GatewayJson.MAPPER.readTree(json);
      if (snapshot == null || !snapshot.isObject()) throw new IllegalArgumentException();
      return GatewayModelFailure.read(snapshot.get("failureDiagnostic"));
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  private static void rejectImageDataOrSecrets(com.fasterxml.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
        if (List.of(
                "authorization",
                "api_key",
                "apikey",
                "credential",
                "credentials",
                "token",
                "image_url",
                "base64",
                "pngbase64",
                "b64_json")
            .contains(key)) throw new IllegalArgumentException();
        rejectImageDataOrSecrets(field.getValue());
      }
      if ("image".equals(node.path("type").asText())) {
        if (node.size() != 3
            || !node.path("sha256").asText().matches("[a-fA-F0-9]{64}")
            || !node.path("bytes").canConvertToLong()
            || node.path("bytes").asLong() < 1) throw new IllegalArgumentException();
      }
    } else if (node.isArray()) {
      node.forEach(GatewayStore::rejectImageDataOrSecrets);
    } else if (node.isTextual() && node.asText().matches("(?is).*data:image/[^;\\s]+;base64,.*"))
      throw new IllegalArgumentException();
  }

  private void recordModelSnapshot(
      UUID userId, UUID requestId, String kind, String column, String value, String requiredState) {
    String table = modelCallTable(kind);
    if (userId == null || requestId == null) throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          accountInternal(userId);
          try (var query =
              connection.prepareStatement(
                  "SELECT state,charged,"
                      + column
                      + " FROM "
                      + table
                      + " WHERE user_id=? AND request_id=? FOR UPDATE")) {
            query.setString(1, userId.toString());
            query.setString(2, requestId.toString());
            try (var row = query.executeQuery()) {
              if (!row.next()) throw new GatewayException(NOT_FOUND);
              String old = row.getString(column);
              if (old != null) {
                if (!old.equals(value)) throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return null;
              }
              if (!requiredState.equals(row.getString("state"))
                  || row.getBoolean("charged") != requiredState.equals("DISPATCHING"))
                throw new GatewayException(REQUEST_IN_PROGRESS);
            }
          }
          try (var update =
              connection.prepareStatement(
                  "UPDATE "
                      + table
                      + " SET "
                      + column
                      + "=? WHERE user_id=? AND request_id=? AND "
                      + column
                      + " IS NULL")) {
            update.setString(1, value);
            update.setString(2, userId.toString());
            update.setString(3, requestId.toString());
            if (update.executeUpdate() != 1) throw new GatewayException(STORAGE_FAILURE);
          }
          return null;
        });
  }

  public Page<ModelCall> adminModelCalls(
      UUID adminId, UUID userId, String username, int page, int pageSize) {
    String name = username == null || username.isBlank() ? null : normalizeUsername(username);
    pageOffset(page, pageSize);
    return transaction(
        () -> {
          requireAdmin(adminId);
          UUID selected = userId;
          if (name != null) {
            try (var query =
                connection.prepareStatement("SELECT id FROM accounts WHERE username=?")) {
              query.setString(1, name);
              try (var row = query.executeQuery()) {
                if (!row.next()) return new Page<>(List.of(), page, pageSize, 0, 0);
                UUID matched = UUID.fromString(row.getString(1));
                if (selected != null && !selected.equals(matched))
                  return new Page<>(List.of(), page, pageSize, 0, 0);
                selected = matched;
              }
            }
          }
          return modelCallsInternal(selected, page, pageSize);
        });
  }

  public Page<ModelCall> modelCalls(UUID userId, int page, int pageSize) {
    pageOffset(page, pageSize);
    return transaction(
        () -> {
          accountInternal(userId);
          return modelCallsInternal(userId, page, pageSize);
        });
  }

  private Page<ModelCall> modelCallsInternal(UUID userId, int page, int pageSize)
      throws SQLException {
    int offset = pageOffset(page, pageSize), bound = offset + pageSize;
    String where = userId == null ? "" : " WHERE user_id=?";
    long total = 0;
    for (String table : List.of("grade_requests", "rubric_generation_requests")) {
      try (var count = connection.prepareStatement("SELECT COUNT(*) FROM " + table + where)) {
        if (userId != null) count.setString(1, userId.toString());
        try (var row = count.executeQuery()) {
          row.next();
          total += row.getLong(1);
        }
      }
    }
    String projection =
        "user_id,request_id,model_id,profile_id,policy_revision,state,charged,error_code,created_at,completed_at,request_json IS NOT NULL AS request_saved,response_json IS NOT NULL AS response_saved,raw_model_output IS NOT NULL AS output_saved";
    String union =
        "(SELECT 'GRADING' AS kind,"
            + projection
            + " FROM grade_requests"
            + where
            + " ORDER BY created_at DESC,request_id DESC,user_id DESC LIMIT ?) UNION ALL "
            + "(SELECT 'RUBRIC_GENERATION' AS kind,"
            + projection
            + " FROM rubric_generation_requests"
            + where
            + " ORDER BY created_at DESC,request_id DESC,user_id DESC LIMIT ?)";
    var items = new ArrayList<ModelCall>();
    try (var query =
        connection.prepareStatement(
            "SELECT c.*,u.username FROM ("
                + union
                + ") c JOIN accounts u ON u.id=c.user_id ORDER BY c.created_at DESC,c.request_id DESC,c.kind,c.user_id DESC LIMIT ? OFFSET ?")) {
      int index = 1;
      for (int source = 0; source < 2; source++) {
        if (userId != null) query.setString(index++, userId.toString());
        query.setInt(index++, bound);
      }
      query.setInt(index++, pageSize);
      query.setInt(index, offset);
      try (var rows = query.executeQuery()) {
        while (rows.next()) items.add(readModelCall(rows));
      }
    }
    return new Page<>(List.copyOf(items), page, pageSize, total, (total + pageSize - 1) / pageSize);
  }

  public ModelCallDetail adminModelCallDetail(
      UUID adminId, UUID userId, UUID requestId, String kind) {
    modelCallTable(kind);
    return transaction(
        () -> {
          requireAdmin(adminId);
          return modelCallDetailInternal(userId, requestId, kind);
        });
  }

  public ModelCallDetail modelCallDetail(UUID userId, UUID requestId, String kind) {
    modelCallTable(kind);
    return transaction(
        () -> {
          accountInternal(userId);
          return modelCallDetailInternal(userId, requestId, kind);
        });
  }

  private ModelCallDetail modelCallDetailInternal(UUID userId, UUID requestId, String kind)
      throws SQLException {
    if (userId == null || requestId == null) throw new GatewayException(INVALID_REQUEST);
    try (var query =
        connection.prepareStatement(
            "SELECT c.*,u.username,? AS kind,c.request_json IS NOT NULL AS request_saved,c.response_json IS NOT NULL AS response_saved,c.raw_model_output IS NOT NULL AS output_saved FROM "
                + modelCallTable(kind)
                + " c JOIN accounts u ON u.id=c.user_id WHERE c.user_id=? AND c.request_id=?")) {
      query.setString(1, kind);
      query.setString(2, userId.toString());
      query.setString(3, requestId.toString());
      try (var row = query.executeQuery()) {
        if (!row.next()) throw new GatewayException(NOT_FOUND);
        return new ModelCallDetail(
            readModelCall(row),
            row.getString("request_json"),
            row.getString("response_json"),
            row.getString("raw_model_output"));
      }
    }
  }

  private static ModelCall readModelCall(ResultSet row) throws SQLException {
    Long completed = row.getObject("completed_at", Long.class);
    return new ModelCall(
        row.getString("kind"),
        UUID.fromString(row.getString("request_id")),
        UUID.fromString(row.getString("user_id")),
        row.getString("username"),
        row.getString("model_id"),
        row.getString("profile_id"),
        row.getObject("policy_revision", Long.class),
        row.getString("state"),
        row.getBoolean("charged") ? 1 : 0,
        row.getString("error_code"),
        Instant.ofEpochMilli(row.getLong("created_at")),
        completed == null ? null : Instant.ofEpochMilli(completed),
        row.getBoolean("request_saved"),
        row.getBoolean("response_saved"),
        row.getBoolean("output_saved"));
  }

  private static String modelCallTable(String kind) {
    if ("GRADING".equals(kind)) return "grade_requests";
    if ("RUBRIC_GENERATION".equals(kind)) return "rubric_generation_requests";
    throw new GatewayException(INVALID_REQUEST);
  }

  public Page<Account> userPage(UUID adminId, int page, int pageSize, String search) {
    String query = search(search);
    pageOffset(page, pageSize);
    return transaction(
        () -> {
          requireAdmin(adminId);
          return readPage(
              "SELECT a.*",
              "FROM accounts a WHERE (?='' OR LOCATE(?,a.username)>0)",
              "a.created_at DESC,a.id",
              List.of(query, query),
              page,
              pageSize,
              GatewayStore::readAccount);
        });
  }

  public Page<NamedLedger> ledgerPage(
      UUID adminId, UUID userId, int page, int pageSize, String kind) {
    pageOffset(page, pageSize);
    if (!List.of("ALL", "ADMIN_ADJUSTMENT", "MODEL_DISPATCH", "RUBRIC_GENERATION").contains(kind))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          if (userId != null) accountInternal(userId);
          var parameters = new ArrayList<Object>();
          String where =
              "FROM quota_events q JOIN accounts u ON u.id=q.user_id LEFT JOIN adjustments a ON q.kind='ADMIN_ADJUSTMENT' AND a.id=q.reference_id WHERE 1=1";
          if (userId != null) {
            where += " AND q.user_id=?";
            parameters.add(userId.toString());
          }
          if (!kind.equals("ALL")) {
            where += " AND q.kind=?";
            parameters.add(kind);
          }
          return readPage(
              "SELECT q.*,u.username,a.reason AS adjustment_reason,a.admin_id AS adjustment_admin_id",
              where,
              "q.created_at DESC,q.id",
              parameters,
              page,
              pageSize,
              rs -> {
                long delta = rs.getLong("delta"), after = rs.getLong("balance_after");
                String actor = rs.getString("adjustment_admin_id");
                var entry =
                    new LedgerEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("kind"),
                        UUID.fromString(rs.getString("reference_id")),
                        delta,
                        Math.subtractExact(after, delta),
                        after,
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        actor == null
                            ? ("RUBRIC_GENERATION".equals(rs.getString("kind"))
                                ? "RUBRIC_GENERATION_DISPATCH"
                                : "MODEL_REQUEST_DISPATCH")
                            : rs.getString("adjustment_reason"),
                        actor == null ? null : UUID.fromString(actor));
                return new NamedLedger(entry, rs.getString("username"));
              });
        });
  }

  public Page<NamedOrder> adminOrders(
      UUID adminId, UUID userId, int page, int pageSize, String search) {
    String query = search(search);
    pageOffset(page, pageSize);
    return transaction(
        () -> {
          requireAdmin(adminId);
          if (userId != null) accountInternal(userId);
          var parameters = new ArrayList<Object>(List.of(query, query));
          String where =
              "FROM orders o JOIN accounts u ON o.user_id=u.id WHERE (?='' OR LOCATE(?,u.username)>0)";
          if (userId != null) {
            where += " AND o.user_id=?";
            parameters.add(userId.toString());
          }
          return readPage(
              "SELECT o.*,u.username",
              where,
              "o.created_at DESC,o.id",
              parameters,
              page,
              pageSize,
              rs -> new NamedOrder(readOrder(rs), rs.getString("username")));
        });
  }

  public Page<AuditEntry> adminAudit(UUID adminId, int page, int pageSize, String type) {
    pageOffset(page, pageSize);
    if (!List.of("ALL", "CREDITS", "MODEL_POLICY", "RUBRIC_POLICY", "PACKAGE").contains(type))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          String union =
              "SELECT a.id,a.admin_id,u.username AS admin_username,'CREDITS' AS kind,CAST(NULL AS VARCHAR(64)) AS package_id,a.user_id AS target_user_id,t.username AS target_username,a.delta,a.balance_after,CAST(NULL AS VARCHAR(100)) AS profile_id,CAST(NULL AS VARCHAR(200)) AS model_id,CAST(NULL AS VARCHAR(200)) AS previous_model_id,CAST(NULL AS BIGINT) AS previous_revision,CAST(NULL AS BIGINT) AS revision,a.reason,a.created_at FROM adjustments a JOIN accounts u ON u.id=a.admin_id JOIN accounts t ON t.id=a.user_id"
                  + " UNION ALL SELECT p.id,p.admin_id,u.username AS admin_username,'MODEL_POLICY' AS kind,CAST(NULL AS VARCHAR(64)) AS package_id,CAST(NULL AS VARCHAR(36)) AS target_user_id,CAST(NULL AS VARCHAR(32)) AS target_username,CAST(NULL AS BIGINT) AS delta,CAST(NULL AS BIGINT) AS balance_after,p.profile_id,p.model_id,p.previous_model_id,p.previous_revision,p.revision,p.reason,p.created_at FROM model_policy_audit p JOIN accounts u ON u.id=p.admin_id"
                  + " UNION ALL SELECT p.id,p.admin_id,u.username AS admin_username,'RUBRIC_POLICY' AS kind,CAST(NULL AS VARCHAR(64)) AS package_id,CAST(NULL AS VARCHAR(36)) AS target_user_id,CAST(NULL AS VARCHAR(32)) AS target_username,CAST(NULL AS BIGINT) AS delta,CAST(NULL AS BIGINT) AS balance_after,p.profile_id,p.model_id,p.previous_model_id,p.previous_revision,p.revision,p.reason,p.created_at FROM rubric_model_policy_audit p JOIN accounts u ON u.id=p.admin_id"
                  + " UNION ALL SELECT p.id,p.admin_id,u.username AS admin_username,'PACKAGE' AS kind,p.package_id,CAST(NULL AS VARCHAR(36)) AS target_user_id,CAST(NULL AS VARCHAR(32)) AS target_username,CAST(NULL AS BIGINT) AS delta,CAST(NULL AS BIGINT) AS balance_after,CAST(NULL AS VARCHAR(100)) AS profile_id,CAST(NULL AS VARCHAR(200)) AS model_id,CAST(NULL AS VARCHAR(200)) AS previous_model_id,p.previous_revision,p.revision,p.action AS reason,p.created_at FROM package_audit p JOIN accounts u ON u.id=p.admin_id";
          if (dataSource != null)
            union = union.replace("AS VARCHAR(", "AS CHAR(").replace("AS BIGINT)", "AS SIGNED)");
          return readPage(
              "SELECT audit.*",
              "FROM (" + union + ") audit WHERE (?='ALL' OR audit.kind=?)",
              "audit.created_at DESC,audit.revision DESC,audit.kind,audit.id DESC",
              List.of(type, type),
              page,
              pageSize,
              rs -> {
                String target = rs.getString("target_user_id");
                return new AuditEntry(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("admin_id")),
                    rs.getString("admin_username"),
                    rs.getString("kind"),
                    rs.getString("package_id"),
                    target == null ? null : UUID.fromString(target),
                    rs.getString("target_username"),
                    rs.getObject("delta", Long.class),
                    rs.getObject("balance_after", Long.class),
                    rs.getString("profile_id"),
                    rs.getString("model_id"),
                    rs.getString("previous_model_id"),
                    rs.getObject("previous_revision", Long.class),
                    rs.getObject("revision", Long.class),
                    rs.getString("reason"),
                    Instant.ofEpochMilli(rs.getLong("created_at")));
              });
        });
  }

  @FunctionalInterface
  private interface RowReader<T> {
    T read(ResultSet result) throws SQLException;
  }

  private <T> Page<T> readPage(
      String select,
      String fromWhere,
      String ordering,
      List<?> parameters,
      int page,
      int pageSize,
      RowReader<T> reader)
      throws SQLException {
    int offset = pageOffset(page, pageSize);
    long total;
    try (var count = connection.prepareStatement("SELECT COUNT(*) " + fromWhere)) {
      for (int i = 0; i < parameters.size(); i++) count.setObject(i + 1, parameters.get(i));
      try (var rs = count.executeQuery()) {
        rs.next();
        total = rs.getLong(1);
      }
    }
    var entries = new ArrayList<T>();
    try (var query =
        connection.prepareStatement(
            select + " " + fromWhere + " ORDER BY " + ordering + " LIMIT ? OFFSET ?")) {
      for (int i = 0; i < parameters.size(); i++) query.setObject(i + 1, parameters.get(i));
      query.setInt(parameters.size() + 1, pageSize);
      query.setInt(parameters.size() + 2, offset);
      try (var rs = query.executeQuery()) {
        while (rs.next()) entries.add(reader.read(rs));
      }
    }
    return new Page<>(
        List.copyOf(entries), page, pageSize, total, (total + pageSize - 1) / pageSize);
  }

  private static int pageOffset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100 || (long) (page - 1) * pageSize > 1_000_000)
      throw new GatewayException(INVALID_REQUEST);
    return (page - 1) * pageSize;
  }

  private static String search(String input) {
    if (input == null || !input.matches("[A-Za-z0-9_.-]{0,32}"))
      throw new GatewayException(INVALID_REQUEST);
    return input.toLowerCase(Locale.ROOT);
  }

  public List<Account> users(UUID adminId, int offset, int limit) {
    page(offset, limit);
    return transaction(
        () -> {
          requireAdmin(adminId);
          var result = new ArrayList<Account>();
          try (var query =
              connection.prepareStatement(
                  "SELECT id,username,role,balance,created_at FROM accounts ORDER BY created_at,id LIMIT ? OFFSET ?")) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rs = query.executeQuery()) {
              while (rs.next()) result.add(readAccount(rs));
            }
          }
          return List.copyOf(result);
        });
  }

  public Adjustment adjust(UUID adminId, UUID id, UUID userId, long delta, String reason) {
    if (id == null
        || userId == null
        || delta == 0
        || delta < -1_000_000_000L
        || delta > 1_000_000_000L
        || reason == null
        || reason.isBlank()
        || reason.length() > 500) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          Account target = accountForUpdate(userId);
          try (var query = connection.prepareStatement("SELECT * FROM adjustments WHERE id=?")) {
            query.setString(1, id.toString());
            try (var rs = query.executeQuery()) {
              if (rs.next()) {
                var prior = readAdjustment(rs);
                if (!prior.adminId().equals(adminId)
                    || !prior.userId().equals(userId)
                    || prior.delta() != delta
                    || !prior.reason().equals(reason))
                  throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return prior;
              }
            }
          }
          long balance;
          try {
            balance = Math.addExact(target.balance(), delta);
          } catch (ArithmeticException overflow) {
            throw new GatewayException(INVALID_REQUEST);
          }
          if (balance < 0) throw new GatewayException(INSUFFICIENT_QUOTA);
          updateBalance(userId, balance);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement("INSERT INTO adjustments VALUES(?,?,?,?,?,?,?)")) {
            insert.setString(1, id.toString());
            insert.setString(2, adminId.toString());
            insert.setString(3, userId.toString());
            insert.setLong(4, delta);
            insert.setString(5, reason);
            insert.setLong(6, balance);
            insert.setLong(7, now.toEpochMilli());
            insert.executeUpdate();
          }
          quotaEvent(userId, "ADMIN_ADJUSTMENT", id, delta, balance, now);
          return new Adjustment(id, adminId, userId, delta, reason, balance, now);
        });
  }

  public List<Adjustment> adjustments(UUID adminId, int offset, int limit) {
    page(offset, limit);
    return transaction(
        () -> {
          requireAdmin(adminId);
          var result = new ArrayList<Adjustment>();
          try (var query =
              connection.prepareStatement(
                  "SELECT * FROM adjustments ORDER BY created_at DESC,id LIMIT ? OFFSET ?")) {
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rs = query.executeQuery()) {
              while (rs.next()) result.add(readAdjustment(rs));
            }
          }
          return List.copyOf(result);
        });
  }

  public List<LedgerEntry> ledger(UUID adminId, UUID userId, int limit) {
    page(0, limit);
    return transaction(
        () -> {
          requireAdmin(adminId);
          var result = new ArrayList<LedgerEntry>();
          String sql =
              "SELECT q.*, a.reason AS adjustment_reason, a.admin_id AS adjustment_admin_id FROM quota_events q LEFT JOIN adjustments a ON q.kind='ADMIN_ADJUSTMENT' AND a.id=q.reference_id"
                  + (userId == null ? "" : " WHERE q.user_id=?")
                  + " ORDER BY q.created_at DESC,q.id LIMIT ?";
          try (var query = connection.prepareStatement(sql)) {
            int at = 1;
            if (userId != null) query.setString(at++, userId.toString());
            query.setInt(at, limit);
            try (var rs = query.executeQuery()) {
              while (rs.next()) {
                long delta = rs.getLong("delta"), after = rs.getLong("balance_after");
                String admin = rs.getString("adjustment_admin_id");
                result.add(
                    new LedgerEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("kind"),
                        UUID.fromString(rs.getString("reference_id")),
                        delta,
                        Math.subtractExact(after, delta),
                        after,
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        admin == null
                            ? "MODEL_REQUEST_DISPATCH"
                            : rs.getString("adjustment_reason"),
                        admin == null ? null : UUID.fromString(admin)));
              }
            }
          }
          return List.copyOf(result);
        });
  }

  public boolean adjustmentExists(UUID adminId, UUID id) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          try (var query = connection.prepareStatement("SELECT id FROM adjustments WHERE id=?")) {
            query.setString(1, id.toString());
            try (var rs = query.executeQuery()) {
              return rs.next();
            }
          }
        });
  }

  /**
   * A client-selected profile ID cannot silently become a different paid supplier configuration.
   */
  public void bindProfile(String id, String digest) {
    if (id == null
        || !id.matches("[A-Za-z0-9_-]{1,100}")
        || digest == null
        || !digest.matches("[a-f0-9]{64}")) throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          lockSchemaRow();
          try (var query =
              connection.prepareStatement("SELECT digest FROM gateway_profiles WHERE id=?")) {
            query.setString(1, id);
            try (var rs = query.executeQuery()) {
              if (rs.next()) {
                if (!rs.getString(1).equals(digest))
                  throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return null;
              }
            }
          }
          try (var insert =
              connection.prepareStatement("INSERT INTO gateway_profiles VALUES(?,?)")) {
            insert.setString(1, id);
            insert.setString(2, digest);
            insert.executeUpdate();
          }
          return null;
        });
  }

  public ModelPolicy modelPolicy() {
    return transaction(this::policyInternal);
  }

  public ModelPolicy adminModelPolicy(UUID adminId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          return policyInternal();
        });
  }

  /**
   * The resolver is server-owned, local-only, and returns a model only if verified and available.
   */
  public PolicyChange selectModelPolicy(
      UUID adminId,
      UUID requestId,
      String profileId,
      long expectedRevision,
      String reason,
      java.util.function.Function<String, String> availableModel) {
    if (requestId == null
        || profileId == null
        || !profileId.matches("[A-Za-z0-9_-]{1,100}")
        || expectedRevision < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 500
        || availableModel == null) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          ModelPolicy previous = policyInternal();
          try (var query =
              connection.prepareStatement("SELECT * FROM model_policy_audit WHERE id=?")) {
            query.setString(1, requestId.toString());
            try (var rs = query.executeQuery()) {
              if (rs.next()) {
                PolicyAudit prior = readPolicyAudit(rs);
                if (!prior.adminId().equals(adminId)
                    || !prior.profileId().equals(profileId)
                    || prior.previousRevision() != expectedRevision
                    || !prior.reason().equals(reason))
                  throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return new PolicyChange(
                    new ModelPolicy(prior.revision(), prior.profileId(), prior.modelId()),
                    prior.id(),
                    true);
              }
            }
          }
          if (previous.revision() != expectedRevision)
            throw new GatewayException(MODEL_POLICY_CHANGED);
          String modelId = availableModel.apply(profileId);
          if (modelId == null) throw new GatewayException(MODEL_UNAVAILABLE);
          try (var query =
              connection.prepareStatement("SELECT id FROM gateway_profiles WHERE id=?")) {
            query.setString(1, profileId);
            try (var rs = query.executeQuery()) {
              if (!rs.next()) throw new GatewayException(MODEL_UNAVAILABLE);
            }
          }
          long revision = Math.addExact(previous.revision(), 1);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO model_policy_audit VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, requestId.toString());
            insert.setString(2, adminId.toString());
            insert.setString(3, previous.profileId());
            insert.setString(4, profileId);
            insert.setString(5, previous.modelId());
            insert.setString(6, modelId);
            insert.setLong(7, previous.revision());
            insert.setLong(8, revision);
            insert.setString(9, reason);
            insert.setLong(10, now.toEpochMilli());
            insert.executeUpdate();
          }
          try (var update =
              connection.prepareStatement(
                  "UPDATE gateway_model_policy SET revision=?,profile_id=?,model_id=? WHERE id=1")) {
            update.setLong(1, revision);
            update.setString(2, profileId);
            update.setString(3, modelId);
            if (update.executeUpdate() != 1) throw new GatewayException(STORAGE_FAILURE);
          }
          return new PolicyChange(new ModelPolicy(revision, profileId, modelId), requestId, false);
        });
  }

  public List<PolicyAudit> modelPolicyAudit(UUID adminId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          var entries = new ArrayList<PolicyAudit>();
          try (var query =
                  connection.prepareStatement(
                      "SELECT * FROM model_policy_audit ORDER BY revision DESC LIMIT 100");
              var rs = query.executeQuery()) {
            while (rs.next()) entries.add(readPolicyAudit(rs));
          }
          return List.copyOf(entries);
        });
  }

  private ModelPolicy policyInternal() throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT revision,profile_id,model_id FROM gateway_model_policy WHERE id=1 FOR UPDATE");
        var rs = query.executeQuery()) {
      if (!rs.next()) throw new GatewayException(STORAGE_FAILURE);
      return new ModelPolicy(rs.getLong(1), rs.getString(2), rs.getString(3));
    }
  }

  private void requirePolicy(String profileId, String modelId, long revision) throws SQLException {
    ModelPolicy policy = policyInternal();
    if (policy.revision() != revision
        || !java.util.Objects.equals(policy.profileId(), profileId)
        || !java.util.Objects.equals(policy.modelId(), modelId))
      throw new GatewayException(MODEL_POLICY_CHANGED);
  }

  private static PolicyAudit readPolicyAudit(ResultSet rs) throws SQLException {
    return new PolicyAudit(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("admin_id")),
        rs.getString("previous_profile_id"),
        rs.getString("profile_id"),
        rs.getString("previous_model_id"),
        rs.getString("model_id"),
        rs.getLong("previous_revision"),
        rs.getLong("revision"),
        rs.getString("reason"),
        Instant.ofEpochMilli(rs.getLong("created_at")));
  }

  public ModelPolicy rubricModelPolicy() {
    return transaction(this::rubricPolicyInternal);
  }

  public ModelPolicy adminRubricModelPolicy(UUID adminId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          return rubricPolicyInternal();
        });
  }

  /** Changes only the model used to generate scoring points; grading policy stays untouched. */
  public PolicyChange selectRubricModelPolicy(
      UUID adminId,
      UUID requestId,
      String profileId,
      long expectedRevision,
      String reason,
      java.util.function.Function<String, String> availableModel) {
    if (requestId == null
        || profileId == null
        || !profileId.matches("[A-Za-z0-9_-]{1,100}")
        || expectedRevision < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 500
        || availableModel == null) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          requireAdmin(adminId);
          ModelPolicy previous = rubricPolicyInternal();
          try (var query =
              connection.prepareStatement("SELECT * FROM rubric_model_policy_audit WHERE id=?")) {
            query.setString(1, requestId.toString());
            try (var rs = query.executeQuery()) {
              if (rs.next()) {
                PolicyAudit prior = readPolicyAudit(rs);
                if (!prior.adminId().equals(adminId)
                    || !prior.profileId().equals(profileId)
                    || prior.previousRevision() != expectedRevision
                    || !prior.reason().equals(reason))
                  throw new GatewayException(IDEMPOTENCY_CONFLICT);
                return new PolicyChange(
                    new ModelPolicy(prior.revision(), prior.profileId(), prior.modelId()),
                    prior.id(),
                    true);
              }
            }
          }
          if (previous.revision() != expectedRevision)
            throw new GatewayException(MODEL_POLICY_CHANGED);
          String modelId = availableModel.apply(profileId);
          if (modelId == null) throw new GatewayException(MODEL_UNAVAILABLE);
          try (var query =
              connection.prepareStatement("SELECT id FROM gateway_profiles WHERE id=?")) {
            query.setString(1, profileId);
            try (var rs = query.executeQuery()) {
              if (!rs.next()) throw new GatewayException(MODEL_UNAVAILABLE);
            }
          }
          long revision = Math.addExact(previous.revision(), 1);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO rubric_model_policy_audit VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, requestId.toString());
            insert.setString(2, adminId.toString());
            insert.setString(3, previous.profileId());
            insert.setString(4, profileId);
            insert.setString(5, previous.modelId());
            insert.setString(6, modelId);
            insert.setLong(7, previous.revision());
            insert.setLong(8, revision);
            insert.setString(9, reason);
            insert.setLong(10, now.toEpochMilli());
            insert.executeUpdate();
          }
          try (var update =
              connection.prepareStatement(
                  "UPDATE gateway_rubric_model_policy SET revision=?,profile_id=?,model_id=? WHERE id=1")) {
            update.setLong(1, revision);
            update.setString(2, profileId);
            update.setString(3, modelId);
            if (update.executeUpdate() != 1) throw new GatewayException(STORAGE_FAILURE);
          }
          return new PolicyChange(new ModelPolicy(revision, profileId, modelId), requestId, false);
        });
  }

  public List<PolicyAudit> rubricModelPolicyAudit(UUID adminId) {
    return transaction(
        () -> {
          requireAdmin(adminId);
          var entries = new ArrayList<PolicyAudit>();
          try (var query =
                  connection.prepareStatement(
                      "SELECT * FROM rubric_model_policy_audit ORDER BY revision DESC LIMIT 100");
              var rs = query.executeQuery()) {
            while (rs.next()) entries.add(readPolicyAudit(rs));
          }
          return List.copyOf(entries);
        });
  }

  private ModelPolicy rubricPolicyInternal() throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT revision,profile_id,model_id FROM gateway_rubric_model_policy WHERE id=1 FOR UPDATE");
        var rs = query.executeQuery()) {
      if (!rs.next()) throw new GatewayException(STORAGE_FAILURE);
      return new ModelPolicy(rs.getLong(1), rs.getString(2), rs.getString(3));
    }
  }

  private void requireRubricPolicy(String profileId, String modelId, long revision)
      throws SQLException {
    ModelPolicy policy = rubricPolicyInternal();
    if (policy.revision() != revision
        || !java.util.Objects.equals(policy.profileId(), profileId)
        || !java.util.Objects.equals(policy.modelId(), modelId))
      throw new GatewayException(MODEL_POLICY_CHANGED);
  }

  /**
   * Atomically resolves and locks the live package, then creates or replays the order. This is the
   * HTTP-facing path; catalogue values never come from client input.
   */
  public Order createOrderFromPackage(UUID userId, UUID requestId, String packageId) {
    String id = validatePackageId(packageId);
    if (userId == null || requestId == null) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          accountForUpdate(userId);
          Order prior = existingOrder(userId, requestId);
          if (prior != null) {
            if (!prior.packageId().equals(id)) throw new GatewayException(IDEMPOTENCY_CONFLICT);
            return prior;
          }
          CreditPackageRow row = packageRow(id, true);
          if (row == null || row.deletedAt() != null || !row.value().enabled())
            throw new GatewayException(INVALID_REQUEST);
          CreditPackage pack = row.value();
          return insertOrder(
              userId,
              requestId,
              pack.id(),
              pack.credits(),
              pack.priceMinor(),
              pack.name(),
              pack.currency());
        });
  }

  /**
   * Compatibility path for existing internal callers. If the package exists, its live values are
   * locked and must still match; a missing legacy package keeps the old explicit-snapshot behavior.
   */
  public Order createOrder(
      UUID userId, UUID requestId, String packageId, long units, Long priceCents) {
    if (requestId == null
        || packageId == null
        || !packageId.matches("[A-Za-z0-9_-]{1,64}")
        || units <= 0
        || units > 1_000_000_000L
        || (priceCents != null && (priceCents < 0 || priceCents > 100_000_000_000L)))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          accountForUpdate(userId);
          Order prior = existingOrder(userId, requestId);
          if (prior != null) {
            if (!prior.packageId().equals(packageId))
              throw new GatewayException(IDEMPOTENCY_CONFLICT);
            return prior;
          }
          CreditPackageRow packageRow = packageRow(packageId, true);
          String packageName = packageId;
          String currency = "CNY";
          if (packageRow != null) {
            if (packageRow.deletedAt() != null || !packageRow.value().enabled())
              throw new GatewayException(INVALID_REQUEST);
            if (packageRow.value().credits() != units
                || !java.util.Objects.equals(packageRow.value().priceMinor(), priceCents))
              throw new GatewayException(IDEMPOTENCY_CONFLICT);
            packageName = packageRow.value().name();
            currency = packageRow.value().currency();
          }
          return insertOrder(
              userId, requestId, packageId, units, priceCents, packageName, currency);
        });
  }

  private Order existingOrder(UUID userId, UUID requestId) throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT * FROM orders WHERE user_id=? AND request_id=?")) {
      query.setString(1, userId.toString());
      query.setString(2, requestId.toString());
      try (var rows = query.executeQuery()) {
        return rows.next() ? readOrder(rows) : null;
      }
    }
  }

  private Order insertOrder(
      UUID userId,
      UUID requestId,
      String packageId,
      long units,
      Long priceCents,
      String packageName,
      String currency)
      throws SQLException {
    UUID id = UUID.randomUUID();
    Instant now = Instant.ofEpochMilli(clock.millis());
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO orders(id,user_id,request_id,package_id,units,price_cents,status,created_at,package_name,currency) VALUES(?,?,?,?,?,?,'PENDING_PAYMENT',?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, userId.toString());
      insert.setString(3, requestId.toString());
      insert.setString(4, packageId);
      insert.setLong(5, units);
      setNullableLong(insert, 6, priceCents);
      insert.setLong(7, now.toEpochMilli());
      insert.setString(8, packageName);
      insert.setString(9, currency);
      if (insert.executeUpdate() != 1) throw new SQLException();
    }
    return new Order(
        id,
        userId,
        requestId,
        packageId,
        units,
        priceCents,
        "PENDING_PAYMENT",
        now,
        packageName,
        currency);
  }

  public List<Order> orders(UUID userId, int offset, int limit) {
    page(offset, limit);
    return transaction(
        () -> {
          accountInternal(userId);
          var result = new ArrayList<Order>();
          try (var query =
              connection.prepareStatement(
                  "SELECT * FROM orders WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?")) {
            query.setString(1, userId.toString());
            query.setInt(2, limit);
            query.setInt(3, offset);
            try (var rs = query.executeQuery()) {
              while (rs.next()) result.add(readOrder(rs));
            }
          }
          return List.copyOf(result);
        });
  }

  public Prepared prepare(UUID userId, UUID requestId, String fingerprint, String modelId) {
    return prepare(userId, requestId, fingerprint, modelId, null, null);
  }

  public Prepared prepare(
      UUID userId,
      UUID requestId,
      String fingerprint,
      String modelId,
      String profileId,
      Long policyRevision) {
    return prepareBound(
        userId, requestId, fingerprint, modelId, profileId, policyRevision, profileId, modelId);
  }

  public Prepared prepareFallback(
      UUID userId,
      UUID requestId,
      String fingerprint,
      String fallbackModelId,
      String fallbackProfileId,
      long policyRevision,
      String primaryProfileId,
      String primaryModelId) {
    return prepareBound(
        userId,
        requestId,
        fingerprint,
        fallbackModelId,
        fallbackProfileId,
        policyRevision,
        primaryProfileId,
        primaryModelId);
  }

  private Prepared prepareBound(
      UUID userId,
      UUID requestId,
      String fingerprint,
      String modelId,
      String profileId,
      Long policyRevision,
      String requiredPolicyProfileId,
      String requiredPolicyModelId) {
    if (requestId == null
        || fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || modelId == null
        || !modelId.matches("[A-Za-z0-9_.:/-]{1,200}")
        || ((profileId == null) != (policyRevision == null))
        || (policyRevision != null
            && (policyRevision < 1
                || !profileId.matches("[A-Za-z0-9_-]{1,100}")
                || requiredPolicyProfileId == null
                || !requiredPolicyProfileId.matches("[A-Za-z0-9_-]{1,100}")
                || requiredPolicyModelId == null
                || !requiredPolicyModelId.matches("[A-Za-z0-9_.:/-]{1,200}"))))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          Account account = accountForUpdate(userId);
          Request prior = requestInternal(userId, requestId);
          if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)
                || !prior.modelId().equals(modelId)
                || !java.util.Objects.equals(prior.profileId(), profileId)
                || !java.util.Objects.equals(prior.policyRevision(), policyRevision))
              throw new GatewayException(IDEMPOTENCY_CONFLICT);
            return new Prepared(prior, false);
          }
          // Do not let random IDs from an exhausted account permanently grow grade_requests.
          if (account.balance() < 1) throw new GatewayException(INSUFFICIENT_QUOTA);
          if (policyRevision != null)
            requirePolicy(requiredPolicyProfileId, requiredPolicyModelId, policyRevision);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO grade_requests(user_id,request_id,fingerprint,model_id,state,charged,response_json,error_code,created_at,profile_id,policy_revision) VALUES(?,?,?,?,'PREPARED',FALSE,NULL,NULL,?,?,?)")) {
            insert.setString(1, userId.toString());
            insert.setString(2, requestId.toString());
            insert.setString(3, fingerprint);
            insert.setString(4, modelId);
            insert.setLong(5, now.toEpochMilli());
            insert.setString(6, profileId);
            if (policyRevision == null) insert.setNull(7, java.sql.Types.BIGINT);
            else insert.setLong(7, policyRevision);
            insert.executeUpdate();
          }
          return new Prepared(
              new Request(
                  userId,
                  requestId,
                  fingerprint,
                  modelId,
                  "PREPARED",
                  false,
                  null,
                  null,
                  now,
                  profileId,
                  policyRevision),
              true);
        });
  }

  /** Called synchronously immediately before the provider's first sendAsync call. */
  public void beforeDispatch(UUID userId, UUID requestId) {
    beforeDispatch(userId, requestId, null);
  }

  /** HTTP dispatch rechecks the same live session inside the balance/debit transaction. */
  public void beforeDispatch(UUID userId, UUID requestId, String sessionToken) {
    beforeDispatchBound(userId, requestId, sessionToken, null, null, null);
  }

  public void beforeFallbackDispatch(
      UUID userId,
      UUID requestId,
      String sessionToken,
      String primaryProfileId,
      String primaryModelId,
      long policyRevision) {
    if (primaryProfileId == null || primaryModelId == null || policyRevision < 1)
      throw new GatewayException(INVALID_REQUEST);
    beforeDispatchBound(
        userId, requestId, sessionToken, primaryProfileId, primaryModelId, policyRevision);
  }

  private void beforeDispatchBound(
      UUID userId,
      UUID requestId,
      String sessionToken,
      String requiredPolicyProfileId,
      String requiredPolicyModelId,
      Long requiredPolicyRevision) {
    transaction(
        () -> {
          if (sessionToken != null) {
            if (!sessionUser(sessionToken).equals(userId))
              throw new GatewayException(UNAUTHENTICATED);
          }
          Account account = accountForUpdate(userId);
          Request request = requireRequest(userId, requestId);
          if (!request.state().equals("PREPARED")) throw new GatewayException(REQUEST_IN_PROGRESS);
          if (request.policyRevision() != null) {
            if (requiredPolicyRevision == null)
              requirePolicy(request.profileId(), request.modelId(), request.policyRevision());
            else {
              if (!request.policyRevision().equals(requiredPolicyRevision))
                throw new GatewayException(MODEL_POLICY_CHANGED);
              requirePolicy(requiredPolicyProfileId, requiredPolicyModelId, requiredPolicyRevision);
            }
          }
          if (account.balance() < 1) throw new GatewayException(INSUFFICIENT_QUOTA);
          long balance = account.balance() - 1;
          updateBalance(userId, balance);
          try (var update =
              connection.prepareStatement(
                  "UPDATE grade_requests SET state='DISPATCHING',charged=TRUE WHERE user_id=? AND request_id=? AND state='PREPARED'")) {
            update.setString(1, userId.toString());
            update.setString(2, requestId.toString());
            if (update.executeUpdate() != 1) throw new GatewayException(REQUEST_IN_PROGRESS);
          }
          quotaEvent(userId, "MODEL_DISPATCH", requestId, -1, balance, clock.instant());
          return null;
        });
  }

  /**
   * Only the owning in-process trace may authorize a confirmed pre-connection retry. This performs
   * no debit, ledger insert or request state update; a paid request remains valid at balance zero.
   */
  void authorizeConnectRetry(
      UUID userId,
      UUID requestId,
      String sessionToken,
      String requestProfileId,
      String requestModelId,
      String primaryProfileId,
      String primaryModelId,
      long policyRevision) {
    if (userId == null
        || requestId == null
        || requestProfileId == null
        || requestModelId == null
        || primaryProfileId == null
        || primaryModelId == null
        || policyRevision < 1) throw new GatewayException(INVALID_REQUEST);
    transaction(
        () -> {
          if (sessionToken != null && !sessionUser(sessionToken).equals(userId))
            throw new GatewayException(UNAUTHENTICATED);
          accountInternal(userId);
          Request request = requireRequest(userId, requestId);
          if (!request.charged() || !"DISPATCHING".equals(request.state()))
            throw new GatewayException(REQUEST_IN_PROGRESS);
          if (!requestProfileId.equals(request.profileId())
              || !requestModelId.equals(request.modelId())
              || !Long.valueOf(policyRevision).equals(request.policyRevision()))
            throw new GatewayException(MODEL_POLICY_CHANGED);
          requirePolicy(primaryProfileId, primaryModelId, policyRevision);
          return null;
        });
  }

  public Request finish(
      UUID userId, UUID requestId, String responseJson, String errorCode, boolean uncertain) {
    return finishBound(userId, requestId, responseJson, errorCode, uncertain, null, null, null);
  }

  public Request finishFallback(
      UUID userId,
      UUID requestId,
      String responseJson,
      String errorCode,
      boolean uncertain,
      String primaryProfileId,
      String primaryModelId,
      long policyRevision) {
    if (primaryProfileId == null || primaryModelId == null || policyRevision < 1)
      throw new GatewayException(INVALID_REQUEST);
    return finishBound(
        userId,
        requestId,
        responseJson,
        errorCode,
        uncertain,
        primaryProfileId,
        primaryModelId,
        policyRevision);
  }

  private Request finishBound(
      UUID userId,
      UUID requestId,
      String responseJson,
      String errorCode,
      boolean uncertain,
      String requiredPolicyProfileId,
      String requiredPolicyModelId,
      Long requiredPolicyRevision) {
    if (responseJson != null && responseJson.length() > 1_048_576)
      throw new GatewayException(INVALID_REQUEST);
    if (errorCode != null && !errorCode.matches("[A-Z0-9_]{1,64}"))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          Request prior = requireRequest(userId, requestId);
          if (!List.of("PREPARED", "DISPATCHING").contains(prior.state())) return prior;
          String finalResponse = responseJson;
          String finalError = errorCode;
          // A response is accepted under the same policy transaction as its persisted completion.
          // Existing terminal receipts remain immutable and replay under their original revision.
          if (responseJson != null && prior.policyRevision() != null) {
            try {
              if (requiredPolicyRevision == null)
                requirePolicy(prior.profileId(), prior.modelId(), prior.policyRevision());
              else {
                if (!prior.policyRevision().equals(requiredPolicyRevision))
                  throw new GatewayException(MODEL_POLICY_CHANGED);
                requirePolicy(
                    requiredPolicyProfileId, requiredPolicyModelId, requiredPolicyRevision);
              }
            } catch (GatewayException changed) {
              if (changed.code() != MODEL_POLICY_CHANGED) throw changed;
              finalResponse = null;
              finalError = "MODEL_POLICY_CHANGED";
            }
          }
          String state =
              prior.charged()
                  ? (uncertain ? "UNKNOWN" : (finalError == null ? "SUCCEEDED" : "FAILED"))
                  : "NOT_DISPATCHED";
          if (finalError == null && finalResponse == null)
            throw new GatewayException(INVALID_REQUEST);
          try (var update =
              connection.prepareStatement(
                  "UPDATE grade_requests SET state=?,response_json=?,error_code=?,completed_at=? WHERE user_id=? AND request_id=?")) {
            update.setString(1, state);
            update.setString(2, finalResponse);
            update.setString(3, finalError);
            update.setLong(4, clock.millis());
            update.setString(5, userId.toString());
            update.setString(6, requestId.toString());
            update.executeUpdate();
          }
          return requireRequest(userId, requestId);
        });
  }

  public Request request(UUID userId, UUID requestId) {
    return transaction(() -> requireRequest(userId, requestId));
  }

  public Request findRequest(UUID userId, UUID requestId) {
    return transaction(() -> requestInternal(userId, requestId));
  }

  private Request requireRequest(UUID userId, UUID requestId) throws SQLException {
    Request request = requestInternal(userId, requestId);
    if (request == null) throw new GatewayException(NOT_FOUND);
    return request;
  }

  private Request requestInternal(UUID userId, UUID requestId) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT * FROM grade_requests WHERE user_id=? AND request_id=? FOR UPDATE")) {
      query.setString(1, userId.toString());
      query.setString(2, requestId.toString());
      try (var rs = query.executeQuery()) {
        if (!rs.next()) return null;
        return new Request(
            userId,
            requestId,
            rs.getString("fingerprint"),
            rs.getString("model_id"),
            rs.getString("state"),
            rs.getBoolean("charged"),
            rs.getString("response_json"),
            rs.getString("error_code"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            rs.getString("profile_id"),
            rs.getObject("policy_revision", Long.class));
      }
    }
  }

  public Prepared prepareRubricGeneration(
      UUID userId,
      UUID requestId,
      String fingerprint,
      String modelId,
      String profileId,
      long policyRevision) {
    if (requestId == null
        || fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || modelId == null
        || !modelId.matches("[A-Za-z0-9_.:/-]{1,200}")
        || profileId == null
        || !profileId.matches("[A-Za-z0-9_-]{1,100}")
        || policyRevision < 1) throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          Account account = accountForUpdate(userId);
          Request prior = rubricGenerationRequestInternal(userId, requestId);
          if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)
                || !prior.modelId().equals(modelId)
                || !prior.profileId().equals(profileId)
                || !prior.policyRevision().equals(policyRevision))
              throw new GatewayException(IDEMPOTENCY_CONFLICT);
            return new Prepared(prior, false);
          }
          if (account.balance() < 1) throw new GatewayException(INSUFFICIENT_QUOTA);
          requireRubricPolicy(profileId, modelId, policyRevision);
          Instant now = Instant.ofEpochMilli(clock.millis());
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO rubric_generation_requests(user_id,request_id,fingerprint,model_id,state,charged,response_json,error_code,created_at,profile_id,policy_revision) VALUES(?,?,?,?,'PREPARED',FALSE,NULL,NULL,?,?,?)")) {
            insert.setString(1, userId.toString());
            insert.setString(2, requestId.toString());
            insert.setString(3, fingerprint);
            insert.setString(4, modelId);
            insert.setLong(5, now.toEpochMilli());
            insert.setString(6, profileId);
            insert.setLong(7, policyRevision);
            insert.executeUpdate();
          }
          return new Prepared(
              new Request(
                  userId,
                  requestId,
                  fingerprint,
                  modelId,
                  "PREPARED",
                  false,
                  null,
                  null,
                  now,
                  profileId,
                  policyRevision),
              true);
        });
  }

  /** Atomically revalidates the session and generator policy, then spends exactly one credit. */
  public void beforeRubricGenerationDispatch(UUID userId, UUID requestId, String sessionToken) {
    transaction(
        () -> {
          if (sessionToken != null && !sessionUser(sessionToken).equals(userId))
            throw new GatewayException(UNAUTHENTICATED);
          Account account = accountForUpdate(userId);
          Request request = requireRubricGenerationRequest(userId, requestId);
          if (!request.state().equals("PREPARED")) throw new GatewayException(REQUEST_IN_PROGRESS);
          requireRubricPolicy(
              request.profileId(), request.modelId(), request.policyRevision().longValue());
          if (account.balance() < 1) throw new GatewayException(INSUFFICIENT_QUOTA);
          long balance = account.balance() - 1;
          updateBalance(userId, balance);
          try (var update =
              connection.prepareStatement(
                  "UPDATE rubric_generation_requests SET state='DISPATCHING',charged=TRUE WHERE user_id=? AND request_id=? AND state='PREPARED'")) {
            update.setString(1, userId.toString());
            update.setString(2, requestId.toString());
            if (update.executeUpdate() != 1) throw new GatewayException(REQUEST_IN_PROGRESS);
          }
          quotaEvent(userId, "RUBRIC_GENERATION", requestId, -1, balance, clock.instant());
          return null;
        });
  }

  public Request finishRubricGeneration(
      UUID userId, UUID requestId, String responseJson, String errorCode, boolean uncertain) {
    if (responseJson != null && responseJson.length() > 1_048_576)
      throw new GatewayException(INVALID_REQUEST);
    if (errorCode != null && !errorCode.matches("[A-Z0-9_]{1,64}"))
      throw new GatewayException(INVALID_REQUEST);
    return transaction(
        () -> {
          Request prior = requireRubricGenerationRequest(userId, requestId);
          if (!List.of("PREPARED", "DISPATCHING").contains(prior.state())) return prior;
          String finalResponse = responseJson;
          String finalError = errorCode;
          if (responseJson != null) {
            try {
              requireRubricPolicy(
                  prior.profileId(), prior.modelId(), prior.policyRevision().longValue());
            } catch (GatewayException changed) {
              if (changed.code() != MODEL_POLICY_CHANGED) throw changed;
              finalResponse = null;
              finalError = "MODEL_POLICY_CHANGED";
            }
          }
          String state =
              prior.charged()
                  ? (uncertain ? "UNKNOWN" : (finalError == null ? "SUCCEEDED" : "FAILED"))
                  : "NOT_DISPATCHED";
          if (finalError == null && finalResponse == null)
            throw new GatewayException(INVALID_REQUEST);
          try (var update =
              connection.prepareStatement(
                  "UPDATE rubric_generation_requests SET state=?,response_json=?,error_code=?,completed_at=? WHERE user_id=? AND request_id=?")) {
            update.setString(1, state);
            update.setString(2, finalResponse);
            update.setString(3, finalError);
            update.setLong(4, clock.millis());
            update.setString(5, userId.toString());
            update.setString(6, requestId.toString());
            update.executeUpdate();
          }
          return requireRubricGenerationRequest(userId, requestId);
        });
  }

  public Request rubricGenerationRequest(UUID userId, UUID requestId) {
    return transaction(() -> requireRubricGenerationRequest(userId, requestId));
  }

  public Request findRubricGenerationRequest(UUID userId, UUID requestId) {
    return transaction(() -> rubricGenerationRequestInternal(userId, requestId));
  }

  private Request requireRubricGenerationRequest(UUID userId, UUID requestId) throws SQLException {
    Request request = rubricGenerationRequestInternal(userId, requestId);
    if (request == null) throw new GatewayException(NOT_FOUND);
    return request;
  }

  private Request rubricGenerationRequestInternal(UUID userId, UUID requestId) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT * FROM rubric_generation_requests WHERE user_id=? AND request_id=? FOR UPDATE")) {
      query.setString(1, userId.toString());
      query.setString(2, requestId.toString());
      try (var rs = query.executeQuery()) {
        if (!rs.next()) return null;
        return new Request(
            userId,
            requestId,
            rs.getString("fingerprint"),
            rs.getString("model_id"),
            rs.getString("state"),
            rs.getBoolean("charged"),
            rs.getString("response_json"),
            rs.getString("error_code"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            rs.getString("profile_id"),
            rs.getLong("policy_revision"));
      }
    }
  }

  private PasswordRow passwordRow(String username) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM accounts WHERE username=?")) {
      query.setString(1, username);
      try (var rs = query.executeQuery()) {
        if (!rs.next()) return null;
        return new PasswordRow(
            readAccount(rs),
            rs.getBytes("salt"),
            rs.getBytes("password_hash"),
            rs.getInt("iterations"));
      }
    }
  }

  private Account accountInternal(UUID userId) throws SQLException {
    return accountInternal(userId, false);
  }

  private Account accountForUpdate(UUID userId) throws SQLException {
    return accountInternal(userId, true);
  }

  private Account accountInternal(UUID userId, boolean lock) throws SQLException {
    if (userId == null) throw new GatewayException(UNAUTHENTICATED);
    try (var query =
        connection.prepareStatement(
            "SELECT id,username,role,balance,created_at FROM accounts WHERE id=?"
                + (lock ? " FOR UPDATE" : ""))) {
      query.setString(1, userId.toString());
      try (var rs = query.executeQuery()) {
        if (!rs.next()) throw new GatewayException(NOT_FOUND);
        return readAccount(rs);
      }
    }
  }

  private void lockSchemaRow() throws SQLException {
    try (var query =
            connection.prepareStatement("SELECT id FROM gateway_schema WHERE id=1 FOR UPDATE");
        var rs = query.executeQuery()) {
      if (!rs.next()) throw new SQLException();
    }
  }

  private void requireAdmin(UUID id) throws SQLException {
    if (!accountInternal(id).role().equals("ADMIN")) throw new GatewayException(FORBIDDEN);
  }

  private void updateBalance(UUID userId, long balance) throws SQLException {
    try (var update = connection.prepareStatement("UPDATE accounts SET balance=? WHERE id=?")) {
      update.setLong(1, balance);
      update.setString(2, userId.toString());
      if (update.executeUpdate() != 1) throw new GatewayException(NOT_FOUND);
    }
  }

  private void quotaEvent(
      UUID userId, String kind, UUID reference, long delta, long balance, Instant at)
      throws SQLException {
    try (var insert =
        connection.prepareStatement("INSERT INTO quota_events VALUES(?,?,?,?,?,?,?)")) {
      insert.setString(
          1, (kind.equals("ADMIN_ADJUSTMENT") ? reference : UUID.randomUUID()).toString());
      insert.setString(2, userId.toString());
      insert.setString(3, kind);
      insert.setString(4, reference.toString());
      insert.setLong(5, delta);
      insert.setLong(6, balance);
      insert.setLong(7, at.toEpochMilli());
      insert.executeUpdate();
    }
  }

  private static Account readAccount(ResultSet rs) throws SQLException {
    return new Account(
        UUID.fromString(rs.getString("id")),
        rs.getString("username"),
        rs.getString("role"),
        rs.getLong("balance"),
        Instant.ofEpochMilli(rs.getLong("created_at")));
  }

  private static Adjustment readAdjustment(ResultSet rs) throws SQLException {
    return new Adjustment(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("admin_id")),
        UUID.fromString(rs.getString("user_id")),
        rs.getLong("delta"),
        rs.getString("reason"),
        rs.getLong("balance_after"),
        Instant.ofEpochMilli(rs.getLong("created_at")));
  }

  private static Order readOrder(ResultSet rs) throws SQLException {
    long price = rs.getLong("price_cents");
    Long nullablePrice = rs.wasNull() ? null : price;
    return new Order(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("user_id")),
        UUID.fromString(rs.getString("request_id")),
        rs.getString("package_id"),
        rs.getLong("units"),
        nullablePrice,
        rs.getString("status"),
        Instant.ofEpochMilli(rs.getLong("created_at")),
        rs.getString("package_name"),
        rs.getString("currency"));
  }

  static String normalizeUsername(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_.-]{3,32}"))
      throw new GatewayException(INVALID_REQUEST);
    return name.toLowerCase(Locale.ROOT);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new GatewayException(SERVICE_UNAVAILABLE);
    }
  }

  private static void validateToken(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new GatewayException(UNAUTHENTICATED);
  }

  private static void page(int offset, int limit) {
    if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100)
      throw new GatewayException(INVALID_REQUEST);
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws SQLException;
  }

  private synchronized <T> T transaction(Work<T> work) {
    if (closed) throw new GatewayException(STORAGE_FAILURE);
    try {
      ensureConnection();
      T result = work.run();
      connection.commit();
      return result;
    } catch (Exception failure) {
      try {
        if (connection != null) connection.rollback();
      } catch (SQLException ignored) {
      }
      if (failure instanceof GatewayException safe) throw safe;
      throw new GatewayException(STORAGE_FAILURE);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    Arrays.fill(dummy.hash(), (byte) 0);
    Arrays.fill(dummy.salt(), (byte) 0);
    boolean failed = false;
    try {
      if (connection != null) {
        try {
          connection.rollback();
        } finally {
          try {
            releaseMySqlLock(connection);
          } finally {
            connection.close();
          }
        }
      }
    } catch (Exception failure) {
      failed = true;
    }
    try {
      if (processLock != null) processLock.close();
    } catch (Exception failure) {
      failed = true;
    }
    try {
      if (lockChannel != null) lockChannel.close();
    } catch (Exception failure) {
      failed = true;
    }
    try {
      if (sessionTokens != null) sessionTokens.close();
    } catch (Exception failure) {
      failed = true;
    }
    if (failed) throw new GatewayException(STORAGE_FAILURE);
  }
}
