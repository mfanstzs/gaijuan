package app.deskgrade.adapters.windows.manual;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.ActionPermission;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.application.session.SessionSettings;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.port.GradingProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real controller, manual adapter and durable journal; no native input, server or model calls. */
class ManualSubmissionTransitionIntegrationTest {
  @TempDir Path directory;

  @Test
  void postSubmitMixedFramesWaitThenConfirmOnceAndReachTheNextAnswer() throws Exception {
    var fixture = new Fixture(false);
    var calls = new AtomicInteger();
    var secondAnswer = new AtomicReference<String>();
    GradingProvider provider =
        (request, token) -> {
          if (calls.incrementAndGet() == 1)
            return CompletableFuture.completedFuture(scored(request));
          secondAnswer.set(new String(request.answerImage(), StandardCharsets.UTF_8));
          return new CompletableFuture<>();
        };
    try (var journal = new H2SessionJournal(directory.resolve("transition-success"));
        var controller = fixture.controller(journal, provider)) {
      controller.start();
      fixture.awaitWithCaptureTime(() -> calls.get() == 1);
      await(() -> fixture.io.postSubmitSamples.get() >= 3);
      assertEquals(0, controller.status().confirmedSubmissions());
      assertEquals(1, fixture.io.submits.get());
      fixture.awaitWithCaptureTime(() -> calls.get() == 2);
      assertEquals(SessionState.GRADING, controller.status().state());
      assertEquals(1, controller.status().confirmedSubmissions());
      assertEquals("answer-b", secondAnswer.get());
      assertEquals(1, fixture.io.submits.get());
      assertEquals(0, fixture.io.nexts.get());
      assertNull(controller.status().failureDiagnostic());
      assertEquals(1, journal.runs().getFirst().confirmedSubmissions());
      assertEquals(
          1,
          journal.attempts(controller.status().runId()).stream()
              .filter(a -> a.confirmed())
              .count());
    }
  }

