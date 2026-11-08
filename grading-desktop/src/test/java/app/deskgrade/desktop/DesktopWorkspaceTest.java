package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.model.RubricJsonCodec;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Default tests never load native libraries, register hotkeys, capture windows or call an API. */
class DesktopWorkspaceTest {
  @TempDir Path directory;

  @Test
  void stopDuringCountdownInvalidatesTicketBeforeAnyWorkerOrControllerExists() throws Exception {
    var queue = new QueuedWorker();
    var workspace = new DesktopWorkspace(directory, queue);
    var cancelled = new AtomicBoolean();
    workspace.onActionRevoked = () -> cancelled.set(true);
    long countdownTicket = workspace.actionTicket();
    workspace.stop();
    assertTrue(cancelled.get(), "F9 must notify the UI to cancel its pending countdown");
    var start = workspace.startMock(true, 20, 20, countdownTicket);
    queue.drain();
    assertInstanceOf(
        CancellationException.class,
        assertThrows(ExecutionException.class, () -> start.get(1, TimeUnit.SECONDS)).getCause());
    assertNull(workspace.status());
    workspace.close();
    queue.drain();
    workspace.awaitClosed().get(1, TimeUnit.SECONDS);
  }

  @Test
  void stopInvalidatesStartAndResumeAlreadyQueuedBeforeControllerCreation() throws Exception {
    var queue = new QueuedWorker();
    var workspace = new DesktopWorkspace(directory, queue);
    long ticket = workspace.actionTicket();
    var start = workspace.startMock(true, 20, 20, ticket);
    var resume = workspace.resumeMock(20, 20, ticket);
    workspace.stop();
    queue.drain();
    assertInstanceOf(
        CancellationException.class,
        assertThrows(ExecutionException.class, () -> start.get(1, TimeUnit.SECONDS)).getCause());
    assertInstanceOf(
        CancellationException.class,
        assertThrows(ExecutionException.class, () -> resume.get(1, TimeUnit.SECONDS)).getCause());
    assertNull(workspace.status());
    workspace.close();
    queue.drain();
    workspace.awaitClosed().get(1, TimeUnit.SECONDS);
  }

  private static final class QueuedWorker extends AbstractExecutorService {
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
    private boolean shutdown;

    void drain() {
      while (!queued.isEmpty()) queued.removeFirst().run();
    }

    @Override
    public void execute(Runnable runnable) {
      queued.addLast(runnable);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.copyOf(queued);
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && queued.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }

