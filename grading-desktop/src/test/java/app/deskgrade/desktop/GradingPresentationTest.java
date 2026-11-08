package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.H2SessionJournal;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real controller, validator and journal; only the model and platform are local fixtures. */
@Timeout(10)
class GradingPresentationTest {
  @TempDir Path directory;

  @Test
  void invalidCriterionNeverPublishesAnUnvalidatedScore() throws Exception {
    runCase(true, false);
  }

  @Test
  void failedGradePersistenceNeverPublishesAScore() throws Exception {
    runCase(false, true);
  }

  @Test
  void successfulPresentationUsesPersistedJavaTotalAndOriginalAuthority() throws Exception {
    runCase(false, false);
  }

  @Test
  void unablePresentationKeepsNullScoreAndRejectsNumericZero() {
    var result =
        new GradeResult(
            GradeResult.Status.UNABLE,
            GradeResult.Readability.UNREADABLE,
            null,
            List.of(new GradeResult.CriterionResult("p", null, null, "Cannot read fixture")),
            List.of("UNREADABLE"));
    var response =
        new GradingResponse(UUID.randomUUID(), GradingResponse.Source.MOCK, result, Instant.now());
    var run = UUID.randomUUID();
    assertNull(new GradingPresentation(run, response, null, 7, 3).validatedTotal());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GradingPresentation(run, response, BigDecimal.ZERO, 7, 3));
  }

  private void runCase(boolean invalid, boolean failSave) throws Exception {
    var question =
        new QuestionDefinition(
            "q",
            1,
            "Synthetic question",
            "Synthetic answer",
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "p", "Fixture criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    var settings =
        new SessionSettings(
            question,
            "fixture",
            1,
            1,
            "{}",
            1,
            1,
            0,
            null,
            Duration.ofMillis(2),
            Duration.ofSeconds(2));
    var saved = new AtomicBoolean();
    var current = new AtomicReference<SessionController>();
    var frames = new CopyOnWriteArrayList<GradingPresentation>();
    var platform = new FixturePlatform();
    try (var database = new H2SessionJournal(directory.resolve("grading"))) {
      var journal =
          (SessionJournal)
              Proxy.newProxyInstance(
                  SessionJournal.class.getClassLoader(),
                  new Class<?>[] {SessionJournal.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("saveGrade") && failSave)
                      throw new IllegalStateException("Synthetic save failure");
                    try {
                      Object result = method.invoke(database, args);
                      if (method.getName().equals("saveGrade")) saved.set(true);
                      return result;
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      try (var controller =
          new SessionController(
              platform,
              journal,
              (run, attempt, image) -> "NO_IMAGE_STORED",
              (request, token) ->
                  CompletableFuture.completedFuture(
                      new GradingResponse(
                          request.requestId(),
                          GradingResponse.Source.MOCK,
                          new GradeResult(
                              GradeResult.Status.SCORED,
                              GradeResult.Readability.CLEAR,
                              "Synthetic response",
                              List.of(
                                  new GradeResult.CriterionResult(
                                      invalid ? "wrong" : "p",
                                      BigDecimal.ONE,
                                      "fixture",
                                      "fixture")),
                              List.of()),
                          Instant.now())),
              result -> GradeValidator.validate(question, result).totalScore(),
              settings,
              Clock.systemUTC(),
              () -> true,
              status -> {
                var owner = current.get();
                if (owner == null) return;
                GradingPresentation.automatic(status, owner.status(), owner.lastResponse(), 7, 3)
                    .ifPresent(
                        frame -> {
                          assertTrue(
                              saved.get(), "Presentation must follow durable grade persistence");
                          frames.add(frame);
                        });
              })) {
        current.set(controller);
        controller.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!Set.of(
                    SessionState.PAUSED,
                    SessionState.FAILED,
                    SessionState.COMPLETED,
                    SessionState.STOPPED)
                .contains(controller.status().state())
            && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(
            Set.of(
                    SessionState.PAUSED,
                    SessionState.FAILED,
                    SessionState.COMPLETED,
                    SessionState.STOPPED)
                .contains(controller.status().state()));
        if (invalid || failSave) {
          assertTrue(frames.isEmpty());
          assertEquals(0, platform.fills.get());
          assertFalse(saved.get());
        } else {
          assertEquals(1, frames.size());
          var frame = frames.getFirst();
          assertEquals(BigDecimal.ONE, frame.validatedTotal());
          assertEquals(controller.status().runId(), frame.runId());
          assertEquals(controller.lastResponse().requestId(), frame.response().requestId());
          assertTrue(frame.belongsTo(7, 3));
          assertFalse(frame.belongsTo(8, 3), "Stop must revoke an already queued UI result");
          assertFalse(frame.belongsTo(7, 4), "Another account must never display this response");
          assertEquals(1, platform.fills.get());
        }
      }
    }
  }

  private static final class FixturePlatform implements SessionPlatform {
    final AtomicInteger fills = new AtomicInteger();
    BigDecimal score;
    boolean submitted;

    public String targetIdentity() {
      return "presentation-fixture";
    }

    public boolean supportsUnattended() {
      return true;
    }

    public Duration readinessStability() {
      return Duration.ZERO;
    }

    public View observe() {
      return new View(
          "a",
          "q",
          1,
          submitted ? Page.COMPLETED : Page.READY,
          submitted ? "a" : null,
          submitted ? 1 : 0,
          score,
          false);
    }

    public Capture capture(View view) {
      return captured(new byte[] {1});
    }

    public IdentityCapture captureIdentity(View view) {
      return new IdentityCapture(
          targetIdentity(),
          view.answerId(),
          view.questionId(),
          view.pageRevision(),
          "presentation-identity-region",
          captured("identity:a".getBytes(StandardCharsets.UTF_8)));
    }

    public void verify(View view) {}

    public void fillScore(View view, BigDecimal total, ActionPermission permission) {
      permission.verify();
      score = total;
      fills.incrementAndGet();
    }

    public void submit(View view, ActionPermission permission) {
      permission.verify();
      submitted = true;
    }

    public void next(View view, ActionPermission permission) {
      fail("Fixture advances on submit");
    }

    private static Capture captured(byte[] pixels) {
      try {
        return new Capture(
            pixels,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels)),
            Instant.now());
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }
  }
}
