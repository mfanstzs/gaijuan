package app.deskgrade.gateway;

import static app.deskgrade.gateway.GatewayException.Code.STORAGE_FAILURE;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Explicit, offline H2-to-MySQL import. Existing business data is never merged or overwritten. */
public final class GatewayDatabaseMigration {
  private enum Kind {
    TEXT,
    NUMBER,
    BINARY,
    BOOLEAN
  }

  private record Column(String name, Kind kind) {}

  private record Table(String name, String ordering, List<Column> columns) {
    String fields() {
      return String.join(",", columns.stream().map(Column::name).toList());
    }
  }

  private record TableFingerprint(long count, String digest) {}

  public record LedgerSummary(
      long accounts,
      long administrators,
      BigDecimal availableCredits,
      BigDecimal adjustmentDelta,
      BigDecimal quotaEventDelta,
      long chargedRequests,
      BigDecimal pendingOrderUnits) {}

  public record MigrationReport(
      int schemaVersion,
      String status,
      String completedUtc,
      String sourceDatabaseSha256,
      long sourceBytes,
      Map<String, Long> copiedRows,
      LedgerSummary ledger,
      boolean allColumnValuesVerified,
      boolean originalDatabaseUnchanged) {}

  static record VerifiedImport(Map<String, Long> rows, LedgerSummary ledger) {}

  private record SourceSnapshot(String sha256, long bytes, FileTime modifiedAt) {}

  private static final String TEMPORARY_PREFIX = "deskgrade-gateway-migration-";
  private static final String TEMPORARY_DATABASE = "source-copy";
  private static final Set<String> VERSION_THREE_TABLES =
      Set.of(
          "rubric_generation_requests", "gateway_rubric_model_policy", "rubric_model_policy_audit");
  private static final Set<String> VERSION_FIVE_TABLES = Set.of("credit_packages");
  private static final Set<String> VERSION_SIX_TABLES = Set.of("package_audit");

  static final class PreparedSource implements AutoCloseable {
    private final Path base;
    private final Path temporaryRoot;
    private final Path temporaryDirectory;
    private boolean closed;

    private PreparedSource(Path base, Path temporaryRoot, Path temporaryDirectory) {
      this.base = base;
      this.temporaryRoot = temporaryRoot;
      this.temporaryDirectory = temporaryDirectory;
    }

    Path base() {
      return base;
    }

    boolean temporary() {
      return temporaryDirectory != null;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      if (temporaryDirectory != null) deleteTemporaryDirectory(temporaryRoot, temporaryDirectory);
      closed = true;
    }
  }

  private static final List<Table> TABLES =
      List.of(
          table("gateway_profiles", "id", "id:t,digest:t"),
          table(
              "accounts",
              "id",
              "id:t,username:t,role:t,balance:n,salt:b,password_hash:b,iterations:n,created_at:n"),
          table("sessions", "token_hash", "token_hash:t,user_id:t,expires_at:n"),
          table(
              "adjustments",
              "id",
              "id:t,admin_id:t,user_id:t,delta:n,reason:t,balance_after:n,created_at:n"),
          table(
              "quota_events",
              "id",
              "id:t,user_id:t,kind:t,reference_id:t,delta:n,balance_after:n,created_at:n"),
          table(
              "orders",
              "id",
              "id:t,user_id:t,request_id:t,package_id:t,units:n,price_cents:n,status:t,created_at:n,package_name:t,currency:t"),
          table(
              "credit_packages",
              "id",
              "id:t,name:t,credits:n,price_minor:n,currency:t,enabled:z,revision:n,created_at:n,updated_at:n,deleted_at:n"),
          table(
              "package_audit",
              "id",
              "id:t,admin_id:t,package_id:t,action:t,previous_revision:n,revision:n,before_json:t,after_json:t,created_at:n"),
          table(
              "grade_requests",
              "user_id,request_id",
              "user_id:t,request_id:t,fingerprint:t,model_id:t,state:t,charged:z,response_json:t,error_code:t,created_at:n,profile_id:t,policy_revision:n,request_json:t,raw_model_output:t,completed_at:n"),
          table(
              "rubric_generation_requests",
              "user_id,request_id",
              "user_id:t,request_id:t,fingerprint:t,model_id:t,state:t,charged:z,response_json:t,error_code:t,created_at:n,profile_id:t,policy_revision:n,request_json:t,raw_model_output:t,completed_at:n"),
          table("gateway_model_policy", "id", "id:n,revision:n,profile_id:t,model_id:t"),
          table(
              "model_policy_audit",
              "id",
              "id:t,admin_id:t,previous_profile_id:t,profile_id:t,previous_model_id:t,model_id:t,previous_revision:n,revision:n,reason:t,created_at:n"),
          table("gateway_rubric_model_policy", "id", "id:n,revision:n,profile_id:t,model_id:t"),
          table(
              "rubric_model_policy_audit",
              "id",
              "id:t,admin_id:t,previous_profile_id:t,profile_id:t,previous_model_id:t,model_id:t,previous_revision:n,revision:n,reason:t,created_at:n"));

