package app.deskgrade.adapters.persistence;

import static app.deskgrade.adapters.persistence.SessionControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.port.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionPerformanceIntegrationTest {
  @TempDir Path directory;

  @Test
  void brokenOptionalTimingCapabilityDoesNotBlockGrading() throws Exception {
    var platform = new FakePlatform(1);
    try (var journal = new H2SessionJournal(directory.resolve("capability-unavailable"))) {
      var broken =
          (SessionJournal)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("performanceEnabled"))
                      throw new LinkageError("fixture");
                    try {
                      return method.invoke(journal, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      try (var controller = controller(platform, broken, immediate(), 1)) {
        controller.start();
        await(controller, SessionState.COMPLETED);
        assertEquals(1, platform.ledger.get(1).size());
        assertEquals(1, controller.status().confirmedSubmissions());
      }
    }
  }

  @Test
  void eachAnswerGetsOneExclusiveTimelineAndOwnNestedCaptureCount() throws Exception {
    var summaries = new LinkedBlockingQueue<AttemptPerformance>();
    var platform =
        new FakePlatform(2) {
          @Override
          public CaptureEvidence captureEvidence(View view) {
            PerformanceTrace.record(AttemptPerformance.Metric.CAPTURE_TOTAL, 100, true);
            return super.captureEvidence(view);
          }
        };
    try (var journal = new H2SessionJournal(directory.resolve("two"));
        var controller =
            controller(platform, observing(journal, summaries, false), immediate(), 2)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      var first = take(summaries);
      var second = take(summaries);
      assertEquals(1, first.ordinal());
      assertEquals(2, second.ordinal());
      assertEquals(first.runId(), second.runId());
      assertNotEquals(first.traceId(), second.traceId());
      assertNotEquals(first.attemptId(), second.attemptId());
      for (var summary : List.of(first, second)) {
        assertNotNull(summary.untilAcknowledgedNanos());
        assertEquals(
            summary.totalNanos(),
            summary.phases().stream().mapToLong(AttemptPerformance.PhaseTiming::nanos).sum());
        assertTrue(summary.totalNanos() > 0);
        assertEquals(1, summary.modelCalls().size());
        assertEquals(
            AttemptPerformance.ModelOutcome.SUCCEEDED, summary.modelCalls().getFirst().outcome());
        assertTrue(metric(summary, AttemptPerformance.Metric.JOURNAL_WRITE).count() > 0);
        assertEquals(
            metric(summary, AttemptPerformance.Metric.CAPTURE_EVIDENCE).count(),
            metric(summary, AttemptPerformance.Metric.CAPTURE_TOTAL).count());
        assertEquals(1, metric(summary, AttemptPerformance.Metric.SUBMIT_ACTION).count());
      }
      assertTrue(summaries.isEmpty());
      assertEquals(2, platform.ledger.size());
      assertEquals(2, platform.inputs.get());
    }
  }

  @Test
  void unknownSubmissionProducesIncompleteTimingWithoutRetryingSubmit() throws Exception {
    var summaries = new LinkedBlockingQueue<AttemptPerformance>();
    var platform = new FakePlatform(1);
    platform.loseAck = true;
    try (var journal = new H2SessionJournal(directory.resolve("unknown"));
        var controller =
            controller(platform, observing(journal, summaries, false), immediate(), 1)) {
      controller.start();
      await(controller, SessionState.SUBMISSION_UNKNOWN);
      var summary = take(summaries);
      assertNull(summary.untilAcknowledgedNanos());
      assertEquals(SessionState.SUBMISSION_UNKNOWN, summary.finalState());
      assertEquals(PauseReason.SUBMISSION_UNCERTAIN, summary.pauseReason());
      assertTrue(
          summary.phases().stream()
              .anyMatch(p -> p.state() == SessionState.WAIT_ACK && p.nanos() > 0));
      assertEquals(1, platform.ledger.get(1).size());
      assertEquals(1, metric(summary, AttemptPerformance.Metric.SUBMIT_ACTION).count());
    }
  }

  @Test
  void optionalPerformanceWriteFailureDoesNotRepeatOrPreventSubmission() throws Exception {
    var summaries = new LinkedBlockingQueue<AttemptPerformance>();
    var platform = new FakePlatform(2);
    try (var journal = new H2SessionJournal(directory.resolve("unavailable"));
        var controller =
            controller(platform, observing(journal, summaries, true), immediate(), 2)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      take(summaries);
      take(summaries);
      assertEquals(2, controller.status().confirmedSubmissions());
      assertEquals(2, platform.ledger.size());
      assertTrue(platform.ledger.values().stream().allMatch(scores -> scores.size() == 1));
    }
  }

  @Test
  void stopDuringModelWaitFreezesInFlightTimingAndLateCompletionCannotAddAnotherSummary()
      throws Exception {
    var summaries = new LinkedBlockingQueue<AttemptPerformance>();
    var pending = new CompletableFuture<GradingResponse>();
    var received = new CompletableFuture<GradingRequest>();
    var platform = new FakePlatform(1);
    GradingProvider provider =
        (request, token) -> {
          received.complete(request);
          return pending;
        };
    try (var journal = new H2SessionJournal(directory.resolve("late"));
        var controller = controller(platform, observing(journal, summaries, false), provider, 1)) {
      controller.start();
      var request = received.get(5, TimeUnit.SECONDS);
      controller.stop();
      await(controller, SessionState.STOPPED);
      var summary = take(summaries);
      assertEquals(
          AttemptPerformance.ModelOutcome.IN_FLIGHT, summary.modelCalls().getFirst().outcome());
      pending.complete(
          new GradingResponse(
              request.requestId(), GradingResponse.Source.MOCK, RESULT, Instant.now()));
      controller.resume().toCompletableFuture().get(5, TimeUnit.SECONDS); // serialized barrier only
      assertTrue(summaries.isEmpty());
      assertEquals(
          AttemptPerformance.ModelOutcome.IN_FLIGHT, summary.modelCalls().getFirst().outcome());
      assertEquals(0, platform.inputs.get());
      assertTrue(platform.ledger.isEmpty());
    }
  }

  @Test
  void primaryAndFallbackHaveSeparateModelTimersWithinTheSameAnswer() throws Exception {
    var summaries = new LinkedBlockingQueue<AttemptPerformance>();
    FallbackGradingProvider provider =
        new FallbackGradingProvider() {
          public Optional<String> fallbackProfileId() {
            return Optional.of("mock-B");
          }

          public CompletionStage<GradingResponse> grade(
              GradingRequest request, CancellationToken token) {
            var unable =
                new GradeResult(
                    GradeResult.Status.UNABLE,
                    GradeResult.Readability.UNREADABLE,
                    null,
                    List.of(new GradeResult.CriterionResult("p", null, null, "fixture")),
                    List.of("fixture"));
            return CompletableFuture.completedFuture(
                new GradingResponse(
                    request.requestId(), GradingResponse.Source.MOCK, unable, Instant.now()));
          }

          public CompletionStage<GradingResponse> gradeFallback(
              UUID primary, GradingRequest request, CancellationToken token) {
            return immediate().grade(request, token);
          }
        };
    try (var journal = new H2SessionJournal(directory.resolve("fallback"));
        var controller =
            controller(new FakePlatform(1), observing(journal, summaries, false), provider, 1)) {
      controller.start();
      await(controller, SessionState.COMPLETED);
      var summary = take(summaries);
      assertEquals(
          List.of(AttemptPerformance.ModelRole.PRIMARY, AttemptPerformance.ModelRole.FALLBACK),
          summary.modelCalls().stream().map(AttemptPerformance.ModelTiming::role).toList());
      assertNotEquals(
          summary.modelCalls().getFirst().callId(), summary.modelCalls().getLast().callId());
      assertEquals(2, controller.status().modelCalls());
      assertTrue(summaries.isEmpty());
    }
  }

  private SessionController controller(
      FakePlatform platform, SessionJournal journal, GradingProvider provider, int count) {
    return new SessionController(
        platform,
        journal,
        (run, attempt, capture) -> "synthetic.png",
        provider,
        result -> new BigDecimal("4"),
        settings(count),
        Clock.systemUTC(),
        () -> true,
        status -> {});
  }

  private SessionJournal observing(
      SessionJournal delegate, BlockingQueue<AttemptPerformance> summaries, boolean failWrite) {
    return (SessionJournal)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionJournal.class},
            (proxy, method, args) -> {
              if (method.getName().equals("performanceEnabled")) return true;
              if (method.getName().equals("recordPerformance")) {
                summaries.add((AttemptPerformance) args[0]);
                if (failWrite)
                  throw new IllegalStateException("synthetic diagnostic storage failure");
                return null;
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static AttemptPerformance take(BlockingQueue<AttemptPerformance> summaries)
      throws InterruptedException {
    var summary = summaries.poll(5, TimeUnit.SECONDS);
    assertNotNull(
        summary, "Per-answer summary must be emitted after the serialized operation completes");
    return summary;
  }

  private static AttemptPerformance.MetricTiming metric(
      AttemptPerformance summary, AttemptPerformance.Metric metric) {
    return summary.metrics().stream().filter(m -> m.metric() == metric).findFirst().orElseThrow();
  }
}
