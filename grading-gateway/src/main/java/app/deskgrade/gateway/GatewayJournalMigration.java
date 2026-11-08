package app.deskgrade.gateway;

import app.deskgrade.adapters.persistence.LegacyJournalImporter;
import app.deskgrade.adapters.persistence.MySqlSessionJournal;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Explicit offline import of the desktop record ledger into an already migrated MySQL service. */
public final class GatewayJournalMigration {
  public record MigrationReport(
      int schemaVersion,
      String status,
      String completedUtc,
      String sourceSha256Before,
      String sourceSha256After,
      long sourceBytes,
      boolean originalDatabaseUnchanged,
      boolean imageFilesRead,
      int importedRuns,
      int skippedRuns,
      int importedAttempts,
      int importedCalls,
      List<String> quarantinedRunIds) {
    public MigrationReport {
      quarantinedRunIds = List.copyOf(quarantinedRunIds);
    }
  }

  record Paths(Path sourceBase, Path sourceFile, Path storageConfiguration, Path report) {}

  private GatewayJournalMigration() {}

  public static void main(String[] arguments) {
    Path report = null;
    boolean importCompleted = false;
    try {
      var options = options(arguments);
      Paths paths =
          validatePaths(
              Path.of(options.get("--source-h2")),
              Path.of(options.get("--storage-config")),
              Path.of(options.get("--report")));
      report = paths.report();
      // CREATE_NEW prevents an existing report, source, or protected configuration being
      // overwritten.
      try (FileChannel output =
          FileChannel.open(report, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        writeReport(output, Map.of("schemaVersion", 1, "status", "RUNNING"));
        try {
          var configuration = StorageConfiguration.load(paths.storageConfiguration());
          try (var target = configuration.openDataSource()) {
            MigrationReport result = migrate(paths.sourceBase(), target);
            importCompleted = true;
            writeReport(output, result);
            System.out.println("DeskGrade journal migration: " + result.status());
            System.out.println("Report: " + report);
          }
        } catch (Exception problem) {
          if (!importCompleted) {
            try {
              writeReport(
                  output,
                  Map.of(
                      "schemaVersion",
                      1,
                      "status",
                      "FAILED_CHECKPOINTS_MAY_EXIST",
                      "originalSourceRetained",
                      true,
                      "note",
                      "No successful import is reported. Existing verified per-run checkpoints permit a deliberate retry."));
            } catch (Exception reportFailure) {
              /* Console still reports a safe failure category. */
            }
          }
          throw failure();
        }
      }
    } catch (Exception problem) {
      // JDBC exceptions can contain connection details and row contents. Never print them.
      System.err.println(
          "DeskGrade journal migration: "
              + (importCompleted ? "COMMITTED_REPORT_WRITE_FAILED" : "FAILED_NO_SUCCESS_REPORTED"));
      if (report != null) System.err.println("Report: " + report);
      System.exit(1);
    }
  }

  public static MigrationReport migrate(Path sourceBase, DataSource target) {
    try {
      Path base = validateSource(sourceBase);
      Path sourceFile = databaseFile(base);
      assertNotOpenForWriting(sourceFile);
      String before = fileHash(sourceFile);
      long size = Files.size(sourceFile);
      LegacyJournalImporter.Report imported;
      try (Connection source = openSource(base)) {
        verifySource(source);
        try (Connection destination = target.getConnection()) {
          Set<String> owners = requireReadyAccounts(destination);
          // This is the explicit migration path; opening a journal never initializes schemas.
          MySqlSessionJournal.initializeSchema(destination);
          imported = LegacyJournalImporter.importV2(source, destination, owners);
        }
      }
      String after = fileHash(sourceFile);
      if (size != Files.size(sourceFile) || !before.equals(after)) throw failure();
      return new MigrationReport(
          1,
          imported.quarantinedRunIds().isEmpty()
              ? "COMMITTED_SOURCE_VERIFIED"
              : "COMMITTED_WITH_QUARANTINE",
          Instant.now().toString(),
          before,
          after,
          size,
          true,
          false,
          imported.importedRuns(),
          imported.skippedRuns(),
          imported.importedAttempts(),
          imported.importedCalls(),
          imported.quarantinedRunIds());
    } catch (Exception problem) {
      throw failure();
    }
  }

  static Connection openSource(Path sourceBase) throws SQLException {
    Path base = validateSource(sourceBase);
    Connection source =
        DriverManager.getConnection(
            "jdbc:h2:file:"
                + base.toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "");
    source.setReadOnly(true);
    return source;
  }

  static Paths validatePaths(Path sourceBase, Path storageConfiguration, Path reportPath) {
    try {
      Path base = validateSource(sourceBase);
      Path source = databaseFile(base);
      Path config = storageConfiguration.toAbsolutePath().normalize();
      Path report = reportPath.toAbsolutePath().normalize();
      if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(config)
          || report.getParent() == null
          || !Files.isDirectory(report.getParent())
          || Files.exists(report, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(report)
          || report.equals(base)
          || report.equals(source)
          || report.equals(config)
          || Files.isSameFile(config, source)) throw failure();
      config = config.toRealPath();
      report = report.getParent().toRealPath().resolve(report.getFileName());
      if (report.equals(base) || report.equals(source) || report.equals(config)) throw failure();
      return new Paths(base, source, config, report);
    } catch (Exception problem) {
      throw failure();
    }
  }

  private static Path validateSource(Path sourceBase) {
    try {
      Path base = sourceBase.toAbsolutePath().normalize();
      if (base.getParent() == null
          || base.getFileName() == null
          || base.toString().contains(";")
          || base.toString().indexOf('\n') >= 0
          || base.toString().indexOf('\r') >= 0
          || base.toString().endsWith(".mv.db")) throw failure();
      Path file = databaseFile(base);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(file)
          || Files.size(file) <= 0) throw failure();
      Path real = file.toRealPath();
      if (real.toString().contains(";")
          || real.toString().indexOf('\n') >= 0
          || real.toString().indexOf('\r') >= 0) throw failure();
      String name = real.getFileName().toString();
      return real.resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
    } catch (Exception problem) {
      throw failure();
    }
  }