  @Test
  void startupOnlyLoadsLocalDataAndDoesNotAuthorizeAnyDesktopAction() throws Exception {
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      var loaded = workspace.initialize().get(10, TimeUnit.SECONDS);
      assertTrue(workspace.ready());
      assertFalse(workspace.commercialMode());
      assertTrue(workspace.localOnlyRuntime());
      assertNull(workspace.accountView().account());
      assertFalse(workspace.commercialCreditsAvailable());
      assertNull(workspace.binding());
      assertNull(workspace.status());
      assertFalse(workspace.hotkeysAvailable());
      assertTrue(loaded.runs().isEmpty());
      assertEquals(3, loaded.profiles().size());
      assertTrue(loaded.profiles().stream().noneMatch(ProviderProfile::isVerified));
      assertFalse(loaded.rubric().missingSourceAccepted());
      assertDoesNotThrow(
          loaded.rubric()::validateReady,
          "No source text is a supported grading mode and must not unlock desktop actions");
      assertThrows(
          Exception.class, () -> workspace.startMock(false, 20, 20).get(5, TimeUnit.SECONDS));
      assertNull(workspace.status());
      assertEquals(
          0,
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .total());
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void savedRubricAndSubjectSurviveAColdWorkspaceRestart() throws Exception {
    List<ScoringCriterion> points =
        List.of(
            new ScoringCriterion("P1", "指出这些形势有利于我方坚守", List.of(BigDecimal.ZERO, BigDecimal.ONE)),
            new ScoringCriterion(
                "P2",
                "指出这些形势不利于强攻且道理不变",
                List.of(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("2"))));
    String normalizedCriteria = "P1 | 0,1 | 指出这些形势有利于我方坚守\n" + "P2 | 0,1,2 | 指出这些形势不利于强攻且道理不变";
    assertEquals(normalizedCriteria, DeskGradeShell.formatCriteria(points));
    Rubric expected =
        new Rubric(
            new QuestionDefinition(
                "history-material-17",
                17,
                "[原文缺失]",
                "这几种形势，都利于我方坚守而不利于强攻，这是不变的道理。",
                new BigDecimal("3"),
                points),
            normalizedCriteria,
            new BigDecimal("0.5"),
            false,
            true);

    var first = new DesktopWorkspace(directory);
    try (first) {
      first.initialize().get(10, TimeUnit.SECONDS);
      first.saveRubric(expected, "  高中历史 · 材料题  ").get(10, TimeUnit.SECONDS);
    }
    first.awaitClosed().get(10, TimeUnit.SECONDS);

    var reopened = new DesktopWorkspace(directory);
    try (reopened) {
      var loaded = reopened.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(
          expected.question().standardAnswer(), loaded.rubric().question().standardAnswer());
      assertEquals(expected.question().maximumScore(), loaded.rubric().question().maximumScore());
      assertEquals(expected.scoreStep(), loaded.rubric().scoreStep());
      assertEquals(expected.question().criteria(), loaded.rubric().question().criteria());
      assertEquals(normalizedCriteria, loaded.rubric().rawRubric());
      assertEquals("高中历史 · 材料题", loaded.rubricSubject());
    }
    reopened.awaitClosed().get(10, TimeUnit.SECONDS);
    assertEquals("高中历史 · 材料题", Files.readString(directory.resolve("settings/rubric-subject.txt")));
  }

  @Test
  void preparedRubricSaveRollsForwardWhenNeitherTargetWasReplaced() throws Exception {
    assertInterruptedRubricSaveRecovers(RubricCrashState.BOTH_BEFORE);
  }

  @Test
  void preparedRubricSaveRollsForwardWhenOnlyRubricWasReplaced() throws Exception {
    assertInterruptedRubricSaveRecovers(RubricCrashState.RUBRIC_AFTER);
  }

  @Test
  void preparedRubricSaveRollsForwardWhenOnlySubjectWasReplaced() throws Exception {
    assertInterruptedRubricSaveRecovers(RubricCrashState.SUBJECT_AFTER);
  }

  @Test
  void preparedRubricSaveFinishesIdempotentlyWhenBothTargetsWereReplaced() throws Exception {
    assertInterruptedRubricSaveRecovers(RubricCrashState.BOTH_AFTER);
  }

  @Test
  void saveRecoversAnOlderPendingTransactionBeforeStartingTheNextOne() throws Exception {
    Rubric oldRubric = transactionRubric("old", "旧答案");
    Rubric interruptedRubric = transactionRubric("interrupted", "中断答案");
    Rubric newestRubric = transactionRubric("newest", "最终答案");
    writeInterruptedRubricSave(
        directory, oldRubric, "旧学科", interruptedRubric, "中断学科", RubricCrashState.RUBRIC_AFTER);

    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      workspace.saveRubric(newestRubric, "最终学科").get(10, TimeUnit.SECONDS);
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);

    RubricJsonCodec codec = new RubricJsonCodec();
    assertEquals(
        newestRubric, codec.read(Files.readString(directory.resolve("settings/rubric.json"))));
    assertEquals("最终学科", Files.readString(directory.resolve("settings/rubric-subject.txt")));
    assertFalse(Files.exists(directory.resolve("settings/rubric-save.pending.json")));
  }

