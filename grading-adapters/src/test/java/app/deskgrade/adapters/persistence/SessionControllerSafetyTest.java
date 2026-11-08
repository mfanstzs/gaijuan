package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import app.deskgrade.core.port.GradingProvider;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControllerSafetyTest {
  @TempDir Path directory;

  private static SessionSettings config(long revision, BigDecimal budget, Duration poll) {
    var base = settings(5);
    return new SessionSettings(
        base.question(),
        base.modelProfileId(),
        revision,
        base.platformRevision(),
        "{\"modelRevision\":" + revision + "}",
        5,
        10,
        0,
        budget,
        poll,
        Duration.ofSeconds(3));
  }

  private SessionController controller(
      SessionPlatform platform,
      H2SessionJournal journal,
      GradingProvider provider,
      SessionSettings settings) {
    return new SessionController(
        platform,
        journal,
        (run, attempt, capture) -> "synthetic.png",
        provider,
        result -> result.status() == GradeResult.Status.UNABLE ? null : new BigDecimal("4"),
        settings,
        Clock.systemUTC(),
        () -> true,
        s -> {});
  }

  private static void awaitState(SessionController controller, SessionState state)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (controller.status().state() != state && System.nanoTime() < until) Thread.sleep(5);
    assertEquals(state, controller.status().state(), controller.status().toString());
  }

  @Test
  void scheduledPollFromOldEpochCannotStartSecondGradingChainAfterResume() throws Exception {
    var platform = new FakePlatform(5);
    var count = new AtomicInteger();
    var called = new CountDownLatch(1);
    GradingProvider delayed =
        (request, token) -> {
          count.incrementAndGet();
          called.countDown();
          return new CompletableFuture<>();
        };
    try (var journal = new H2SessionJournal(directory.resolve("poll"));
        var controller =
            controller(platform, journal, delayed, config(1, null, Duration.ofMillis(75)))) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      controller.pause(PauseReason.USER_PAUSED);
      awaitState(controller, SessionState.PAUSED);
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertTrue(called.await(3, TimeUnit.SECONDS));
      Thread.sleep(250);
      assertEquals(1, count.get());
      assertEquals(1, journal.attempts(controller.status().runId()).size());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void unchangedRevisionCannotInstallNewProviderAndFailStopsWithoutResume() throws Exception {
    Path database = directory.resolve("unchanged-config");
    var platform = new FakePlatform(5);
    var originalCalls = new AtomicInteger();
    var replacementCalls = new AtomicInteger();
    var originalRequest = new AtomicReference<UUID>();
    var originalResponse = new CompletableFuture<GradingResponse>();
    GradingProvider original =
        (request, token) -> {
          originalCalls.incrementAndGet();
          originalRequest.set(request.requestId());
          return originalResponse;
        };
    GradingProvider replacement =
        (request, token) -> {
          replacementCalls.incrementAndGet();
          return new CompletableFuture<>();
        };
    var initial = config(1, null, Duration.ofMillis(2));
    try (var journal = new H2SessionJournal(database);
        var controller = controller(platform, journal, original, initial)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitState(controller, SessionState.GRADING);
      long activeEpoch = controller.actionEpoch();
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  controller
                      .changeConfiguration(initial, replacement, r -> new BigDecimal("4"))
                      .toCompletableFuture()
                      .get(2, TimeUnit.SECONDS));
      assertInstanceOf(IllegalArgumentException.class, failure.getCause());
      awaitState(controller, SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertTrue(
          controller.actionEpoch() > activeEpoch, "configuration failure must revoke the gate");

      originalResponse.complete(
          new GradingResponse(
              originalRequest.get(), GradingResponse.Source.MOCK, RESULT, Instant.now()));
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      Thread.sleep(100);

      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(0, replacementCalls.get());
      assertEquals(1, originalCalls.get());
      assertEquals(0, platform.inputs.get());
    }
    assertEquals(List.of(initial.configurationJson()), savedConfigurationJson(database));
  }

  @Test
  void failedPersistenceCannotInstallNewProviderAndRetainsSavedConfiguration() throws Exception {
    Path database = directory.resolve("failed-persistence");
    var platform = new FakePlatform(5);
    var originalCalls = new AtomicInteger();
    var replacementCalls = new AtomicInteger();
    GradingProvider original =
        (request, token) -> {
          originalCalls.incrementAndGet();
          return new CompletableFuture<>();
        };
    GradingProvider replacement =
        (request, token) -> {
          replacementCalls.incrementAndGet();
          return new CompletableFuture<>();
        };
    var initial = config(1, null, Duration.ofMillis(2));
    var next = config(2, null, Duration.ofMillis(2));
    var reserved =
        new SessionSettings(
            next.question(),
            next.modelProfileId(),
            2,
            next.platformRevision(),
            "{\"reservedDifferentSnapshot\":true}",
            next.maxSubmissions(),
            next.maxCalls(),
            0,
            null,
            next.pollInterval(),
            next.pageTimeout());
    try (var journal = new H2SessionJournal(database);
        var controller = controller(platform, journal, original, initial)) {
      controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitState(controller, SessionState.GRADING);
      journal.recordConfiguration(controller.status().runId(), reserved, Instant.now());
      long activeEpoch = controller.actionEpoch();
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  controller
                      .changeConfiguration(next, replacement, r -> new BigDecimal("4"))
                      .toCompletableFuture()
                      .get(2, TimeUnit.SECONDS));
      var storageFailure = assertInstanceOf(DeskGradeException.class, failure.getCause());
      assertEquals(ErrorCode.STORAGE_FAILURE, storageFailure.code());
      awaitState(controller, SessionState.STOPPED);
      assertEquals(PauseReason.STORAGE_UNAVAILABLE, controller.status().reason());
      assertTrue(
          controller.actionEpoch() > activeEpoch, "persistence failure must revoke the gate");

      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      Thread.sleep(100);

      assertEquals(SessionState.STOPPED, controller.status().state());
      assertEquals(PauseReason.STORAGE_UNAVAILABLE, controller.status().reason());
      assertEquals(0, replacementCalls.get());
      assertEquals(1, originalCalls.get());
      assertEquals(0, platform.inputs.get());
    }
    assertEquals(
        List.of(initial.configurationJson(), reserved.configurationJson()),
        savedConfigurationJson(database));
  }

  private static List<String> savedConfigurationJson(Path database) throws Exception {
    String url =
        "jdbc:h2:file:"
            + database.toAbsolutePath().normalize()
            + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;IFEXISTS=TRUE";
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT json FROM config_document "
                        + "WHERE kind='RUN_CONFIGURATION' ORDER BY revision")) {
      List<String> configurations = new ArrayList<>();
      while (rows.next()) configurations.add(rows.getString(1));
      return configurations;
    }
  }

  @Test
  void unableAndInvalidResultsStillConsumeKnownBudget() throws Exception {
    var platform = new FakePlatform(5);
    GradeResult unable =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            null,
            List.of(new GradeResult.CriterionResult("p", null, null, "无法辨认")),
            List.of("不可读"));
    GradingProvider provider =
        (request, token) ->
            CompletableFuture.completedFuture(
                new GradingResponse(
                    request.requestId(),
                    GradingResponse.Source.MOCK,
                    unable,
                    Instant.now(),
                    new GradingResponse.Usage(1L, 1L, new BigDecimal("0.60")),
                    Duration.ofMillis(1)));
    try (var journal = new H2SessionJournal(directory.resolve("unable"));
        var controller =
            controller(
                platform,
                journal,
                provider,
                config(1, new BigDecimal("0.50"), Duration.ofMillis(2)))) {
      controller.start();
      awaitState(controller, SessionState.PAUSED);
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      awaitState(controller, SessionState.PAUSED);
      assertEquals(1, journal.runs().getFirst().modelCalls());
      assertEquals(0, platform.inputs.get());
    }
  }

  @Test
  void lateOldModelCostRevokesCurrentBudgetWithoutOverwritingScore() throws Exception {
    var platform = new FakePlatform(5);
    var old = new CompletableFuture<GradingResponse>();
    var requestId = new AtomicReference<UUID>();
    GradingProvider oldProvider =
        (request, token) -> {
          requestId.set(request.requestId());
          return old;
        };
    GradingProvider nextProvider = (request, token) -> new CompletableFuture<>();
    try (var journal = new H2SessionJournal(directory.resolve("late"));
        var controller =
            controller(
                platform,
                journal,
                oldProvider,
                config(1, new BigDecimal("0.50"), Duration.ofMillis(2)))) {
      controller.start();
      awaitState(controller, SessionState.GRADING);
      controller
          .changeConfiguration(
              config(2, new BigDecimal("0.50"), Duration.ofMillis(2)),
              nextProvider,
              r -> new BigDecimal("4"))
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
      controller.resume();
      awaitState(controller, SessionState.GRADING);
      old.complete(
          new GradingResponse(
              requestId.get(),
              GradingResponse.Source.MOCK,
              RESULT,
              Instant.now(),
              new GradingResponse.Usage(1L, 1L, new BigDecimal("0.60")),
              Duration.ofMillis(1)));
      awaitState(controller, SessionState.PAUSED);
      assertEquals(PauseReason.LIMIT_REACHED, controller.status().reason());
      assertNull(controller.lastResponse());
      assertEquals(0, platform.inputs.get());
    }
  }
}