  private GatewayDatabaseMigration() {}

  public static void main(String[] args) {
    Path reportPath = null;
    boolean committed = false;
    try {
      Map<String, String> options = options(args);
      reportPath = Path.of(options.get("--report")).toAbsolutePath().normalize();
      if (reportPath.getParent() == null || !Files.isDirectory(reportPath.getParent()))
        throw failure();
      Files.writeString(
          reportPath,
          "{\"status\":\"RUNNING\"}\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      var configuration = StorageConfiguration.load(Path.of(options.get("--storage-config")));
      try (var target = configuration.openDataSource()) {
        MigrationReport report = migrate(Path.of(options.get("--source-h2")), target);
        committed = true;
        Files.write(
            reportPath,
            GatewayJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        System.out.println("DeskGrade migration: COMMITTED_AND_VERIFIED");
        System.out.println("Report: " + reportPath);
      }
    } catch (Exception problem) {
      // Never print a JDBC exception, connection URL, row contents, password, or token hash.
      System.err.println(
          "DeskGrade migration: "
              + (committed ? "COMMITTED_REPORT_WRITE_FAILED" : "FAILED_NO_SUCCESS_REPORTED"));
      if (reportPath != null) System.err.println("Report: " + reportPath);
      System.exit(1);
    }
  }

  public static MigrationReport migrate(Path sourceBase, DataSource target) {
    return migrate(sourceBase, target, defaultTemporaryRoot());
  }

  static MigrationReport migrate(Path sourceBase, DataSource target, Path temporaryRoot) {
    Path base = sourceBase.toAbsolutePath().normalize();
    Path file = base.resolveSibling(base.getFileName() + ".mv.db");
    if (base.toString().contains(";")
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)) throw failure();
    try (FileChannel lockChannel =
            FileChannel.open(
                base.resolveSibling(base.getFileName() + ".gateway.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock sourceLock = lockChannel.tryLock()) {
      if (sourceLock == null || Files.size(file) <= 0) throw failure();
      SourceSnapshot snapshot = sourceSnapshot(file);
      try (PreparedSource prepared = prepareSource(base, temporaryRoot, snapshot)) {
        try (Connection inspected = target.getConnection()) {
          verifyTargetBeforeInitialization(inspected);
        }
        GatewayStore.initializeMySql(target);
        VerifiedImport copied =
            GatewayStore.withMySqlMigration(
                target,
                destination -> {
                  requirePending(destination);
                  VerifiedImport result;
                  try (Connection source = openSource(prepared.base())) {
                    result = copyAndVerify(source, destination);
                  }
                  requireUnchanged(file, snapshot);
                  // Remove an upgraded v2 staging copy before READY can be committed. A cleanup
                  // failure therefore leaves the target PENDING and rolls back all copied rows.
                  prepared.close();
                  GatewayStore.markMySqlReady(destination, "H2:" + snapshot.sha256());
                  return result;
                });
        return new MigrationReport(
            1,
            "COMMITTED_AND_VERIFIED",
            Instant.now().toString(),
            snapshot.sha256(),
            snapshot.bytes(),
            copied.rows(),
            copied.ledger(),
            true,
            true);
      }
    } catch (Exception problem) {
      throw failure();
    }
  }

  static PreparedSource prepareSource(Path sourceBase, Path temporaryRoot) throws Exception {
    Path base = sourceBase.toAbsolutePath().normalize();
    Path file = base.resolveSibling(base.getFileName() + ".mv.db");
    if (base.toString().contains(";")
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)) throw failure();
    return prepareSource(base, temporaryRoot, sourceSnapshot(file));
  }

  private static PreparedSource prepareSource(
      Path base, Path temporaryRoot, SourceSnapshot snapshot) throws Exception {
    int version;
    try (Connection source = openSource(base)) {
      version = sourceSchemaVersion(source);
      verifySource(source, version);
    }
    requireUnchanged(base.resolveSibling(base.getFileName() + ".mv.db"), snapshot);
    if (version == 6) return new PreparedSource(base, null, null);
    if (version != 2 && version != 3 && version != 4 && version != 5) throw failure();

    Path root = temporaryRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
      throw failure();
    Path directory = Files.createTempDirectory(root, TEMPORARY_PREFIX).toAbsolutePath().normalize();
    var prepared = new PreparedSource(directory.resolve(TEMPORARY_DATABASE), root, directory);
    try {
      if (!root.equals(directory.getParent())
          || !directory.getFileName().toString().startsWith(TEMPORARY_PREFIX)
          || Files.isSymbolicLink(directory)) throw failure();
      Path original = base.resolveSibling(base.getFileName() + ".mv.db");
      Path copy = prepared.base().resolveSibling(TEMPORARY_DATABASE + ".mv.db");
      Files.copy(original, copy);
      if (Files.size(copy) != snapshot.bytes() || !fileHash(copy).equals(snapshot.sha256()))
        throw failure();
      requireUnchanged(original, snapshot);

      // Reuse the production H2 schema upgrade against the private copy only. The original stays
      // under the caller's gateway lock and is never opened writable.
      try (var ignored = new GatewayStore(prepared.base(), Clock.systemUTC())) {}
      try (Connection upgraded = openSource(prepared.base())) {
        verifySource(upgraded);
      }
      requireUnchanged(original, snapshot);
      return prepared;
    } catch (Exception problem) {
      try {
        prepared.close();
      } catch (Exception cleanup) {
        problem.addSuppressed(cleanup);
      }
      throw problem;
    }
  }

  static Connection openSource(Path base) throws SQLException {
    Path absolute = base.toAbsolutePath().normalize();
    Path file = absolute.resolveSibling(absolute.getFileName() + ".mv.db");
    if (absolute.toString().contains(";")
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)) throw failure();
    Connection source =
        DriverManager.getConnection(
            "jdbc:h2:file:"
                + absolute.toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "");
    source.setReadOnly(true);
    return source;
  }

  static VerifiedImport copyAndVerify(Connection source, Connection target) throws Exception {
    verifySource(source);
    verifyPristineBusinessTables(target);
    try (var removeSeed = target.prepareStatement("DELETE FROM gateway_model_policy WHERE id=1")) {
      if (removeSeed.executeUpdate() != 1) throw failure();
    }
    try (var removeSeed =
        target.prepareStatement("DELETE FROM gateway_rubric_model_policy WHERE id=1")) {
      if (removeSeed.executeUpdate() != 1) throw failure();
    }
    try (var removeSeed = target.prepareStatement("DELETE FROM credit_packages")) {
      int removed = removeSeed.executeUpdate();
      if (removed != 0 && removed != GatewayStore.DEFAULT_CREDIT_PACKAGES.size()) throw failure();
    }
    Map<String, Long> rows = new LinkedHashMap<>();
    for (Table table : TABLES) {
      copyTable(source, target, table);
      TableFingerprint original = fingerprint(source, table);
      if (!original.equals(fingerprint(target, table))) throw failure();
      rows.put(table.name(), original.count());
    }
    LedgerSummary ledger = ledger(source);
    if (!ledger.equals(ledger(target))) throw failure();
    return new VerifiedImport(java.util.Collections.unmodifiableMap(rows), ledger);
  }

  static void verifyCopiedContents(Connection source, Connection target) throws Exception {
    for (Table table : TABLES)
      if (!fingerprint(source, table).equals(fingerprint(target, table))) throw failure();
    if (!ledger(source).equals(ledger(target))) throw failure();
  }

  private static void verifySource(Connection source) throws SQLException {
    verifySource(source, 6);
  }

  private static void verifySource(Connection source, int version) throws SQLException {
    if (version < 2 || version > 6) throw failure();
    List<Table> expectedTables =
        TABLES.stream()
            .filter(table -> version >= 3 || !VERSION_THREE_TABLES.contains(table.name()))
            .filter(table -> version >= 5 || !VERSION_FIVE_TABLES.contains(table.name()))
            .filter(table -> version >= 6 || !VERSION_SIX_TABLES.contains(table.name()))
            .toList();
    Set<String> required = new LinkedHashSet<>();
    required.add("gateway_schema");
    expectedTables.forEach(table -> required.add(table.name()));
    Set<String> actual = new LinkedHashSet<>();
    try (var statement =
            source.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'");
        var rows = statement.executeQuery()) {
      while (rows.next()) actual.add(rows.getString(1).toLowerCase(Locale.ROOT));
    }
    if (!required.equals(actual)) throw failure();
    verifySchema(source, version);
    for (Table table : expectedTables) {
      Set<String> columns = new LinkedHashSet<>();
      try (var query = source.prepareStatement("SELECT * FROM " + table.name() + " WHERE 1=0");
          var result = query.executeQuery()) {
        var metadata = result.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++)
          columns.add(metadata.getColumnName(i).toLowerCase(Locale.ROOT));
      }
      var expectedColumns =
          table.columns().stream()
              .map(Column::name)
              .filter(
                  name ->
                      (version >= 4
                              || !List.of("request_json", "raw_model_output", "completed_at")
                                  .contains(name))
                          && (version >= 6
                              || !table.name().equals("orders")
                              || !List.of("package_name", "currency").contains(name)))
              .toList();
      if (!columns.equals(new LinkedHashSet<>(expectedColumns))) throw failure();
    }
    if (version >= 6
        && number(
                source,
                "SELECT COUNT(*) FROM orders WHERE package_name IS NULL OR currency IS NULL")
            != 0) throw failure();
    if (number(source, "SELECT COUNT(*) FROM accounts WHERE role='ADMIN'") < 1) throw failure();
  }

