package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end persistence with the real local journal, no model API or native desktop input. */
class LocalPerformancePersistenceIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path root;

  @Test
  void controllerSummariesAreWrittenOncePerAnswerUnderRootPerformanceAndSurviveReopen()
      throws Exception {
    var platform = new FakePlatform(2);
    try (var journal = open()) {
      try (var controller = controller(platform, journal)) {
        controller.start();
        await(controller, SessionState.COMPLETED);
      } // Serial close is a barrier after terminal publication and its final performance append.
      assertEquals(2, platform.ledger.size());
      assertTrue(platform.ledger.values().stream().allMatch(scores -> scores.size() == 1));
    }
    var original = performanceLines();
    assertEquals(2, original.size());
    assertBoundToTwoDistinctAttempts(original);
    assertFalse(Files.exists(root.resolve("records/performance")));
    assertFalse(Files.exists(root.resolve("database/performance")));
    try (var restarted = open()) {
      restarted.recoverInterrupted(java.time.Instant.now());
      var rows =
          restarted.queryRecords(GradingRecords.Owner.local(), GradingRecords.Query.firstPage());
      assertEquals(2, rows.total());
      assertTrue(
          rows.rows().stream().allMatch(r -> r.javaTotal().compareTo(new BigDecimal("4")) == 0));
      var attemptIds =
          new HashSet<>(rows.rows().stream().map(r -> r.attemptId().toString()).toList());
      assertEquals(
          attemptIds,
          new HashSet<>(original.stream().map(r -> r.path("attemptId").asText()).toList()));
    }
    assertEquals(
        original,
        performanceLines(),
        "Startup recovery cannot duplicate or erase performance history");
  }

  @Test
  void pausingAfterAcknowledgementThenObservingNextOnResumeDoesNotWriteAnswerTwice()
      throws Exception {
    var platform = new FakePlatform(2);
    var active = new AtomicReference<SessionController>();
    var paused = new AtomicBoolean();
    try (var journal = open()) {
      SessionJournal observed =
          (SessionJournal)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, arguments) -> {
                    Object result;
                    try {
                      result = method.invoke(journal, arguments);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                    if (method.getName().equals("confirmSubmission")
                        && paused.compareAndSet(false, true))
                      active.get().pause(PauseReason.USER_PAUSED);
                    return result;
                  });
      try (var controller = controller(platform, observed)) {
        active.set(controller);
        controller.start();
        await(controller, SessionState.PAUSED);
        controller.resume().toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(controller, SessionState.COMPLETED);
        assertEquals(2, controller.status().modelCalls());
        assertEquals(2, controller.status().confirmedSubmissions());
      }
      assertEquals(2, platform.inputs.get());
      assertEquals(2, platform.ledger.size());
      assertTrue(platform.ledger.values().stream().allMatch(scores -> scores.size() == 1));
    }
    var summaries = performanceLines();
    assertEquals(
        2,
        summaries.size(),
        "Observation-only resume of acknowledged A is not another timing trace for A");
    assertBoundToTwoDistinctAttempts(summaries);
    assertEquals("PAUSED", summaries.getFirst().path("finalState").asText());
    assertFalse(summaries.getFirst().path("untilAcknowledgedMs").isNull());
  }

  private LocalLoggingSessionJournal open() {
    return new LocalLoggingSessionJournal(
        new H2SessionJournal(root.resolve("database/local-deskgrade")), root.resolve("records"));
  }

  private static SessionController controller(FakePlatform platform, SessionJournal journal) {
    var base = settings(2);
    var local =
        new SessionSettings(
            base.question(),
            base.modelProfileId(),
            base.modelRevision(),
            base.platformRevision(),
            "{\"access\":{\"mode\":\"LOCAL\"}}",
            base.maxSubmissions(),
            base.maxCalls(),
            base.maxRetries(),
            base.maxCost(),
            base.pollInterval(),
            base.pageTimeout());
    return new SessionController(
        platform,
        journal,
        (run, attempt, capture) -> MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
        immediate(),
        result -> new BigDecimal("4"),
        local,
        Clock.systemUTC(),
        () -> true,
        status -> {});
  }

  private List<JsonNode> performanceLines() throws Exception {
    Path performance = root.resolve("performance");
    assertTrue(Files.isDirectory(performance));
    var result = new ArrayList<JsonNode>();
    try (var files = Files.list(performance)) {
      for (Path file : files.sorted().toList()) {
        assertTrue(
            file.getFileName()
                .toString()
                .matches("performance-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}\\.jsonl"));
        for (String line : Files.readAllLines(file))
          if (!line.isBlank()) result.add(JSON.readTree(line));
      }
    }
    return result;
  }

  private static void assertBoundToTwoDistinctAttempts(List<JsonNode> summaries) {
    assertEquals(
        2, new HashSet<>(summaries.stream().map(r -> r.path("traceId").asText()).toList()).size());
    assertEquals(
        2,
        new HashSet<>(summaries.stream().map(r -> r.path("attemptId").asText()).toList()).size());
    assertEquals(
        1, new HashSet<>(summaries.stream().map(r -> r.path("runId").asText()).toList()).size());
    assertEquals(List.of(1, 2), summaries.stream().map(r -> r.path("ordinal").asInt()).toList());
    assertTrue(summaries.stream().allMatch(r -> !r.path("untilAcknowledgedMs").isNull()));
  }
}
