package app.deskgrade.adapters.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** A fresh JVM is essential: UnixDomainSockets caches its directory on first initialization. */
@EnabledOnOs(OS.WINDOWS)
class RuntimeSocketDirectoryWindowsIT {
  @Test
  @Timeout(45)
  void defaultBootstrapSupportsAnActualHttpClientWithoutAnyRequest() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp",
                classpath,
                Probe.class.getName())
            .redirectErrorStream(true);
    // The test must exercise initialize's defaults, not the parent's working -D override.
    for (String option :
        java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
      builder.environment().remove(option);
    Process child = builder.start();
    var output =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return child.getInputStream().readNBytes(16_385);
              } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
              }
            });
    try {
      assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Runtime bootstrap probe did not exit");
      byte[] captured = output.get(3, TimeUnit.SECONDS);
      assertTrue(captured.length <= 16_384, "Runtime probe exceeded bounded diagnostics");
      String diagnostic = new String(captured, StandardCharsets.UTF_8);
      assertEquals(0, child.exitValue(), diagnostic);
      assertTrue(diagnostic.lines().anyMatch("NIO_CLIENT_READY_NO_REQUEST"::equals), diagnostic);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Runtime probe did not terminate");
      }
      child.getOutputStream().close();
      child.getInputStream().close();
      child.getErrorStream().close();
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      if (System.getProperty("jdk.net.unixdomain.tmpdir") != null)
        throw new IllegalStateException("Probe must not inherit a socket directory override");
      Path selected = RuntimeSocketDirectory.initialize();
      System.out.println("socketDirectory=" + selected);
      try (var client = java.net.http.HttpClient.newHttpClient()) {
        // Construction exercises NIO's local pipe. No HTTP request or remote endpoint exists here.
        System.out.println("NIO_CLIENT_READY_NO_REQUEST");
      }
    }
  }
}
