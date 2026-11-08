package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.commercial.PublicModelProfile;
import app.deskgrade.adapters.commercial.PublicModelProfileJsonCodec;
import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.PauseReason;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Local synthetic account wire only: no native library, supplier Key, real model or GUI input. */
@Timeout(30)
class DesktopWorkspaceCommercialTest {
  @TempDir Path directory;

  @Test
  void manualTargetIdentityIsStableForTheSameAccountAndSiteButLeaksNoOrigin() {
    var alice = new GradingRecords.Owner("http://127.0.0.1:8787", "account-alice");
    var bob = new GradingRecords.Owner("http://127.0.0.1:8787", "account-bob");
    String first = DesktopWorkspace.manualTargetIdentity(alice, "https://school.example:443");

    assertEquals(first, DesktopWorkspace.manualTargetIdentity(alice, "https://school.example:443"));
    assertNotEquals(
        first, DesktopWorkspace.manualTargetIdentity(alice, "https://other.example:443"));
    assertNotEquals(
        first, DesktopWorkspace.manualTargetIdentity(bob, "https://school.example:443"));
    assertFalse(first.contains("school.example"));
    assertFalse(first.contains("account-alice"));
    assertTrue(first.startsWith("manual-site-v1:"));
  }

  @Test
  void rejectedAccountClearsCacheButTemporaryServerFailureDoesNotLogOut() throws Exception {
    for (String code : List.of("AUTH_REQUIRED", "ACCOUNT_DISABLED", "STORE_UNAVAILABLE")) {
      var secrets = new MemorySessionSecrets();
      try (var server = new AccountServer()) {
        var workspace =
            new DesktopWorkspace(
                directory.resolve(code),
                Executors.newSingleThreadExecutor(),
                secrets,
                server.base());
        try (workspace) {
          workspace.initialize().get(5, TimeUnit.SECONDS);
          workspace
              .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
              .get(5, TimeUnit.SECONDS);
          long ticket = workspace.actionTicket();
          server.accountError = code;
          assertThrows(Exception.class, () -> workspace.refreshAccount().get(5, TimeUnit.SECONDS));
          if (code.equals("STORE_UNAVAILABLE")) {
            assertNotNull(workspace.accountView().account());
            assertEquals(
                ticket,
                workspace.actionTicket(),
                "Temporary refresh failure must not revoke a paid in-flight result");
          } else {
            assertNull(workspace.accountView().account());
            assertNotEquals(ticket, workspace.actionTicket());
          }
          assertEquals(0, server.gradeRequests.get());
        }
        workspace.awaitClosed().get(5, TimeUnit.SECONDS);
        assertEquals(!code.equals("STORE_UNAVAILABLE"), secrets.empty());
      }
    }
  }

  @Test
  void encryptedSessionSurvivesCloseButLogoutRemovesItAndRestartNeverStartsGrading()
      throws Exception {
    var secrets = new MemorySessionSecrets();
    try (var server = new AccountServer()) {
      var first =
          new DesktopWorkspace(
              directory, Executors.newSingleThreadExecutor(), secrets, server.base());
      first.initialize().get(5, TimeUnit.SECONDS);
      assertFalse(
          Files.exists(directory.resolve("database")),
          "Production startup must not create an H2 database");
      first
          .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
          .get(5, TimeUnit.SECONDS);
      assertFalse(secrets.empty());
      first.close();
      first.awaitClosed().get(5, TimeUnit.SECONDS);
      assertFalse(secrets.empty(), "Normal application exit preserves the protected session");
      var restarted =
          new DesktopWorkspace(
              directory, Executors.newSingleThreadExecutor(), secrets, server.base());
      try (restarted) {
        restarted.initialize().get(5, TimeUnit.SECONDS);
        assertNotNull(restarted.accountView().account());
        assertEquals(
            1, server.loginRequests.get(), "Recovery verifies the token without logging in again");
        assertNull(restarted.status());
        assertNull(restarted.binding());
        assertFalse(restarted.hotkeysAvailable());
        assertEquals(0, server.gradeRequests.get());
        restarted.logoutAccount();
        assertNull(restarted.accountView().account());
      }
      restarted.awaitClosed().get(5, TimeUnit.SECONDS);
      assertTrue(secrets.empty(), "Logout removal must finish before workspace close completes");
    }
  }

