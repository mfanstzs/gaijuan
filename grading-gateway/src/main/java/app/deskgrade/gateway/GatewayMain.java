package app.deskgrade.gateway;

import app.deskgrade.adapters.model.ProfileJsonCodec;
import app.deskgrade.adapters.persistence.MySqlSessionJournal;
import app.deskgrade.adapters.runtime.RuntimeSocketDirectory;
import app.deskgrade.adapters.windows.security.DpapiSecretStore;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.port.SecretStore;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;

/**
 * Independent server CLI. No default administrator, embedded supplier key, or automatic live
 * enable.
 */
public final class GatewayMain {
  private GatewayMain() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception | LinkageError failure) {
      System.err.println(
          "Gateway startup or command failed; check arguments, local permissions, and configuration. No credential details are printed.");
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    if (args.length == 0 || !Set.of("serve", "bootstrap-admin", "admin-status").contains(args[0]))
      throw new IllegalArgumentException();
    var options = new HashMap<String, String>();
    boolean allowLive = false;
    boolean developmentH2 = false;
    for (int i = 1; i < args.length; i++) {
      String key = args[i];
      if (key.equals("--development-h2")) {
        if (developmentH2) throw new IllegalArgumentException();
        developmentH2 = true;
        continue;
      }
      if (key.equals("--allow-live")) {
        if (allowLive) throw new IllegalArgumentException();
        allowLive = true;
        continue;
      }
      if (!Set.of(
                  "--data-dir",
                  "--port",
                  "--profiles",
                  "--credentials-dir",
                  "--username",
                  "--storage-config")
              .contains(key)
          || i + 1 >= args.length
          || options.putIfAbsent(key, args[++i]) != null) throw new IllegalArgumentException();
    }
    if (!options.containsKey("--data-dir")) throw new IllegalArgumentException();
    Path data = Path.of(options.get("--data-dir")).toAbsolutePath().normalize();
    if (options.containsKey("--storage-config")) {
      if (developmentH2) throw new IllegalArgumentException();
      runProduction(args[0], options, allowLive);
      return;
    }
    // A missing production configuration must never silently start an empty local account store.
    if (!developmentH2)
      throw new IllegalArgumentException("MySQL storage configuration is required");
    if (args[0].equals("admin-status")) {
      if (allowLive || options.size() != 1) throw new IllegalArgumentException();
      System.out.println(
          GatewayStore.adminPresent(data.resolve("gateway")) ? "ADMIN_READY" : "ADMIN_REQUIRED");
      return;
    }
    if (args[0].equals("bootstrap-admin")) {
      if (allowLive || options.size() != 2 || !options.containsKey("--username"))
        throw new IllegalArgumentException();
      Console console = System.console();
      if (console == null) throw new IllegalStateException();
      char[] password = console.readPassword("管理员密码（15–128 位，输入时不显示字符；输入完成后按回车）：");
      char[] confirmation = null;
      try {
        confirmation = console.readPassword("请再次输入相同密码，然后按回车：");
        if (password == null || confirmation == null || !Arrays.equals(password, confirmation))
          throw new IllegalArgumentException();
        try (var store = new GatewayStore(data.resolve("gateway"), Clock.systemUTC())) {
          store.bootstrapAdmin(options.get("--username"), password);
        }
        console.writer().println("管理员创建成功。初始额度为 0，可登录管理后台配置模型和用户额度。");
        console.writer().flush();
      } finally {
        if (password != null) Arrays.fill(password, '\0');
        if (confirmation != null) Arrays.fill(confirmation, '\0');
      }
      return;
    }
    if (options.containsKey("--username")) throw new IllegalArgumentException();
    configureHttpServerLimits();
    RuntimeSocketDirectory.initialize();
    int port = Integer.parseInt(options.getOrDefault("--port", "8787"));
    if (port < 1 || port > 65535) throw new IllegalArgumentException();
    List<ProviderProfile> profiles = List.of();
    if (options.containsKey("--profiles")) {
      Path file = Path.of(options.get("--profiles"));
      if (Files.size(file) > 1024 * 1024) throw new IllegalArgumentException();
      profiles = new ProfileJsonCodec().readLocal(Files.readString(file));
    }
    for (var profile : profiles) ProductionModelEndpoints.requireAllowed(profile.endpoint());
    SecretStore secrets =
        options.containsKey("--credentials-dir")
            ? serverSecrets(Path.of(options.get("--credentials-dir")))
            : new EmptySecrets();
    try (var store = new GatewayStore(data.resolve("gateway"), Clock.systemUTC());
        var server =
            new GatewayServer(
                port,
                store,
                GatewayModelService.live(store, profiles, secrets, allowLive, Clock.systemUTC()),
                Clock.systemUTC())) {
      var stopped = new CountDownLatch(1);
      Thread hook =
          new Thread(
              () -> {
                try {
                  server.close();
                  store.close();
                } finally {
                  stopped.countDown();
                }
              },
              "gateway-shutdown");
      Runtime.getRuntime().addShutdownHook(hook);
      server.start();
      System.out.println(
          "DeskGrade gateway listening on 127.0.0.1:" + server.port() + ". Payment is disabled.");
      try {
        stopped.await();
      } finally {
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
        }
      }
    }
  }

  private static void runProduction(
      String command, java.util.Map<String, String> options, boolean allowLive) throws Exception {
    var storage = StorageConfiguration.load(Path.of(options.get("--storage-config")));
    try (var dataSource = storage.openDataSource()) {
      if (command.equals("admin-status")) {
        if (allowLive || options.size() != 2) throw new IllegalArgumentException();
        GatewayStore.verifyMySqlInstallation(dataSource);
        System.out.println("ADMIN_READY");
        return;
      }
      if (command.equals("bootstrap-admin")) {
        if (allowLive || options.size() != 3 || !options.containsKey("--username"))
          throw new IllegalArgumentException();
        bootstrapMySql(dataSource, options.get("--username"));
        return;
      }
      if (options.containsKey("--username")) throw new IllegalArgumentException();
      configureHttpServerLimits();
      RuntimeSocketDirectory.initialize();
      int port = Integer.parseInt(options.getOrDefault("--port", "8787"));
      if (port < 1 || port > 65535) throw new IllegalArgumentException();
      List<ProviderProfile> profiles = List.of();
      if (options.containsKey("--profiles")) {
        Path file = Path.of(options.get("--profiles"));
        if (Files.size(file) > 1024 * 1024) throw new IllegalArgumentException();
        profiles = new ProfileJsonCodec().readLocal(Files.readString(file));
      }
      for (var profile : profiles) ProductionModelEndpoints.requireAllowed(profile.endpoint());
      SecretStore secrets =
          options.containsKey("--credentials-dir")
              ? serverSecrets(Path.of(options.get("--credentials-dir")))
              : new EmptySecrets();
      try (var sessions = storage.openSessions(Clock.systemUTC());
          var store = new GatewayStore(dataSource, Clock.systemUTC(), sessions)) {
        try (var connection = dataSource.getConnection()) {
          MySqlSessionJournal.initializeSchema(connection);
        }
        try (var server =
            new GatewayServer(
                port,
                store,
                GatewayModelService.live(store, profiles, secrets, allowLive, Clock.systemUTC()),
                Clock.systemUTC(),
                dataSource)) {
          var stopped = new CountDownLatch(1);
          Thread hook =
              new Thread(
                  () -> {
                    try {
                      server.close();
                      store.close();
                    } finally {
                      stopped.countDown();
                    }
                  },
                  "gateway-shutdown");
          Runtime.getRuntime().addShutdownHook(hook);
          server.start();
          System.out.println(
              "DeskGrade gateway listening on 127.0.0.1:"
                  + port
                  + ". Storage: MySQL; sessions: Redis.");
          try {
            stopped.await();
          } finally {
            try {
              Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
            }
          }
        }
      }
    }
  }

  private static void bootstrapMySql(DataSource source, String username) throws Exception {
    if (username == null || !username.matches("[A-Za-z0-9_]{3,32}"))
      throw new IllegalArgumentException();
    GatewayStore.assertNewMySqlDatabase(source);
    Console console = System.console();
    if (console == null) throw new IllegalStateException();
    char[] password = console.readPassword("管理员密码（15–128位，输入不回显）：");
    char[] confirmation = null;
    try {
      confirmation = console.readPassword("请再次输入管理员密码：");
      if (password == null || !Arrays.equals(password, confirmation))
        throw new IllegalArgumentException();
      var encoded = PasswordHasher.create(password);
      try {
        GatewayStore.bootstrapNewMySql(
            source, username, encoded.salt(), encoded.hash(), encoded.iterations());
      } finally {
        Arrays.fill(encoded.salt(), (byte) 0);
        Arrays.fill(encoded.hash(), (byte) 0);
      }
      console.writer().println("MySQL 管理员创建成功。已有数据库不会被重置。");
    } finally {
      if (password != null) Arrays.fill(password, '\0');
      if (confirmation != null) Arrays.fill(confirmation, '\0');
    }
  }

  /** Must run before the first JDK HttpServer is created in this JVM. */
  static void configureHttpServerLimits() {
    System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
    System.setProperty("sun.net.httpserver.maxReqHeaderSize", "32768");
    // The grading gate is separately bounded. Keep enough connections for its largest supported
    // concurrency/queue combination plus health, authentication, and administration traffic.
    System.setProperty("jdk.httpserver.maxConnections", "256");
    System.setProperty("sun.net.httpserver.maxReqTime", "30");
    System.setProperty("sun.net.httpserver.maxRspTime", "660");
    System.setProperty("sun.net.httpserver.idleInterval", "30");
  }

  static SecretStore serverSecrets(Path directory) {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
        ? new DpapiSecretStore(directory)
        : new FileSecretStore(directory);
  }

  private static final class EmptySecrets implements SecretStore {
    public void put(String id, char[] secret) {
      throw new UnsupportedOperationException();
    }

    public Optional<char[]> read(String id) {
      return Optional.empty();
    }

    public void delete(String id) {
      throw new UnsupportedOperationException();
    }
  }
}