  @Test
  void legacyRubricWithoutJournalOrSubjectStillLoads() throws Exception {
    Rubric legacy = transactionRubric("legacy", "兼容旧答案");
    Path settings = Files.createDirectories(directory.resolve("settings"));
    Files.writeString(settings.resolve("rubric.json"), new RubricJsonCodec().write(legacy));

    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      var loaded = workspace.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(legacy, loaded.rubric());
      assertEquals("", loaded.rubricSubject());
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void malformedRubricSaveJournalFailsClosedWithoutChangingLegacyTargets() throws Exception {
    Rubric oldRubric = transactionRubric("old", "旧答案");
    Path settings = Files.createDirectories(directory.resolve("settings"));
    String oldJson = new RubricJsonCodec().write(oldRubric);
    Files.writeString(settings.resolve("rubric.json"), oldJson);
    Files.writeString(settings.resolve("rubric-subject.txt"), "旧学科");
    Files.writeString(
        settings.resolve("rubric-save.pending.json"), "{\"schemaVersion\":1,\"unexpected\":true}");

    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      assertThrows(
          ExecutionException.class, () -> workspace.initialize().get(10, TimeUnit.SECONDS));
      assertFalse(workspace.ready());
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
    assertEquals(oldJson, Files.readString(settings.resolve("rubric.json")));
    assertEquals("旧学科", Files.readString(settings.resolve("rubric-subject.txt")));
    assertTrue(Files.exists(settings.resolve("rubric-save.pending.json")));
  }

  @Test
  void unexpectedThirdTargetValueFailsClosedBeforeEitherTargetIsChanged() throws Exception {
    Rubric oldRubric = transactionRubric("old", "旧答案");
    Rubric intendedRubric = transactionRubric("intended", "预期答案");
    Rubric unrelatedRubric = transactionRubric("unrelated", "外部修改答案");
    writeInterruptedRubricSave(
        directory, oldRubric, "旧学科", intendedRubric, "新学科", RubricCrashState.BOTH_BEFORE);
    Path settings = directory.resolve("settings");
    String unrelatedJson = new RubricJsonCodec().write(unrelatedRubric);
    Files.writeString(settings.resolve("rubric.json"), unrelatedJson);

    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      assertThrows(
          ExecutionException.class, () -> workspace.initialize().get(10, TimeUnit.SECONDS));
      assertFalse(workspace.ready());
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
    assertEquals(unrelatedJson, Files.readString(settings.resolve("rubric.json")));
    assertEquals("旧学科", Files.readString(settings.resolve("rubric-subject.txt")));
    assertTrue(Files.exists(settings.resolve("rubric-save.pending.json")));
  }

  @Test
  void modelProfilesExportReferencesOnlyAndNeverReadCredentials() throws Exception {
    var workspace = DesktopTestMode.developer(directory);
    try (workspace) {
      workspace.initialize().get(10, TimeUnit.SECONDS);
      workspace.setCommercialMode(false).get(5, TimeUnit.SECONDS);
      ProviderProfile p =
          new ProviderProfile(
              "profile_A",
              1,
              "未验证 A",
              URI.create("https://example.invalid/chat/completions"),
              "account-verified-model",
              "credential_A",
              ProviderProfile.StructuredMode.NONE,
              Map.of(),
              Set.of(),
              Duration.ofSeconds(10),
              Duration.ofSeconds(60),
              null,
              null);
      workspace.saveProfile(p).get(5, TimeUnit.SECONDS);
      assertFalse(workspace.hasCredential(p).get(5, TimeUnit.SECONDS));
      Path exported = directory.resolve("export.json");
      workspace.exportProfiles(exported).get(5, TimeUnit.SECONDS);
      String json = Files.readString(exported);
      assertTrue(json.contains("credential_A"));
      assertFalse(json.contains("apiKey"));
      assertFalse(json.contains("password"));
      assertFalse(Files.exists(directory.resolve("credentials")));
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void stoppedQueuedPaidSyntheticStartCannotCreateAnyControllerOrRequest() throws Exception {
    var queue = new QueuedWorker();
    var workspace = new DesktopWorkspace(directory, queue);
    ProviderProfile p =
        app.deskgrade.adapters.model.QwenEndpointCatalog.unverifiedProfile(
            "profile_A",
            1,
            "千问未验证",
            app.deskgrade.adapters.model.QwenEndpointCatalog.qwenAiEndpoint(),
            "qwen3.8-flash",
            "credential_A");
    long ticket = workspace.actionTicket();
    var operation = workspace.startLiveSynthetic(p, 3, 3, ticket);
    workspace.stop();
    queue.drain();
    assertInstanceOf(
        CancellationException.class,
        assertThrows(ExecutionException.class, () -> operation.get(1, TimeUnit.SECONDS))
            .getCause());
    assertNull(workspace.status());
    assertFalse(workspace.liveSyntheticSession());
    assertFalse(Files.exists(directory.resolve("credentials")));
    workspace.close();
    queue.drain();
    workspace.awaitClosed().get(1, TimeUnit.SECONDS);
  }

  @Test
  void unverifiedSavedModelIsRejectedBeforeAnyNativeActionOrPaidRequest() throws Exception {
    var workspace = DesktopTestMode.developer(directory);
    try (workspace) {
      workspace.initialize().get(10, TimeUnit.SECONDS);
      workspace.setCommercialMode(false).get(5, TimeUnit.SECONDS);
      ProviderProfile p =
          app.deskgrade.adapters.model.QwenEndpointCatalog.unverifiedProfile(
              "profile_A",
              1,
              "千问未验证",
              app.deskgrade.adapters.model.QwenEndpointCatalog.qwenAiEndpoint(),
              "qwen3.8-flash",
              "credential_A");
      workspace.saveProfile(p).get(5, TimeUnit.SECONDS);
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  workspace
                      .startLiveSynthetic(p, 3, 3, workspace.actionTicket())
                      .get(5, TimeUnit.SECONDS));
      assertEquals("Image capability is not verified", failure.getCause().getMessage());
      assertNull(workspace.status());
      assertTrue(workspace.runs().get(5, TimeUnit.SECONDS).isEmpty());
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  @Test
  void realSyntheticRunUsesCompleteExplicitArithmeticRubricWithoutChangingTeacherRules()
      throws Exception {
    var arithmetic = DesktopWorkspace.syntheticVisionRubric();
    arithmetic.validateReady();
    assertEquals("1", arithmetic.question().id());
    assertEquals(
        List.of("calculation", "expression", "explanation"),
        arithmetic.question().criteria().stream().map(c -> c.id()).toList());
    assertEquals(new java.math.BigDecimal("4"), arithmetic.question().maximumScore());
    assertFalse(arithmetic.missingSourceAccepted());
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      var loaded = workspace.initialize().get(10, TimeUnit.SECONDS);
      assertEquals("example-translation-4points", loaded.rubric().question().id());
      assertFalse(Files.exists(directory.resolve("settings/rubric.json")));
    }
    workspace.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  private enum RubricCrashState {
    BOTH_BEFORE,
    RUBRIC_AFTER,
    SUBJECT_AFTER,
    BOTH_AFTER
  }

  private void assertInterruptedRubricSaveRecovers(RubricCrashState state) throws Exception {
    Rubric oldRubric = transactionRubric("old", "旧答案");
    Rubric expected = transactionRubric("expected", "恢复后的答案");
    writeInterruptedRubricSave(directory, oldRubric, "旧学科", expected, "恢复后的学科", state);

    var first = new DesktopWorkspace(directory);
    try (first) {
      var loaded = first.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(expected, loaded.rubric());
      assertEquals("恢复后的学科", loaded.rubricSubject());
    }
    first.awaitClosed().get(10, TimeUnit.SECONDS);
    assertFalse(Files.exists(directory.resolve("settings/rubric-save.pending.json")));

    // A second cold start has no journal and must observe exactly the same committed pair.
    var reopened = new DesktopWorkspace(directory);
    try (reopened) {
      var loaded = reopened.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(expected, loaded.rubric());
      assertEquals("恢复后的学科", loaded.rubricSubject());
    }
    reopened.awaitClosed().get(10, TimeUnit.SECONDS);
  }

  private static void writeInterruptedRubricSave(
      Path root,
      Rubric before,
      String beforeSubject,
      Rubric after,
      String afterSubject,
      RubricCrashState state)
      throws Exception {
    Path settings = Files.createDirectories(root.resolve("settings"));
    RubricJsonCodec codec = new RubricJsonCodec();
    String beforeJson = codec.write(before);
    String afterJson = codec.write(after);
    Files.writeString(
        settings.resolve("rubric.json"),
        state == RubricCrashState.RUBRIC_AFTER || state == RubricCrashState.BOTH_AFTER
            ? afterJson
            : beforeJson);
    Files.writeString(
        settings.resolve("rubric-subject.txt"),
        state == RubricCrashState.SUBJECT_AFTER || state == RubricCrashState.BOTH_AFTER
            ? afterSubject
            : beforeSubject);

    var journal = new ObjectMapper().createObjectNode();
    journal.put("schemaVersion", 1);
    journal.put("transactionId", UUID.randomUUID().toString());
    journal.put("state", "PREPARED");
    journal.put("rubricBeforeSha256", testSha256(beforeJson));
    journal.put("subjectBeforeSha256", testSha256(beforeSubject));
    journal.put("rubricAfterSha256", testSha256(afterJson));
    journal.put("subjectAfterSha256", testSha256(afterSubject));
    journal.put("rubricJson", afterJson);
    journal.put("subject", afterSubject);
    Files.writeString(settings.resolve("rubric-save.pending.json"), journal.toString());
  }

  private static Rubric transactionRubric(String id, String referenceAnswer) {
    return new Rubric(
        new QuestionDefinition(
            id,
            1,
            "[原文缺失]",
            referenceAnswer,
            BigDecimal.ONE,
            List.of(
                new ScoringCriterion(
                    "P1", "回答符合参考答案得1分", List.of(BigDecimal.ZERO, BigDecimal.ONE)))),
        "P1 | 0,1 | 回答符合参考答案得1分",
        BigDecimal.ONE,
        false,
        true);
  }

  private static String testSha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