  private static int sourceSchemaVersion(Connection source) throws SQLException {
    try (var query = source.prepareStatement("SELECT id,version FROM gateway_schema");
        var rows = query.executeQuery()) {
      if (!rows.next() || rows.getInt("id") != 1) throw failure();
      int version = rows.getInt("version");
      if (rows.next() || version < 2 || version > 6) throw failure();
      return version;
    }
  }

  private static void verifyTargetBeforeInitialization(Connection target) throws SQLException {
    if (!"MySQL".equals(target.getMetaData().getDatabaseProductName())) throw failure();
    Set<String> allowed = new LinkedHashSet<>();
    allowed.add("gateway_schema");
    allowed.add("gateway_storage_state");
    TABLES.forEach(table -> allowed.add(table.name()));
    Set<String> existing = new LinkedHashSet<>();
    try (var query =
            target.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE()");
        var rows = query.executeQuery()) {
      while (rows.next()) existing.add(rows.getString(1).toLowerCase(Locale.ROOT));
    }
    if (!allowed.containsAll(existing)) throw failure();
    if (existing.contains("gateway_storage_state")
        && number(target, "SELECT COUNT(*) FROM gateway_storage_state") != 0)
      requirePending(target);
    for (String name : existing) {
      if (name.equals("gateway_storage_state")) continue;
      if (name.equals("gateway_schema")) {
        if (number(target, "SELECT COUNT(*) FROM gateway_schema") != 0)
          verifyUpgradeableTargetSchema(target);
      } else if (name.equals("gateway_model_policy")
          || name.equals("gateway_rubric_model_policy")) {
        if (number(target, "SELECT COUNT(*) FROM " + name) != 0) verifyDefaultPolicy(target, name);
      } else if (number(target, "SELECT COUNT(*) FROM " + name) != 0) throw failure();
    }
  }