  @Test
  void unavailableServerJournalCannotUnlockTheWorkspaceOrFallBackToALocalDatabase()
      throws Exception {
    try (var server = new AccountServer()) {
      server.journalError = "STORE_UNAVAILABLE";
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        assertThrows(
            Exception.class,
            () ->
                workspace
                    .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
                    .get(5, TimeUnit.SECONDS));
        assertNull(workspace.accountView().account());
        assertFalse(workspace.commercialCreditsAvailable());
        assertFalse(Files.exists(directory.resolve("database")));
        assertNull(workspace.status());
        assertEquals(0, server.gradeRequests.get());
        server.journalError = null;
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        assertNotNull(workspace.accountView().account());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void logoutWhileJournalRecoveryIsWaitingDoesNotDeadlockOrPublishTheOldLogin() throws Exception {
    try (var server = new AccountServer()) {
      server.delayJournal = true;
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        var pending =
            workspace.authenticate("fixture_user", "fixture-password-only".toCharArray(), false);
        assertTrue(server.journalEntered.await(5, TimeUnit.SECONDS));
        workspace.logoutAccount();
        assertNull(workspace.accountView().account());
        server.journalRelease.countDown();
        assertThrows(Exception.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertNull(workspace.accountView().account());
        assertEquals(0, server.gradeRequests.get());
      } finally {
        server.journalRelease.countDown();
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void normalLocalRuntimeNeverConfiguresAServiceAndUsesTheSavedTeacherRules() throws Exception {
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      var loaded = workspace.initialize().get(5, TimeUnit.SECONDS);
      assertEquals("", workspace.gatewayAddress());
      assertTrue(workspace.localOnlyRuntime());
      assertThrows(
          Exception.class,
          () -> workspace.saveGatewayAddress("http://127.0.0.1:9").get(5, TimeUnit.SECONDS));
      var saved =
          new app.deskgrade.core.domain.model.Rubric(
              loaded.rubric().question(),
              loaded.rubric().rawRubric(),
              loaded.rubric().scoreStep(),
              true,
              true);
      workspace.saveRubric(saved).get(5, TimeUnit.SECONDS);
      assertEquals(saved, workspace.automaticRubric());
      assertNotEquals(
          DesktopWorkspace.syntheticVisionRubric().question(),
          workspace.automaticRubric().question());
      assertEquals(
          0,
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .total());
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  @Test
  void accountCatalogIsReadOnlyAndPauseRetainsLoginWithoutLoadingPersonalCredentials()
      throws Exception {
    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        assertTrue(workspace.commercialMode());
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        char[] secret = "fixture-password-only".toCharArray();
        var account =
            workspace.authenticate("fixture_user", secret, false).get(5, TimeUnit.SECONDS);
        assertEquals(5, account.account().credits());
        assertArrayEquals(new char[secret.length], secret);
        assertEquals(List.of(server.publicProfile), account.models());
        assertTrue(workspace.hasCredential(server.publicProfile).get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(directory.resolve("credentials")));
        long authorization = workspace.actionTicket();
        long login = workspace.accountTicket();
        workspace.pause(PauseReason.USER_PAUSED);
        assertNotEquals(authorization, workspace.actionTicket());
        assertEquals(login, workspace.accountTicket(), "Pausing must not log the user out");
        assertNotNull(workspace.accountView().account());
        assertThrows(
            Exception.class, () -> workspace.saveProfile(server.profile).get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(directory.resolve("settings/profiles.json")));
        char[] forbiddenKey = "synthetic-not-a-provider-key".toCharArray();
        assertThrows(
            Exception.class,
            () -> workspace.saveCredential(server.profile, forbiddenKey).get(5, TimeUnit.SECONDS));
        assertArrayEquals(new char[forbiddenKey.length], forbiddenKey);
        assertThrows(
            Exception.class,
            () -> workspace.testCapability(server.profile).get(5, TimeUnit.SECONDS));
        assertTrue(
            workspace
                .recordsSource()
                .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .rows()
                .isEmpty());
        assertEquals(0, server.gradeRequests.get());
        var order = workspace.createPackageOrder("pack_fixture").get(5, TimeUnit.SECONDS);
        assertEquals("PENDING_PAYMENT", order.status());
        assertFalse(order.paymentEnabled());
        assertEquals(
            5,
            workspace.accountView().account().credits(),
            "An unpaid order must not grant credits");
        assertEquals(1, workspace.accountView().orders().size());
        String settings = Files.readString(directory.resolve("settings/access.json"));
        assertTrue(settings.contains(server.base().toString()));
        assertFalse(settings.contains("token"));
        assertFalse(settings.contains("password"));
        assertFalse(settings.contains("fixture_user"));
        assertFalse(settings.contains(AccountServer.TOKEN));
        workspace.logoutAccount();
        assertNull(workspace.accountView().account());
        assertFalse(workspace.hasCredential(server.publicProfile).get(5, TimeUnit.SECONDS));
        assertFalse(workspace.commercialCreditsAvailable());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      var restarted = fixtureWorkspace(server);
      try (restarted) {
        restarted.initialize().get(5, TimeUnit.SECONDS);
        assertTrue(restarted.commercialMode());
        assertEquals(server.base().toString(), restarted.gatewayAddress());
        assertNull(
            restarted.accountView().account(),
            "Explicit logout must prevent cached login recovery");
        assertNull(restarted.status());
      }
      restarted.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void rubricGenerationUsesTheAuthenticatedGatewayAndRefreshesTheVisibleBalance() throws Exception {
    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);

        var generated =
            workspace.generateRubric("历史", "标准答案内容", new BigDecimal("2")).get(5, TimeUnit.SECONDS);

        assertEquals(BigDecimal.ONE, generated.scoreStep());
        assertEquals(List.of("P1", "P2"), generated.criteria().stream().map(p -> p.id()).toList());
        assertEquals(1, server.rubricRequests.get());
        assertEquals(0, server.gradeRequests.get());
        assertEquals(4, workspace.accountView().account().credits());
        assertEquals("历史", server.rubricRequest.get().path("subject").asText());
        assertEquals("标准答案内容", server.rubricRequest.get().path("referenceAnswer").asText());
        assertEquals(2, server.rubricRequest.get().path("maxScore").decimalValue().intValueExact());
        Path pending = rubricGenerationPendingPath("acct_fixture", server.base());
        assertTrue(Files.exists(pending));
        var receipt = new ObjectMapper().readTree(Files.readString(pending));
        assertEquals("RESULT_CONFIRMED", receipt.path("state").asText());
        assertFalse(receipt.has("token"));
        assertFalse(receipt.has("imageBase64"));

        var saved =
            new Rubric(
                new QuestionDefinition(
                    "generated-question",
                    1,
                    "题目原文未提供",
                    "标准答案内容",
                    new BigDecimal("2"),
                    generated.criteria()),
                "P1/P2 semantic points",
                generated.scoreStep(),
                false,
                true);
        workspace.saveRubric(saved, "历史").get(5, TimeUnit.SECONDS);
        workspace.finalizeConfirmedRubricGeneration(generated.requestId()).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(pending));
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void lostResponseKeepsPendingIdBlocksDifferentInputAndReplaysWithoutSecondDebit()
      throws Exception {
    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        server.dropNextRubricResponse.set(true);

        var lost =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () ->
                    workspace
                        .generateRubric("历史", "标准答案内容", new BigDecimal("2.0"))
                        .get(5, TimeUnit.SECONDS));
        var network =
            assertInstanceOf(
                app.deskgrade.adapters.commercial.GatewayException.class, lost.getCause());
        assertEquals(
            app.deskgrade.adapters.commercial.GatewayException.Code.NETWORK, network.code());
        Path pending = rubricGenerationPendingPath("acct_fixture", server.base());
        assertTrue(Files.exists(pending));
        var receipt = new ObjectMapper().readTree(Files.readString(pending));
        String originalId = receipt.path("requestId").asText();
        assertEquals("PENDING", receipt.path("state").asText());
        assertEquals(1, server.rubricDispatches.get());
        assertEquals(4, server.credits.get());

        String beforeConflict = Files.readString(pending);
        var conflict =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () ->
                    workspace
                        .generateRubric("历史", "另一个标准答案", new BigDecimal("2"))
                        .get(5, TimeUnit.SECONDS));
        var pendingFailure =
            assertInstanceOf(
                DesktopWorkspace.RubricGenerationPendingException.class, conflict.getCause());
        assertEquals(
            DesktopWorkspace.RubricGenerationPendingFailure.CONFLICT, pendingFailure.reason());
        assertEquals(beforeConflict, Files.readString(pending));
        assertEquals(1, server.rubricRequests.get());

        var replayed =
            workspace.generateRubric("历史", "标准答案内容", new BigDecimal("2")).get(5, TimeUnit.SECONDS);
        assertEquals(originalId, replayed.requestId().toString());
        assertTrue(replayed.replayed());
        assertEquals(List.of(originalId, originalId), server.rubricRequestIds);
        assertEquals(2, server.rubricRequests.get());
        assertEquals(1, server.rubricDispatches.get());
        assertEquals(4, server.credits.get());
        assertEquals(
            "RESULT_CONFIRMED",
            new ObjectMapper().readTree(Files.readString(pending)).path("state").asText());

        // This test explicitly rejects the confirmed synthetic result instead of saving it.
        workspace.finalizeConfirmedRubricGeneration(replayed.requestId()).get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(pending));
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void pendingRubricRequestSurvivesWorkspaceRestartAndReusesTheSameId() throws Exception {
    var sessionSecrets = new MemorySessionSecrets();
    try (var server = new AccountServer()) {
      String requestId;
      var first =
          new DesktopWorkspace(
              directory, Executors.newSingleThreadExecutor(), sessionSecrets, server.base());
      try (first) {
        first.initialize().get(5, TimeUnit.SECONDS);
        first
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        server.dropNextRubricResponse.set(true);
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () ->
                first
                    .generateRubric("历史", "重启恢复标准答案", new BigDecimal("2"))
                    .get(5, TimeUnit.SECONDS));
        requestId =
            new ObjectMapper()
                .readTree(
                    Files.readString(rubricGenerationPendingPath("acct_fixture", server.base())))
                .path("requestId")
                .asText();
      }
      first.awaitClosed().get(5, TimeUnit.SECONDS);

      var restarted =
          new DesktopWorkspace(
              directory, Executors.newSingleThreadExecutor(), sessionSecrets, server.base());
      try (restarted) {
        restarted.initialize().get(5, TimeUnit.SECONDS);
        assertNotNull(restarted.accountView().account());
        var replayed =
            restarted
                .generateRubric("历史", "重启恢复标准答案", new BigDecimal("2.00"))
                .get(5, TimeUnit.SECONDS);
        assertEquals(requestId, replayed.requestId().toString());
        assertTrue(replayed.replayed());
        assertEquals(List.of(requestId, requestId), server.rubricRequestIds);
        assertEquals(1, server.rubricDispatches.get());
        restarted.finalizeConfirmedRubricGeneration(replayed.requestId()).get(5, TimeUnit.SECONDS);
      }
      restarted.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void unresolvedReceiptsAreIsolatedByAccountAndService() throws Exception {
    try (var firstServer = new AccountServer();
        var otherService = new AccountServer()) {
      Path originalPending = rubricGenerationPendingPath("acct_fixture", firstServer.base());
      String originalRequestId;
      var first = fixtureWorkspace(firstServer);
      try (first) {
        first.initialize().get(5, TimeUnit.SECONDS);
        first
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        firstServer.dropNextRubricResponse.set(true);
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () ->
                first.generateRubric("历史", "隔离标准答案", new BigDecimal("2")).get(5, TimeUnit.SECONDS));
        assertTrue(Files.exists(originalPending));
        originalRequestId =
            new ObjectMapper()
                .readTree(Files.readString(originalPending))
                .path("requestId")
                .asText();
      }
      first.awaitClosed().get(5, TimeUnit.SECONDS);

      firstServer.accountId = "acct_other";
      Path otherAccountPending = rubricGenerationPendingPath("acct_other", firstServer.base());
      var otherAccount =
          new DesktopWorkspace(
              directory,
              Executors.newSingleThreadExecutor(),
              new MemorySessionSecrets(),
              firstServer.base());
      try (otherAccount) {
        otherAccount.initialize().get(5, TimeUnit.SECONDS);
        otherAccount
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        var generated =
            otherAccount
                .generateRubric("历史", "隔离标准答案", new BigDecimal("2"))
                .get(5, TimeUnit.SECONDS);
        assertNotEquals(originalRequestId, generated.requestId().toString());
        assertTrue(Files.exists(originalPending));
        assertTrue(Files.exists(otherAccountPending));
        assertEquals(2, firstServer.rubricRequests.get());
        otherAccount
            .finalizeConfirmedRubricGeneration(generated.requestId())
            .get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(otherAccountPending));
        assertTrue(Files.exists(originalPending));
      }
      otherAccount.awaitClosed().get(5, TimeUnit.SECONDS);

      Path otherServicePending = rubricGenerationPendingPath("acct_fixture", otherService.base());
      var otherOrigin =
          new DesktopWorkspace(
              directory,
              Executors.newSingleThreadExecutor(),
              new MemorySessionSecrets(),
              otherService.base());
      try (otherOrigin) {
        otherOrigin.initialize().get(5, TimeUnit.SECONDS);
        otherOrigin
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        var generated =
            otherOrigin
                .generateRubric("历史", "隔离标准答案", new BigDecimal("2"))
                .get(5, TimeUnit.SECONDS);
        assertNotEquals(originalRequestId, generated.requestId().toString());
        assertTrue(Files.exists(originalPending));
        assertTrue(Files.exists(otherServicePending));
        assertEquals(1, otherService.rubricRequests.get());
        otherOrigin
            .finalizeConfirmedRubricGeneration(generated.requestId())
            .get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(otherServicePending));
        assertTrue(Files.exists(originalPending));
      }
      otherOrigin.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void malformedPendingReceiptFailsClosedWithoutOverwriteOrDispatch() throws Exception {
    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        Path pending = rubricGenerationPendingPath("acct_fixture", server.base());
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "{\"schemaVersion\":1,\"requestId\":\"broken\"}");
        String original = Files.readString(pending);

        var failure =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () ->
                    workspace
                        .generateRubric("历史", "标准答案内容", new BigDecimal("2"))
                        .get(5, TimeUnit.SECONDS));
        assertEquals(
            DesktopWorkspace.RubricGenerationPendingFailure.CORRUPT,
            assertInstanceOf(
                    DesktopWorkspace.RubricGenerationPendingException.class, failure.getCause())
                .reason());
        assertEquals(original, Files.readString(pending));
        assertEquals(0, server.rubricRequests.get());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void knownPreDispatchFailureClearsPendingReceipt() throws Exception {
    try (var server = new AccountServer()) {
      server.rubricError = "MODEL_UNAVAILABLE";
      server.rubricErrorChargedCredits = 0;
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        var failure =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () ->
                    workspace
                        .generateRubric("历史", "标准答案内容", new BigDecimal("2"))
                        .get(5, TimeUnit.SECONDS));
        assertEquals(
            app.deskgrade.adapters.commercial.GatewayException.Code.MODEL_UNAVAILABLE,
            assertInstanceOf(
                    app.deskgrade.adapters.commercial.GatewayException.class, failure.getCause())
                .code());
        assertFalse(Files.exists(rubricGenerationPendingPath("acct_fixture", server.base())));
        assertEquals(1, server.rubricRequests.get());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void concurrentDoubleClickSharesOnePendingRequestAndOneDispatch() throws Exception {
    try (var server = new AccountServer()) {
      server.delayRubric = true;
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        var first = workspace.generateRubric("历史", "标准答案内容", new BigDecimal("2"));
        assertTrue(server.rubricEntered.await(5, TimeUnit.SECONDS));
        var second = workspace.generateRubric("历史", "标准答案内容", new BigDecimal("2.0"));
        var different = workspace.generateRubric("历史", "不同标准答案", new BigDecimal("2"));
        assertEquals(1, server.rubricRequests.get());
        server.rubricRelease.countDown();
        var firstResult = first.get(5, TimeUnit.SECONDS);
        var secondResult = second.get(5, TimeUnit.SECONDS);
        assertEquals(firstResult.requestId(), secondResult.requestId());
        assertEquals(1, server.rubricRequests.get());
        assertEquals(1, server.rubricDispatches.get());
        var conflict =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> different.get(5, TimeUnit.SECONDS));
        assertEquals(
            DesktopWorkspace.RubricGenerationPendingFailure.CONFLICT,
            assertInstanceOf(
                    DesktopWorkspace.RubricGenerationPendingException.class, conflict.getCause())
                .reason());
        workspace
            .finalizeConfirmedRubricGeneration(firstResult.requestId())
            .get(5, TimeUnit.SECONDS);
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void zeroBalanceBlocksBothRealEntrypointsBeforeCaptureOrModelRequest() throws Exception {
    try (var server = new AccountServer()) {
      server.credits.set(0);
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), true)
            .get(5, TimeUnit.SECONDS);
        assertNotNull(workspace.accountView().account());
        assertFalse(workspace.commercialCreditsAvailable());
        assertThrows(
            Exception.class,
            () ->
                workspace
                    .trial(DesktopWorkspace.ModelSelection.of(server.publicProfile))
                    .get(5, TimeUnit.SECONDS));
        assertThrows(
            Exception.class,
            () ->
                workspace
                    .startLiveSynthetic(
                        DesktopWorkspace.ModelSelection.of(server.publicProfile),
                        3,
                        3,
                        workspace.actionTicket())
                    .get(5, TimeUnit.SECONDS));
        assertNull(workspace.status());
        assertNull(workspace.binding());
        assertFalse(workspace.hotkeysAvailable());
        assertTrue(
            workspace
                .recordsSource()
                .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .rows()
                .isEmpty());
        assertFalse(Files.exists(directory.resolve("credentials")));
        assertEquals(0, server.gradeRequests.get());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void refreshingNewPolicyRevisionRevokesPermissionEvenWhenModelIdIsUnchanged() throws Exception {
    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        long old = workspace.actionTicket();
        assertEquals(1, workspace.modelPolicyRevision());
        server.policyRevision = 2;
        workspace.refreshAccount().get(5, TimeUnit.SECONDS);
        assertEquals(2, workspace.modelPolicyRevision());
        assertNotEquals(old, workspace.actionTicket());
        assertEquals(List.of(server.publicProfile), workspace.accountView().models());
        assertThrows(Exception.class, () -> workspace.chooseSlot("B").get(5, TimeUnit.SECONDS));
        assertThrows(
            Exception.class, () -> workspace.setCommercialMode(false).get(5, TimeUnit.SECONDS));
        assertTrue(workspace.commercialMode());
        assertEquals(0, server.gradeRequests.get());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void ordinaryStartupIgnoresHistoricByokPreferenceAndPositionFilesDoNotAuthorizeBinding()
      throws Exception {
    Files.createDirectories(directory.resolve("settings"));
    Files.writeString(
        directory.resolve("settings/access.json"),
        "{\"schemaVersion\":1,\"mode\":\"BYOK\",\"serviceAddress\":\"\"}");
    var calibration =
        new app.deskgrade.core.domain.desktop.ManualPlatformCalibration(
            "manual-platform",
            1,
            "Synthetic positions",
            new app.deskgrade.core.domain.desktop.ManualPlatformCalibration.WindowLayout(
                1000, 700, 96, "Synthetic"),
            new app.deskgrade.core.domain.desktop.LogicalRect(10, 10, 500, 300),
            new app.deskgrade.core.domain.desktop.LogicalRect(650, 100, 100, 30),
            new app.deskgrade.core.domain.desktop.LogicalRect(800, 100, 100, 30),
            null,
            Instant.now());
    new app.deskgrade.adapters.calibration.ManualCalibrationStore(
            directory.resolve("settings/manual-calibration.json"))
        .save(calibration);
    var workspace = new DesktopWorkspace(directory);
    try (workspace) {
      workspace.initialize().get(5, TimeUnit.SECONDS);
      assertFalse(workspace.developerMode());
      assertFalse(workspace.commercialMode());
      assertNull(
          workspace.manualCalibration(),
          "Unowned legacy coordinates must not be assigned to a future login");
      assertNull(workspace.binding());
      assertNull(workspace.status());
      assertFalse(workspace.hotkeysAvailable());
      assertDoesNotThrow(() -> workspace.setCommercialMode(false).get(5, TimeUnit.SECONDS));
      assertThrows(
          Exception.class, () -> workspace.setCommercialMode(true).get(5, TimeUnit.SECONDS));
      assertFalse(Files.exists(directory.resolve("credentials")));
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  @Test
  void authenticatedAccountNeverImportsOrWritesLegacyCoordinates() throws Exception {
    Files.createDirectories(directory.resolve("settings"));
    var legacy =
        new app.deskgrade.core.domain.desktop.ManualPlatformCalibration(
            "manual-platform",
            1,
            "Old global positions",
            new app.deskgrade.core.domain.desktop.ManualPlatformCalibration.WindowLayout(
                1000, 700, 96, "Synthetic"),
            new app.deskgrade.core.domain.desktop.LogicalRect(10, 10, 500, 300),
            new app.deskgrade.core.domain.desktop.LogicalRect(650, 100, 100, 30),
            new app.deskgrade.core.domain.desktop.LogicalRect(800, 100, 100, 30),
            null,
            Instant.parse("2026-09-10T12:00:00Z"));
    Path legacyFile = directory.resolve("settings/manual-calibration.json");
    new app.deskgrade.adapters.calibration.ManualCalibrationStore(legacyFile).save(legacy);

    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);

        assertNull(workspace.manualCalibration());
        assertFalse(workspace.automaticTarget());
        assertNull(workspace.binding());
        assertTrue(Files.exists(legacyFile), "The old file is left untouched for manual recovery");
        String owner = DesktopWorkspace.calibrationOwnerKey("acct_fixture", server.base());
        Path accountCatalog =
            directory
                .resolve("settings/accounts")
                .resolve(owner)
                .resolve("manual-calibrations.json");
        assertFalse(Files.exists(accountCatalog), "Login must not create a coordinate catalog");

        workspace.logoutAccount();
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        assertNull(workspace.manualCalibration());
        assertFalse(
            Files.exists(accountCatalog), "Re-login must not import or persist coordinates");
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void corruptUnownedLegacyPositionsNeverBlockLoginOrGainAuthority() throws Exception {
    Files.createDirectories(directory.resolve("settings"));
    Files.writeString(directory.resolve("settings/manual-calibration.json"), "{broken");

    try (var server = new AccountServer()) {
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);
        assertNotNull(workspace.accountView().account());
        assertNull(workspace.manualCalibration());
        assertNull(workspace.binding());
        assertFalse(workspace.automaticTarget());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void obsoleteAccountCatalogIsNeverReadOrOverwrittenDuringLogin() throws Exception {
    try (var server = new AccountServer()) {
      String owner = DesktopWorkspace.calibrationOwnerKey("acct_fixture", server.base());
      Path catalog =
          directory.resolve("settings/accounts").resolve(owner).resolve("manual-calibrations.json");
      Files.createDirectories(catalog.getParent());
      byte[] corrupt = "{broken-account-catalog".getBytes(StandardCharsets.UTF_8);
      Files.write(catalog, corrupt);
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace
            .authenticate("fixture_user", "fixture-password-only".toCharArray(), false)
            .get(5, TimeUnit.SECONDS);

        assertNotNull(workspace.accountView().account());
        assertFalse(workspace.accountView().models().isEmpty());
        assertNull(workspace.manualCalibration());
        assertNull(workspace.binding());
        assertFalse(workspace.automaticTarget());
        assertArrayEquals(corrupt, Files.readAllBytes(catalog));
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void jvmPropertyCannotEnableDeveloperModeOrCloudInTheLocalEdition() throws Exception {
    String previous = System.getProperty("deskgrade.developerMode");
    System.setProperty("deskgrade.developerMode", "true");
    try {
      Files.createDirectories(directory.resolve("settings"));
      Files.writeString(
          directory.resolve("settings/access.json"),
          "{\"schemaVersion\":1,\"mode\":\"BYOK\",\"serviceAddress\":\"\"}");
      var workspace = new DesktopWorkspace(directory);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        assertFalse(workspace.developerMode());
        assertFalse(workspace.commercialMode());
        assertThrows(
            Exception.class, () -> workspace.setCommercialMode(true).get(5, TimeUnit.SECONDS));
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    } finally {
      if (previous == null) System.clearProperty("deskgrade.developerMode");
      else System.setProperty("deskgrade.developerMode", previous);
    }
  }

  @Test
  void logoutBeforeQueuedAuthenticationStartsPreventsAnyLoginRequestAndErasesPassword()
      throws Exception {
    try (var server = new AccountServer()) {
      var worker = Executors.newSingleThreadExecutor();
      var workspace =
          new DesktopWorkspace(directory, worker, new MemorySessionSecrets(), server.base());
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        worker.execute(
            () -> {
              entered.countDown();
              await(release);
            });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        char[] password = "fixture-password-only".toCharArray();
        var pending = workspace.authenticate("fixture_user", password, false);
        long ticket = workspace.actionTicket();
        workspace.logoutAccount();
        assertNotEquals(ticket, workspace.actionTicket());
        release.countDown();
        assertThrows(Exception.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertArrayEquals(new char[password.length], password);
        assertNull(workspace.accountView().account());
        assertEquals(0, server.loginRequests.get());
        assertEquals(0, server.gradeRequests.get());
      } finally {
        release.countDown();
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void serviceEditDuringLoginDiscardsLateSessionAndModelCatalog() throws Exception {
    try (var server = new AccountServer()) {
      server.delayLogin = true;
      var workspace = fixtureWorkspace(server);
      try (workspace) {
        workspace.initialize().get(5, TimeUnit.SECONDS);
        workspace.saveGatewayAddress(server.base().toString()).get(5, TimeUnit.SECONDS);
        var pending =
            workspace.authenticate("fixture_user", "fixture-password-only".toCharArray(), false);
        assertTrue(server.loginEntered.await(5, TimeUnit.SECONDS));
        long ticket = workspace.actionTicket();
        workspace.accountServiceEdited();
        assertNotEquals(ticket, workspace.actionTicket());
        server.release.countDown();
        assertThrows(Exception.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertNull(workspace.accountView().account());
        assertTrue(workspace.accountView().models().isEmpty());
        assertFalse(workspace.commercialCreditsAvailable());
        assertEquals(0, server.gradeRequests.get());
      }
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
    }
  }

  private DesktopWorkspace fixtureWorkspace(AccountServer server) {
    return new DesktopWorkspace(
        directory, Executors.newSingleThreadExecutor(), new MemorySessionSecrets(), server.base());
  }

  private Path rubricGenerationPendingPath(String accountId, URI service) {
    return directory
        .resolve("settings/accounts")
        .resolve(DesktopWorkspace.calibrationOwnerKey(accountId, service))
        .resolve("rubric-generation-pending.json");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static final class AccountServer implements AutoCloseable {
    static final String TOKEN = "fixture_token_only_never_a_real_account_123456789";
    final HttpServer server;
    final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(4);
    final AtomicInteger credits = new AtomicInteger(5),
        requests = new AtomicInteger(),
        loginRequests = new AtomicInteger(),
        gradeRequests = new AtomicInteger(),
        rubricRequests = new AtomicInteger(),
        rubricDispatches = new AtomicInteger();
    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> rubricRequest =
        new AtomicReference<>();
    final java.util.concurrent.CopyOnWriteArrayList<String> rubricRequestIds =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.concurrent.ConcurrentHashMap<String, String> rubricReceipts =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicBoolean dropNextRubricResponse =
        new java.util.concurrent.atomic.AtomicBoolean();
    final CountDownLatch loginEntered = new CountDownLatch(1), release = new CountDownLatch(1);
    final CountDownLatch journalEntered = new CountDownLatch(1),
        journalRelease = new CountDownLatch(1);
    final CountDownLatch rubricEntered = new CountDownLatch(1),
        rubricRelease = new CountDownLatch(1);
    final ObjectMapper json = new ObjectMapper();
    final ProviderProfile profile;
    final PublicModelProfile publicProfile;
    volatile long policyRevision = 1;
    volatile boolean delayLogin;
    volatile boolean delayJournal;
    volatile boolean delayRubric;
    volatile String accountError;
    volatile String journalError;
    volatile String rubricError;
    volatile Integer rubricErrorChargedCredits;
    volatile String accountId = "acct_fixture";
    volatile String orderJson;

    AccountServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var unverified =
          new ProviderProfile(
              "fixture_server_A",
              1,
              "Local gateway fixture",
              URI.create("https://example.invalid/chat/completions"),
              "fixture-vision",
              "server_only_no_desktop_key",
              ProviderProfile.StructuredMode.JSON_OBJECT,
              Map.of(),
              Set.of(),
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              null,
              null);
      // Test fixture metadata only, never proof for a real provider or a production profile.
      profile =
          unverified.withVerification(
              new ProviderProfile.Verification(
                  unverified.configDigest(), Instant.now(), true, true));
      publicProfile =
          new PublicModelProfile(
              profile.id(),
              profile.revision(),
              profile.displayName(),
              profile.modelId(),
              profile.requestTimeout(),
              true,
              PublicModelProfile.Role.PRIMARY);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              if (requests.incrementAndGet() > 60) {
                exchange.sendResponseHeaders(503, -1);
                return;
              }
              String path = exchange.getRequestURI().getPath();
              String failureCode =
                  path.equals("/v1/account")
                      ? accountError
                      : path.equals("/v1/journal") ? journalError : null;
              if (failureCode != null) {
                byte[] error =
                    json.writeValueAsBytes(
                        Map.of(
                            "error",
                            Map.of("code", failureCode, "message", "Synthetic service error"),
                            "chargedCredits",
                            0));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                    failureCode.equals("STORE_UNAVAILABLE") ? 503 : 401, error.length);
                exchange.getResponseBody().write(error);
                return;
              }
              String response;
              int responseStatus = 200;
              if (path.equals("/v1/auth/login") || path.equals("/v1/auth/register")) {
                loginRequests.incrementAndGet();
                loginEntered.countDown();
                if (delayLogin) await(release);
                response =
                    json.writeValueAsString(
                        Map.of(
                            "token",
                            TOKEN,
                            "expiresAt",
                            Instant.now().plusSeconds(600).toString(),
                            "account",
                            account()));
              } else if (path.equals("/v1/auth/logout")) {
                exchange.sendResponseHeaders(204, -1);
                return;
              } else if (path.equals("/v1/account")) response = json.writeValueAsString(account());
              else if (path.equals("/v1/journal")) {
                journalEntered.countDown();
                if (delayJournal) await(journalRelease);
                assertEquals(
                    "Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
                var body = json.readTree(exchange.getRequestBody().readNBytes(1024 * 1024));
                String operation = body.path("operation").asText();
                response =
                    switch (operation) {
                      case "assertWritable" -> "{\"result\":null}";
                      case "recoverInterrupted", "runs", "attempts" -> "{\"result\":[]}";
                      case "queryRecords" -> {
                        var query = body.path("arguments").path("query");
                        yield "{\"result\":{\"rows\":[],\"total\":0,\"page\":"
                            + query.path("page").asInt()
                            + ",\"pageSize\":"
                            + query.path("pageSize").asInt()
                            + "}}";
                      }
                      default -> throw new IllegalStateException("Unexpected journal operation");
                    };
              } else if (path.equals("/v1/model-policy"))
                response =
                    json.createObjectNode()
                        .put("revision", policyRevision)
                        .put("profileId", profile.id())
                        .put("modelId", profile.modelId())
                        .putNull("fallbackProfileId")
                        .putNull("fallbackModelId")
                        .toString();
              else if (path.equals("/v1/models"))
                response =
                    new PublicModelProfileJsonCodec().write(List.of(publicProfile)).toString();
              else if (path.equals("/v1/packages"))
                response =
                    "{\"paymentEnabled\":false,\"packages\":[{\"id\":\"pack_fixture\",\"name\":\"Synthetic package\",\"credits\":100,\"priceMinor\":null,\"currency\":\"CNY\",\"enabled\":true}]}";
              else if (path.equals("/v1/orders") && exchange.getRequestMethod().equals("POST")) {
                var body = json.readTree(exchange.getRequestBody().readNBytes(4096));
                var order =
                    json.createObjectNode()
                        .put("id", "order_fixture")
                        .put("requestId", body.path("requestId").asText())
                        .put("packageId", "pack_fixture")
                        .put("packageName", "Synthetic package")
                        .put("credits", 100)
                        .putNull("priceMinor")
                        .put("currency", "CNY")
                        .put("status", "PENDING_PAYMENT")
                        .put("paymentEnabled", false);
                response = orderJson = order.toString();
              } else if (path.equals("/v1/orders"))
                response = "{\"orders\":[" + (orderJson == null ? "" : orderJson) + "]}";
              else if (path.equals("/v1/rubrics/generate")) {
                rubricRequests.incrementAndGet();
                var body = json.readTree(exchange.getRequestBody().readNBytes(512 * 1024));
                rubricRequest.set(body);
                String requestId = body.path("requestId").asText();
                rubricRequestIds.add(requestId);
                rubricEntered.countDown();
                if (delayRubric) await(rubricRelease);
                if (rubricError != null) {
                  var rejected = json.createObjectNode();
                  rejected
                      .putObject("error")
                      .put("code", rubricError)
                      .put("message", "Synthetic rubric error");
                  rejected.put("requestId", requestId);
                  rejected.put("creditsRemaining", credits.get());
                  if (rubricErrorChargedCredits == null) rejected.putNull("chargedCredits");
                  else rejected.put("chargedCredits", rubricErrorChargedCredits);
                  rejected.put("replayed", false);
                  response = rejected.toString();
                  responseStatus =
                      switch (rubricError) {
                        case "REQUEST_IN_PROGRESS", "DISPATCH_UNKNOWN", "MODEL_POLICY_CHANGED" ->
                            409;
                        case "MODEL_FAILED", "MODEL_RESULT_INVALID" -> 502;
                        case "INTERNAL_ERROR" -> 500;
                        default -> 503;
                      };
                } else {
                  String saved = rubricReceipts.get(requestId);
                  if (saved == null) {
                    rubricDispatches.incrementAndGet();
                    var generated =
                        json.createObjectNode().put("requestId", requestId).put("scoreStep", 1);
                    var points = generated.putArray("criteria");
                    for (String id : List.of("P1", "P2"))
                      points
                          .addObject()
                          .put("id", id)
                          .put("rule", id + " semantic point")
                          .putArray("allowedScores")
                          .add(0)
                          .add(1);
                    generated.put("completedAt", Instant.now().toString());
                    generated.putNull("usage");
                    generated.put("creditsRemaining", credits.decrementAndGet());
                    generated.put("chargedCredits", 1);
                    generated.put("replayed", false);
                    saved = generated.toString();
                    rubricReceipts.put(requestId, saved);
                  } else {
                    var replay =
                        (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(saved);
                    replay.put("creditsRemaining", credits.get());
                    replay.put("replayed", true);
                    saved = replay.toString();
                  }
                  response = saved;
                  if (dropNextRubricResponse.compareAndSet(true, false)) return;
                }
              } else {
                if (path.equals("/v1/grade")) gradeRequests.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                return;
              }
              byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(responseStatus, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) {
              /* A revoked request may close its local connection. */
            }
          });
      server.setExecutor(pool);
      server.start();
    }

    Map<String, Object> account() {
      return Map.of(
          "id", accountId, "username", "fixture_user", "role", "USER", "credits", credits.get());
    }

    URI base() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      release.countDown();
      journalRelease.countDown();
      rubricRelease.countDown();
      server.stop(0);
      pool.shutdownNow();
    }
  }
}