  @Test
  void permanentlyMixedPostSubmitFramesRemainPendingUntilTheTwentySecondDeadline()
      throws Exception {
    var fixture = new Fixture(true);
    var calls = new AtomicInteger();
    GradingProvider provider =
        (request, token) -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(scored(request));
        };
    try (var journal = new H2SessionJournal(directory.resolve("transition-timeout"));
        var controller = fixture.controller(journal, provider)) {
      controller.start();
      fixture.awaitWithCaptureTime(() -> calls.get() == 1);
      // More than one passive poll must finish without turning temporary pixels into a failure.
      await(() -> fixture.io.postSubmitSamples.get() >= 6);
      assertEquals(SessionState.WAIT_ACK, controller.status().state());
      assertEquals(0, controller.status().confirmedSubmissions());
      assertEquals(1, fixture.io.submits.get());
      fixture.clock.advance(Duration.ofSeconds(21));
      await(() -> controller.status().state() == SessionState.SUBMISSION_UNKNOWN);
      assertEquals(PauseReason.SUBMISSION_UNCERTAIN, controller.status().reason());
      assertEquals(1, fixture.io.submits.get());
      assertEquals(0, fixture.io.nexts.get());
      assertEquals(1, calls.get());
      assertTrue(journal.hasUnknownSubmission(fixture.adapter.targetIdentity()));
      controller.resume().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(SessionState.SUBMISSION_UNKNOWN, controller.status().state());
      assertEquals(1, fixture.io.submits.get());
    }
  }

  @Test
  void mixedReadyFramesAfterScoringStillStopBeforeAnyScoreInputOrSubmit() throws Exception {
    var fixture = new Fixture(false);
    var request = new AtomicReference<GradingRequest>();
    var response = new CompletableFuture<GradingResponse>();
    GradingProvider provider =
        (input, token) -> {
          request.set(input);
          return response;
        };
    try (var journal = new H2SessionJournal(directory.resolve("ready-still-strict"));
        var controller = fixture.controller(journal, provider)) {
      controller.start();
      fixture.awaitWithCaptureTime(() -> request.get() != null);
      fixture.io.mixedWhileReady = true;
      response.complete(scored(request.get()));
      await(() -> controller.status().state() == SessionState.STOPPED);
      assertEquals(PauseReason.LOCAL_FAILURE, controller.status().reason());
      assertEquals(SessionState.BEFORE_INPUT, controller.status().failureDiagnostic().phase());
      assertTrue(
          controller
              .status()
              .failureDiagnostic()
              .codes()
              .contains("ANSWER_CHANGED_DURING_CAPTURE"));
      assertEquals(0, fixture.io.inputs.get());
      assertEquals(0, fixture.io.submits.get());
      assertEquals(0, fixture.io.nexts.get());
      assertFalse(fixture.adapter.supportsUnattended());
    }
  }

  private static GradingResponse scored(GradingRequest request) {
    var result =
        new GradeResult(
            GradeResult.Status.SCORED,
            GradeResult.Readability.CLEAR,
            "synthetic answer",
            List.of(
                new GradeResult.CriterionResult("P1", new BigDecimal("4"), "synthetic", "fixture")),
            List.of());
    return new GradingResponse(
        request.requestId(), GradingResponse.Source.MOCK, result, Instant.EPOCH);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "Expected bounded controller progress");
  }

  private static final class Fixture {
    final MutableClock clock = new MutableClock();
    final SyntheticIo io;
    final ManualCoordinatePlatformAdapter adapter;

    Fixture(boolean permanentlyUnstable) {
      io = new SyntheticIo(clock, permanentlyUnstable);
      adapter = new ManualCoordinatePlatformAdapter(io, "transition-fixture", false, "q1", clock);
    }

    SessionController controller(H2SessionJournal journal, GradingProvider provider) {
      var question =
          new QuestionDefinition(
              "q1",
              1,
              "synthetic question",
              "synthetic reference",
              new BigDecimal("4"),
              List.of(
                  new ScoringCriterion(
                      "P1", "synthetic criterion", List.of(BigDecimal.ZERO, new BigDecimal("4")))));
      var settings =
          new SessionSettings(
              question,
              "synthetic-model",
              1,
              1,
              "{}",
              2,
              10,
              0,
              null,
              Duration.ofMillis(5),
              Duration.ofSeconds(20));
      return new SessionController(
          adapter,
          journal,
          (run, attempt, capture) -> "synthetic.png",
          provider,
          result -> GradeValidator.validate(question, result).totalScore(),
          settings,
          clock,
          () -> true,
          ignored -> {});
    }

    void awaitWithCaptureTime(BooleanSupplier condition) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      int previousCaptures = io.samples.get();
      while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
        int captures = io.samples.get();
        if (captures - previousCaptures >= 3) {
          // Move virtual time only after fresh observation, not while journal I/O is in flight.
          clock.advance(Duration.ofMillis(751));
          previousCaptures = captures;
        }
        Thread.sleep(5);
      }
      assertTrue(
          condition.getAsBoolean(), "Expected bounded progress through fresh stable captures");
    }
  }

  private static final class SyntheticIo implements ManualCoordinatePlatformAdapter.Io {
    final MutableClock clock;
    final boolean permanentlyUnstable;
    final AtomicInteger samples = new AtomicInteger();
    final AtomicInteger postSubmitSamples = new AtomicInteger();
    final AtomicInteger inputs = new AtomicInteger();
    final AtomicInteger submits = new AtomicInteger();
    final AtomicInteger nexts = new AtomicInteger();
    volatile boolean mixedWhileReady;
    BigDecimal value;

    SyntheticIo(MutableClock clock, boolean permanentlyUnstable) {
      this.clock = clock;
      this.permanentlyUnstable = permanentlyUnstable;
    }

    @Override
    public void verifyWindow() {}

    @Override
    public SessionPlatform.Capture captureAnswer() {
      return sample();
    }

    @Override
    public SessionPlatform.Capture captureIdentity() {
      return sample();
    }

    @Override
    public SessionPlatform.Capture captureSubmissionAck() {
      throw new AssertionError("Combined navigation must not sample an acknowledgement region");
    }

    private SessionPlatform.Capture sample() {
      int sequence = samples.incrementAndGet();
      String frame = "answer-a";
      if (mixedWhileReady) frame = sequence % 3 == 2 ? "answer-b" : "answer-a";
      else if (submits.get() > 0) {
        int afterSubmit = postSubmitSamples.incrementAndGet();
        frame =
            permanentlyUnstable || afterSubmit <= 3
                ? (afterSubmit % 3 == 2 ? "answer-b" : "answer-a")
                : "answer-b";
      }
      try {
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        return new SessionPlatform.Capture(
            bytes,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            clock.instant());
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }

    @Override
    public void fill(BigDecimal score, ActionPermission permission) {
      permission.verify();
      inputs.incrementAndGet();
      value = score;
    }

    @Override
    public BigDecimal read(ActionPermission permission) {
      permission.verify();
      inputs.incrementAndGet();
      return value;
    }

    @Override
    public void submit(ActionPermission permission) {
      permission.verify();
      inputs.incrementAndGet();
      submits.incrementAndGet();
    }

    @Override
    public void next(ActionPermission permission) {
      permission.verify();
      inputs.incrementAndGet();
      nexts.incrementAndGet();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicLong millis = new AtomicLong();

    void advance(Duration duration) {
      millis.addAndGet(duration.toMillis());
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.parse("2026-10-21T00:00:00Z").plusMillis(millis.get());
    }
  }
}
