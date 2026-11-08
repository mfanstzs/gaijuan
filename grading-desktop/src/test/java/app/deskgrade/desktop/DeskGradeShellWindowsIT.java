package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.deskgrade.core.domain.desktop.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in smoke test of only this application's scene; it never injects system input. */
@EnabledOnOs(OS.WINDOWS)
class DeskGradeShellWindowsIT {
  private static final Logger LOG = LoggerFactory.getLogger(DeskGradeShellWindowsIT.class);
  private static final int FX_TIMEOUT_SECONDS = 15;

  @BeforeAll
  static void startToolkit() throws Exception {
    System.setProperty(
        "deskgrade.dataDir", Files.createTempDirectory("deskgrade-ui-smoke-").toString());
    CountDownLatch started = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          started.countDown();
        });
    assertTrue(started.await(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS), "JavaFX startup timed out");
  }

  @AfterAll
  static void stopToolkit() {
    System.clearProperty("deskgrade.dataDir");
    Platform.exit();
  }

  @Test
  @Timeout(20)
  void escapeCancelsOwnSelectionOverlayWithoutCapturingOrInjectingInput() throws Exception {
    AtomicReference<Optional<LogicalRect>> selected = new AtomicReference<>();
    Stage overlay =
        onFxThread(
            () -> {
              var display =
                  new DisplaySnapshot(
                      "synthetic-overlay-test",
                      new PixelRect(0, 0, 800, 600),
                      new PixelRect(0, 0, 800, 600),
                      1,
                      1);
              var window =
                  new WindowSnapshot(
                      new WindowIdentity(1, 1, Instant.EPOCH),
                      "Own overlay test",
                      "Synthetic",
                      new PixelRect(80, 80, 640, 420),
                      new PixelRect(80, 80, 640, 420),
                      display,
                      96,
                      true,
                      true,
                      false,
                      false,
                      true);
              Stage shown = SelectionOverlay.show(window, selected::set);
              assertTrue(shown.isShowing());
              Event.fireEvent(
                  shown.getScene(),
                  new KeyEvent(
                      KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
              assertFalse(
                  shown.isShowing(),
                  "Escape must close the selector before returning a cancellation");
              return shown;
            });
    onFxThread(
        () -> {
          assertNotNull(selected.get());
          assertTrue(selected.get().isEmpty());
          assertFalse(overlay.isShowing());
          return null;
        });
  }

  @Test
  @Timeout(30)
  void allManualPositionOverlaysCancelWithoutSavingOrStartingARequest() throws Exception {
    Path isolated = Files.createTempDirectory("deskgrade-position-cancel-");
    var workspace = new DesktopWorkspace(isolated);
    try (workspace) {
      workspace.initialize().get(5, TimeUnit.SECONDS);
      var display =
          new DisplaySnapshot(
              "position-fixture",
              new PixelRect(0, 0, 1000, 700),
              new PixelRect(0, 0, 1000, 700),
              1,
              1);
      var window =
          new WindowSnapshot(
              new WindowIdentity(1, 1, Instant.EPOCH),
              "Own position selector",
              "Synthetic",
              new PixelRect(10, 10, 900, 600),
              new PixelRect(10, 10, 900, 600),
              display,
              96,
              true,
              true,
              false,
              false,
              true);
      for (String region : List.of("答题区域", "分数输入框", "提交按钮", "下一份按钮")) {
        var selected = new AtomicReference<Optional<LogicalRect>>();
        Stage overlay =
            onFxThread(
                () -> {
                  Stage shown = SelectionOverlay.show(window, region, selected::set);
                  assertTrue(shown.isShowing());
                  assertTrue(
                      shown.getScene().getRoot().getChildrenUnmodifiable().stream()
                          .filter(Label.class::isInstance)
                          .map(Label.class::cast)
                          .anyMatch(label -> label.getText().contains(region)));
                  Event.fireEvent(
                      shown.getScene(),
                      new KeyEvent(
                          KeyEvent.KEY_PRESSED,
                          "",
                          "",
                          KeyCode.ESCAPE,
                          false,
                          false,
                          false,
                          false));
                  assertFalse(shown.isShowing());
                  return shown;
                });
        onFxThread(
            () -> {
              assertNotNull(selected.get());
              assertTrue(selected.get().isEmpty());
              assertFalse(overlay.isShowing());
              return null;
            });
      }
      assertNull(workspace.manualCalibration());
      assertNull(workspace.binding());
      assertNull(workspace.status());
      assertFalse(workspace.hotkeysAvailable());
      assertTrue(
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .rows()
              .isEmpty());
      assertFalse(Files.exists(isolated.resolve("settings/manual-calibration.json")));
      if (Files.isDirectory(isolated.resolve("captures"))) {
        try (var files = Files.list(isolated.resolve("captures"))) {
          assertTrue(files.findAny().isEmpty());
        }
      }
    }
    workspace.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(40)
  void localWorkspaceOpensWithoutAccountsAndNeverCallsModelsOnStartup() throws Exception {
    Path isolated = Files.createTempDirectory("deskgrade-local-ui-");
    var workspace = new DesktopWorkspace(isolated);
    Stage stage =
        onFxThread(
            () -> {
              Stage shown = new Stage();
              Scene scene = new Scene(new DeskGradeShell(workspace, shown), 1100, 860);
              scene
                  .getStylesheets()
                  .add(
                      DeskGradeApplication.class
                          .getResource("/app/deskgrade/desktop/shell.css")
                          .toExternalForm());
              shown.setScene(scene);
              shown.show();
              return shown;
            });
    try {
      awaitLoaded(workspace);
      saveSceneSnapshot(
          onFxThread(
              () -> {
                Parent root = stage.getScene().getRoot();
                ((TabPane) find(root, "main-tabs")).getSelectionModel().select(4);
                root.applyCss();
                root.layout();
                return stage.getScene().snapshot(null);
              }),
          Path.of("target/ui-smoke-model-fresh-overview.png"));
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            TabPane tabs = (TabPane) find(root, "main-tabs");
            assertNotNull(tabs);
            assertEquals(
                List.of("阅卷", "平台配置", "评分规则", "记录", "模型与 Key"),
                tabs.getTabs().stream().map(Tab::getText).toList());
            assertFalse(tabs.isDisabled());
            for (String id :
                List.of(
                    "login-surface",
                    "authentication-form",
                    "account-login",
                    "account-register",
                    "account-username",
                    "account-password",
                    "account-header",
                    "account-balance",
                    "account-refresh",
                    "account-logout",
                    "credit-package",
                    "create-package-order",
                    "gateway-address",
                    "access-mode",
                    "run-mode",
                    "mark-identity",
                    "verify-identity"))
              assertNull(find(root, id), "Local scene must not contain " + id);
            assertTrue(find(root, "embedded-browser-entry").isVisible());
            TextField website = (TextField) find(root, "embedded-browser-url");
            assertEquals("", website.getText());
            assertEquals("请在此打开评分网站", website.getPromptText());
            assertTrue(find(root, "open-embedded-browser").isDisabled());
            website.setText("http://127.0.0.1:8766/");
            assertFalse(find(root, "open-embedded-browser").isDisabled());
            website.clear();
            assertTrue(find(root, "open-embedded-browser").isDisabled());
            TextField quantity = (TextField) find(root, "max-submissions");
            assertEquals("5", quantity.getText());
            assertFalse(quantity.isDisabled());
            for (String id :
                List.of(
                    "start-auto",
                    "pause",
                    "stop",
                    "mark-answer",
                    "mark-score",
                    "mark-submit",
                    "bind-current-positions",
                    "generate-rubric",
                    "capability-test",
                    "test-text-capability",
                    "save-key",
                    "forget-key")) {
              Button action = (Button) find(root, id);
              assertTrue(action.isDisabled(), id);
              action.fire();
            }
            assertEquals("0", ((Label) find(root, "confirmed-submissions")).getText());
            assertTrue(((Label) find(root, "start-blockers")).getText().contains("Key"));
            assertNotNull(find(root, "scoring-model"));
            assertNotNull(find(root, "fallback-model"));
            assertNotNull(find(root, "rubric-generation-model"));
            tabs.getSelectionModel().select(4);
            assertTrue(find(root, "model-configuration-overview").isVisible());
            assertFalse(find(root, "model-setup-wizard").isVisible());
            assertFalse(find(root, "model-setup-wizard").isManaged());
            ((Button) find(root, "edit-primary-model")).fire();
            assertTrue(find(root, "model-setup-wizard").isVisible());
            assertEquals(
                LocalModelSetup.Purpose.PRIMARY,
                ((ComboBox<?>) find(root, "model-purpose")).getValue());
            ((Button) find(root, "model-setup-next")).fire();
            assertTrue(((Label) find(root, "start-blockers")).getText().contains("配置向导"));
            assertTrue(
                ((javafx.scene.control.TitledPane) find(root, "model-advanced-settings"))
                        .isExpanded()
                    == false);
            assertFalse(find(root, "model-slot").isDisabled());
            assertFalse(find(root, "model-endpoint").isDisabled());
            assertFalse(find(root, "api-key").isDisabled());
            assertEquals("", ((PasswordField) find(root, "api-key")).getText());
            return null;
          });
      saveSceneSnapshot(
          onFxThread(() -> stage.getScene().snapshot(null)),
          Path.of("target/ui-smoke-model-setup-key-step.png"));
      assertTrue(
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .rows()
              .isEmpty());
      assertNull(workspace.status());
      assertNull(workspace.binding());
      assertTrue(workspace.profiles().stream().noneMatch(p -> p.isVerified()));
      LOG.atInfo()
          .addKeyValue("event", "local_windows_ui_verified")
          .addKeyValue("account_widgets", 0)
          .addKeyValue("model_requests", 0)
          .addKeyValue("tab_count", 5)
          .log("Local shell smoke checks passed");
    } finally {
      workspace.close();
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      onFxThread(
          () -> {
            stage.close();
            return null;
          });
    }
  }

  @Test
  @Timeout(30)
  void providerPresetsAndEditorSelectionNeverChangeSavedModelRoles() throws Exception {
    var workspace = new DesktopWorkspace(Files.createTempDirectory("deskgrade-local-preset-ui-"));
    Stage stage =
        onFxThread(
            () -> {
              Stage shown = new Stage();
              shown.setScene(new Scene(new DeskGradeShell(workspace, shown), 1100, 860));
              shown.show();
              return shown;
            });
    try {
      awaitLoaded(workspace);
      var originalRoles = workspace.localModelSelection();
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            TabPane tabs = (TabPane) find(root, "main-tabs");
            tabs.getSelectionModel().select(4);
            ((Button) find(root, "edit-primary-model")).fire();
            ((Button) find(root, "model-setup-next")).fire();
            var busyField = DeskGradeShell.class.getDeclaredField("busy");
            busyField.setAccessible(true);
            var update = DeskGradeShell.class.getDeclaredMethod("updateActions");
            update.setAccessible(true);
            busyField.setBoolean(root, true);
            update.invoke(root);
            String modelBeforeSave = ((TextField) find(root, "model-id")).getText();
            String endpointBeforeSave = ((TextField) find(root, "model-endpoint")).getText();
            try {
              for (String id :
                  List.of(
                      "qwen-model-candidate",
                      "apply-qwen-preset",
                      "apply-openai-preset",
                      "qwen-thinking-mode"))
                assertTrue(find(root, id).isDisabled(), "Saving must lock " + id);
              ((Button) find(root, "apply-openai-preset")).fire();
              @SuppressWarnings("unchecked")
              ComboBox<String> lockedCandidate =
                  (ComboBox<String>) find(root, "qwen-model-candidate");
              lockedCandidate.setValue("qwen3.8-max");
              assertEquals(modelBeforeSave, ((TextField) find(root, "model-id")).getText());
              assertEquals(
                  endpointBeforeSave, ((TextField) find(root, "model-endpoint")).getText());
            } finally {
              busyField.setBoolean(root, false);
              update.invoke(root);
            }
            @SuppressWarnings("unchecked")
            ComboBox<String> editor = (ComboBox<String>) find(root, "model-slot");
            assertTrue(editor.getItems().contains(originalRoles.primaryId()));
            assertTrue(editor.getItems().contains(originalRoles.rubricId()));
            editor.setValue(originalRoles.rubricId());
            assertEquals(originalRoles, workspace.localModelSelection());
            @SuppressWarnings("unchecked")
            ComboBox<String> qwen = (ComboBox<String>) find(root, "qwen-model-candidate");
            qwen.setValue("qwen3.8-flash");
            ((Button) find(root, "apply-qwen-preset")).fire();
            assertEquals("qwen3.8-flash", ((TextField) find(root, "model-id")).getText());
            assertEquals(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                ((TextField) find(root, "model-endpoint")).getText());
            assertTrue(find(root, "capability-test").isDisabled());
            ((Button) find(root, "apply-openai-preset")).fire();
            assertEquals(
                "https://api.openai.com/v1/chat/completions",
                ((TextField) find(root, "model-endpoint")).getText());
            assertEquals("", ((TextField) find(root, "model-id")).getText());
            assertTrue(find(root, "qwen-thinking-mode").isDisabled());
            assertEquals(
                QwenThinkingOptions.Mode.KEEP,
                ((ComboBox<?>) find(root, "qwen-thinking-mode")).getValue());
            assertTrue(((Label) find(root, "qwen-thinking-hint")).getText().contains("不会向 OpenAI"));
            assertTrue(find(root, "save-model").isDisabled());
            @SuppressWarnings("unchecked")
            ComboBox<LocalModelSetup.Vendor> vendor =
                (ComboBox<LocalModelSetup.Vendor>) find(root, "model-provider");
            vendor.setValue(LocalModelSetup.Vendor.DOUBAO);
            assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                ((TextField) find(root, "model-endpoint")).getText());
            assertEquals(
                "doubao-seed-2-1-lite-260915", ((TextField) find(root, "model-id")).getText());
            assertTrue(find(root, "qwen-thinking-mode").isDisabled());
            assertEquals(
                QwenThinkingOptions.Mode.KEEP,
                ((ComboBox<?>) find(root, "qwen-thinking-mode")).getValue());
            assertEquals("", ((PasswordField) find(root, "api-key")).getText());
            assertEquals(originalRoles, workspace.localModelSelection());
            assertNull(workspace.status());
            ((Button) find(root, "model-setup-cancel")).fire();
            assertEquals(originalRoles, workspace.localModelSelection());
            assertFalse(find(root, "model-setup-wizard").isVisible());
            assertEquals("", ((PasswordField) find(root, "api-key")).getText());
            return null;
          });
    } finally {
      workspace.close();
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      onFxThread(
          () -> {
            stage.close();
            return null;
          });
    }
  }

  @Test
  @Timeout(40)
  void rubricVendorSwitchReusesSavedQwenAndOnlyActivatesAfterFinish() throws Exception {
    Path isolated = Files.createTempDirectory("deskgrade-rubric-vendor-ui-");
    Files.createDirectories(isolated.resolve("settings"));
    Files.createDirectories(isolated.resolve("credentials"));
    var configured = new java.util.ArrayList<app.deskgrade.core.domain.model.ProviderProfile>();
    for (var profile : LocalModelDefaults.profiles()) {
      configured.add(
          profile.withVerification(
              new app.deskgrade.core.domain.model.ProviderProfile.Verification(
                  profile.configDigest(), Instant.EPOCH, true, true)));
    }
    var legacy =
        LocalModelSetup.savedOrIsolatedDraft(
            null,
            LocalModelSetup.Purpose.RUBRIC,
            "Previously selected OpenAI",
            java.net.URI.create(LocalModelSetup.Vendor.OPENAI.endpoint),
            "gpt-6-luna",
            app.deskgrade.core.domain.model.ProviderProfile.StructuredMode.JSON_OBJECT,
            true,
            java.util.UUID.randomUUID());
    legacy =
        legacy.withVerification(
            new app.deskgrade.core.domain.model.ProviderProfile.Verification(
                legacy.configDigest(), Instant.EPOCH, false, true));
    configured.add(legacy);
    Files.writeString(
        isolated.resolve("settings/local-profiles.json"),
        new app.deskgrade.adapters.model.ProfileJsonCodec().write(configured));
    var initial = LocalModelDefaults.selection();
    Files.writeString(
        isolated.resolve("settings/local-model-selection.json"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                java.util.Map.of(
                    "schemaVersion",
                    1,
                    "primaryId",
                    initial.primaryId(),
                    "fallbackId",
                    initial.fallbackId(),
                    "rubricId",
                    legacy.id())));
    for (var profile : configured) {
      Path marker = isolated.resolve("credentials").resolve(profile.credentialRef() + ".dpapi");
      if (!Files.exists(marker)) Files.createFile(marker);
    }
    // Presence-only synthetic credentials. No decryption, paid validation or model requests.
    var expected =
        LocalModelSetup.savedVendorProfile(
            configured,
            LocalModelSetup.Purpose.RUBRIC,
            LocalModelSetup.Vendor.QWEN,
            legacy.id(),
            ignored -> true);
    var workspace = new DesktopWorkspace(isolated);
    Stage stage =
        onFxThread(
            () -> {
              Stage shown = new Stage();
              shown.setScene(new Scene(new DeskGradeShell(workspace, shown), 1100, 860));
              shown.show();
              return shown;
            });
    try {
      awaitLoaded(workspace);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!onFxThread(
              () ->
                  ((Label) find(stage.getScene().getRoot(), "model-summary-fallback"))
                      .getText()
                      .contains("已验证，可直接使用"))
          && System.nanoTime() < until) Thread.sleep(50);
      var oldRoles = workspace.localModelSelection();
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            ((TabPane) find(root, "main-tabs")).getSelectionModel().select(4);
            ((Button) find(root, "edit-rubric-model")).fire();
            @SuppressWarnings("unchecked")
            ComboBox<LocalModelSetup.Vendor> vendor =
                (ComboBox<LocalModelSetup.Vendor>) find(root, "model-provider");
            assertEquals(LocalModelSetup.Vendor.OPENAI, vendor.getValue());
            vendor.setValue(LocalModelSetup.Vendor.QWEN);
            assertEquals(expected.id(), ((ComboBox<?>) find(root, "model-slot")).getValue());
            assertEquals(expected.modelId(), ((TextField) find(root, "model-id")).getText());
            assertEquals(
                LocalModelSetup.Vendor.QWEN.endpoint,
                ((TextField) find(root, "model-endpoint")).getText());
            assertEquals("", ((PasswordField) find(root, "api-key")).getText());
            assertEquals(oldRoles, workspace.localModelSelection());
            assertEquals(configured.size(), workspace.profiles().size());
            ((Button) find(root, "model-setup-cancel")).fire();
            assertEquals(oldRoles, workspace.localModelSelection());
            ((Button) find(root, "edit-rubric-model")).fire();
            vendor.setValue(LocalModelSetup.Vendor.QWEN);
            ((Button) find(root, "model-setup-next")).fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(() -> find(stage.getScene().getRoot(), "model-setup-save").isDisabled())
          && System.nanoTime() < until) Thread.sleep(50);
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            assertFalse(
                find(root, "model-setup-save").isDisabled(), "Existing Qwen Key must be reusable");
            ((Button) find(root, "model-setup-save")).fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(
              () ->
                  !find(stage.getScene().getRoot(), "model-setup-next").isVisible()
                      || find(stage.getScene().getRoot(), "model-setup-next").isDisabled())
          && System.nanoTime() < until) Thread.sleep(50);
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            assertEquals(oldRoles, workspace.localModelSelection());
            assertFalse(
                find(root, "model-setup-next").isDisabled(),
                "No paid revalidation for unchanged verified Qwen");
            assertTrue(
                find(root, "model-setup-next").isVisible(),
                "Must reach the verification step, not fire a hidden control");
            ((Button) find(root, "model-setup-next")).fire();
            assertFalse(find(root, "model-setup-finish").isDisabled());
            ((Button) find(root, "model-setup-finish")).fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(() -> find(stage.getScene().getRoot(), "model-setup-wizard").isManaged())
          && System.nanoTime() < until) Thread.sleep(50);
      assertEquals(
          new DesktopWorkspace.LocalModelSelection(
              oldRoles.primaryId(), oldRoles.fallbackId(), expected.id()),
          workspace.localModelSelection());
      assertEquals(configured.size(), workspace.profiles().size());
      assertTrue(workspace.profiles().containsAll(configured));
      assertTrue(workspace.generationModelAvailable());
      assertTrue(
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .rows()
              .isEmpty());
    } finally {
      workspace.close();
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      onFxThread(
          () -> {
            stage.close();
            return null;
          });
    }
    var reopened = new DesktopWorkspace(isolated);
    try (reopened) {
      reopened.initialize().get(10, TimeUnit.SECONDS);
      assertEquals(expected.id(), reopened.localModelSelection().rubricId());
      assertTrue(reopened.profiles().containsAll(configured));
    }
    reopened.awaitClosed().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(40)
  void verifiedConfigurationStaysCompactAndCanFinishWithoutPaidRetesting() throws Exception {
    Path isolated = Files.createTempDirectory("deskgrade-verified-wizard-ui-");
    Files.createDirectories(isolated.resolve("settings"));
    Files.createDirectories(isolated.resolve("credentials"));
    var configured =
        LocalModelDefaults.profiles().stream()
            .map(
                p ->
                    p.withVerification(
                        new app.deskgrade.core.domain.model.ProviderProfile.Verification(
                            p.configDigest(),
                            Instant.EPOCH,
                            !p.id().equals(LocalModelDefaults.selection().rubricId()),
                            true)))
            .toList();
    Files.writeString(
        isolated.resolve("settings/local-profiles.json"),
        new app.deskgrade.adapters.model.ProfileJsonCodec().write(configured));
    // Presence-only fixture, never decrypted or submitted: no real credentials or model calls.
    for (var p : configured) {
      Path marker = isolated.resolve("credentials").resolve(p.credentialRef() + ".dpapi");
      if (!Files.exists(marker)) Files.createFile(marker);
    }
    var workspace = new DesktopWorkspace(isolated);
    Stage stage =
        onFxThread(
            () -> {
              Stage shown = new Stage();
              Scene scene = new Scene(new DeskGradeShell(workspace, shown), 1100, 860);
              scene
                  .getStylesheets()
                  .add(
                      DeskGradeApplication.class
                          .getResource("/app/deskgrade/desktop/shell.css")
                          .toExternalForm());
              shown.setScene(scene);
              shown.show();
              return shown;
            });
    try {
      awaitLoaded(workspace);
      workspace.profiles();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!onFxThread(
              () ->
                  ((Label) find(stage.getScene().getRoot(), "model-summary-primary"))
                      .getText()
                      .contains("已验证，可直接使用"))
          && System.nanoTime() < until) Thread.sleep(50);
      var roles = workspace.localModelSelection();
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            ((TabPane) find(root, "main-tabs")).getSelectionModel().select(4);
            assertTrue(find(root, "model-configuration-overview").isVisible());
            assertFalse(find(root, "model-setup-wizard").isManaged());
            assertTrue(
                ((Label) find(root, "model-summary-primary")).getText().contains("已验证，可直接使用"));
            ((TextField) find(root, "rubric-subject")).setText("历史");
            ((TextArea) find(root, "reference-answer")).setText("有利于坚守，不利于强攻。");
            ((TextField) find(root, "maximum-score")).setText("8");
            ((TextArea) find(root, "criteria-editor")).clear();
            assertFalse(
                find(root, "generate-rubric").isDisabled(),
                "Empty criteria are the input to generation, not a blocker");
            ((Button) find(root, "edit-primary-model")).fire();
            assertEquals(roles, workspace.localModelSelection());
            assertFalse(((Label) find(root, "start-blockers")).getText().contains("未保存修改"));
            assertTrue(((Label) find(root, "model-summary-primary")).getText().contains("供应商默认"));
            ((Button) find(root, "model-setup-next")).fire();
            @SuppressWarnings("unchecked")
            ComboBox<QwenThinkingOptions.Mode> thinking =
                (ComboBox<QwenThinkingOptions.Mode>) find(root, "qwen-thinking-mode");
            assertFalse(thinking.isDisabled());
            assertEquals(QwenThinkingOptions.Mode.KEEP, thinking.getValue());
            thinking.setValue(QwenThinkingOptions.Mode.LOW);
            assertTrue(find(root, "capability-test").isDisabled());
            assertEquals(roles, workspace.localModelSelection());
            assertEquals(
                configured,
                workspace.profiles().stream()
                    .sorted(
                        java.util.Comparator.comparing(
                            app.deskgrade.core.domain.model.ProviderProfile::id))
                    .toList());
            ((Button) find(root, "model-setup-cancel")).fire();
            ((Button) find(root, "edit-primary-model")).fire();
            assertEquals(QwenThinkingOptions.Mode.KEEP, thinking.getValue());
            assertFalse(((Label) find(root, "start-blockers")).getText().contains("未保存修改"));
            ((TextField) find(root, "model-id")).setText("unassigned-draft");
            assertTrue(thinking.isDisabled());
            assertEquals(QwenThinkingOptions.Mode.KEEP, thinking.getValue());
            assertFalse(
                find(root, "generate-rubric").isDisabled(),
                "An unassigned model draft cannot disable the active generation model");
            ((TextField) find(root, "maximum-score")).setText("0");
            assertTrue(find(root, "generate-rubric").isDisabled());
            assertTrue(
                DisabledActionHints.explanation((Button) find(root, "generate-rubric"))
                    .contains("满分"));
            assertTrue(((Label) find(root, "generate-rubric-blockers")).getText().contains("满分"));
            ((TextField) find(root, "maximum-score")).setText("8");
            ((Button) find(root, "model-setup-cancel")).fire();
            assertEquals(roles, workspace.localModelSelection());
            assertEquals(
                configured,
                workspace.profiles().stream()
                    .sorted(
                        java.util.Comparator.comparing(
                            app.deskgrade.core.domain.model.ProviderProfile::id))
                    .toList());
            return null;
          });
      saveSceneSnapshot(
          onFxThread(
              () -> {
                var root = stage.getScene().getRoot();
                root.applyCss();
                root.layout();
                return stage.getScene().snapshot(null);
              }),
          Path.of("target/ui-smoke-model-ready-overview.png"));
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            ((Button) find(root, "edit-primary-model")).fire();
            ((Button) find(root, "model-setup-next")).fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(() -> find(stage.getScene().getRoot(), "model-setup-save").isDisabled())
          && System.nanoTime() < until) Thread.sleep(50);
      onFxThread(
          () -> {
            Button save = (Button) find(stage.getScene().getRoot(), "model-setup-save");
            assertFalse(save.isDisabled());
            save.fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(() -> find(stage.getScene().getRoot(), "model-setup-next").isDisabled())
          && System.nanoTime() < until) Thread.sleep(50);
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            assertFalse(
                find(root, "model-setup-next").isDisabled(),
                "Existing verification must be reusable");
            ((Button) find(root, "model-setup-next")).fire();
            assertFalse(find(root, "model-setup-finish").isDisabled());
            ((Button) find(root, "model-setup-finish")).fire();
            assertFalse(find(root, "model-setup-wizard").isManaged());
            assertEquals(roles, workspace.localModelSelection());
            assertEquals(
                3,
                workspace.profiles().size(),
                "No replacement draft for an unchanged valid config");
            assertNull(workspace.status());
            return null;
          });
      assertTrue(
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .rows()
              .isEmpty());
    } finally {
      workspace.close();
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      onFxThread(
          () -> {
            stage.close();
            return null;
          });
    }
  }

  private static void awaitLoaded(DesktopWorkspace workspace) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while ((!workspace.ready() || workspace.restoringAccount()) && System.nanoTime() < until)
      Thread.sleep(100);
    assertTrue(workspace.ready());
    assertFalse(workspace.restoringAccount());
    onFxThread(() -> null);
  }

  @Test
  @Timeout(35)
  void thinkingModeSavesOnlyAnUnverifiedDraftUntilExplicitCapabilityTestAndFinish()
      throws Exception {
    Path isolated = Files.createTempDirectory("deskgrade-thinking-draft-ui-");
    Files.createDirectories(isolated.resolve("settings"));
    Files.createDirectories(isolated.resolve("credentials"));
    var configured =
        LocalModelDefaults.profiles().stream()
            .map(
                p ->
                    p.withVerification(
                        new app.deskgrade.core.domain.model.ProviderProfile.Verification(
                            p.configDigest(), Instant.EPOCH, true, true)))
            .toList();
    Files.writeString(
        isolated.resolve("settings/local-profiles.json"),
        new app.deskgrade.adapters.model.ProfileJsonCodec().write(configured));
    for (var profile : configured) {
      Path marker = isolated.resolve("credentials").resolve(profile.credentialRef() + ".dpapi");
      if (!Files.exists(marker)) Files.createFile(marker);
    }
    var workspace = new DesktopWorkspace(isolated);
    Stage stage =
        onFxThread(
            () -> {
              var shown = new Stage();
              shown.setScene(new Scene(new DeskGradeShell(workspace, shown), 1100, 860));
              shown.show();
              return shown;
            });
    try {
      awaitLoaded(workspace);
      var originalRoles = workspace.localModelSelection();
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            ((TabPane) find(root, "main-tabs")).getSelectionModel().select(4);
            ((Button) find(root, "edit-primary-model")).fire();
            ((Button) find(root, "model-setup-next")).fire();
            @SuppressWarnings("unchecked")
            ComboBox<QwenThinkingOptions.Mode> thinking =
                (ComboBox<QwenThinkingOptions.Mode>) find(root, "qwen-thinking-mode");
            thinking.setValue(QwenThinkingOptions.Mode.LOW);
            return null;
          });
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(() -> find(stage.getScene().getRoot(), "model-setup-save").isDisabled())
          && System.nanoTime() < until) Thread.sleep(40);
      onFxThread(
          () -> {
            Button save = (Button) find(stage.getScene().getRoot(), "model-setup-save");
            assertFalse(save.isDisabled());
            save.fire();
            return null;
          });
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (onFxThread(
              () ->
                  originalRoles
                      .primaryId()
                      .equals(
                          ((ComboBox<?>) find(stage.getScene().getRoot(), "model-slot"))
                              .getValue()))
          && System.nanoTime() < until) Thread.sleep(40);
      onFxThread(
          () -> {
            Parent root = stage.getScene().getRoot();
            String selected = (String) ((ComboBox<?>) find(root, "model-slot")).getValue();
            assertTrue(selected.startsWith("draft_primary_"));
            var draft =
                workspace.profiles().stream()
                    .filter(p -> p.id().equals(selected))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                java.util.Map.of("enable_thinking", "true", "reasoning_effort", "low"),
                draft.parameters());
            assertNull(draft.verification());
            assertTrue(find(root, "model-setup-next").isDisabled());
            assertTrue(find(root, "model-setup-finish").isDisabled());
            assertEquals(originalRoles, workspace.localModelSelection());
            var original =
                workspace.profiles().stream()
                    .filter(p -> p.id().equals(originalRoles.primaryId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(original.isVerified());
            assertTrue(original.parameters().isEmpty());
            ((Button) find(root, "model-setup-cancel")).fire();
            assertEquals(originalRoles, workspace.localModelSelection());
            assertTrue(((Label) find(root, "model-summary-primary")).getText().contains("供应商默认"));
            assertFalse(find(root, "model-setup-wizard").isManaged());
            return null;
          });
      assertNull(workspace.status());
      assertTrue(
          workspace
              .recordsSource()
              .query(app.deskgrade.core.application.session.GradingRecords.Query.firstPage())
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .rows()
              .isEmpty());
    } finally {
      workspace.close();
      workspace.awaitClosed().get(5, TimeUnit.SECONDS);
      onFxThread(
          () -> {
            stage.close();
            return null;
          });
    }
  }

  private static void collectTextAreas(Node node, List<TextArea> areas) {
    if (node instanceof ScrollPane scroll) {
      collectTextAreas(scroll.getContent(), areas);
      return;
    }
    if (node instanceof TextArea area) {
      areas.add(area);
      return;
    }
    if (node instanceof Parent parent)
      for (Node child : parent.getChildrenUnmodifiable()) collectTextAreas(child, areas);
  }

  private static void collectButtons(Node node, List<Button> buttons) {
    if (node instanceof ScrollPane scroll) {
      collectButtons(scroll.getContent(), buttons);
      return;
    }
    if (node instanceof Button button) {
      buttons.add(button);
    }
    if (node instanceof Parent parent) {
      for (Node child : parent.getChildrenUnmodifiable()) {
        collectButtons(child, buttons);
      }
    }
  }

  private static Node find(TabPane tabs, String id) {
    for (Tab tab : tabs.getTabs()) {
      Node found = find(tab.getContent(), id);
      if (found != null) return found;
    }
    throw new AssertionError("Missing control " + id);
  }

  private static boolean hasLabel(Node node, String text) {
    if (node instanceof Label label && text.equals(label.getText())) return true;
    if (node instanceof ScrollPane scroll) return hasLabel(scroll.getContent(), text);
    if (node instanceof Parent parent)
      return parent.getChildrenUnmodifiable().stream().anyMatch(child -> hasLabel(child, text));
    return false;
  }

  private static Node find(Node node, String id) {
    if (node == null) return null;
    if (id.equals(node.getId())) return node;
    if (node instanceof TabPane tabs) {
      for (Tab tab : tabs.getTabs()) {
        Node found = find(tab.getContent(), id);
        if (found != null) return found;
      }
      return null;
    }
    if (node instanceof ScrollPane scroll) return find(scroll.getContent(), id);
    if (node instanceof TitledPane titled) return find(titled.getContent(), id);
    if (node instanceof Parent parent)
      for (Node child : parent.getChildrenUnmodifiable()) {
        Node found = find(child, id);
        if (found != null) return found;
      }
    return null;
  }

  private static <T> T onFxThread(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void saveSceneSnapshot(WritableImage image, Path target) throws IOException {
    int width = (int) image.getWidth();
    int height = (int) image.getHeight();
    BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    PixelReader pixels = image.getPixelReader();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        buffered.setRGB(x, y, pixels.getArgb(x, y));
      }
    }
    Files.createDirectories(target.toAbsolutePath().getParent());
    assertTrue(ImageIO.write(buffered, "png", target.toFile()), "PNG writer is required");
    assertTrue(Files.size(target) > 0);
  }
}