  private static Path databaseFile(Path base) {
    return base.resolveSibling(base.getFileName() + ".mv.db");
  }

  private static void assertNotOpenForWriting(Path source) throws Exception {
    // Probe only: never create/delete a lock file alongside the original database.
    try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);
        var lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
      if (lock == null) throw failure();
    }
  }

  private static void verifySource(Connection source) throws SQLException {
    try (var query =
            source.prepareStatement(
                "SELECT kind,revision,json FROM config_document WHERE id='schema-version'");
        var row = query.executeQuery()) {
      if (!row.next()
          || !"MIGRATION".equals(row.getString(1))
          || row.getLong(2) != 2
          || !"2".equals(row.getString(3))
          || row.next()) throw failure();
    }
    for (String table : List.of("grading_run", "grading_attempt", "model_call", "run_event")) {
      try (var query = source.prepareStatement("SELECT * FROM " + table + " WHERE 1=0")) {
        query.executeQuery().close();
      }
    }
  }

  private static Set<String> requireReadyAccounts(Connection target) throws SQLException {
    if (!"MySQL".equals(target.getMetaData().getDatabaseProductName()) || !target.getAutoCommit())
      throw failure();
    try (var query = target.prepareStatement("SELECT id,version FROM gateway_schema");
        var row = query.executeQuery()) {
      // Journal import runs only after the commercial store has completed every current migration.
      // Accepting an older READY layout could write records beside a schema the running gateway no
      // longer understands.
      if (!row.next() || row.getInt(1) != 1 || row.getInt(2) != 6 || row.next()) throw failure();
    }
    try (var query =
            target.prepareStatement("SELECT id,migration_state FROM gateway_storage_state");
        var row = query.executeQuery()) {
      if (!row.next() || row.getInt(1) != 1 || !"READY".equals(row.getString(2)) || row.next())
        throw failure();
    }
    Set<String> accounts = new LinkedHashSet<>();
    try (var query = target.prepareStatement("SELECT id FROM accounts");
        var rows = query.executeQuery()) {
      while (rows.next()) accounts.add(rows.getString(1));
    }
    return Set.copyOf(accounts);
  }

  private static String fileHash(Path file) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] block = new byte[65536];
      try {
        int count;
        while ((count = input.read(block)) != -1) hash.update(block, 0, count);
      } finally {
        Arrays.fill(block, (byte) 0);
      }
    }
    return HexFormat.of().formatHex(hash.digest());
  }

  private static void writeReport(FileChannel output, Object report) throws Exception {
    byte[] json = GatewayJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
    output.position(0);
    output.truncate(0);
    ByteBuffer buffer = ByteBuffer.wrap(json);
    while (buffer.hasRemaining()) output.write(buffer);
    output.force(true);
  }

  private static Map<String, String> options(String[] arguments) {
    if (arguments.length != 6) throw failure();
    Map<String, String> parsed = new LinkedHashMap<>();
    Set<String> allowed = Set.of("--source-h2", "--storage-config", "--report");
    for (int index = 0; index < arguments.length; index += 2) {
      if (!allowed.contains(arguments[index])
          || arguments[index + 1].isBlank()
          || parsed.putIfAbsent(arguments[index], arguments[index + 1]) != null) throw failure();
    }
    return parsed;
  }

  private static GatewayException failure() {
    return new GatewayException(GatewayException.Code.STORAGE_FAILURE);
  }
}
