package app.deskgrade.adapters.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceGuardTest {
  @TempDir Path directory;

  @Test
  void secondInstanceCannotAcquireUntilFirstClosesAndLockFileIsNeverDeleted() throws Exception {
    var first = SingleInstanceGuard.tryAcquire(directory).orElseThrow();
    Path lockFile = directory.resolve(".deskgrade-instance.lock");
    assertTrue(Files.isRegularFile(lockFile));
    assertTrue(SingleInstanceGuard.tryAcquire(directory.resolve(".")).isEmpty());
    assertTrue(Files.isRegularFile(lockFile));
    first.close();
    first.close();
    assertTrue(Files.isRegularFile(lockFile));
    try (var next = SingleInstanceGuard.tryAcquire(directory).orElseThrow()) {
      assertTrue(SingleInstanceGuard.tryAcquire(directory).isEmpty());
    }
    assertTrue(Files.isRegularFile(lockFile));
  }

  @Test
  void independentDataDirectoriesDoNotBlockEachOther() throws Exception {
    try (var one = SingleInstanceGuard.tryAcquire(directory.resolve("中文 一")).orElseThrow();
        var two = SingleInstanceGuard.tryAcquire(directory.resolve("中文 二")).orElseThrow()) {
      assertNotEquals(one.dataDirectory(), two.dataDirectory());
    }
  }

  @Test
  void failedDirectoryOrLockFileDoesNotMasqueradeAsAnotherInstance() throws Exception {
    Path file = Files.writeString(directory.resolve("file"), "untouched");
    assertThrows(java.io.IOException.class, () -> SingleInstanceGuard.tryAcquire(file));
    Files.createDirectory(directory.resolve(".deskgrade-instance.lock"));
    assertThrows(java.io.IOException.class, () -> SingleInstanceGuard.tryAcquire(directory));
    assertEquals("untouched", Files.readString(file));
  }

  @Test
  void operatingSystemReclaimsFileLockAfterAbruptProcessDeath() throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child =
        new ProcessBuilder(
                executable, "-cp", classpath, Holder.class.getName(), directory.toString())
            .redirectErrorStream(true)
            .start();
    var reader =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    try {
      var ready =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return reader.readLine();
                } catch (java.io.IOException failure) {
                  throw new IllegalStateException(failure);
                }
              });
      assertEquals("LOCKED", ready.get(15, TimeUnit.SECONDS));
      assertTrue(SingleInstanceGuard.tryAcquire(directory).isEmpty());
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      assertFalse(child.isAlive());
      try (var recovered = awaitOperatingSystemUnlock(child)) {
        assertTrue(Files.isRegularFile(directory.resolve(".deskgrade-instance.lock")));
        assertTrue(
            SingleInstanceGuard.tryAcquire(directory).isEmpty(),
            "The recovered instance must actually own the exclusive lock");
      }
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      }
      reader.close();
    }
  }

  private SingleInstanceGuard awaitOperatingSystemUnlock(Process terminated) throws Exception {
    // LockFileEx documents that OS unlock after process death can lag process termination.
    // https://learn.microsoft.com/windows/win32/api/fileapi/nf-fileapi-lockfileex
    // Await the actual readiness condition; never delete the file or accept an unacquired lock.
    long started = System.nanoTime();
    long deadline = started + TimeUnit.SECONDS.toNanos(5);
    int attempts = 0;
    while (System.nanoTime() < deadline) {
      assertFalse(
          terminated.isAlive(), "Recovery polling is only allowed after confirmed process exit");
      attempts++;
      var acquired = SingleInstanceGuard.tryAcquire(directory);
      if (acquired.isPresent()) {
        var guard = acquired.orElseThrow();
        try {
          Files.createDirectories(Path.of("target"));
          Files.writeString(
              Path.of("target/single-instance-recovery.json"),
              "{\"processAlive\":false,\"lockReacquired\":true,\"attempts\":"
                  + attempts
                  + ",\"elapsedMillis\":"
                  + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                  + "}");
          return guard;
        } catch (Throwable failure) {
          guard.close();
          throw failure;
        }
      }
      Thread.sleep(25);
    }
    return fail("OS did not release the terminated process's file lock within 5 seconds");
  }

  public static final class Holder {
    public static void main(String[] args) throws Exception {
      try (var guard = SingleInstanceGuard.tryAcquire(Path.of(args[0])).orElseThrow()) {
        System.out.println("LOCKED");
        System.out.flush();
        new CountDownLatch(1).await();
      }
    }
  }
}