  /** Allows an empty PENDING target to resume additive 2/3/4/5 to 6 initialization. */
  private static void verifyUpgradeableTargetSchema(Connection target) throws SQLException {
    try (var query = target.prepareStatement("SELECT id,version FROM gateway_schema");
        var rows = query.executeQuery()) {
      if (!rows.next() || rows.getInt("id") != 1) throw failure();
      int version = rows.getInt("version");
      if (rows.next() || version < 2 || version > 6) throw failure();
    }
  }

  private static void verifyPristineBusinessTables(Connection target) throws SQLException {
    verifySchema(target);
    for (Table table : TABLES) {
      if (table.name().equals("gateway_model_policy")
          || table.name().equals("gateway_rubric_model_policy"))
        verifyDefaultPolicy(target, table.name());
      else if (table.name().equals("credit_packages")) verifyDefaultPackages(target);
      else if (number(target, "SELECT COUNT(*) FROM " + table.name()) != 0) throw failure();
    }
  }

  /** H2 test/legacy stores seed the public catalogue on open; accept only that exact seed. */
  private static void verifyDefaultPackages(Connection connection) throws SQLException {
    long count = number(connection, "SELECT COUNT(*) FROM credit_packages");
    if (count == 0) return;
    if (count != GatewayStore.DEFAULT_CREDIT_PACKAGES.size()) throw failure();
    try (var query =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM credit_packages WHERE id=? AND name=? AND credits=?"
                + " AND price_minor IS NULL AND currency=? AND enabled=TRUE AND revision=1"
                + " AND created_at=updated_at AND deleted_at IS NULL")) {
      for (var pack : GatewayStore.DEFAULT_CREDIT_PACKAGES) {
        query.setString(1, pack.id());
        query.setString(2, pack.name());
        query.setLong(3, pack.credits());
        query.setString(4, pack.currency());
        try (var rows = query.executeQuery()) {
          if (!rows.next() || rows.getLong(1) != 1) throw failure();
        }
      }
    }
  }

  private static void verifyDefaultPolicy(Connection connection, String table) throws SQLException {
    if (!List.of("gateway_model_policy", "gateway_rubric_model_policy").contains(table))
      throw failure();
    if (number(connection, "SELECT COUNT(*) FROM " + table) != 1
        || number(
                connection,
                "SELECT COUNT(*) FROM "
                    + table
                    + " WHERE id=1 AND revision=0 AND profile_id IS NULL AND model_id IS NULL")
            != 1) throw failure();
  }

  private static void requirePending(Connection connection) throws SQLException {
    if (number(connection, "SELECT COUNT(*) FROM gateway_storage_state") != 1
        || number(
                connection,
                "SELECT COUNT(*) FROM gateway_storage_state WHERE id=1 AND migration_state='PENDING' AND migration_source IS NULL AND ready_at IS NULL")
            != 1) throw failure();
  }

  private static void verifySchema(Connection connection) throws SQLException {
    verifySchema(connection, 6);
  }

  private static void verifySchema(Connection connection, int version) throws SQLException {
    if (number(connection, "SELECT COUNT(*) FROM gateway_schema") != 1
        || number(
                connection, "SELECT COUNT(*) FROM gateway_schema WHERE id=1 AND version=" + version)
            != 1) throw failure();
  }

  private static void copyTable(Connection source, Connection target, Table table)
      throws SQLException {
    String placeholders =
        String.join(",", java.util.Collections.nCopies(table.columns().size(), "?"));
    try (var read = source.prepareStatement("SELECT " + table.fields() + " FROM " + table.name());
        var insert =
            target.prepareStatement(
                "INSERT INTO "
                    + table.name()
                    + " ("
                    + table.fields()
                    + ") VALUES ("
                    + placeholders
                    + ")");
        var rows = read.executeQuery()) {
      while (rows.next()) {
        List<byte[]> sensitive = new ArrayList<>();
        try {
          for (int i = 1; i <= table.columns().size(); i++) {
            switch (table.columns().get(i - 1).kind()) {
              case TEXT -> insert.setString(i, rows.getString(i));
              case NUMBER -> {
                long value = rows.getLong(i);
                if (rows.wasNull()) insert.setNull(i, Types.BIGINT);
                else insert.setLong(i, value);
              }
              case BOOLEAN -> {
                boolean value = rows.getBoolean(i);
                if (rows.wasNull()) insert.setNull(i, Types.BOOLEAN);
                else insert.setBoolean(i, value);
              }
              case BINARY -> {
                byte[] value = rows.getBytes(i);
                if (value != null) sensitive.add(value);
                insert.setBytes(i, value);
              }
            }
          }
          if (insert.executeUpdate() != 1) throw failure();
        } finally {
          sensitive.forEach(value -> Arrays.fill(value, (byte) 0));
        }
      }
    }
  }

  private static TableFingerprint fingerprint(Connection connection, Table table) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long count = 0;
    try (var query =
            connection.prepareStatement(
                "SELECT "
                    + table.fields()
                    + " FROM "
                    + table.name()
                    + " ORDER BY "
                    + table.ordering());
        var rows = query.executeQuery()) {
      while (rows.next()) {
        count++;
        for (int i = 1; i <= table.columns().size(); i++) {
          byte[] value =
              switch (table.columns().get(i - 1).kind()) {
                case TEXT -> {
                  String text = rows.getString(i);
                  yield text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                }
                case BINARY -> rows.getBytes(i);
                case NUMBER -> {
                  long number = rows.getLong(i);
                  yield rows.wasNull() ? null : ByteBuffer.allocate(8).putLong(number).array();
                }
                case BOOLEAN -> {
                  boolean flag = rows.getBoolean(i);
                  yield rows.wasNull() ? null : new byte[] {(byte) (flag ? 1 : 0)};
                }
              };
          digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
          if (value != null) {
            digest.update(value);
            Arrays.fill(value, (byte) 0);
          }
        }
      }
    }
    return new TableFingerprint(count, HexFormat.of().formatHex(digest.digest()));
  }

  private static LedgerSummary ledger(Connection connection) throws SQLException {
    return new LedgerSummary(
        number(connection, "SELECT COUNT(*) FROM accounts"),
        number(connection, "SELECT COUNT(*) FROM accounts WHERE role='ADMIN'"),
        sum(connection, "SELECT COALESCE(SUM(balance),0) FROM accounts"),
        sum(connection, "SELECT COALESCE(SUM(delta),0) FROM adjustments"),
        sum(connection, "SELECT COALESCE(SUM(delta),0) FROM quota_events"),
        number(
            connection,
            "SELECT (SELECT COUNT(*) FROM grade_requests WHERE charged=TRUE) + (SELECT COUNT(*) FROM rubric_generation_requests WHERE charged=TRUE)"),
        sum(
            connection,
            "SELECT COALESCE(SUM(units),0) FROM orders WHERE status='PENDING_PAYMENT'"));
  }

  private static long number(Connection connection, String sql) throws SQLException {
    try (var query = connection.prepareStatement(sql);
        var result = query.executeQuery()) {
      if (!result.next()) throw failure();
      return result.getLong(1);
    }
  }

  private static BigDecimal sum(Connection connection, String sql) throws SQLException {
    try (var query = connection.prepareStatement(sql);
        var result = query.executeQuery()) {
      if (!result.next()) throw failure();
      return result.getBigDecimal(1).stripTrailingZeros();
    }
  }

  private static Path defaultTemporaryRoot() {
    String configured = System.getProperty("java.io.tmpdir");
    if (configured == null || configured.isBlank()) throw failure();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static SourceSnapshot sourceSnapshot(Path file) throws Exception {
    return new SourceSnapshot(
        fileHash(file),
        Files.size(file),
        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
  }

  private static void requireUnchanged(Path file, SourceSnapshot expected) throws Exception {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)
        || Files.size(file) != expected.bytes()
        || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).equals(expected.modifiedAt())
        || !fileHash(file).equals(expected.sha256())) throw failure();
  }

  /** Deletes only files created for one random staging database and never follows links. */
  private static void deleteTemporaryDirectory(Path temporaryRoot, Path temporaryDirectory)
      throws IOException {
    Path root = temporaryRoot.toAbsolutePath().normalize();
    Path directory = temporaryDirectory.toAbsolutePath().normalize();
    if (directory.equals(root)
        || !root.equals(directory.getParent())
        || !directory.getFileName().toString().startsWith(TEMPORARY_PREFIX)
        || Files.isSymbolicLink(directory)) throw new IOException("Unsafe migration staging path");
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Invalid migration staging directory");
    try (var children = Files.newDirectoryStream(directory)) {
      for (Path child : children) {
        Path normalized = child.toAbsolutePath().normalize();
        String name = normalized.getFileName().toString();
        if (!directory.equals(normalized.getParent())
            || !name.startsWith(TEMPORARY_DATABASE + ".")
            || Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Unexpected migration staging entry");
        Files.deleteIfExists(normalized);
      }
    }
    Files.delete(directory);
  }

  private static String fileHash(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] block = new byte[65536];
      int read;
      while ((read = input.read(block)) != -1) digest.update(block, 0, read);
      Arrays.fill(block, (byte) 0);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static Table table(String name, String ordering, String specification) {
    List<Column> columns =
        Arrays.stream(specification.split(","))
            .map(
                field -> {
                  String[] parts = field.split(":");
                  Kind kind =
                      switch (parts[1]) {
                        case "t" -> Kind.TEXT;
                        case "n" -> Kind.NUMBER;
                        case "b" -> Kind.BINARY;
                        case "z" -> Kind.BOOLEAN;
                        default -> throw new IllegalArgumentException();
                      };
                  return new Column(parts[0], kind);
                })
            .toList();
    return new Table(name, ordering, columns);
  }

  private static Map<String, String> options(String[] arguments) {
    if (arguments.length != 6) throw failure();
    Map<String, String> options = new LinkedHashMap<>();
    Set<String> accepted = Set.of("--source-h2", "--storage-config", "--report");
    for (int i = 0; i < arguments.length; i += 2) {
      if (!accepted.contains(arguments[i])
          || arguments[i + 1].isBlank()
          || options.putIfAbsent(arguments[i], arguments[i + 1]) != null) throw failure();
    }
    return options;
  }

  private static GatewayException failure() {
    return new GatewayException(STORAGE_FAILURE);
  }
}
