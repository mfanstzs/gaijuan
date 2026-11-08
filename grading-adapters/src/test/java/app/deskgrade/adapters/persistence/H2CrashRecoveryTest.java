package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.H2TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.error.DeskGradeException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real process termination of a storage writer; this does not claim Windows GUI crash testing. */
class H2CrashRecoveryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(
      value = SessionState.class,
      names = {"FILLING", "SUBMIT_PREPARED", "SUBMIT_SENT", "WAIT_ACK", "WAIT_NEXT"})
  void forciblyKilledWriterRecoversConservatively(SessionState boundary) throws Exception {
    Path db = directory.resolve("crash-" + boundary);
    Path sockets = Files.createDirectories(directory.resolve("sockets"));
    String javaExecutable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child =
        new ProcessBuilder(
                javaExecutable,
                "-Djava.awt.headless=true",
                "-Djdk.net.unixdomain.tmpdir=" + sockets,
                "-cp",
                classpath,
                Writer.class.getName(),
                db.toString(),
                boundary.name())
            .redirectErrorStream(true)
            .start();
    try {
      var output =
          new BufferedReader(
              new InputStreamReader(
                  child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
      var line =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return output.readLine();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      assertEquals("DURABLE:" + boundary, line.get(20, TimeUnit.SECONDS));
      assertTrue(child.isAlive());
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      try (var journal = new H2SessionJournal(db)) {
        var run = journal.recoverInterrupted(Instant.now()).getFirst();
        var attempt = journal.attempts(RUN).getFirst();
        boolean unknown =
            boundary == SessionState.SUBMIT_PREPARED
                || boundary == SessionState.SUBMIT_SENT
                || boundary == SessionState.WAIT_ACK;
        assertEquals(unknown ? SessionState.SUBMISSION_UNKNOWN : SessionState.PAUSED, run.state());
        assertEquals(unknown, journal.hasUnknownSubmission(TARGET));
        assertEquals(boundary == SessionState.WAIT_NEXT, attempt.confirmed());
        assertEquals(boundary != SessionState.FILLING, attempt.submissionPrepared());
        assertEquals(boundary == SessionState.WAIT_NEXT ? 1 : 0, run.confirmedSubmissions());
        if (boundary != SessionState.FILLING) {
          assertThrows(
              DeskGradeException.class,
              () ->
                  journal.transition(
                      RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, Instant.now()));
        }
        assertEquals(0, run.modelCalls());
      }
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  public static final class Writer {
    public static void main(String[] args) throws Exception {
      var boundary = SessionState.valueOf(args[1]);
      try (var journal = new H2SessionJournal(Path.of(args[0]))) {
        begin(journal);
        journal.transition(RUN, ATTEMPT, SessionState.FILLING, PauseReason.NONE, NOW);
        if (boundary != SessionState.FILLING)
          journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_PREPARED, PauseReason.NONE, NOW);
        if (boundary == SessionState.SUBMIT_SENT
            || boundary == SessionState.WAIT_ACK
            || boundary == SessionState.WAIT_NEXT)
          journal.transition(RUN, ATTEMPT, SessionState.SUBMIT_SENT, PauseReason.NONE, NOW);
        if (boundary == SessionState.WAIT_ACK || boundary == SessionState.WAIT_NEXT)
          journal.transition(RUN, ATTEMPT, SessionState.WAIT_ACK, PauseReason.NONE, NOW);
        if (boundary == SessionState.WAIT_NEXT) {
          journal.confirmSubmission(RUN, ATTEMPT, NOW);
          journal.transition(RUN, ATTEMPT, SessionState.WAIT_NEXT, PauseReason.NONE, NOW);
        }
        System.out.println("DURABLE:" + boundary);
        System.out.flush();
        new CountDownLatch(1).await();
      }
    }
  }
}
