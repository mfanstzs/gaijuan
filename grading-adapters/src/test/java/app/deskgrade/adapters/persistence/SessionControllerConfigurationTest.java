package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.port.GradingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class SessionControllerConfigurationTest {
  @TempDir Path directory;

  @Test
  void sameProfileCanReplaceItsProviderAndResumeAfterItsSessionGenerationIncreases()
      throws Exception {
    var platform = new FakePlatform(1);
    var oldCalled = new CountDownLatch(1);
    var oldCalls = new AtomicInteger();
    var newCalls = new AtomicInteger();
    GradingProvider oldProvider =
        (request, token) -> {
          oldCalls.incrementAndGet();
          oldCalled.countDown();
          return new CompletableFuture<>();
        };
    GradingProvider replacement =
        (request, token) -> {
          newCalls.incrementAndGet();
          return CompletableFuture.completedFuture(
              new GradingResponse(
                  request.requestId(), GradingResponse.Source.MOCK, RESULT, Instant.now()));
        };
    var initial = generation(4);
    var resumed = generation(5);
    assertEquals(initial.modelProfileId(), resumed.modelProfileId());
    try (var journal = new H2SessionJournal(directory.resolve("configuration-resume"));
        var controller =
            new SessionController(
                platform,
                journal,
                (run, attempt, capture) -> "synthetic.png",
                oldProvider,
                ignored -> BigDecimal.ZERO,
                initial,
                Clock.systemUTC(),
                () -> true,
                ignored -> {})) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertTrue(oldCalled.await(3, TimeUnit.SECONDS));
      long oldEpoch = controller.actionEpoch();
      controller
          .changeConfiguration(
              resumed, replacement, result -> result.criteria().getFirst().awarded())
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.PAUSED, controller.status().state());
      assertEquals(PauseReason.CONFIG_CHANGED, controller.status().reason());
      assertTrue(controller.actionEpoch() > oldEpoch);
      assertEquals(0, newCalls.get(), "Installing configuration does not itself authorize resume");
      assertEquals(0, platform.inputs.get());

      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitState(controller, SessionState.COMPLETED);
      assertEquals(1, oldCalls.get());
      assertEquals(1, newCalls.get());
      assertEquals(2, controller.status().modelCalls());
      assertEquals(1, controller.status().confirmedSubmissions());
      assertEquals(List.of(new BigDecimal("4")), platform.ledger.get(1));
      var attempts = journal.attempts(controller.status().runId());
      assertEquals(2, attempts.size());
      var completed =
          attempts.stream().filter(attempt -> attempt.confirmed()).findFirst().orElseThrow();
      var audit = new ObjectMapper().readTree(journal.attemptAuditJson(completed.attemptId()));
      assertEquals(5, audit.path("configuration").path("sessionGeneration").asLong());
      assertEquals(37, audit.path("configuration").path("profile").path("revision").asLong());
      assertEquals(0, new BigDecimal("4").compareTo(completed.score()));
    }
  }

  private static SessionSettings generation(long generation) {
    var base = settings(1);
    return new SessionSettings(
        base.question(),
        "same-server-profile",
        generation,
        generation,
        "{\"sessionGeneration\":"
            + generation
            + ",\"profile\":{\"serverProfileId\":\"same-server-profile\",\"revision\":37}}",
        1,
        10,
        0,
        null,
        Duration.ofMillis(2),
        Duration.ofSeconds(3));
  }

  private static void awaitState(SessionController controller, SessionState state)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (controller.status().state() != state && System.nanoTime() < deadline) Thread.sleep(5);
    assertEquals(state, controller.status().state(), controller.status().toString());
  }
}
