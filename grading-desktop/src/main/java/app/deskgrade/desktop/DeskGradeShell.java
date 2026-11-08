package app.deskgrade.desktop;

import app.deskgrade.adapters.commercial.GatewayException;
import app.deskgrade.adapters.model.QwenEndpointCatalog;
import app.deskgrade.adapters.persistence.PersistentSessionJournal;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.*;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import app.deskgrade.core.domain.model.*;
import app.deskgrade.core.error.DeskGradeException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;

/** Local-only workspace: credentials and grading history remain on this computer. */
final class DeskGradeShell extends VBox {
  record StartReadiness(
      boolean initialized,
      boolean bound,
      boolean automaticMode,
      boolean automaticTarget,
      boolean liveModelRequired,
      boolean modelPresent,
      boolean modelVerified,
      boolean credentialPresent,
      boolean modelClean,
      boolean rubricRequired,
      boolean rubricReady,
      boolean hotkeysAvailable,
      boolean operationBusy,
      boolean running,
      boolean submissionUnknown,
      boolean validSubmissionCount) {}

  private final DesktopWorkspace workspace;
  private final Stage stage;
  private final Label state = label("尚未配置", "status-pill"),
      notice = label("尚未开启自动阅卷", "muted"),
      storage = label("正在打开本地记录…", "muted"),
      bindingLabel = label("未绑定；重启后须重新选择窗口", "muted"),
      hotkeyLabel = label("全局暂停/停止热键未注册", "muted"),
      modelLabel = label("未配置真实模型", "muted"),
      gradeLabel = label("AI 尚未评分", "score-empty"),
      confirmed = label("0", "metric-number"),
      completionCaption = label("已处理份数", "card-title"),
      calls = label("模型调用 0 次", "muted"),
      usage = label("耗时与用量尚无数据", "muted"),
      rubricStatus = label("规则尚未保存", "muted"),
      generationHint = label("", "muted"),
      startReadiness = label("正在检查开始条件…", "muted");
  private final ImageView recordImage = imageView(650, 220);
  private final TextArea result = area(5),
      recordDetail = area(6),
      reference = area(3),
      criteria = area(4);
  private final ComboBox<String> mode = combo("真实模型 · 只读试评", "MOCK · 本地模拟自动", "真实模型 · 已绑定目标自动（付费）"),
      slot = combo("profile_A", "profile_B", "profile_C"),
      recordState = combo("全部状态", "已确认", "未确认", "提交未知");
  private final ComboBox<String> qwenModels =
      new ComboBox<>(
          FXCollections.observableArrayList(
              QwenEndpointCatalog.qwenAiVisionModels().stream()
                  .map(QwenEndpointCatalog.ModelCandidate::modelId)
                  .toList()));
  private final ComboBox<WindowsWindowService.WindowChoice> windows = new ComboBox<>();
  private final Button start = button("开始模拟自动", "start-auto"),
      pause = button("暂停", "pause"),
      stop = button("停止", "stop"),
      recoverUnresolved = button("处理旧未决提交", "recover-unresolved"),
      refreshWindows = button("刷新窗口列表", "refresh-windows"),
      hotkeys = button("注册全局热键", "register-hotkeys"),
      capability = button("验证图像能力（供应商可能计费）", "capability-test"),
      saveModel = button("保存模型配置", "save-model"),
      saveKey = button("加密缓存 Key", "save-key"),
      forgetKey = button("忘记此 Key", "forget-key"),
      textCapability = button("验证文本生成能力（供应商可能计费）", "test-text-capability"),
      saveModelRoles = button("保存模型用途", "save-model-roles"),
      generateRules = button("AI 一键生成并保存评分点", "generate-rubric"),
      saveRules = button("保存规则新版本", "save-rubric"),
      exportCsv = button("导出所选会话 CSV", "export-csv");
  private final TextField modelName = new TextField(),
      endpoint = new TextField(),
      modelId = new TextField(),
      questionId = new TextField(),
      rubricSubject = new TextField(),
      maximum = new TextField("4"),
      step = new TextField("1"),
      submissionLimit = new TextField("5"),
      answerFilter = new TextField(),
      embeddedBrowserUrl = new TextField();
  private final PasswordField password = new PasswordField();
  private final ComboBox<ProviderProfile.StructuredMode> structured =
      new ComboBox<>(FXCollections.observableArrayList(ProviderProfile.StructuredMode.values()));
  private final Map<String, ProviderProfile> profiles = new HashMap<>();
  private final ComboBox<SessionJournal.RunRecord> runChoice = new ComboBox<>();
  private final DatePicker runDate = new DatePicker();
  private final TableView<SessionJournal.AttemptRecord> records = new TableView<>();
  private List<SessionJournal.RunRecord> allRuns = List.of();
  private List<SessionJournal.AttemptRecord> allAttempts = List.of();
  private Rubric savedRubric;
  private PendingGeneratedRubric pendingGeneratedRubric;
  private boolean formLoading,
      modelDirty,
      rubricDirty,
      rubricSaving,
      credentialPresent,
      busy,
      countdownActive;
  private Timeline countdown;
  private Alert countdownDialog;
  private long uiRevision;
  private long rubricEditRevision;
  private long rubricGenerationUiEpoch;
  private long activeRubricGenerationAccountTicket = -1;
  private boolean starting;

  private record RubricSaveOutcome(Rubric rubric, Throwable receiptFailure) {}

  record PendingGeneratedRubric(
      UUID requestId,
      long accountTicket,
      String subject,
      String referenceAnswer,
      BigDecimal maximumScore,
      BigDecimal scoreStep,
      List<ScoringCriterion> criteria) {
    PendingGeneratedRubric {
      Objects.requireNonNull(requestId);
      subject = Objects.requireNonNull(subject).strip();
      referenceAnswer = Objects.requireNonNull(referenceAnswer).strip();
      maximumScore = Objects.requireNonNull(maximumScore).stripTrailingZeros();
      scoreStep = Objects.requireNonNull(scoreStep).stripTrailingZeros();
      criteria = List.copyOf(criteria);
    }

    boolean matches(Rubric candidate, String candidateSubject, long currentAccountTicket) {
      if (candidate == null || currentAccountTicket != accountTicket) return false;
      var question = candidate.question();
      return subject.equals(Objects.requireNonNull(candidateSubject).strip())
          && referenceAnswer.equals(question.standardAnswer().strip())
          && maximumScore.compareTo(question.maximumScore()) == 0
          && scoreStep.compareTo(candidate.scoreStep()) == 0
          && criteria.equals(question.criteria());
    }
  }

  private final Button openEmbeddedBrowser = button("打开", "open-embedded-browser");
  private final ComboBox<ProviderProfile> scoringModel = new ComboBox<>(),
      fallbackModel = new ComboBox<>(),
      rubricModel = new ComboBox<>();
  private final Label modelRolesStatus = label("请配置模型、Key，并指定评分与规则生成用途", "muted");
  private final Map<String, Boolean> credentialStatuses = new HashMap<>();
  private boolean modelRolesDirty;
  private final TabPane mainTabs = new TabPane();
  private HBox embeddedBrowserEntry;
  private RecordsView recordsView;
  private UnresolvedRecoveryDialog unresolvedDialog;
  private long shownAccountTicket = -1;
  private Button applyQwen, applyOpenAi;
  private final ComboBox<LocalModelSetup.Purpose> setupPurpose =
      new ComboBox<>(FXCollections.observableArrayList(LocalModelSetup.Purpose.values()));
  private final ComboBox<LocalModelSetup.Vendor> setupVendor =
      new ComboBox<>(FXCollections.observableArrayList(LocalModelSetup.Vendor.values()));
  private final ComboBox<QwenThinkingOptions.Mode> qwenThinking =
      new ComboBox<>(FXCollections.observableArrayList(QwenThinkingOptions.Mode.values()));
  private final Label qwenThinkingHint = label("", "page-note");
  private final CheckBox disableFallback = new CheckBox("不启用备用模型（保留已有配置）");
  private final Button setupNext = button("下一步", "model-setup-next"),
      setupBack = button("上一步", "model-setup-back"),
      setupSave = button("保存并继续", "model-setup-save"),
      setupCancel = button("取消配置", "model-setup-cancel"),
      setupFinish = button("完成并收起", "model-setup-finish");
  private final Label setupProgress = label("", "card-title"),
      setupHint = label("", "muted"),
      setupVerification = label("", "muted"),
      setupReview = label("", "muted");
  private final FlowPane modelOverview = new FlowPane(16, 16), setupSteps = new FlowPane(16, 8);
  private final Map<LocalModelSetup.Purpose, Label> modelSummaries =
      new EnumMap<>(LocalModelSetup.Purpose.class);
  private final Map<LocalModelSetup.Purpose, VBox> modelSummaryCards =
      new EnumMap<>(LocalModelSetup.Purpose.class);
  private final Map<LocalModelSetup.Purpose, Button> modelEditButtons =
      new EnumMap<>(LocalModelSetup.Purpose.class);
  private final List<VBox> modelSetupPages = new ArrayList<>();
  private VBox modelWizard;
  private boolean modelWizardOpen, wizardSavedDraft, wizardForgotCredential;
  private int modelSetupStep;
  private String wizardDraftProfileId;

  private final Button markAnswer = button("标定答题区域", "mark-answer"),
      markScore = button("标定分数输入框", "mark-score"),
      markSubmit = button("标定提交按钮", "mark-submit"),
      markSubmissionAck = button("标定提交成功提示区", "mark-submission-ack"),
      markNext = button("标定下一题 / 下一页按钮", "mark-next"),
      clearNext = button("清除提交提示 / 下一份位置", "clear-next-position"),
      usePositions = button("完成本次框选并绑定", "bind-current-positions");
  private final ComboBox<ManualPlatformCalibration.NavigationMode> navigationMode =
      new ComboBox<>(
          FXCollections.observableArrayList(ManualPlatformCalibration.NavigationMode.values()));
  private final Label positionStatus = label("尚未完成本次框选", "muted");
  private WindowSnapshot calibrationTarget;
  private LogicalRect markedAnswer, markedScore, markedSubmit, markedSubmissionAck, markedNext;
  private boolean positionBusy;
  private long positionRequestGeneration;
  private final ManagedWebView embeddedBrowser;
  private ManagedWebView.Session activeBrowserSession;
  private ManagedWebView.Session loadedBrowserSession;

  DeskGradeShell(DesktopWorkspace workspace, Stage stage) {
    this.workspace = workspace;
    this.stage = stage;
    embeddedBrowser =
        new ManagedWebView(
            stage,
            new ManagedWebView.Listener() {
              @Override
              public void loaded(ManagedWebView.Session session) {
                embeddedBrowserLoaded(session);
              }

              @Override
              public void invalidated(ManagedWebView.Session session, String message) {
                embeddedBrowserInvalidated(session, message);
              }

              @Override
              public void failed(ManagedWebView.Session session, String message) {
                embeddedBrowserFailed(session, message);
              }

              @Override
              public void geometryChanged(ManagedWebView.Session session) {
                embeddedBrowserGeometryChanged(session);
              }
            });
    workspace.setCaptureBoundary(embeddedBrowser::prepareCapture);
    workspace.onAnswerScan = embeddedBrowser::updateAnswerScan;
    getStyleClass().add("app-shell");
    state.setId("configuration-status");
    storage.setId("storage-status");
    gradeLabel.setId("ai-status");
    confirmed.setId("confirmed-submissions");
    completionCaption.setId("completion-caption");
    startReadiness.setId("start-blockers");
    HBox header =
        new HBox(
            14,
            label("DG", "monogram"),
            new VBox(3, label("DeskGrade", "brand"), label("单题自动阅卷助手", "muted")));
    embeddedBrowserUrl.setId("embedded-browser-url");
    embeddedBrowserUrl.setPromptText("请在此打开评分网站");
    embeddedBrowserUrl.setMinWidth(180);
    embeddedBrowserUrl.setPrefWidth(220);
    openEmbeddedBrowser.setMinWidth(64);
    embeddedBrowserEntry = new HBox(6, embeddedBrowserUrl, openEmbeddedBrowser);
    embeddedBrowserEntry.setId("embedded-browser-entry");
    embeddedBrowserEntry.setAlignment(Pos.CENTER_LEFT);
    embeddedBrowserEntry.setMinWidth(250);
    embeddedBrowserEntry.setPrefWidth(290);
    embeddedBrowserEntry.setMaxWidth(340);
    HBox.setHgrow(embeddedBrowserUrl, Priority.ALWAYS);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    header
        .getChildren()
        .addAll(embeddedBrowserEntry, spacer, label("本地版 · 自备 Key", "status-pill"), state);
    header.setAlignment(Pos.CENTER_LEFT);
    header.getStyleClass().add("app-header");
    TabPane tabs = mainTabs;
    tabs.setId("main-tabs");
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.getTabs()
        .addAll(
            tab("阅卷", gradingPage()),
            tab("平台配置", platformPage()),
            tab("评分规则", rulesPage()),
            tab("记录", recordsView = new RecordsView(workspace.recordsSource(), true)));
    Tab recordsTab = tabs.getTabs().get(3);
    tabs.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (observable, before, selected) -> {
              if (selected == recordsTab) refreshRecords();
            });
    tabs.getTabs().add(tab("模型与 Key", modelPage()));
    StackPane surfaces = new StackPane(tabs);
    VBox.setVgrow(surfaces, Priority.ALWAYS);
    notice.setWrapText(true);
    notice.setMinWidth(0);
    notice.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(notice, Priority.ALWAYS);
    HBox footer = new HBox(notice);
    footer.getStyleClass().add("app-footer");
    getChildren().addAll(header, surfaces, footer);
    DisabledActionHints.install(this);
    stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> embeddedBrowser.close());
    wire();
    updateActions();
    workspace.onStatus = v -> fx(() -> showStatus(v));
    workspace.onNotice =
        message ->
            fx(
                () -> {
                  notice.setText(message);
                });
    workspace.onAccountChanged =
        () ->
            fx(
                () -> {
                  positionRequestGeneration++;
                  positionBusy = false;
                  long currentAccountTicket = workspace.accountTicket();
                  if (activeRubricGenerationAccountTicket >= 0
                      && activeRubricGenerationAccountTicket != currentAccountTicket) {
                    rubricGenerationUiEpoch++;
                    activeRubricGenerationAccountTicket = -1;
                    busy = false;
                    rubricStatus.setText("本地配置已变化；旧生成结果不会写入当前界面");
                  }
                  if (pendingGeneratedRubric != null
                      && pendingGeneratedRubric.accountTicket() != currentAccountTicket)
                    pendingGeneratedRubric = null;
                  showLocalContext();
                  resetPositionDraft("本地运行配置已变化，请重新选择窗口并完成本次框选。");
                });
    workspace.onResult =
        presentation -> {
          fx(
              () -> {
                var r = presentation.response();
                if (presentation.belongsTo(workspace.actionTicket(), workspace.accountTicket())
                    && ((r.source() == GradingResponse.Source.MOCK && mockMode())
                        || (r.source() == GradingResponse.Source.LIVE && liveSyntheticMode())))
                  showResult(presentation);
              });
        };
    workspace.onResumeRequested = () -> fx(() -> notice.setText("安全暂停后旧任务不能恢复；请重新绑定当前页面并开始新任务。"));
    workspace.onActionRevoked =
        () ->
            fx(
                () -> {
                  uiRevision++;
                  cancelCountdown();
                });
    observe(
        workspace.initialize(),
        loaded -> {
          storage.setText("本地评分记录可写 · 不保存答题截图 · 运行日志保留 7 天 / 100 MB");
          savedRubric = loaded.rubric();
          loadRubric(savedRubric, loaded.rubricSubject());
          loaded.profiles().forEach(p -> profiles.put(p.id(), p));
          refreshModelRoles();
          formLoading = true;
          slot.setValue(workspace.localModelSelection().primaryId());
          formLoading = false;
          showLocalContext();
          resetPositionDraft("请选择当前阅卷窗口，并逐项框选本次使用的位置。");
          loadProfile();
          if (loaded.runs().stream().anyMatch(r -> r.state() == SessionState.SUBMISSION_UNKNOWN))
            notice.setText("存在上次运行的未知提交。请点击阅卷页“处理旧未决提交”核对并结束旧批次；同份答卷不会自动重提。");
          if (recordsView != null) recordsView.initializeHistory();
        });
  }

  private void showLocalContext() {
    boolean hadContext = shownAccountTicket >= 0;
    if (shownAccountTicket != workspace.accountTicket()) {
      if (unresolvedDialog != null) unresolvedDialog.close();
      shownAccountTicket = workspace.accountTicket();
      recordDetail.clear();
      recordImage.setImage(null);
      records.getItems().clear();
      allAttempts = List.of();
      allRuns = List.of();
      result.clear();
      gradeLabel.setText("AI 尚未评分");
      confirmed.setText("0");
      calls.setText("模型调用 0 次");
      if (workspace.binding() == null) bindingLabel.setText("未绑定；请重新选择答题窗口");
      if (recordsView != null) {
        recordsView.clear();
        if (hadContext && workspace.ready()) refreshRecords();
      }
    }
    loadProfile();
    updateActions();
  }

  private VBox gradingPage() {
    VBox page = page("阅卷", "开始后自动完成评分、填分与提交；达到本次阅卷数量后立即停止。");
    mode.setId("run-mode");
    mode.setPrefWidth(295);
    slot.setId("model-slot");
    slot.setPrefWidth(320);
    submissionLimit.setId("max-submissions");
    submissionLimit.setPromptText("1–10000");
    submissionLimit.setPrefColumnCount(5);
    submissionLimit.setMaxWidth(90);
    submissionLimit.setTooltip(new Tooltip("请输入 1–10000；按当前绑定方式的处理证据累计，坐标操作不等同于平台业务回执。"));
    submissionLimit.setTextFormatter(
        new TextFormatter<String>(
            change -> change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
    Label submissionLimitLabel = label("本次阅卷数量", "muted");
    submissionLimitLabel.setLabelFor(submissionLimit);
    if (workspace.developerMode()) page.getChildren().add(flow(label("开发验证方式", "muted"), mode));
    FlowPane controls =
        flow(start, pause, stop, submissionLimitLabel, submissionLimit, label("份完成后自动停止", "muted"));
    recoverUnresolved.setOnAction(
        event -> {
          if (recoverUnresolved.isDisabled() || unresolvedDialog != null) return;
          unresolvedDialog = new UnresolvedRecoveryDialog();
          unresolvedDialog.show();
          unresolvedDialog.load();
          updateActions();
        });
    VBox controlsCard =
        card(
            "阅卷控制",
            controls,
            startReadiness,
            flow(recoverUnresolved, label("存在旧批次未决记录时，可人工核对并结束该批次。", "muted")));
    VBox scoreCard = card("当前评分", gradeLabel, usage);
    VBox progressCard = card("处理进度", completionCaption, confirmed, label("达到设定数量后自动停止", "muted"));
    scoreCard.setMaxWidth(Double.MAX_VALUE);
    progressCard.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(scoreCard, Priority.ALWAYS);
    HBox.setHgrow(progressCard, Priority.ALWAYS);
    HBox summary = new HBox(16, scoreCard, progressCard);
    result.setEditable(false);
    result.setPrefRowCount(12);
    VBox resultCard = card("评分结果", result);
    VBox.setVgrow(result, Priority.ALWAYS);
    VBox.setVgrow(resultCard, Priority.ALWAYS);
    page.getChildren()
        .addAll(
            controlsCard,
            summary,
            resultCard,
            label("发生页面变化、评分异常、提交状态不明确或系统错误时会立即停止。紧急停止：Ctrl+Alt+F9。", "page-note"));
    return page;
  }

  private final class UnresolvedRecoveryDialog extends Dialog<Void> {
    private final long accountTicket = workspace.accountTicket();
    private final TableView<PersistentSessionJournal.UnresolvedSubmission> rows = new TableView<>();
    private final Label information = label("正在读取当前网站配置的旧未决提交…", "muted");
    private final TextArea selection = area(7);
    private final Button refresh = button("刷新", "refresh-unresolved"),
        finish = button("结束旧批次，保留结果未知", "abandon-unresolved-run");
    private DesktopWorkspace.UnresolvedSubmissionReview review;
    private long requestGeneration;
    private boolean pending;

    UnresolvedRecoveryDialog() {
      initOwner(stage);
      setTitle("处理旧未决提交");
      setHeaderText("人工结束旧批次，不确认平台提交成功");
      getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
      rows.setId("unresolved-submissions-table");
      rows.setPrefHeight(245);
      rows.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
      rows.getColumns()
          .addAll(
              unresolvedColumn("发生时间", row -> unresolvedTime(row.run().startedAt())),
              unresolvedColumn("题号", PersistentSessionJournal.UnresolvedSubmission::questionId),
              unresolvedColumn("答卷", PersistentSessionJournal.UnresolvedSubmission::answerId),
              unresolvedColumn("Java 分数", row -> unresolvedScore(row.score())),
              unresolvedColumn("批次", row -> row.run().runId().toString()));
      rows.setPlaceholder(label("当前配置没有待处理记录", "muted"));
      selection.setEditable(false);
      selection.setPromptText("选择一条记录查看批次信息；操作会结束该批次的全部未决提交。");
      rows.getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (ignored, before, selected) -> {
                selection.setText(
                    selected == null ? "" : unresolvedSubmissionDescription(selected));
                updateButtons();
              });
      refresh.setOnAction(event -> load());
      finish.setOnAction(event -> finishSelected());
      VBox content =
          new VBox(
              12,
              information,
              rows,
              selection,
              flow(refresh, finish),
              label("结束后请人工核对平台，切换新答卷并重新绑定。原始记录、分数、未知状态与防重复提交记录都会保留。", "page-note"));
      getDialogPane().setContent(content);
      DisabledActionHints.install(getDialogPane());
      getDialogPane().setPrefWidth(980);
      setOnHidden(
          event -> {
            requestGeneration++;
            rows.getItems().clear();
            selection.clear();
            review = null;
            if (unresolvedDialog == this) unresolvedDialog = null;
            updateActions();
          });
      updateButtons();
    }

    private boolean current(long request) {
      return isShowing()
          && request == requestGeneration
          && accountTicket == workspace.accountTicket();
    }

    void load() {
      if (pending || !isShowing() || accountTicket != workspace.accountTicket()) return;
      pending = true;
      review = null;
      rows.getItems().clear();
      selection.clear();
      information.setText("正在读取当前网站配置的旧未决提交…");
      updateButtons();
      long request = ++requestGeneration;
      workspace
          .unresolvedSubmissions()
          .whenComplete(
              (loaded, failure) ->
                  fx(
                      () -> {
                        if (!current(request)) return;
                        pending = false;
                        if (failure != null) {
                          information.setText(safeError(failure) + " 请核对本机记录和当前网站绑定后点击刷新。");
                        } else {
                          review = loaded;
                          rows.getItems().setAll(loaded.submissions());
                          long batches =
                              loaded.submissions().stream()
                                  .map(row -> row.run().runId())
                                  .distinct()
                                  .count();
                          information.setText(
                              loaded.calibration().displayName()
                                  + "："
                                  + (batches == 0
                                      ? "没有旧未决提交。"
                                      : "显示 "
                                          + batches
                                          + " 个旧批次、"
                                          + loaded.submissions().size()
                                          + " 条未决记录。")
                                  + (loaded.submissions().size() == 100
                                      ? " 每次最多显示 100 条，结束后可刷新查看剩余记录。"
                                      : ""));
                        }
                        updateButtons();
                      }));
    }

    private void finishSelected() {
      var selected = rows.getSelectionModel().getSelectedItem();
      var reviewed = review;
      if (pending
          || selected == null
          || reviewed == null
          || accountTicket != workspace.accountTicket()) return;
      var confirm =
          new Alert(
              Alert.AlertType.CONFIRMATION,
              unresolvedSubmissionDescription(selected)
                  + "\n\n将结束该旧批次中的全部未决提交，保留结果未知，不会确认成功或再次点击提交。"
                  + "\n请人工核对平台并切换到新答卷；结束后须重新绑定，再开始新任务。",
              new ButtonType("结束旧批次", ButtonBar.ButtonData.OK_DONE),
              ButtonType.CANCEL);
      confirm.initOwner(getDialogPane().getScene().getWindow());
      confirm.setTitle("结束旧批次");
      confirm.setHeaderText("保留提交结果未知，撤销当前自动操作权限");
      var answer = confirm.showAndWait();
      if (answer.isEmpty()
          || answer.get().getButtonData() != ButtonBar.ButtonData.OK_DONE
          || !isShowing()
          || reviewed != review
          || accountTicket != workspace.accountTicket()) return;
      pending = true;
      updateButtons();
      information.setText("正在停止旧任务并保存人工结束记录…");
      long request = ++requestGeneration;
      workspace
          .abandonUnresolvedRun(reviewed, selected.run().runId())
          .whenComplete(
              (ignored, failure) ->
                  fx(
                      () -> {
                        if (!current(request)) return;
                        pending = false;
                        review = null;
                        if (failure != null) {
                          information.setText(safeError(failure) + " 操作尚未确认；请点击刷新核对保存状态后再重试。");
                          updateButtons();
                        } else {
                          notice.setText("旧批次已结束，提交结果仍未知。请人工核对平台、切换新答卷并重新绑定，再开始阅卷。");
                          bindingLabel.setText("自动操作权限已撤销；请切换新答卷并重新绑定");
                          state.setText("旧批次已结束，等待重新绑定");
                          refreshRecords();
                          load();
                        }
                        updateActions();
                      }));
    }

    private void updateButtons() {
      refresh.setDisable(pending);
      DisabledActionHints.reason(refresh, "正在读取或保存旧未决提交，请等待当前操作完成。");
      rows.setDisable(pending);
      finish.setDisable(
          pending || review == null || rows.getSelectionModel().getSelectedItem() == null);
      DisabledActionHints.reason(
          finish,
          pending ? "正在读取或保存旧未决提交，请稍候。" : review == null ? "请先刷新并成功读取旧未决提交。" : "请先选中需要结束的旧批次记录。");
    }
  }

  private static TableColumn<PersistentSessionJournal.UnresolvedSubmission, String>
      unresolvedColumn(
          String title, Function<PersistentSessionJournal.UnresolvedSubmission, String> value) {
    var column = new TableColumn<PersistentSessionJournal.UnresolvedSubmission, String>(title);
    column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
    return column;
  }

  private static String unresolvedTime(Instant time) {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(time);
  }

  private static String unresolvedScore(BigDecimal score) {
    return score == null ? "未保存有效评分" : score.stripTrailingZeros().toPlainString() + " 分";
  }

  static String unresolvedSubmissionDescription(PersistentSessionJournal.UnresolvedSubmission row) {
    return "发生时间："
        + unresolvedTime(row.run().startedAt())
        + "\n题号："
        + row.questionId()
        + "\n答卷："
        + row.answerId()
        + "\nJava 校验分数："
        + unresolvedScore(row.score())
        + "\n批次："
        + row.run().runId()
        + "\n提交结果：未知（未确认平台成功）";
  }

  private VBox platformPage() {
    VBox page = page("平台配置", "每次使用都须针对当前网站手工框选；位置只在本次运行中使用，不保存、不复用。");
    windows.setPrefWidth(740);
    windows.setId("target-window");
    windows.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(WindowsWindowService.WindowChoice value) {
            if (value == null) return "";
            return workspace.developerMode() && !workspace.commercialMode()
                ? value.toString()
                : "当前内置阅卷网页";
          }

          @Override
          public WindowsWindowService.WindowChoice fromString(String value) {
            return null;
          }
        });
    navigationMode.setId("calibration-navigation");
    navigationMode.setPrefWidth(260);
    navigationMode.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(ManualPlatformCalibration.NavigationMode value) {
            return value == null ? "" : value.description();
          }

          @Override
          public ManualPlatformCalibration.NavigationMode fromString(String value) {
            return null;
          }
        });
    navigationMode.setValue(ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT);
    page.getChildren()
        .addAll(
            card(
                "选择当前阅卷窗口",
                field("目标窗口", windows),
                flow(refreshWindows),
                bindingLabel,
                label("窗口、尺寸或页面变化后，当前绑定会立即失效并要求重新框选。", "page-note")),
            card(
                "本次位置框选",
                field("提交后的页面变化", navigationMode),
                flow(markAnswer, markScore, markSubmit),
                flow(markSubmissionAck, markNext, clearNext),
                usePositions,
                positionStatus,
                label("必须框选答题区域、分数输入框和提交按钮。若提交后还要点击下一题/下一页，还须框选提交成功提示区及下一题/下一页按钮。", "page-note")),
            card(
                "安全控制",
                flow(hotkeys, hotkeyLabel),
                label("Ctrl+Alt+F8 安全暂停；Ctrl+Alt+F9 立即停止。热键不可用时禁止开始阅卷。", "muted"),
                storage));
    positionStatus.setId("position-status");
    markAnswer.setOnAction(e -> selectPosition("答题区域", 0));
    markScore.setOnAction(e -> selectPosition("分数输入框", 1));
    markSubmit.setOnAction(e -> selectPosition("提交按钮", 2));
    markSubmissionAck.setOnAction(e -> selectPosition("提交成功提示区域", 3));
    markNext.setOnAction(e -> selectPosition("下一题 / 下一页按钮", 4));
    navigationMode.setOnAction(
        e -> {
          workspace.invalidate();
          if (navigationMode.getValue()
              == ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT)
            markedSubmissionAck = markedNext = null;
          showPositions();
        });
    clearNext.setOnAction(
        e -> {
          workspace.invalidate();
          markedSubmissionAck = markedNext = null;
          showPositions();
        });
    usePositions.setOnAction(
        e -> {
          var selected = windows.getValue();
          var session = loadedBrowserSession;
          if (selected == null
              || calibrationTarget == null
              || session == null
              || !embeddedBrowser.isLoadedWindow(session, selected)) {
            workspace.resetEmbeddedBrowserTarget();
            resetPositionDraft("目标网页已变化或尚未加载，请重新打开页面并完成本次框选。");
            return;
          }
          observePosition(
              workspace.bindEphemeralManualAnswer(
                  session.target().origin(),
                  selected.identity(),
                  navigationMode.getValue(),
                  calibrationTarget,
                  markedAnswer,
                  markedScore,
                  markedSubmit,
                  markedSubmissionAck,
                  markedNext),
              bound -> {
                showBinding(bound);
                positionStatus.setText("本次框选已绑定；关闭程序或目标变化后需要重新框选。");
              });
        });
    return page;
  }

  private void selectPosition(String label, int region) {
    var selected = windows.getValue();
    if (selected == null) return;
    if (!isCurrentManagedWindow(selected)) {
      workspace.resetEmbeddedBrowserTarget();
      resetPositionDraft("请先打开并成功加载内置网页，再刷新窗口列表后进行框选。");
      return;
    }
    workspace.invalidate();
    long ticket = workspace.actionTicket();
    stage.setIconified(true);
    observe(
        workspace.prepareSelection(selected.identity()),
        snapshot -> {
          if (workspace.actionTicket() != ticket) {
            restoreWindow();
            return;
          }
          if (calibrationTarget != null && !sameCalibrationLayout(calibrationTarget, snapshot)) {
            markedAnswer = markedScore = markedSubmit = markedSubmissionAck = markedNext = null;
          }
          calibrationTarget = snapshot;
          SelectionOverlay.show(
              snapshot,
              label,
              rectangle -> {
                restoreWindow();
                if (workspace.actionTicket() != ticket || rectangle.isEmpty()) {
                  showPositions();
                  return;
                }
                switch (region) {
                  case 0 -> markedAnswer = rectangle.orElseThrow();
                  case 1 -> markedScore = rectangle.orElseThrow();
                  case 2 -> markedSubmit = rectangle.orElseThrow();
                  case 3 -> markedSubmissionAck = rectangle.orElseThrow();
                  case 4 -> markedNext = rectangle.orElseThrow();
                  default -> throw new IllegalArgumentException();
                }
                notice.setText(label + "已记录；请继续标记其余位置，全部完成后点击“完成本次框选并绑定”。");
                showPositions();
              });
        });
  }

  private void showPositions() {
    String prefix = calibrationTarget == null ? "尚未开始本次框选" : "本次框选仅在内存中使用，关闭程序后不会保留";
    if (markedAnswer != null
        || markedScore != null
        || markedSubmit != null
        || markedSubmissionAck != null
        || markedNext != null) {
      if (workspace.developerMode() && !workspace.commercialMode())
        positionStatus.setText(
            prefix
                + "\n当前草稿：答题 "
                + markedAnswer
                + "\n分数框 "
                + markedScore
                + "\n提交 "
                + markedSubmit
                + "\n提交成功提示区 "
                + markedSubmissionAck
                + "\n下一份 "
                + markedNext
                + "\n导航 "
                + navigationDescription());
      else
        positionStatus.setText(
            prefix
                + "\n答题区域："
                + markingState(markedAnswer)
                + "　分数输入框："
                + markingState(markedScore)
                + "　提交按钮："
                + markingState(markedSubmit)
                + "\n提交成功提示区："
                + markingState(markedSubmissionAck)
                + "　下一题 / 下一页："
                + markingState(markedNext)
                + "\n提交后："
                + navigationDescription());
    } else positionStatus.setText(prefix + "；请选择目标窗口并逐项框选必需区域。");
    updateActions();
  }

  private static String markingState(LogicalRect region) {
    return region == null ? "未标定" : "已标定";
  }

  private void resetPositionDraft(String message) {
    calibrationTarget = null;
    markedAnswer = markedScore = markedSubmit = markedSubmissionAck = markedNext = null;
    navigationMode.setValue(ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT);
    positionStatus.setText(message);
    updateActions();
  }

  private static boolean sameCalibrationLayout(WindowSnapshot left, WindowSnapshot right) {
    return left.identity().equals(right.identity())
        && left.dpi() == right.dpi()
        && left.clientBounds().width() == right.clientBounds().width()
        && left.clientBounds().height() == right.clientBounds().height()
        && left.windowClass().equals(right.windowClass());
  }

  private String navigationDescription() {
    var value = navigationMode.getValue();
    return value == null ? "尚未选择" : value.description();
  }

  private VBox modelPage() {
    VBox page = page("模型与 Key", "按用途配置自己的模型。完成后只显示状态卡片；需要修改时再打开。调用由本机直连供应商，软件不限制次数。");
    modelOverview.setId("model-configuration-overview");
    for (var purpose : LocalModelSetup.Purpose.values()) {
      Label summary = label("正在读取本机配置…", "muted");
      summary.setWrapText(true);
      summary.setId("model-summary-" + purpose.key);
      Button edit = button("配置", "edit-" + purpose.key + "-model");
      edit.setOnAction(e -> openModelSetup(purpose));
      VBox summaryCard = card(purpose.title, summary, edit);
      summaryCard.setPrefWidth(305);
      summaryCard.setMinWidth(260);
      summaryCard.setMaxWidth(360);
      modelSummaries.put(purpose, summary);
      modelEditButtons.put(purpose, edit);
      modelSummaryCards.put(purpose, summaryCard);
      modelOverview.getChildren().add(summaryCard);
    }
    slot.setId("model-slot");
    slot.setPrefWidth(320);
    slot.setConverter(
        new StringConverter<>() {
          public String toString(String id) {
            if (id == null) return "";
            var p = profiles.get(id);
            return p == null ? "未保存的新配置" : p.displayName() + " · " + p.modelId();
          }

          public String fromString(String value) {
            return value;
          }
        });
    slot.setOnAction(
        e -> {
          if (formLoading) return;
          uiRevision++;
          workspace.invalidate();
          wizardDraftProfileId = slot.getValue();
          loadProfile();
          syncSetupVendor();
        });
    endpoint.setPromptText("完整 HTTPS 接口地址，结尾 /chat/completions");
    modelId.setPromptText("填写供应商账户可用的模型名称 / ID");
    endpoint.setId("model-endpoint");
    modelId.setId("model-id");
    modelName.setId("model-name");
    qwenModels.setId("qwen-model-candidate");
    qwenModels.setPromptText("可选择千问模型，或在上方手动填写");
    qwenModels.setPrefWidth(350);
    qwenModels.setConverter(
        new StringConverter<>() {
          public String toString(String id) {
            return LocalModelSetup.modelLabel(id);
          }

          public String fromString(String value) {
            return value;
          }
        });
    qwenModels.setOnAction(
        e -> {
          if (!qwenModels.isDisabled()
              && !formLoading
              && modelWizardOpen
              && setupVendor.getValue() == LocalModelSetup.Vendor.QWEN
              && qwenModels.getValue() != null) modelId.setText(qwenModels.getValue());
        });
    applyQwen = button("切换为千问", "apply-qwen-preset");
    applyQwen.setOnAction(
        e -> {
          if (applyQwen.isDisabled()) return;
          String candidate = qwenModels.getValue();
          setupVendor.setValue(LocalModelSetup.Vendor.QWEN);
          if (candidate != null) modelId.setText(candidate);
        });
    applyOpenAi = button("切换为 OpenAI", "apply-openai-preset");
    applyOpenAi.setOnAction(
        e -> {
          if (!applyOpenAi.isDisabled()) setupVendor.setValue(LocalModelSetup.Vendor.OPENAI);
        });
    password.setPromptText("已有 Key 可留空沿用；输入新 Key 只用于本次待验证配置");
    password.setId("api-key");
    structured.setValue(ProviderProfile.StructuredMode.NONE);
    structured.setId("structured-mode");
    Button exportModels = button("导出配置（不含 Key）", "export-profiles");
    exportModels.setOnAction(
        e ->
            chooseFile(
                "导出模型配置",
                "model-profiles.json",
                "*.json",
                p -> observe(workspace.exportProfiles(p), v -> notice.setText("已导出不含 Key 的模型配置"))));
    for (var choice : List.of(scoringModel, fallbackModel, rubricModel)) {
      choice.setPrefWidth(340);
      choice.setConverter(
          new StringConverter<>() {
            public String toString(ProviderProfile p) {
              return p == null ? "不使用备用模型" : p.displayName() + " · " + p.modelId();
            }

            public ProviderProfile fromString(String value) {
              return null;
            }
          });
      choice.setOnAction(
          e -> {
            if (formLoading) return;
            modelRolesDirty = true;
            workspace.invalidate();
            modelRolesStatus.setText("高级用途映射有未保存修改");
            updateActions();
          });
    }
    scoringModel.setId("scoring-model");
    fallbackModel.setId("fallback-model");
    rubricModel.setId("rubric-generation-model");
    setupPurpose.setId("model-purpose");
    setupVendor.setId("model-provider");
    qwenThinking.setId("qwen-thinking-mode");
    qwenThinkingHint.setId("qwen-thinking-hint");
    qwenThinkingHint.setWrapText(true);
    qwenThinking.setValue(QwenThinkingOptions.Mode.KEEP);
    qwenThinking.setPrefWidth(340);
    qwenThinking.setTooltip(new Tooltip(""));
    qwenThinking.setOnAction(
        e -> {
          if (!formLoading && modelWizardOpen && !qwenThinking.isDisabled()) modelEdited();
        });
    setupPurpose.setPrefWidth(340);
    setupVendor.setPrefWidth(340);
    disableFallback.setId("disable-fallback-model");
    setupPurpose.setOnAction(
        e -> {
          if (formLoading || !modelWizardOpen) return;
          loadSetupPurpose();
        });
    setupVendor.setOnAction(
        e -> {
          if (!setupVendor.isDisabled() && !formLoading && modelWizardOpen) switchSetupVendor();
        });
    disableFallback.setOnAction(e -> updateActions());
    setupBack.setOnAction(
        e -> {
          if (modelSetupStep > 0)
            modelSetupStep =
                modelSetupStep == 3 && setupFallbackDisabled() ? 0 : modelSetupStep - 1;
          updateActions();
        });
    setupNext.setOnAction(
        e -> {
          if (setupNext.isDisabled()) return;
          modelSetupStep = modelSetupStep == 0 && setupFallbackDisabled() ? 3 : modelSetupStep + 1;
          updateActions();
        });
    setupSave.setOnAction(e -> saveWizardDraft(true));
    setupCancel.setOnAction(e -> cancelModelSetup());
    setupFinish.setOnAction(e -> finishModelSetup());
    VBox choose =
        card(
            "第 1 步 · 选择用途和供应商",
            field("你要配置什么用途？", setupPurpose),
            field("Key 属于哪个供应商？", setupVendor),
            field("已有本机配置（可直接选用）", slot),
            disableFallback,
            label("优先选用同供应商已有配置和缓存 Key；没有可用配置时再填写。未保存的 Key 会清空，完成前原生效用途不变。", "page-note"));
    TitledPane advanced =
        new TitledPane(
            "高级设置（通常无需修改）",
            new VBox(
                12,
                field("接口地址", endpoint),
                field("JSON 输出模式", structured),
                field("配置备注名", modelName),
                field("千问模型候选", qwenModels),
                flow(applyQwen, applyOpenAi),
                flow(saveModel, saveKey, forgetKey, exportModels)));
    advanced.setId("model-advanced-settings");
    advanced.setExpanded(false);
    VBox credentials =
        card(
            "第 2 步 · 填写模型和 Key",
            field("使用的模型（一般保持默认即可）", modelId),
            field("思考模式", qwenThinking),
            qwenThinkingHint,
            field("自己的 API Key", password),
            label("模型名称须与供应商控制台一致，不是随意起名。千问也可在高级设置中选择候选模型。", "page-note"),
            modelLabel,
            label("留空表示沿用当前配置的缓存，不会清除 Key。点击“保存并继续”只保存待验证配置，不切换当前用途、不调用模型。", "page-note"),
            advanced,
            setupSave);
    VBox verify =
        card(
            "第 3 步 · 验证能力",
            setupVerification,
            flow(capability, textCapability),
            label("验证必须由你确认后才调用，供应商可能计费。已有本机 Key 且能力验证有效时，可直接进入下一步，无需重复测试。", "page-note"));
    VBox done =
        card(
            "第 4 步 · 确认启用",
            setupReview,
            label("点击“完成并收起”才将本次配置用于所选用途；不会自动开始阅卷。", "page-note"),
            setupFinish);
    modelSetupPages.addAll(List.of(choose, credentials, verify, done));
    VBox legacyRoles =
        new VBox(scoringModel, fallbackModel, rubricModel, saveModelRoles, modelRolesStatus);
    legacyRoles.setVisible(false);
    legacyRoles.setManaged(false);
    modelWizard =
        new VBox(
            16,
            setupSteps,
            setupProgress,
            choose,
            credentials,
            verify,
            done,
            setupHint,
            flow(setupBack, setupNext, setupCancel),
            legacyRoles);
    modelWizard.setId("model-setup-wizard");
    page.getChildren()
        .addAll(
            modelOverview,
            modelWizard,
            label("Key 只保存在当前 Windows 用户的加密缓存，不随安装包分发。更换网络端点须重新输入 Key；不要使用来源不明的接口。", "page-note"));
    return page;
  }

  private ProviderProfile roleProfile(LocalModelSetup.Purpose purpose) {
    var roles = workspace.localModelSelection();
    return profiles.get(
        switch (purpose) {
          case PRIMARY -> roles.primaryId();
          case FALLBACK -> roles.fallbackId();
          case RUBRIC -> roles.rubricId();
        });
  }

  private void openModelSetup(LocalModelSetup.Purpose purpose) {
    modelWizardOpen = true;
    modelSetupStep = 0;
    wizardSavedDraft = false;
    wizardForgotCredential = false;
    formLoading = true;
    setupPurpose.setValue(purpose);
    formLoading = false;
    loadSetupPurpose();
  }

  private void loadSetupPurpose() {
    password.clear();
    var purpose = setupPurpose.getValue();
    var existing = purpose == null ? null : roleProfile(purpose);
    formLoading = true;
    disableFallback.setSelected(
        purpose == LocalModelSetup.Purpose.FALLBACK
            && workspace.localModelSelection().fallbackId().isEmpty());
    wizardDraftProfileId =
        existing == null
            ? "draft_setup_" + UUID.randomUUID().toString().replace("-", "")
            : existing.id();
    slot.getItems().add(wizardDraftProfileId);
    slot.setValue(wizardDraftProfileId);
    formLoading = false;
    loadProfile();
    syncSetupVendor();
    if (existing == null) {
      formLoading = true;
      setupVendor.setValue(LocalModelSetup.Vendor.QWEN);
      formLoading = false;
      switchSetupVendor();
    }
    updateActions();
  }

  private void syncSetupVendor() {
    formLoading = true;
    setupVendor.setValue(
        LocalModelSetup.vendorOf(selectedProfile() == null ? null : selectedProfile().byok()));
    formLoading = false;
  }

  private void switchSetupVendor() {
    var vendor = setupVendor.getValue();
    var purpose = setupPurpose.getValue();
    if (vendor == null || purpose == null) return;
    password.clear();
    var old = selectedProfile();
    if (old != null && LocalModelSetup.vendorOf(old.byok()) == vendor) {
      loadProfile();
      updateActions();
      return;
    }
    var active = roleProfile(purpose);
    var saved =
        LocalModelSetup.savedVendorProfile(
            profiles.values(),
            purpose,
            vendor,
            active == null ? null : active.id(),
            profile -> credentialStatuses.getOrDefault(profile.id(), false));
    if (saved != null) {
      formLoading = true;
      wizardDraftProfileId = saved.id();
      slot.setValue(saved.id());
      formLoading = false;
      uiRevision++;
      workspace.invalidate();
      loadProfile();
      notice.setText("已选用该供应商的本机配置；缓存 Key 和有效验证可沿用，完成向导后才切换当前用途。");
      return;
    }
    formLoading = true;
    wizardDraftProfileId = "draft_setup_" + UUID.randomUUID().toString().replace("-", "");
    slot.getItems().add(wizardDraftProfileId);
    slot.setValue(wizardDraftProfileId);
    endpoint.setText(vendor.endpoint);
    modelId.setText(LocalModelSetup.defaultModelId(purpose, vendor));
    modelName.setText(purpose.title + " · " + vendor.title);
    structured.setValue(ProviderProfile.StructuredMode.JSON_OBJECT);
    qwenModels.setValue(vendor == LocalModelSetup.Vendor.QWEN ? modelId.getText() : null);
    qwenThinking.setValue(QwenThinkingOptions.Mode.KEEP);
    formLoading = false;
    credentialPresent = false;
    modelDirty = true;
    workspace.invalidate();
    modelLabel.setText("新供应商配置：尚未保存 Key，不会沿用其他供应商的缓存。");
    notice.setText("供应商已切换，未保存的 Key 已清空；原生效配置未改变。");
    updateActions();
  }

  private boolean setupFallbackDisabled() {
    return setupPurpose.getValue() == LocalModelSetup.Purpose.FALLBACK
        && disableFallback.isSelected();
  }

  private boolean setupProfileReady() {
    var selected = selectedProfile();
    return setupPurpose.getValue() != null
        && selected != null
        && !modelDirty
        && LocalModelSetup.ready(selected.byok(), setupPurpose.getValue(), credentialPresent);
  }

  private void saveWizardDraft(boolean advance) {
    var purpose = setupPurpose.getValue();
    if (purpose == null) return;
    final ProviderProfile candidate;
    char[] key = password.getText().toCharArray();
    password.clear();
    try {
      var selected = selectedProfile();
      candidate =
          LocalModelSetup.savedOrIsolatedDraft(
              selected == null ? null : selected.byok(),
              purpose,
              modelName.getText().strip(),
              URI.create(endpoint.getText().strip()),
              modelId.getText().strip(),
              structured.getValue(),
              key.length > 0,
              UUID.randomUUID(),
              qwenThinking.getValue());
      if (key.length == 0
          && (selected == null
              || !credentialPresent
              || !selected.endpoint().equals(candidate.endpoint()))) {
        Arrays.fill(key, '\0');
        modelLabel.setText("请填写这个供应商自己的 Key；不会把旧 Key 发送到新端点。");
        return;
      }
    } catch (RuntimeException invalid) {
      Arrays.fill(key, '\0');
      modelLabel.setText("请核对模型名称、接口地址和 Key；原生效配置未修改。");
      return;
    }
    busy = true;
    updateActions();
    var original = selectedProfile();
    CompletableFuture<ProviderProfile> pending =
        original != null && original.byok().equals(candidate)
            ? CompletableFuture.completedFuture(candidate)
            : workspace.saveProfile(candidate);
    wizardSavedDraft |= original == null || !original.byok().equals(candidate);
    if (key.length > 0)
      pending = pending.thenCompose(saved -> workspace.saveCredential(saved, key));
    pending.whenComplete(
        (saved, failure) -> {
          Arrays.fill(key, '\0');
          fx(
              () -> {
                busy = false;
                profiles.clear();
                workspace.profiles().forEach(profile -> profiles.put(profile.id(), profile));
                credentialStatuses.clear();
                if (failure != null) {
                  notice.setText(safeError(failure));
                  refreshCredentialStatuses();
                  updateActions();
                  return;
                }
                wizardSavedDraft |= original == null || !original.byok().equals(saved);
                wizardDraftProfileId = saved.id();
                formLoading = true;
                slot.getItems().add(saved.id());
                slot.setValue(saved.id());
                formLoading = false;
                loadProfile();
                if (advance) modelSetupStep = 2;
                notice.setText("配置已保存在本机；完成向导前不会切换当前用途。");
                updateActions();
              });
        });
  }

  private void finishModelSetup() {
    if (!setupFallbackDisabled() && !setupProfileReady()) return;
    var purpose = setupPurpose.getValue();
    var old = workspace.localModelSelection();
    String selected = setupFallbackDisabled() ? "" : Objects.requireNonNull(selectedProfile()).id();
    String primary = purpose == LocalModelSetup.Purpose.PRIMARY ? selected : old.primaryId();
    String fallback = purpose == LocalModelSetup.Purpose.FALLBACK ? selected : old.fallbackId();
    String rubric = purpose == LocalModelSetup.Purpose.RUBRIC ? selected : old.rubricId();
    if (primary.equals(fallback)) {
      setupReview.setText("主模型和备用模型不能使用同一个配置。请选择其他模型或停用备用。");
      return;
    }
    if (primary.equals(old.primaryId())
        && fallback.equals(old.fallbackId())
        && rubric.equals(old.rubricId())) {
      closeModelSetup();
      return;
    }
    busy = true;
    updateActions();
    observePaid(
        workspace.saveLocalModelSelection(primary, fallback, rubric), saved -> closeModelSetup());
  }

  private void cancelModelSetup() {
    boolean saved = wizardSavedDraft;
    boolean forgotten = wizardForgotCredential;
    closeModelSetup();
    notice.setText(
        forgotten
            ? "已取消用途切换并丢弃未保存输入；明确删除的 Key 和已撤销的验证不会恢复，请重新填写并验证后使用。"
            : saved
                ? "已取消切换用途，原生效配置不变；明确保存过的未启用草稿仍留在本机，已经发生的验证调用不会撤销。"
                : "已丢弃未保存输入，原生效配置与 Key 不变。");
  }

  private void closeModelSetup() {
    modelWizardOpen = false;
    modelSetupStep = 0;
    wizardDraftProfileId = null;
    password.clear();
    modelRolesDirty = false;
    modelDirty = false;
    formLoading = true;
    slot.setValue(workspace.localModelSelection().primaryId());
    formLoading = false;
    loadProfile();
    updateActions();
  }

  private void updateModelSetup(boolean ready, boolean operationBusy, boolean running) {
    if (modelWizard == null) return;
    modelOverview.setVisible(!modelWizardOpen);
    modelOverview.setManaged(!modelWizardOpen);
    modelWizard.setVisible(modelWizardOpen);
    modelWizard.setManaged(modelWizardOpen);
    for (var purpose : LocalModelSetup.Purpose.values()) {
      var profile = roleProfile(purpose);
      boolean keyPresent = profile != null && credentialStatuses.getOrDefault(profile.id(), false);
      boolean configured = LocalModelSetup.ready(profile, purpose, keyPresent);
      boolean disabled = purpose == LocalModelSetup.Purpose.FALLBACK && profile == null;
      modelSummaries
          .get(purpose)
          .setText(
              disabled
                  ? "已停用；无法判断时会安全停止。"
                  : profile == null
                      ? "尚未配置，请按步骤添加。"
                      : LocalModelSetup.vendorOf(profile).title
                          + " · "
                          + LocalModelSetup.modelLabel(profile.modelId())
                          + thinkingSummary(profile)
                          + "\n"
                          + (keyPresent ? "Key 已加密缓存" : "尚未填写 Key")
                          + " · "
                          + (configured
                              ? "已验证，可直接使用"
                              : "需要完成" + (purpose.vision ? "图像" : "文本") + "验证"));
      var card = modelSummaryCards.get(purpose);
      card.getStyleClass().removeAll("model-ready", "model-needs-setup");
      card.getStyleClass().add(configured || disabled ? "model-ready" : "model-needs-setup");
      modelEditButtons.get(purpose).setText(configured || disabled ? "编辑" : "开始配置");
      modelEditButtons.get(purpose).setDisable(!ready || operationBusy || running);
    }
    if (!modelWizardOpen) return;
    setupSteps.getChildren().clear();
    String[] labels = {"1 选择用途", "2 模型与 Key", "3 验证能力", "4 完成"};
    for (int i = 0; i < labels.length; i++)
      setupSteps
          .getChildren()
          .add(label(labels[i], i == modelSetupStep ? "model-step-current" : "model-step-pending"));
    setupProgress.setText(labels[modelSetupStep]);
    for (int i = 0; i < modelSetupPages.size(); i++) {
      modelSetupPages.get(i).setVisible(i == modelSetupStep);
      modelSetupPages.get(i).setManaged(i == modelSetupStep);
    }
    var purpose = setupPurpose.getValue();
    boolean fallbackOff = setupFallbackDisabled();
    boolean valid = setupProfileReady();
    setupHint.setText("原生效配置保持不变。保存草稿和验证均需明确点击；只有最后完成才切换用途。");
    setupVerification.setText(
        valid
            ? "当前本机配置已验证，可直接点击下一步。"
            : purpose != null && purpose.vision
                ? "此用途必须验证图像识别与结构化评分能力。只通过文本测试不能用于阅卷。"
                : "此用途只需验证文本与结构化输出，不要求图像能力。");
    var current = selectedProfile();
    setupReview.setText(
        fallbackOff
            ? "将停用备用模型；原模型和 Key 保留。"
            : current == null
                ? "尚无已验证配置。"
                : purpose.title
                    + " → "
                    + LocalModelSetup.vendorOf(current.byok()).title
                    + " · "
                    + current.modelId()
                    + thinkingSummary(current.byok())
                    + "\nKey 已加密缓存，所需能力验证通过。");
    updateThinkingOptions(ready, operationBusy, running);
    disableFallback.setVisible(purpose == LocalModelSetup.Purpose.FALLBACK);
    disableFallback.setManaged(purpose == LocalModelSetup.Purpose.FALLBACK);
    disableFallback.setDisable(!ready || operationBusy || running);
    setupPurpose.setDisable(!ready || operationBusy || running);
    setupVendor.setDisable(!ready || operationBusy || running || fallbackOff);
    setupBack.setDisable(operationBusy || running || modelSetupStep == 0);
    setupCancel.setDisable(operationBusy || running);
    setupNext.setVisible(modelSetupStep == 0 || modelSetupStep == 2);
    setupNext.setManaged(modelSetupStep == 0 || modelSetupStep == 2);
    setupNext.setDisable(
        !ready
            || operationBusy
            || running
            || purpose == null
            || setupVendor.getValue() == null
            || modelSetupStep == 2 && !valid);
    setupSave.setDisable(
        !ready
            || operationBusy
            || running
            || modelName.getText().isBlank()
            || modelId.getText().isBlank()
            || endpoint.getText().isBlank()
            || password.getText().isEmpty() && !credentialPresent);
    setupFinish.setDisable(!ready || operationBusy || running || !fallbackOff && !valid);
    qwenModels.setVisible(setupVendor.getValue() == LocalModelSetup.Vendor.QWEN);
    qwenModels.setManaged(setupVendor.getValue() == LocalModelSetup.Vendor.QWEN);
    capability.setVisible(purpose != null && purpose.vision);
    capability.setManaged(purpose != null && purpose.vision);
    textCapability.setVisible(purpose != null && !purpose.vision);
    textCapability.setManaged(purpose != null && !purpose.vision);
  }

  private static String thinkingSummary(ProviderProfile profile) {
    String summary =
        QwenThinkingOptions.summary(profile.endpoint(), profile.modelId(), profile.parameters());
    return summary.isEmpty() ? "" : "\n思考模式：" + summary;
  }

  private void updateThinkingOptions(boolean ready, boolean operationBusy, boolean running) {
    boolean supported = QwenThinkingOptions.supported(endpoint.getText(), modelId.getText());
    qwenThinking.setDisable(!ready || operationBusy || running || !supported);
    String description;
    if (!supported) {
      description = "此接口或模型尚未确认支持千问三档思考设置，保持当前配置；不会向 OpenAI、其他接口或未知模型添加千问思考参数。";
    } else {
      var original = selectedProfile();
      boolean sameTarget =
          original != null
              && original.endpoint().toString().equals(endpoint.getText().strip())
              && original.modelId().equals(modelId.getText().strip());
      String current =
          QwenThinkingOptions.summary(
              URI.create(endpoint.getText().strip()),
              modelId.getText().strip(),
              sameTarget ? original.byok().parameters() : Map.of());
      description =
          "保持当前：" + current + "。选择新档位需保存独立草稿并重新验证；不思考或少思考可能影响评分准确性，请先用实际答卷验证。不会自动切换正在使用的配置。";
    }
    qwenThinkingHint.setText(description);
    qwenThinking.getTooltip().setText(description);
  }

  private void refreshModelRoles() {
    var selection = workspace.localModelSelection();
    String editorSelection = slot.getValue();
    boolean wasLoading = formLoading;
    formLoading = true;
    try {
      var ids = new TreeSet<>(profiles.keySet());
      ids.addAll(List.of("profile_A", "profile_B", "profile_C"));
      if (wizardDraftProfileId != null) ids.add(wizardDraftProfileId);
      slot.getItems().setAll(ids);
      slot.setValue(
          ids.contains(editorSelection)
              ? editorSelection
              : profiles.containsKey(selection.primaryId()) ? selection.primaryId() : "profile_A");
    } finally {
      formLoading = wasLoading;
    }
    if (modelRolesDirty) {
      refreshCredentialStatuses();
      return;
    }
    formLoading = true;
    try {
      var choices =
          profiles.values().stream().sorted(Comparator.comparing(ProviderProfile::id)).toList();
      scoringModel.getItems().setAll(choices);
      rubricModel.getItems().setAll(choices);
      fallbackModel.getItems().clear();
      fallbackModel.getItems().add(null);
      fallbackModel.getItems().addAll(choices);
      scoringModel.setValue(profiles.get(selection.primaryId()));
      fallbackModel.setValue(profiles.get(selection.fallbackId()));
      rubricModel.setValue(profiles.get(selection.rubricId()));
      modelRolesStatus.setText("用途已保存 · 本机调用 · 不连接账号服务");
    } finally {
      formLoading = false;
    }
    refreshCredentialStatuses();
  }

  private void refreshCredentialStatuses() {
    for (var profile : profiles.values()) {
      observe(
          workspace.hasCredential(profile),
          present -> {
            if (Objects.equals(profiles.get(profile.id()), profile))
              credentialStatuses.put(profile.id(), present);
          });
    }
  }

  private DesktopWorkspace.ModelSelection scoringProfile() {
    var profile = profiles.get(workspace.localModelSelection().primaryId());
    return profile == null ? null : DesktopWorkspace.ModelSelection.of(profile);
  }

  private VBox rulesPage() {
    VBox page = page("评分规则", "填写标准答案和结构化评分点，保存后用于本次阅卷。修改规则会撤销旧评分的操作权限。");
    rubricSubject.setId("rubric-subject");
    reference.setId("reference-answer");
    criteria.setId("criteria-editor");
    maximum.setId("maximum-score");
    generateRules.setOnAction(e -> requestRubricGeneration());
    saveRules.setOnAction(e -> saveRubric());
    rubricSubject.setPromptText("例如：历史、语文、数学");
    maximum.setPrefColumnCount(5);
    step.setPrefColumnCount(5);
    page.getChildren()
        .add(
            card(
                "题目与评分点",
                field("题号 / ID", questionId),
                field("题目类型 / 学科", rubricSubject),
                field("标准答案", reference),
                field("评分点", criteria),
                label("每行：评分点 ID | 允许分数（逗号分隔） | 评分要求。完整且唯一，不擅自添加半分。", "page-note"),
                flow(label("满分", "muted"), maximum, label("步长", "muted"), step),
                flow(generateRules, saveRules, rubricStatus),
                generationHint));
    generationHint.setId("generate-rubric-blockers");
    generationHint.setWrapText(true);
    return page;
  }

  private VBox recordsPage() {
    VBox page = page("记录", "只读查看模型评分与平台提交。未知提交不会自动重试，缺失用量和费用保留未知。");
    runChoice.setPrefWidth(700);
    runChoice.setConverter(
        new StringConverter<>() {
          public String toString(SessionJournal.RunRecord r) {
            return r == null
                ? ""
                : r.startedAt().atZone(ZoneId.systemDefault()).toLocalDateTime()
                    + " · "
                    + r.state()
                    + " · 调用 "
                    + r.modelCalls()
                    + " / 已处理 "
                    + r.confirmedSubmissions();
          }

          public SessionJournal.RunRecord fromString(String s) {
            return null;
          }
        });
    Button refresh = button("刷新记录", "refresh-records");
    refresh.setOnAction(e -> refreshRecords());
    answerFilter.setPromptText("答卷编号筛选");
    records.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    records
        .getColumns()
        .addAll(
            column("答卷", SessionJournal.AttemptRecord::answerId),
            column("题号", SessionJournal.AttemptRecord::questionId),
            column("分数", r -> r.score() == null ? "—" : r.score().toPlainString()),
            column("状态", r -> r.state().name()),
            column("提交意图", r -> r.submissionPrepared() ? "已持久化" : "无"),
            column("处理状态", r -> r.confirmed() ? "已有处理证据，详见 JSON" : "未完成"));
    records.setPrefHeight(250);
    records.setPlaceholder(label("暂无记录", "muted"));
    recordDetail.setEditable(false);
    page.getChildren()
        .addAll(
            flow(runDate, refresh, exportCsv),
            runChoice,
            flow(recordState, answerFilter),
            records,
            card("所选记录与实际截图", recordDetail, recordImage));
    return page;
  }

  private void openEmbeddedBrowser() {
    final EmbeddedBrowserUrlPolicy.Target target;
    try {
      target = EmbeddedBrowserUrlPolicy.parse(embeddedBrowserUrl.getText());
    } catch (IllegalArgumentException rejected) {
      notice.setText(rejected.getMessage());
      updateActions();
      return;
    }
    workspace.resetEmbeddedBrowserTarget();
    loadedBrowserSession = null;
    activeBrowserSession = null;
    windows.setValue(null);
    windows.getItems().clear();
    bindingLabel.setText("未绑定；新网页须重新验证答题目标");
    state.setText("目标待验证");
    try {
      activeBrowserSession = embeddedBrowser.open(target);
      notice.setText(
          target.localMock()
              ? "正在应用内打开本机测试页；加载完成后仍须手工框选本次使用的全部区域。"
              : "正在应用内打开目标网站；网页加载不授予操作权限，请手工框选全部区域并明确绑定。");
    } catch (RuntimeException failure) {
      notice.setText("内置网页窗口无法打开；请检查发布包是否包含 JavaFX WebView。");
    }
    updateActions();
  }

  private void embeddedBrowserLoaded(ManagedWebView.Session session) {
    if (!session.equals(activeBrowserSession) || !embeddedBrowser.isOpen(session)) return;
    loadedBrowserSession = session;
    notice.setText("目标网站已在应用内加载；请先调整好窗口大小，再刷新窗口列表并手工框选全部区域。");
    updateActions();
  }

  private void embeddedBrowserGeometryChanged(ManagedWebView.Session session) {
    if (!session.equals(activeBrowserSession) || !session.equals(loadedBrowserSession)) return;
    if (workspace.binding() == null && calibrationTarget == null && !positionBusy) return;
    workspace.resetEmbeddedBrowserTarget();
    positionRequestGeneration++;
    positionBusy = false;
    windows.setValue(null);
    var selectedMode = navigationMode.getValue();
    resetPositionDraft("窗口大小或位置已改变，旧框选已撤销；请重新框选全部区域。");
    navigationMode.setValue(selectedMode);
    bindingLabel.setText("未绑定；窗口布局已改变，请重新框选");
    state.setText("目标待重新框选");
    notice.setText("内置网页窗口大小或位置已改变，已停止自动操作；网页仍保持打开，请重新框选后再开始。");
    updateActions();
  }

  private void embeddedBrowserFailed(ManagedWebView.Session session, String message) {
    if (!session.equals(activeBrowserSession)) return;
    loadedBrowserSession = null;
    windows.setValue(null);
    windows.getItems().clear();
    workspace.resetEmbeddedBrowserTarget();
    resetPositionDraft("网页加载失败，原有框选已撤销；请检查网址后重新打开。");
    bindingLabel.setText("未绑定；网页加载失败");
    state.setText("目标不可用");
    notice.setText(message);
    updateActions();
  }

  private void embeddedBrowserInvalidated(ManagedWebView.Session session, String message) {
    if (!session.equals(activeBrowserSession)) return;
    loadedBrowserSession = null;
    activeBrowserSession = null;
    windows.setValue(null);
    windows.getItems().clear();
    workspace.resetEmbeddedBrowserTarget();
    resetPositionDraft("内置网页已关闭或发生导航变化，请重新打开页面并完成本次框选。");
    if (workspace.binding() == null) {
      bindingLabel.setText("未绑定；内置网页关闭或导航变化后须重新打开并验证");
      state.setText("目标已撤销");
    }
    notice.setText(message);
    updateActions();
  }

  private boolean isCurrentManagedWindow(WindowsWindowService.WindowChoice choice) {
    var session = loadedBrowserSession;
    return session != null && embeddedBrowser.isLoadedWindow(session, choice);
  }

  private boolean embeddedBrowserUrlReady() {
    try {
      EmbeddedBrowserUrlPolicy.parse(embeddedBrowserUrl.getText());
      return true;
    } catch (IllegalArgumentException rejected) {
      return false;
    }
  }

  private void wire() {
    embeddedBrowserUrl
        .textProperty()
        .addListener(
            (observable, before, value) -> {
              if (!Objects.equals(before, value)) workspace.invalidate();
              updateActions();
            });
    openEmbeddedBrowser.setOnAction(event -> openEmbeddedBrowser());
    embeddedBrowserUrl.setOnAction(event -> openEmbeddedBrowser());
    mode.setOnAction(
        e -> {
          uiRevision++;
          workspace.invalidate();
          notice.setText(
              mockMode()
                  ? "当前为开发 MOCK 合成测试，不调用真实模型"
                  : liveSyntheticMode() ? "真实视觉模型 + 已绑定目标；开始前确认费用" : "当前为真实模型只读试评，自动输入禁用");
          updateActions();
        });

    refreshWindows.setOnAction(
        e ->
            observe(
                workspace.listWindows(),
                values -> {
                  var eligible = values.stream().filter(this::isCurrentManagedWindow).toList();
                  windows.getItems().setAll(eligible);
                  if (eligible.size() == 1) windows.getSelectionModel().selectFirst();
                  notice.setText(
                      eligible.isEmpty()
                          ? "未找到当前已加载的内置网页窗口；请确认窗口仍打开后重试。"
                          : "已找到当前内置网页窗口，可以开始本次框选。");
                  updateActions();
                }));
    windows.setOnAction(
        e -> {
          var active = workspace.binding();
          var selected = windows.getValue();
          if (calibrationTarget != null
              && (selected == null || !selected.identity().equals(calibrationTarget.identity()))) {
            calibrationTarget = null;
            markedAnswer = markedScore = markedSubmit = markedSubmissionAck = markedNext = null;
          }
          if (active != null
              && (selected == null
                  || !selected.identity().equals(active.calibratedWindow().identity()))) {
            workspace.resetEmbeddedBrowserTarget();
            bindingLabel.setText("未绑定；目标窗口选择已变化，请按当前选择重新绑定");
            state.setText("目标已撤销");
          }
          showPositions();
        });
    hotkeys.setOnAction(
        e ->
            observe(
                workspace.registerHotkeys(),
                v -> hotkeyLabel.setText("已注册 Ctrl+Alt+F8 安全暂停 / Ctrl+Alt+F9 停止")));
    start.setOnAction(e -> beginAuto(false));
    pause.setOnAction(
        e -> {
          cancelCountdown();
          workspace.pause(PauseReason.USER_PAUSED);
          notice.setText("已安全暂停并撤权；旧任务不能恢复，请重新绑定当前页面并开始新任务。");
        });
    stop.setOnAction(
        e -> {
          cancelCountdown();
          workspace.stop();
        });
    saveModel.setOnAction(
        e -> {
          if (modelWizardOpen) saveWizardDraft(false);
          else saveModel();
        });
    saveKey.setOnAction(
        e -> {
          if (modelWizardOpen) {
            saveWizardDraft(false);
            return;
          }
          var p = selectedProfile();
          if (p == null || p.byok() == null) return;
          char[] key = password.getText().toCharArray();
          password.clear();
          busy = true;
          updateActions();
          observePaid(
              workspace.saveCredential(p.byok(), key),
              saved -> {
                profiles.clear();
                workspace.profiles().forEach(profile -> profiles.put(profile.id(), profile));
                credentialStatuses.clear();
                loadProfile();
                notice.setText("Key 已用当前 Windows 用户加密缓存；相关能力验证已撤销，请重新验证。");
              });
        });
    forgetKey.setOnAction(
        e -> {
          var selected = selectedProfile();
          if (selected == null || selected.byok() == null) return;
          Alert confirm =
              new Alert(
                  Alert.AlertType.CONFIRMATION,
                  "这会删除当前 Key 的加密缓存，并撤销所有共用此 Key 的模型验证。取消向导也不会恢复；以后需重新填写 Key。",
                  ButtonType.OK,
                  ButtonType.CANCEL);
          confirm.initOwner(stage);
          confirm.setHeaderText("确认忘记当前 Key？");
          if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
          wizardForgotCredential = true;
          password.clear();
          busy = true;
          updateActions();
          observePaid(
              workspace.forgetCredential(selected.byok()),
              forgotten -> {
                profiles.clear();
                workspace.profiles().forEach(profile -> profiles.put(profile.id(), profile));
                credentialStatuses.clear();
                loadProfile();
                notice.setText("已删除此 Key 的本机加密缓存，并撤销使用它的模型验证。");
              });
        });
    saveModelRoles.setOnAction(
        e -> {
          var primary = scoringModel.getValue();
          var fallback = fallbackModel.getValue();
          var generation = rubricModel.getValue();
          if (primary == null || generation == null) return;
          if (fallback != null && fallback.id().equals(primary.id())) {
            modelRolesStatus.setText("备用模型必须选择其他配置，或选择不使用备用模型。");
            return;
          }
          busy = true;
          updateActions();
          observePaid(
              workspace.saveLocalModelSelection(
                  primary.id(), fallback == null ? null : fallback.id(), generation.id()),
              saved -> {
                modelRolesDirty = false;
                refreshModelRoles();
                notice.setText("模型用途已保存；修改不会自动开始阅卷。");
              });
        });
    textCapability.setOnAction(
        e -> {
          var selected = selectedProfile();
          long ticket = workspace.actionTicket();
          if (selected == null || !confirmPaid("向所编辑的端点发送一条合成文本，验证结构化文本生成能力。供应商可能计费。")) return;
          if (workspace.actionTicket() != ticket || !Objects.equals(selectedProfile(), selected))
            return;
          busy = true;
          updateActions();
          observePaid(
              workspace.testTextCapability(selected.byok()),
              verified -> {
                profiles.put(verified.id(), verified);
                loadProfile();
                notice.setText(
                    DesktopWorkspace.textCapabilityVerified(verified)
                        ? "文本生成能力验证通过"
                        : "文本生成能力验证未通过，规则生成保持禁用");
              });
        });
    capability.setOnAction(
        e -> {
          var p = selectedProfile();
          long confirmedTicket = workspace.actionTicket();
          if (p == null || !confirmPaid("向所选端点发送一张合成数字图片。供应商可能计费。")) return;
          if (workspace.actionTicket() != confirmedTicket || !Objects.equals(selectedProfile(), p))
            return;
          busy = true;
          updateActions();
          observePaid(
              workspace.testCapability(p.byok()),
              verified -> {
                profiles.put(verified.id(), verified);
                loadProfile();
                notice.setText(verified.isVerified() ? "合成图能力验证通过" : "图像测试未通过，真实试评保持禁用");
              });
        });
    modelName.textProperty().addListener((o, a, b) -> modelEdited());
    for (TextInputControl input : List.of(endpoint, modelId))
      input.textProperty().addListener((o, a, b) -> modelTargetEdited());
    structured.setOnAction(e -> modelEdited());
    password.textProperty().addListener((o, a, b) -> updateActions());
    for (TextInputControl input :
        List.of(questionId, rubricSubject, reference, criteria, maximum, step))
      input.textProperty().addListener((o, a, b) -> rubricEdited());
    runDate.setOnAction(e -> filterRuns());
    runChoice.setOnAction(
        e -> {
          var r = runChoice.getValue();
          if (r != null)
            observe(
                workspace.attempts(r.runId()),
                rows -> {
                  allAttempts = rows;
                  filterAttempts();
                });
          updateActions();
        });
    recordState.setOnAction(e -> filterAttempts());
    answerFilter.textProperty().addListener((o, a, b) -> filterAttempts());
    submissionLimit.textProperty().addListener((o, a, b) -> updateActions());
    records
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, r) -> {
              if (r != null) {
                recordDetail.setText("正在读取配置、调用用量和提交证据…");
                recordImage.setImage(null);
                observe(
                    workspace.recordAudit(r),
                    text -> {
                      var selected = records.getSelectionModel().getSelectedItem();
                      if (selected != null && selected.attemptId().equals(r.attemptId()))
                        recordDetail.setText(text);
                    });
                observe(
                    workspace.evidenceImage(r),
                    png -> {
                      var selected = records.getSelectionModel().getSelectedItem();
                      if (selected != null && selected.attemptId().equals(r.attemptId()))
                        recordImage.setImage(new Image(new ByteArrayInputStream(png)));
                    });
              }
            });
    exportCsv.setOnAction(
        e -> {
          var r = runChoice.getValue();
          if (r != null)
            chooseFile(
                "导出会话记录",
                "deskgrade-" + r.runId() + ".csv",
                "*.csv",
                p ->
                    observe(
                        workspace.exportCsv(p, r.runId()),
                        v -> notice.setText("CSV 已导出；保留未知状态并处理外部文本公式注入")));
        });
  }

  private void showBinding(TargetBinding bound) {
    var r = bound.answerRegion();
    if (workspace.developerMode() && !workspace.commercialMode())
      bindingLabel.setText(
          bound.calibratedWindow().title()
              + "\n选区 "
              + r.x()
              + ", "
              + r.y()
              + " · "
              + r.width()
              + " × "
              + r.height()
              + " · DPI "
              + bound.calibratedWindow().dpi());
    else bindingLabel.setText("当前内置阅卷网页\n本次所需区域均已绑定");
    boolean automatic = workspace.automaticTarget();
    boolean manual = workspace.automaticManualTarget();
    state.setText(manual ? "本次框选已绑定，等待开始" : automatic ? "目标已验证，等待开始" : "仅只读绑定");
    notice.setText(
        manual
            ? "本次框选已绑定；程序会在评分返回、填分和提交前复核答题区域，发现页面变化立即停止。"
            : automatic ? "当前目标已经过窗口与页面校验，尚未执行输入。" : "当前只有只读区域，不能自动阅卷；请逐项框选本次需要的位置并完成绑定。");
    updateActions();
  }

  private void beginAuto(boolean resume) {
    if (!canBeginAuto(resume)) return;
    int maximumCount = parseSubmissionLimit(submissionLimit.getText());
    if (maximumCount == 0) {
      notice.setText("本次阅卷数量必须是 1–10000 的整数。");
      updateActions();
      return;
    }
    int maximumCalls = 0;
    DesktopWorkspace.ModelSelection paidProfile = liveSyntheticMode() ? scoringProfile() : null;
    if (liveSyntheticMode()
        && (paidProfile == null
            || !paidProfile.isVerified()
            || modelDirty
            || !credentialStatuses.getOrDefault(paidProfile.id(), false)
            || modelRolesDirty
            || !workspace.developerMode() && !rubricReady())) return;
    long beforePaidConfirmation = workspace.actionTicket();
    if (liveSyntheticMode()
        && !confirmPaid(
            "本次最多阅卷 "
                + maximumCount
                + " 份；按当前绑定方式的处理证据计数，达到上限后自动停止，不点击独立下一份。\n"
                + (workspace.automaticManualTarget() ? "坐标模式将实际填分和提交；点击或画面切换证据不代表平台业务回执。\n" : "")
                + "本机直接调用所配置模型，费用由供应商按账户规则收取。无法判断或评分校验失败时，可由已配置的备用模型再判断一次。安全连接重试最多 3 次、间隔 20 秒；提交结果未知不会自动补交。安全暂停后须重新框选并开始新任务。",
            paidProfile)) return;
    if (workspace.actionTicket() != beforePaidConfirmation || !canBeginAuto(resume)) return;
    if (paidProfile != null
        && (!liveSyntheticMode() || !Objects.equals(scoringProfile(), paidProfile))) return;
    countdownActive = true;
    long authorizedTicket = workspace.actionTicket();
    int[] seconds = {3};
    Alert alert =
        new Alert(
            Alert.AlertType.INFORMATION,
            "本次最多阅卷 " + maximumCount + " 份。将对已绑定窗口实际填分并提交，请暂停其他鼠标键盘操作。可取消；Ctrl+Alt+F9 紧急停止。",
            ButtonType.CANCEL);
    alert.initOwner(stage);
    alert.setTitle("自动阅卷倒计时");
    countdownDialog = alert;
    alert.setHeaderText("3 秒后激活已绑定窗口");
    alert.setOnHidden(
        e -> {
          if (countdownActive) cancelCountdown();
        });
    countdown =
        new Timeline(
            new KeyFrame(
                javafx.util.Duration.seconds(1),
                e -> {
                  seconds[0]--;
                  alert.setHeaderText(seconds[0] + " 秒后激活已绑定窗口");
                  if (seconds[0] == 0) {
                    countdownActive = false;
                    countdown.stop();
                    if (workspace.actionTicket() != authorizedTicket || !canBeginAuto(resume)) {
                      alert.close();
                      notice.setText("开始条件已经变化，本次倒计时已取消；请重新检查绑定与配置。");
                      updateActions();
                      return;
                    }
                    alert.close();
                    starting = true;
                    updateActions();
                    stage.setIconified(true);
                    var operation =
                        paidProfile != null
                            ? (resume
                                ? workspace.resumeLiveSynthetic(
                                    paidProfile, maximumCount, maximumCalls, authorizedTicket)
                                : workspace.startLiveSynthetic(
                                    paidProfile, maximumCount, maximumCalls, authorizedTicket))
                            : (resume
                                ? workspace.resumeMock(maximumCount, maximumCalls, authorizedTicket)
                                : workspace.startMock(
                                    true, maximumCount, maximumCalls, authorizedTicket));
                    operation.whenComplete(
                        (ignored, error) ->
                            fx(
                                () -> {
                                  starting = false;
                                  if (error != null) {
                                    restoreWindow();
                                    notice.setText(safeError(error));
                                  } else {
                                    var current = workspace.status();
                                    if (current != null) showStatus(current);
                                  }
                                  updateActions();
                                }));
                  }
                }));
    countdown.setCycleCount(3);
    alert.show();
    countdown.play();
    updateActions();
  }

  private boolean canBeginAuto(boolean resume) {
    if (!workspace.ready()
        || busy
        || starting
        || countdownActive
        || modelWizardOpen
        || !syntheticAutoMode()
        || !workspace.automaticTarget()
        || !workspace.hotkeysAvailable()
        || !selectedWindowIsBound()) return false;
    var status = workspace.status();
    if (status != null && status.state() == SessionState.SUBMISSION_UNKNOWN) return false;
    if (status != null
        && !Set.of(
                SessionState.IDLE,
                SessionState.PAUSED,
                SessionState.STOPPED,
                SessionState.COMPLETED,
                SessionState.SUBMISSION_UNKNOWN,
                SessionState.FAILED)
            .contains(status.state())) return false;
    return !resume
        || status != null
            && status.state() == SessionState.PAUSED
            && workspace.liveSyntheticSession() == liveSyntheticMode();
  }

  private boolean selectedWindowIsBound() {
    var selected = windows.getValue();
    var bound = workspace.binding();
    return selected != null
        && bound != null
        && selected.identity().equals(bound.calibratedWindow().identity());
  }

  private void cancelCountdown() {
    countdownActive = false;
    if (countdown != null) countdown.stop();
    if (countdownDialog != null && countdownDialog.isShowing()) countdownDialog.close();
    countdownDialog = null;
    updateActions();
  }

  private void showStatus(SessionController.Status s) {
    var current = workspace.status();
    if (current == null || !current.equals(s)) return;
    state.setText(workspace.developerMode() ? s.state().name() : localStateText(s.state()));
    confirmed.setText(Integer.toString(s.confirmedSubmissions()));
    calls.setText("模型调用 " + s.modelCalls() + " 次");
    // Status.lastScore may be assigned before saveGrade succeeds. Only a presentation displays it.
    if (s.state() == SessionState.CAPTURING || s.state() == SessionState.GRADING) {
      gradeLabel.setText("AI 正在评分");
      result.clear();
      usage.setText("正在等待本次调用结果");
    } else if (s.state() == SessionState.VALIDATING) {
      gradeLabel.setText("正在校验评分");
    } else if (s.reason() == PauseReason.INVALID_RESULT) {
      gradeLabel.setText("评分未通过校验，不提交");
      result.clear();
    }
    String pauseMessage = SessionFailurePresenter.message(s);
    if (!pauseMessage.isBlank()) notice.setText(pauseMessage);
    if (Set.of(
            SessionState.PAUSED,
            SessionState.STOPPED,
            SessionState.COMPLETED,
            SessionState.SUBMISSION_UNKNOWN,
            SessionState.FAILED)
        .contains(s.state())) {
      if (!starting) restoreWindow();
      refreshRecords();
    }
    updateActions();
  }

  private static String localStateText(SessionState value) {
    return switch (value) {
      case IDLE -> "等待开始";
      case PRECHECK -> "正在检查";
      case WAIT_READY -> "等待页面就绪";
      case CAPTURING -> "正在读取答题内容";
      case GRADING -> "正在评分";
      case VALIDATING -> "正在校验结果";
      case BEFORE_INPUT -> "准备填分";
      case FILLING -> "正在填分";
      case VERIFYING_SCORE -> "正在核对分数";
      case SUBMIT_PREPARED -> "准备提交";
      case SUBMIT_SENT, WAIT_ACK -> "正在确认提交";
      case NAVIGATING, WAIT_NEXT -> "正在进入下一份";
      case PAUSED -> "已暂停";
      case SUBMISSION_UNKNOWN -> "提交状态待核对";
      case STOPPED -> "已停止";
      case COMPLETED -> "已完成";
      case FAILED -> "异常停止";
    };
  }

  private void showResult(GradingPresentation presentation) {
    if (!presentation.belongsTo(workspace.actionTicket(), workspace.accountTicket())) return;
    GradingResponse r = presentation.response();
    boolean diagnostics = true;
    StringBuilder text = new StringBuilder();
    if (diagnostics)
      text.append("来源：").append(r.source()).append(" · ").append(r.result().status()).append('\n');
    else
      text.append("评分状态：")
          .append(r.result().status() == GradeResult.Status.SCORED ? "已完成" : "无法评分")
          .append('\n');
    for (var c : r.result().criteria())
      text.append(c.criterionId())
          .append("：")
          .append(c.awarded() == null ? "无法评分" : c.awarded().toPlainString())
          .append("　")
          .append(c.reason())
          .append("\n证据：")
          .append(c.evidence())
          .append('\n');
    text.append("识别文字：")
        .append(r.result().studentText())
        .append("\n问题：")
        .append(r.result().issues());
    result.setText(text.toString());
    var u = r.usage();
    String elapsed = r.elapsed() == null ? "未知" : r.elapsed().toMillis() + " ms";
    usage.setText(
        diagnostics
            ? "来源 "
                + r.source()
                + " · 耗时 "
                + elapsed
                + "\n输入 Token "
                + (u == null || u.inputTokens() == null ? "未知" : u.inputTokens())
                + " · 输出 "
                + (u == null || u.outputTokens() == null ? "未知" : u.outputTokens())
                + "\n费用 "
                + (u == null || u.estimatedCost() == null
                    ? "未知"
                    : u.estimatedCost().toPlainString())
            : "本次处理耗时 " + elapsed);
    gradeLabel.setText(
        r.result().status() == GradeResult.Status.SCORED
            ? presentation.validatedTotal().toPlainString() + " 分"
            : "无法评分，不提交");
  }

  private void saveModel() {
    try {
      var old = selectedProfile();
      var next =
          new ProviderProfile(
              profileId(),
              old == null ? 1 : old.revision() + 1,
              modelName.getText().trim(),
              URI.create(endpoint.getText().trim()),
              modelId.getText().trim(),
              old == null ? "credential_" + slot.getValue() : old.byok().credentialRef(),
              structured.getValue(),
              old == null ? Map.of() : old.byok().parameters(),
              old == null ? Set.of() : old.byok().allowedParameters(),
              old == null ? Duration.ofSeconds(10) : old.byok().connectTimeout(),
              old == null ? Duration.ofSeconds(120) : old.byok().requestTimeout(),
              old == null ? null : old.byok().pricing(),
              null);
      busy = true;
      updateActions();
      observePaid(
          workspace.saveProfile(next),
          saved -> {
            profiles.put(saved.id(), saved);
            loadProfile();
            notice.setText("已保存模型新版本，请测试图像能力");
          });
    } catch (RuntimeException invalid) {
      busy = false;
      notice.setText("模型配置无效：检查完整端点、精确模型 ID 与名称");
      updateActions();
    }
  }

  private void loadProfile() {
    formLoading = true;
    credentialPresent = false;
    var p = selectedProfile();
    modelName.setText(p == null ? "模型 " + slot.getValue() : p.displayName());
    endpoint.setText(p == null ? "" : p.endpoint().toString());
    modelId.setText(p == null ? "" : p.modelId());
    qwenModels.setValue(
        p != null && LocalModelSetup.vendorOf(p.byok()) == LocalModelSetup.Vendor.QWEN
            ? p.modelId()
            : null);
    structured.setValue(p == null ? ProviderProfile.StructuredMode.NONE : p.structuredMode());
    qwenThinking.setValue(QwenThinkingOptions.Mode.KEEP);
    password.clear();
    modelDirty = false;
    formLoading = false;
    modelLabel.setText(
        p == null
            ? "尚未配置，不发起真实调用"
            : "版本 "
                + p.revision()
                + " · "
                + (p.isVerified() ? "图像已验证" : "图像未验证")
                + (DesktopWorkspace.textCapabilityVerified(p.byok()) ? " · 文本已验证" : " · 文本未验证"));
    if (p != null)
      observe(
          workspace.hasCredential(p.byok()),
          present -> {
            if (Objects.equals(selectedProfile(), p)) {
              credentialPresent = present;
              credentialStatuses.put(p.id(), present);
              modelLabel.setText(modelLabel.getText() + (present ? " · Key 已加密缓存" : " · 缺少 Key"));
            }
          });
    refreshModelRoles();
    updateActions();
  }

  private void loadRubric(Rubric r, String subject) {
    formLoading = true;
    questionId.setText(r.question().id());
    rubricSubject.setText(subject);
    reference.setText(r.question().standardAnswer());
    maximum.setText(r.question().maximumScore().toPlainString());
    step.setText(r.scoreStep().toPlainString());
    criteria.setText(formatCriteria(r.question().criteria()));
    rubricDirty = false;
    formLoading = false;
    rubricStatus.setText("规则版本 " + r.question().revision() + " · 已保存");
    updateActions();
  }

  private void saveRubric() {
    saveRubric("评分规则新版本已保存，旧结果无执行权限");
  }

  private void saveRubric(String successNotice) {
    if (rubricSaving) {
      rubricStatus.setText("评分规则正在保存；当前修改会保留，完成后可再次保存");
      return;
    }
    try {
      List<ScoringCriterion> points = new ArrayList<>();
      for (String line : criteria.getText().split("\\R")) {
        String[] parts = line.split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException();
        points.add(
            new ScoringCriterion(
                parts[0].trim(),
                parts[2].trim(),
                Arrays.stream(parts[1].split(","))
                    .map(String::trim)
                    .map(BigDecimal::new)
                    .toList()));
      }
      var next =
          new Rubric(
              new QuestionDefinition(
                  questionId.getText().trim(),
                  savedRubric == null ? 1 : savedRubric.question().revision() + 1,
                  "[原文缺失]",
                  reference.getText(),
                  new BigDecimal(maximum.getText()),
                  points),
              formatCriteria(points),
              new BigDecimal(step.getText()),
              false,
              true);
      String nextSubject = rubricSubject.getText();
      long saveAccountTicket = workspace.accountTicket();
      PendingGeneratedRubric generatedReceipt = pendingGeneratedRubric;
      if (generatedReceipt != null && generatedReceipt.accountTicket() != saveAccountTicket) {
        pendingGeneratedRubric = null;
        generatedReceipt = null;
      }
      boolean generatedCandidate =
          generatedReceipt != null
              && generatedReceipt.matches(next, nextSubject, saveAccountTicket);
      boolean explicitlyDiscarded = false;
      if (generatedReceipt != null && !generatedCandidate) {
        if (!confirmDiscardGeneratedRubric()) {
          rubricStatus.setText("当前内容与刚生成的评分点不同；尚未保存，也未清理生成回执");
          updateActions();
          return;
        }
        explicitlyDiscarded = true;
      }
      PendingGeneratedRubric receiptToFinalize =
          generatedCandidate || explicitlyDiscarded ? generatedReceipt : null;
      long saveRevision = rubricEditRevision;
      rubricSaving = true;
      updateActions();
      workspace
          .saveRubric(next, nextSubject)
          .thenCompose(
              saved -> {
                if (receiptToFinalize == null)
                  return CompletableFuture.completedFuture(new RubricSaveOutcome(saved, null));
                return workspace
                    .finalizeConfirmedRubricGeneration(receiptToFinalize.requestId())
                    .handle(
                        (ignored, receiptFailure) -> new RubricSaveOutcome(saved, receiptFailure))
                    .toCompletableFuture();
              })
          .whenComplete(
              (outcome, error) ->
                  fx(
                      () -> {
                        rubricSaving = false;
                        if (receiptToFinalize != null
                            && activeRubricGenerationAccountTicket == saveAccountTicket)
                          activeRubricGenerationAccountTicket = -1;
                        if (error == null) {
                          Rubric saved = outcome.rubric();
                          savedRubric = saved;
                          if (receiptToFinalize != null
                              && outcome.receiptFailure() == null
                              && pendingGeneratedRubric == receiptToFinalize)
                            pendingGeneratedRubric = null;
                          if (rubricEditRevision == saveRevision) {
                            loadRubric(saved, workspace.rubricSubject());
                            if (outcome.receiptFailure() == null) notice.setText(successNotice);
                            else rubricStatus.setText("规则已保存，但生成回执尚未清理；请先核对本地记录再重新生成");
                          } else {
                            rubricDirty = true;
                            rubricStatus.setText("保存已完成，但保存期间有新修改；请再次保存当前内容");
                            notice.setText("已保留你刚才的新修改，旧保存结果不会覆盖当前输入");
                          }
                          if (outcome.receiptFailure() != null)
                            notice.setText(safeError(outcome.receiptFailure()));
                        } else {
                          restoreWindow();
                          rubricStatus.setText("规则保存失败；当前生成或编辑内容仍保留在界面中");
                          notice.setText(safeError(error));
                        }
                        updateActions();
                      }));
    } catch (RuntimeException invalid) {
      rubricSaving = false;
      if (activeRubricGenerationAccountTicket == workspace.accountTicket())
        activeRubricGenerationAccountTicket = -1;
      rubricStatus.setText("规则无效，尚未保存");
      notice.setText("规则无效：检查评分点格式、唯一 ID、允许值、满分求和与步长");
      updateActions();
    }
  }

  private boolean confirmDiscardGeneratedRubric() {
    Alert confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "你已修改刚才 AI 生成的内容。继续会保存当前规则，并放弃那份生成结果；供应商可能已对调用计费。",
            ButtonType.OK,
            ButtonType.CANCEL);
    confirm.initOwner(stage);
    confirm.setTitle("确认放弃 AI 生成结果");
    confirm.setHeaderText("保存当前修改并安全清理生成回执？");
    return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private void requestRubricGeneration() {
    if (rubricSubject.getText().isBlank()
        || reference.getText().isBlank()
        || maximum.getText().isBlank()) {
      rubricStatus.setText("请先填写题目类型 / 学科、标准答案和满分");
      return;
    }
    if (generateRules.isDisabled()) {
      rubricStatus.setText(DisabledActionHints.explanation(generateRules));
      return;
    }
    final BigDecimal requestedMaximum;
    try {
      requestedMaximum = new BigDecimal(maximum.getText().trim());
      if (requestedMaximum.signum() <= 0
          || requestedMaximum.compareTo(new BigDecimal("100")) > 0
          || requestedMaximum.scale() > 8) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      rubricStatus.setText("满分无效，请填写大于 0 的数字");
      return;
    }
    String requestedSubject = rubricSubject.getText().strip();
    String requestedReference = reference.getText().strip();
    long requestRevision = rubricEditRevision;
    long requestAccountTicket = workspace.accountTicket();
    long requestUiEpoch = ++rubricGenerationUiEpoch;
    activeRubricGenerationAccountTicket = requestAccountTicket;
    busy = true;
    rubricStatus.setText("本机正在调用模型生成评分点；供应商可能计费，请勿修改输入…");
    updateActions();
    workspace
        .generateRubric(requestedSubject, requestedReference, requestedMaximum)
        .whenComplete(
            (generated, error) ->
                fx(
                    () -> {
                      if (requestUiEpoch != rubricGenerationUiEpoch) return;
                      if (workspace.accountTicket() != requestAccountTicket) {
                        busy = false;
                        activeRubricGenerationAccountTicket = -1;
                        rubricStatus.setText("本地配置已变化；旧生成结果未写入，也未清理其回执");
                        updateActions();
                        return;
                      }
                      busy = false;
                      if (error != null) {
                        activeRubricGenerationAccountTicket = -1;
                        restoreWindow();
                        rubricStatus.setText("AI 评分点生成未完成；原有评分点未被覆盖，请查看本地记录");
                        notice.setText(safeError(error));
                        updateActions();
                        return;
                      }
                      if (rubricEditRevision != requestRevision
                          || !requestedSubject.equals(rubricSubject.getText().strip())
                          || !requestedReference.equals(reference.getText().strip())
                          || requestedMaximum.compareTo(new BigDecimal(maximum.getText().trim()))
                              != 0) {
                        busy = true;
                        rubricStatus.setText("生成期间输入已变化；结果不覆盖修改，正在安全结束本次生成回执…");
                        updateActions();
                        workspace
                            .finalizeConfirmedRubricGeneration(generated.requestId())
                            .whenComplete(
                                (ignored, finalizeError) ->
                                    fx(
                                        () -> {
                                          if (requestUiEpoch != rubricGenerationUiEpoch
                                              || workspace.accountTicket() != requestAccountTicket)
                                            return;
                                          busy = false;
                                          activeRubricGenerationAccountTicket = -1;
                                          if (finalizeError == null)
                                            rubricStatus.setText("生成期间输入已变化；结果已安全丢弃且未覆盖修改");
                                          else {
                                            rubricStatus.setText("生成期间输入已变化；结果未覆盖，但生成回执仍保留");
                                            notice.setText(safeError(finalizeError));
                                          }
                                          updateActions();
                                        }));
                        return;
                      }
                      formLoading = true;
                      try {
                        step.setText(generated.scoreStep().toPlainString());
                        criteria.setText(formatCriteria(generated.criteria()));
                      } finally {
                        formLoading = false;
                      }
                      rubricEditRevision++;
                      uiRevision++;
                      rubricDirty = true;
                      pendingGeneratedRubric =
                          new PendingGeneratedRubric(
                              generated.requestId(),
                              requestAccountTicket,
                              requestedSubject,
                              requestedReference,
                              requestedMaximum,
                              generated.scoreStep(),
                              generated.criteria());
                      rubricStatus.setText("AI 评分点已生成，正在保存新版本…");
                      saveRubric("AI 评分点已生成并保存到本机");
                      updateActions();
                    }));
  }

  static String formatCriteria(List<ScoringCriterion> points) {
    return String.join(
        "\n",
        points.stream()
            .map(
                point ->
                    point.id()
                        + " | "
                        + String.join(
                            ",",
                            point.allowedScores().stream().map(BigDecimal::toPlainString).toList())
                        + " | "
                        + point.description())
            .toList());
  }

  private void modelEdited() {
    if (!formLoading) {
      uiRevision++;
      modelDirty = true;
      workspace.invalidate();
      modelLabel.setText("有未保存修改，真实调用已禁用");
      updateActions();
    }
  }

  private void modelTargetEdited() {
    if (formLoading) return;
    formLoading = true;
    qwenThinking.setValue(QwenThinkingOptions.Mode.KEEP);
    formLoading = false;
    modelEdited();
  }

  private void rubricEdited() {
    if (!formLoading) {
      uiRevision++;
      rubricEditRevision++;
      rubricDirty = true;
      workspace.invalidate();
      rubricStatus.setText("有未保存规则修改");
      updateActions();
    }
  }

  private boolean rubricReady() {
    try {
      if (savedRubric == null || rubricDirty) return false;
      savedRubric.validateReady();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  static String pauseMessage(PauseReason reason) {
    return reason == PauseReason.DUPLICATE_SCREEN
        ? "当前答题画面已有提交记录，请核对并切换下一份后重新绑定"
        : reason.description();
  }

  private boolean mockMode() {
    return workspace.developerMode() && mode.getSelectionModel().getSelectedIndex() == 1;
  }

  private boolean liveSyntheticMode() {
    if (!workspace.developerMode()) return true;
    return mode.getSelectionModel().getSelectedIndex() == 2;
  }

  private boolean syntheticAutoMode() {
    return mockMode() || liveSyntheticMode();
  }

  private String profileId() {
    return slot.getValue();
  }

  private DesktopWorkspace.ModelSelection selectedProfile() {
    var profile = profiles.get(profileId());
    return profile == null ? null : DesktopWorkspace.ModelSelection.of(profile);
  }

  static List<String> startBlockers(StartReadiness readiness) {
    Objects.requireNonNull(readiness);
    List<String> blockers = new ArrayList<>();
    if (!readiness.initialized()) blockers.add("本地数据尚未就绪");
    if (!readiness.bound()) blockers.add("尚未绑定答题目标");
    else if (!readiness.automaticTarget()) blockers.add("当前仅为只读绑定；请完成本次全部必需区域框选并绑定");
    if (readiness.automaticTarget() && !readiness.automaticMode()) blockers.add("当前运行方式不是自动阅卷");
    if (readiness.liveModelRequired()) {
      if (!readiness.modelPresent()) blockers.add("请在“模型与 Key”配置并指定阅卷主模型");
      else if (!readiness.modelVerified()) blockers.add("模型图像能力尚未验证");
      if (!readiness.credentialPresent()) blockers.add("阅卷主模型尚未缓存 Key");
      if (!readiness.modelClean()) blockers.add("模型配置有未保存修改");
      if (readiness.rubricRequired() && !readiness.rubricReady()) blockers.add("评分规则未保存、已修改或无效");
    }
    if (!readiness.hotkeysAvailable()) blockers.add("全局暂停/停止热键未注册或已被其他程序占用");
    if (readiness.operationBusy()) blockers.add("正在完成绑定或配置操作");
    if (readiness.running()) blockers.add("阅卷任务正在运行");
    if (readiness.submissionUnknown()) blockers.add("存在提交结果未知状态，请点击“处理旧未决提交”核对并结束旧批次");
    if (!readiness.validSubmissionCount()) blockers.add("本次阅卷数量必须为 1–10000 的整数");
    return List.copyOf(blockers);
  }

  static List<String> fallbackBlockers(
      boolean configured, boolean present, boolean verified, boolean keyPresent) {
    if (!configured) return List.of();
    List<String> blockers = new ArrayList<>();
    if (!present) blockers.add("备用模型配置不存在；请重新选择或停用备用模型");
    else if (!verified) blockers.add("备用模型图像能力尚未验证；请验证或停用备用模型");
    if (!keyPresent) blockers.add("备用模型尚未缓存 Key；请配置或停用备用模型");
    return List.copyOf(blockers);
  }

  private void updateActions() {
    boolean initialized = workspace.ready();
    boolean bound = selectedWindowIsBound();
    boolean ready = initialized;
    boolean operationBusy =
        busy
            || rubricSaving
            || positionBusy
            || starting
            || countdownActive
            || unresolvedDialog != null;
    mainTabs.setDisable(!ready);
    if (applyQwen != null) applyQwen.setDisable(!ready || operationBusy);
    if (applyOpenAi != null) applyOpenAi.setDisable(!ready || operationBusy);
    var s = workspace.status();
    boolean managedWindowReady = isCurrentManagedWindow(windows.getValue());
    for (Button mark : List.of(markAnswer, markScore, markSubmit))
      mark.setDisable(!ready || !managedWindowReady || operationBusy);
    boolean clickNext =
        navigationMode.getValue() == ManualPlatformCalibration.NavigationMode.CLICK_NEXT;
    markSubmissionAck.setDisable(!ready || !managedWindowReady || operationBusy || !clickNext);
    markNext.setDisable(!ready || !managedWindowReady || operationBusy || !clickNext);
    clearNext.setDisable(markedSubmissionAck == null && markedNext == null || operationBusy);
    navigationMode.setDisable(!ready || operationBusy);
    usePositions.setDisable(
        !ready
            || operationBusy
            || !managedWindowReady
            || navigationMode.getValue() == null
            || calibrationTarget == null
            || !calibrationTarget.identity().equals(windows.getValue().identity())
            || markedAnswer == null
            || markedScore == null
            || markedSubmit == null
            || clickNext && (markedSubmissionAck == null || markedNext == null)
            || !clickNext && (markedSubmissionAck != null || markedNext != null));
    boolean unknown = s != null && s.state() == SessionState.SUBMISSION_UNKNOWN;
    boolean running =
        s != null
            && !Set.of(
                    SessionState.IDLE,
                    SessionState.PAUSED,
                    SessionState.STOPPED,
                    SessionState.COMPLETED,
                    SessionState.SUBMISSION_UNKNOWN,
                    SessionState.FAILED)
                .contains(s.state());
    boolean automatic = workspace.automaticTarget();
    completionCaption.setText(
        workspace.automaticManualTarget() ? "已处理份数（坐标操作）" : automatic ? "平台确认提交" : "已处理份数");
    var p = scoringProfile();
    var editingProfile = selectedProfile();
    boolean primaryCredentialPresent = p != null && credentialStatuses.getOrDefault(p.id(), false);
    String fallbackId = workspace.localModelSelection().fallbackId();
    var fallback = profiles.get(fallbackId);
    List<String> fallbackReadiness =
        fallbackBlockers(
            !fallbackId.isEmpty(),
            fallback != null,
            fallback != null && fallback.isVerified(),
            credentialStatuses.getOrDefault(fallbackId, false));
    boolean rubricUsable = rubricReady();
    boolean liveReady =
        p != null
            && p.isVerified()
            && primaryCredentialPresent
            && !modelRolesDirty
            && !modelDirty
            && (workspace.developerMode() || rubricUsable)
            && fallbackReadiness.isEmpty();
    boolean validSubmissionCount = parseSubmissionLimit(submissionLimit.getText()) != 0;
    List<String> blockers =
        new ArrayList<>(
            startBlockers(
                new StartReadiness(
                    initialized,
                    bound,
                    syntheticAutoMode(),
                    automatic,
                    liveSyntheticMode(),
                    p != null,
                    p != null && p.isVerified(),
                    primaryCredentialPresent,
                    !modelDirty && !modelRolesDirty,
                    !workspace.developerMode(),
                    rubricUsable,
                    workspace.hotkeysAvailable(),
                    operationBusy,
                    running,
                    unknown,
                    validSubmissionCount)));
    if (liveSyntheticMode()) blockers.addAll(fallbackReadiness);
    if (modelWizardOpen) blockers.add("请先完成或取消模型配置向导");
    startReadiness.setText(
        blockers.isEmpty() ? "开始条件已满足；点击开始后仍会经过 3 秒安全倒计时。" : "暂不能开始：" + String.join("；", blockers));
    start.setText("开始" + (liveSyntheticMode() ? "真实模型自动阅卷" : "模拟自动"));
    if (!workspace.developerMode()) start.setText("开始阅卷");
    start.setDisable(
        !ready
            || !bound
            || !syntheticAutoMode()
            || (liveSyntheticMode() && !liveReady)
            || !automatic
            || !workspace.hotkeysAvailable()
            || operationBusy
            || running
            || unknown
            || !validSubmissionCount
            || modelWizardOpen
            || countdownActive);
    pause.setDisable(!running && !countdownActive);
    stop.setDisable(s == null && !countdownActive);
    recoverUnresolved.setDisable(
        !ready || operationBusy || running || workspace.manualCalibration() == null);
    embeddedBrowserUrl.setDisable(!ready || operationBusy || running || countdownActive);
    openEmbeddedBrowser.setDisable(
        !ready || operationBusy || running || countdownActive || !embeddedBrowserUrlReady());
    submissionLimit.setDisable(!ready || operationBusy || running || countdownActive);
    refreshWindows.setDisable(!ready || operationBusy || loadedBrowserSession == null);
    hotkeys.setDisable(!ready || workspace.hotkeysAvailable());
    slot.setDisable(!ready || operationBusy || running);
    qwenModels.setDisable(!ready || operationBusy || running);
    applyQwen.setDisable(!ready || operationBusy || running);
    applyOpenAi.setDisable(!ready || operationBusy || running);
    for (var choice : List.of(scoringModel, fallbackModel, rubricModel))
      choice.setDisable(!ready || operationBusy || running);
    saveModelRoles.setDisable(
        !ready
            || operationBusy
            || running
            || modelDirty
            || !modelRolesDirty
            || modelWizardOpen
            || scoringModel.getValue() == null
            || rubricModel.getValue() == null);
    capability.setDisable(
        !ready
            || editingProfile == null
            || modelDirty
            || !credentialPresent
            || operationBusy
            || running);
    textCapability.setDisable(
        !ready
            || editingProfile == null
            || modelDirty
            || !credentialPresent
            || operationBusy
            || running);
    saveModel.setDisable(
        !ready
            || endpoint.getText().isBlank()
            || modelId.getText().isBlank()
            || modelName.getText().isBlank()
            || operationBusy
            || running);
    saveKey.setDisable(
        !ready
            || editingProfile == null
            || modelDirty
            || password.getText().isEmpty()
            || operationBusy
            || running);
    forgetKey.setDisable(
        !ready || editingProfile == null || !credentialPresent || operationBusy || running);
    for (TextInputControl input : List.of(modelName, endpoint, modelId, password))
      input.setDisable(!ready || operationBusy || running);
    structured.setDisable(!ready || operationBusy || running);
    List<String> generationBlockers = ruleGenerationBlockers(operationBusy, running, unknown);
    generateRules.setDisable(!generationBlockers.isEmpty());
    generationHint.setText(
        generationBlockers.isEmpty() ? "" : "暂不能生成：" + String.join("；", generationBlockers));
    generationHint.setVisible(!generationBlockers.isEmpty());
    generationHint.setManaged(!generationBlockers.isEmpty());
    DisabledActionHints.reason(generateRules, String.join("；", generationBlockers));
    saveRules.setDisable(!ready || operationBusy || running || unknown || !rubricDirty);
    exportCsv.setDisable(!ready || runChoice.getValue() == null);
    updateModelSetup(ready, operationBusy, running);
    updateDisabledHints(ready, operationBusy, running, unknown, blockers);
  }

  private List<String> ruleGenerationBlockers(
      boolean operationBusy, boolean running, boolean unknown) {
    List<String> reasons = new ArrayList<>();
    String modelReason = workspace.generationModelUnavailableReason();
    if (!modelReason.isEmpty()) reasons.add(modelReason);
    if (operationBusy) reasons.add("正在处理操作，请等待当前操作完成");
    if (running) reasons.add("正在阅卷，请先暂停或停止任务");
    if (unknown) reasons.add("存在提交结果未知的任务，请先在阅卷页处理旧未决提交");
    if (rubricSubject.getText().isBlank()) reasons.add("请填写题目类型 / 学科");
    if (reference.getText().isBlank()) reasons.add("请填写标准答案");
    if (!validGenerationMaximum(maximum.getText())) reasons.add("请填写大于 0 且不超过 100 的满分（最多 8 位小数）");
    return List.copyOf(reasons);
  }

  static boolean validGenerationMaximum(String text) {
    try {
      BigDecimal value = new BigDecimal(text.strip());
      return value.signum() > 0
          && value.compareTo(BigDecimal.valueOf(100)) <= 0
          && value.scale() <= 8;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private void updateDisabledHints(
      boolean ready,
      boolean operationBusy,
      boolean running,
      boolean unknown,
      List<String> blockers) {
    String common =
        !ready
            ? "本地数据正在打开；若一直未就绪，请查看底部错误提示并重新启动软件。"
            : operationBusy ? "正在处理操作，请等待当前操作完成。" : running ? "正在自动阅卷，请先暂停或停止任务。" : "";
    DisabledActionHints.reason(mainTabs, !ready ? common : "");
    DisabledActionHints.reason(start, String.join("；", blockers));
    DisabledActionHints.reason(pause, !ready ? common : "当前没有正在运行的阅卷任务或开始倒计时。");
    DisabledActionHints.reason(stop, !ready ? common : "尚未开始阅卷任务，无需停止。");
    hint(recoverUnresolved, common, "请先完成当前答题窗口的框选并绑定，才能核对旧未决提交。");
    hint(openEmbeddedBrowser, common, "请填写有效的 HTTPS 网址（本机测试页允许 HTTP）。");
    hint(refreshWindows, common, "请先用顶部网址入口打开网站，等待网页加载完成。");
    hint(hotkeys, !ready ? common : "", "全局暂停 / 停止热键已注册，无需重复注册。");
    for (var mark : List.of(markAnswer, markScore, markSubmit))
      hint(mark, common, "请先打开网站，并选择已加载完成的内置阅卷窗口。");
    String markReason =
        !isCurrentManagedWindow(windows.getValue())
            ? "请先打开网站，并选择已加载完成的内置阅卷窗口。"
            : "仅“提交后需点击下一题”模式需要标定此区域。";
    hint(markSubmissionAck, common, markReason);
    hint(markNext, common, markReason);
    hint(clearNext, common, "尚未标定提交成功提示或下一题位置，无需清除。");
    List<String> positions = new ArrayList<>();
    if (!isCurrentManagedWindow(windows.getValue())) positions.add("请选择已加载的内置阅卷窗口");
    if (markedAnswer == null) positions.add("标定答题区域");
    if (markedScore == null) positions.add("标定分数输入框");
    if (markedSubmit == null) positions.add("标定提交按钮");
    if (navigationMode.getValue() == null) positions.add("选择提交后的导航方式");
    else if (navigationMode.getValue() == ManualPlatformCalibration.NavigationMode.CLICK_NEXT) {
      if (markedSubmissionAck == null) positions.add("标定提交成功提示区");
      if (markedNext == null) positions.add("标定下一题按钮");
    } else if (markedSubmissionAck != null || markedNext != null) positions.add("清除手动下一题的位置");
    hint(
        usePositions,
        common,
        positions.isEmpty() ? "窗口已变化，请重新框选并绑定。" : "请先完成：" + String.join("、", positions));
    String profileReason =
        selectedProfile() == null
            ? "请先保存模型配置。"
            : modelDirty
                ? "模型或 Key 有未保存修改，请先保存并继续。"
                : !credentialPresent ? "请先填写这个供应商的 API Key 并保存。" : "请先保存当前模型配置。";
    hint(capability, common, profileReason);
    hint(textCapability, common, profileReason);
    hint(
        saveKey,
        common,
        selectedProfile() == null || modelDirty ? profileReason : "请先填写需要缓存的 API Key。");
    hint(forgetKey, common, "当前模型没有已缓存的 Key，无需删除。");
    hint(saveModel, common, "请填写模型显示名称、接口地址和模型 ID。");
    hint(
        saveModelRoles,
        common,
        modelWizardOpen ? "请在配置向导最后一步确认启用。" : "请先选择主模型和规则生成模型；没有用途修改时无需重复保存。");
    hint(saveRules, common, unknown ? "请先处理旧未决提交，再修改评分规则。" : "当前规则没有未保存的修改。");
    hint(exportCsv, common, "请先选择需要导出的阅卷会话。");
    hint(applyQwen, common, "当前配置不可编辑，请先完成或取消当前操作。");
    hint(applyOpenAi, common, "当前配置不可编辑，请先完成或取消当前操作。");
    for (Button edit : modelEditButtons.values()) hint(edit, common, "请等待当前配置操作完成。");
    hint(setupBack, common, "已经是配置第一步。");
    hint(setupCancel, common, "当前配置操作正在执行，请等待完成后再取消。");
    hint(setupNext, common, modelSetupStep == 2 ? "请先通过此用途所需的能力验证。" : "请先选择用途和供应商。");
    hint(
        setupSave,
        common,
        password.getText().isEmpty() && !credentialPresent
            ? "请填写所选供应商自己的 API Key；当前配置没有可沿用的缓存。"
            : "请填写完整的模型名称、模型 ID 和接口地址。");
    hint(setupFinish, common, "请先保存配置并通过此用途所需的能力验证。");
  }

  private static void hint(ButtonBase button, String common, String specific) {
    if (button != null) DisabledActionHints.reason(button, common.isEmpty() ? specific : common);
  }

  private void refreshRecords() {
    if (recordsView != null) {
      if (workspace.ready()) recordsView.refresh();
      else recordsView.clear();
      return;
    }
    if (workspace.ready())
      observe(
          workspace.runs(),
          rows -> {
            allRuns = rows;
            filterRuns();
          });
  }

  private void filterRuns() {
    var selected = runChoice.getValue();
    runChoice
        .getItems()
        .setAll(
            allRuns.stream()
                .filter(
                    r ->
                        runDate.getValue() == null
                            || r.startedAt()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .equals(runDate.getValue()))
                .toList());
    if (selected != null && runChoice.getItems().contains(selected)) runChoice.setValue(selected);
    else if (!runChoice.getItems().isEmpty()) runChoice.getSelectionModel().selectFirst();
  }

  private void filterAttempts() {
    String text = answerFilter.getText().trim();
    int status = recordState.getSelectionModel().getSelectedIndex();
    records
        .getItems()
        .setAll(
            allAttempts.stream()
                .filter(r -> text.isEmpty() || r.answerId().contains(text))
                .filter(
                    r ->
                        status == 0
                            || status == 1 && r.confirmed()
                            || status == 2 && !r.confirmed()
                            || status == 3 && r.state() == SessionState.SUBMISSION_UNKNOWN)
                .toList());
  }

  private boolean confirmPaid(String text) {
    return confirmPaid(text, selectedProfile());
  }

  private boolean confirmPaid(String text, DesktopWorkspace.ModelSelection p) {
    String destination =
        p == null
            ? ""
            : "\n\n模型：" + p.modelId() + "\n端点：" + p.endpoint() + "\n费用由供应商账户直接结算，软件不收取次数。";
    Alert a =
        new Alert(
            Alert.AlertType.CONFIRMATION, text + destination, ButtonType.OK, ButtonType.CANCEL);
    a.initOwner(stage);
    a.setTitle("模型调用确认");
    a.setHeaderText("确认本机发送范围及供应商费用");
    return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private void chooseFile(String title, String name, String extension, Consumer<Path> selected) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    chooser.setInitialFileName(name);
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension, extension));
    var file = chooser.showSaveDialog(stage);
    if (file != null) selected.accept(file.toPath());
  }

  private <T> void observe(CompletableFuture<T> operation, Consumer<T> completed) {
    operation.whenComplete(
        (value, error) ->
            fx(
                () -> {
                  if (error == null) completed.accept(value);
                  else {
                    restoreWindow();
                    notice.setText(safeError(error));
                  }
                  updateActions();
                }));
  }

  private <T> void observePosition(CompletableFuture<T> operation, Consumer<T> completed) {
    positionBusy = true;
    long generation = ++positionRequestGeneration;
    updateActions();
    operation.whenComplete(
        (value, error) ->
            fx(
                () -> {
                  if (generation != positionRequestGeneration) return;
                  positionBusy = false;
                  if (error == null) completed.accept(value);
                  else {
                    restoreWindow();
                    resetPositionDraft("本次框选未能绑定，请确认窗口未变化后重新框选。");
                    notice.setText(safeError(error));
                  }
                  updateActions();
                }));
  }

  private <T> void observePaid(CompletableFuture<T> operation, Consumer<T> completed) {
    operation.whenComplete(
        (value, error) ->
            fx(
                () -> {
                  busy = false;
                  if (error == null) completed.accept(value);
                  else {
                    restoreWindow();
                    notice.setText(safeError(error));
                  }
                  updateActions();
                }));
  }

  static String safeError(Throwable error) {
    Throwable c = rootCause(error);
    if (c instanceof DesktopWorkspace.LocalProfileStorageException configuration)
      return switch (configuration.reason()) {
        case TOO_MANY_PROFILES -> "本机最多保存 256 个模型配置，本次未覆盖已有配置；请整理配置后重试。";
        case DOCUMENT_TOO_LARGE -> "本机模型配置文件不能超过 1 MiB，本次未覆盖已有配置。";
        case INVALID_CONFIGURATION -> "模型配置含过长或不支持的字段，本次未覆盖已有配置；请检查高级设置。";
      };
    if (c instanceof DesktopWorkspace.SelectionPreparationException selection)
      return switch (selection.reason()) {
        case WINDOW_UNAVAILABLE -> "无法读取所选窗口。请确认窗口仍打开且未最小化，然后刷新窗口列表重试。";
        case WINDOW_CHANGED -> "所选窗口的位置、大小或进程已经变化。请刷新窗口列表并重新选择。";
        case ACTIVATION_FAILED -> "无法将所选窗口切换到前台。请保持桌面解锁、关闭遮挡窗口后重试。";
      };
    if (c instanceof DesktopWorkspace.RubricGenerationPendingException pending)
      return switch (pending.reason()) {
        case CONFLICT -> "上一次 AI 评分点生成仍有未决记录。为防止重复调用，请恢复当时的学科、标准答案和满分后核对；相同请求会沿用原 ID。";
        case CORRUPT -> "本地 AI 评分点生成记录已损坏。为防止重复调用，已禁止创建新请求；请备份并检查本地记录。";
        case STORAGE_UNAVAILABLE -> "无法可靠保存或更新本地生成记录，本次不会发起新的模型请求；请检查用户数据目录。";
      };
    if (c instanceof IllegalStateException && "所选网站配置已变化；未绑定任何位置，请从列表重新选择。".equals(c.getMessage()))
      return c.getMessage();
    if (c instanceof GatewayException gateway) return safeGatewayError(gateway.code());
    if (c instanceof ModelCallException model)
      return switch (model.kind()) {
        case MISSING_CREDENTIAL -> "所选模型尚未缓存 Key，请在“模型与 Key”填写自己的 Key。";
        case UNVERIFIED_CONFIGURATION -> "模型能力尚未验证，请先验证所选模型的图像或文本能力。";
        case AUTHENTICATION -> "模型鉴权失败，请检查该模型的 Key、端点以及供应商账户权限。";
        case RATE_LIMIT -> "模型供应商限制了请求频率，请稍后重试。";
        case TIMEOUT -> "等待模型响应超时，结果可能未知；请先核对本地记录，勿重复提交旧答卷。";
        case NETWORK -> "本机连接模型服务失败，请检查网络与代理设置，并查看本地记录。";
        case CANCELLED -> "模型调用已取消，未授予填分或提交权限。";
        case INVALID_IMAGE -> "答题截图无效，本题未填分或提交，请重新框选。";
        default -> "模型未返回可用结果，本题已停止；请查看本地调用记录。";
      };
    if (c instanceof DeskGradeException) return "操作未完成，程序已停止；请核对当前页面和本地记录后重试。";
    return "操作未完成，请检查目标前台、遮挡、显示环境、配置和本地记录。";
  }

  private static String safeGatewayError(GatewayException.Code code) {
    return switch (code) {
      case AUTH_REQUIRED, INVALID_CREDENTIALS, ACCOUNT_DISABLED -> "模型服务拒绝访问，请检查自己的 Key 与端点配置。";
      case RATE_LIMITED -> "操作过于频繁，请稍后再试。";
      case INSUFFICIENT_CREDITS -> "模型供应商账户可用额度不足，请在供应商处核对。";
      case MODEL_POLICY_CHANGED -> "模型配置已变化，请核对本机配置后重新开始。";
      case REQUEST_IN_PROGRESS -> "上一项请求仍在处理中，请稍后刷新。";
      case DISPATCH_UNKNOWN -> "本次模型请求结果暂时无法确认。为避免重复调用，程序已停止。";
      case MODEL_FAILED, MODEL_RESULT_INVALID, INVALID_RESPONSE, FALLBACK_NOT_ELIGIBLE ->
          "本次评分未能获得可用结果，程序已停止。";
      case NETWORK, TIMEOUT, MODEL_UNAVAILABLE, STORE_UNAVAILABLE, INTERNAL_ERROR ->
          "在线服务暂时不可用，程序已停止；请稍后重试。";
      case CANCELLED, SESSION_CHANGED, CLIENT_CLOSED -> "当前操作已取消，请重新开始。";
      case DUPLICATE_SUBMISSION, IDEMPOTENCY_CONFLICT -> "检测到重复或冲突的请求。为避免重复操作，程序已停止。";
      case INVALID_REQUEST, FORBIDDEN, NOT_FOUND -> "当前操作无法完成，请刷新后重试。";
    };
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = Objects.requireNonNull(error);
    while (current.getCause() != null && current != current.getCause())
      current = current.getCause();
    return current;
  }

  private void restoreWindow() {
    if (stage.isShowing()) {
      stage.setIconified(false);
      stage.toFront();
    }
  }

  private static int positive(String value) {
    int n = Integer.parseInt(value);
    if (n < 1 || n > 10000) throw new IllegalArgumentException();
    return n;
  }

  static int parseSubmissionLimit(String value) {
    if (value == null || !value.matches("[0-9]{1,5}")) return 0;
    try {
      return positive(value);
    } catch (IllegalArgumentException ignored) {
      return 0;
    }
  }

  private static void fx(Runnable action) {
    Runnable guarded =
        () -> {
          try {
            action.run();
          } catch (RuntimeException | LinkageError failure) {
            DeskGradeApplication.handleUncaughtException(Thread.currentThread(), failure);
          }
        };
    if (Platform.isFxApplicationThread()) guarded.run();
    else Platform.runLater(guarded);
  }

  private static TableColumn<SessionJournal.AttemptRecord, String> column(
      String title, Function<SessionJournal.AttemptRecord, String> value) {
    var c = new TableColumn<SessionJournal.AttemptRecord, String>(title);
    c.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
    return c;
  }

  private static Button button(String text, String id) {
    Button b = new Button(text);
    b.setId(id);
    b.getStyleClass().add("secondary-action");
    return b;
  }

  private static ComboBox<String> combo(String... choices) {
    ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(choices));
    c.getSelectionModel().selectFirst();
    return c;
  }

  private static ImageView imageView(double width, double height) {
    ImageView v = new ImageView();
    v.setFitWidth(width);
    v.setFitHeight(height);
    v.setPreserveRatio(true);
    return v;
  }

  private static TextArea area(int rows) {
    TextArea a = new TextArea();
    a.setPrefRowCount(rows);
    a.setWrapText(true);
    return a;
  }

  private static Label label(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    l.setWrapText(true);
    return l;
  }

  private static VBox page(String title, String description) {
    VBox p = new VBox(16, new VBox(6, label(title, "page-title"), label(description, "muted")));
    p.getStyleClass().add("page");
    return p;
  }

  private static VBox card(String title, Node... content) {
    VBox c = new VBox(12, label(title, "card-title"));
    c.getStyleClass().add("card");
    c.getChildren().addAll(content);
    return c;
  }

  private static VBox field(String title, Node input) {
    return new VBox(5, label(title, "muted"), input);
  }

  private static FlowPane flow(Node... content) {
    FlowPane p = new FlowPane(10, 10, content);
    p.setAlignment(Pos.CENTER_LEFT);
    return p;
  }

  private static Tab tab(String name, Node content) {
    ScrollPane p = new ScrollPane(content);
    p.setFitToWidth(true);
    p.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    return new Tab(name, p);
  }
}
