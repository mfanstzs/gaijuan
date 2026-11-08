package app.deskgrade.desktop;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local audit browser. No image files are opened and no image controls are created. */
public final class RecordsView extends BorderPane {
  private static final Logger LOG = LoggerFactory.getLogger(RecordsView.class);

  public interface DataSource {
    CompletionStage<GradingRecords.Page> query(GradingRecords.Query query);

    CompletionStage<String> detail(UUID attemptId);

    CompletionStage<Void> exportJson(Path destination, GradingRecords.Query query);

    default String storageLocation() {
      return "完整记录：%USERPROFILE%\\.deskgrade\\data\\database · 日志摘要：%USERPROFILE%\\.deskgrade\\data\\records";
    }
  }

  private final DataSource source;
  private final boolean diagnosticExports;
  private final TextField answer = new TextField(),
      question = new TextField(),
      run = new TextField();
  private final DatePicker from = new DatePicker(), until = new DatePicker();
  private final ComboBox<String> state = new ComboBox<>();
  private final TableView<GradingRecords.Summary> table = new TableView<>();
  private final VBox detail = new VBox(12);
  private final ScrollPane detailScroll = new ScrollPane(detail);
  private final Label message = new Label("本机评分与提交记录，点击查询读取"), pagination = new Label();
  private final Button previous = new Button("上一页"),
      next = new Button("下一页"),
      export = new Button("导出筛选结果 JSON");
  private GradingRecords.Query current = GradingRecords.Query.firstPage();
  private long generation, detailGeneration;
  private boolean historyLoaded, queryPending, readFailed, exportPending;

  public RecordsView(DataSource source) {
    this(source, false);
  }

  RecordsView(DataSource source, boolean diagnosticExports) {
    this.source = Objects.requireNonNull(source);
    this.diagnosticExports = diagnosticExports;
    setPadding(new Insets(18));
    Label title = new Label("评分与提交记录");
    title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
    Label explanation =
        new Label(
            "自动读取本机全部历史，重启不会清空。查看学生答题内容、总分和分项依据。点击提交、页面推进与平台确认分别标注；手动坐标操作不代表平台已经保存，新答卷图片不保存。");
    explanation.setWrapText(true);
    Label location = new Label(source.storageLocation());
    location.setWrapText(true);
    answer.setPromptText("答卷编号（包含）");
    question.setPromptText("题号（包含）");
    run.setPromptText("批次编号（可选）");
    answer.setPrefColumnCount(11);
    question.setPrefColumnCount(9);
    run.setPrefColumnCount(18);
    from.setPromptText("开始日期");
    until.setPromptText("结束日期（含）");
    state.getItems().add("全部状态");
    for (SessionState value : SessionState.values()) state.getItems().add(value.name());
    state.setConverter(
        new javafx.util.StringConverter<>() {
          @Override
          public String toString(String value) {
            return stateLabel(value);
          }

          @Override
          public String fromString(String value) {
            return value;
          }
        });
    state.getSelectionModel().selectFirst();
    Button search = new Button("查询"), reset = new Button("重置筛选");
    search.setOnAction(event -> refresh());
    reset.setOnAction(event -> initializeHistory());
    FlowPane filters = new FlowPane(8, 8, answer, question, run, from, until, state, search, reset);
    VBox top = new VBox(10, title, explanation, location, filters, message);
    top.setPadding(new Insets(0, 0, 12, 0));
    setTop(top);
    column(
        "捕获时间",
        r ->
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(r.capturedAt()),
        150);
    column("答卷编号", GradingRecords.Summary::answerId, 170);
    column("题号", GradingRecords.Summary::questionId, 100);
    column(
        "分数",
        r -> r.javaTotal() == null ? "—" : r.javaTotal().stripTrailingZeros().toPlainString(),
        90);
    column("状态", r -> stateLabel(r.state().name()), 150);
    column("提交 / 页面进度", r -> submissionLabel(r.submissionStatus()), 145);
    column("评分请求", r -> Integer.toString(r.modelCalls()), 85);
    table.setPlaceholder(new Label("本机暂无符合条件记录，可重置筛选查看全部历史"));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((observable, before, selected) -> loadDetail(selected));
    table.setId("records-table");
    detail.setId("record-detail-content");
    detail.setPadding(new Insets(4, 12, 12, 12));
    detailScroll.setFitToWidth(true);
    detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    detailMessage("选择一条记录，查看学生回答、分项得分和在线评分结果。");
    SplitPane content = new SplitPane(table, detailScroll);
    content.setDividerPositions(0.48);
    setCenter(content);
    previous.setOnAction(event -> load(current.atPage(current.page() - 1)));
    next.setOnAction(event -> load(current.atPage(current.page() + 1)));
    export.setOnAction(event -> export());
    HBox footer = new HBox(10, previous, pagination, next);
    if (diagnosticExports) footer.getChildren().add(export);
    footer.setPadding(new Insets(12, 0, 0, 0));
    setBottom(footer);
    previous.setDisable(true);
    next.setDisable(true);
    export.setDisable(true);
    DisabledActionHints.reason(previous, () -> navigationDisabledReason(true));
    DisabledActionHints.reason(next, () -> navigationDisabledReason(false));
    DisabledActionHints.reason(export, this::exportDisabledReason);
  }

  private void column(
      String label,
      java.util.function.Function<GradingRecords.Summary, String> text,
      double width) {
    TableColumn<GradingRecords.Summary, String> column = new TableColumn<>(label);
    column.setCellValueFactory(value -> new ReadOnlyStringWrapper(text.apply(value.getValue())));
    column.setPrefWidth(width);
    column.setSortable(false);
    table.getColumns().add(column);
  }

  static String stateLabel(String value) {
    if (value == null) return "";
    return switch (value) {
      case "IDLE" -> "待开始";
      case "PRECHECK" -> "运行检查";
      case "WAIT_READY" -> "等待答卷";
      case "CAPTURING" -> "读取答题图";
      case "GRADING" -> "等待评分";
      case "VALIDATING" -> "校验评分";
      case "BEFORE_INPUT" -> "准备填分";
      case "FILLING" -> "正在填分";
      case "VERIFYING_SCORE" -> "核对分数";
      case "SUBMIT_PREPARED" -> "提交已准备";
      case "SUBMIT_SENT" -> "准备发送提交";
      case "WAIT_ACK" -> "等待提交或页面变化";
      case "NAVIGATING" -> "切换下一份";
      case "WAIT_NEXT" -> "等待下一份";
      case "PAUSED" -> "已暂停";
      case "SUBMISSION_UNKNOWN" -> "提交结果未知";
      case "STOPPED" -> "已停止";
      case "COMPLETED" -> "已完成";
      case "FAILED" -> "失败";
      default -> value;
    };
  }

  static String submissionLabel(String value) {
    if (value == null) return "状态未识别";
    return switch (value) {
      case "CONFIRMED" -> "平台已确认";
      case "VISUALLY_ADVANCED" -> "已观察页面推进";
      case "SUBMIT_CLICKED" -> "已点击提交";
      case "UNKNOWN" -> "结果未知";
      case "NOT_PREPARED" -> "未准备提交";
      default -> "状态未识别";
    };
  }

  /** Called after the persistent journal is ready, including a cold application restart. */
  public void initializeHistory() {
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(this::initializeHistory);
      return;
    }
    answer.clear();
    question.clear();
    run.clear();
    from.setValue(null);
    until.setValue(null);
    state.getSelectionModel().selectFirst();
    refresh();
  }

  /** Call on tab activation. Query work is delegated to the workspace background worker. */
  public void refresh() {
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(this::refresh);
      return;
    }
    try {
      ZoneId zone = ZoneId.systemDefault();
      load(
          new GradingRecords.Query(
              run.getText().isBlank() ? null : UUID.fromString(run.getText().strip()),
              answer.getText(),
              question.getText(),
              state.getSelectionModel().getSelectedIndex() <= 0
                  ? null
                  : SessionState.valueOf(state.getValue()),
              from.getValue() == null ? null : from.getValue().atStartOfDay(zone).toInstant(),
              until.getValue() == null
                  ? null
                  : until.getValue().plusDays(1).atStartOfDay(zone).toInstant(),
              0,
              25));
    } catch (RuntimeException invalid) {
      message.setText("请检查批次编号与日期范围。批次编号可从记录详情复制。");
    }
  }

  /**
   * Must be called immediately on logout/account/service switch; late results cannot restore data.
   */
  public void clear() {
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(this::clear);
      return;
    }
    generation++;
    detailGeneration++;
    historyLoaded = false;
    queryPending = false;
    readFailed = false;
    exportPending = false;
    table.getItems().clear();
    detailMessage("选择本机记录查看评分详情。");
    pagination.setText("");
    message.setText("本机评分与提交记录，点击查询读取");
    previous.setDisable(true);
    next.setDisable(true);
    export.setDisable(true);
  }

  private void load(GradingRecords.Query query) {
    long ticket = ++generation;
    detailGeneration++;
    queryPending = true;
    readFailed = false;
    exportPending = false;
    detailMessage("选择一条记录查看详情。");
    previous.setDisable(true);
    next.setDisable(true);
    export.setDisable(true);
    message.setText("正在查询记录…");
    try {
      source
          .query(query)
          .whenComplete(
              (page, error) ->
                  Platform.runLater(
                      () -> {
                        if (ticket != generation) return;
                        queryPending = false;
                        if (error != null || page == null) {
                          readFailed = true;
                          historyLoaded = false;
                          logReadFailure("QUERY", error);
                          table.getItems().clear();
                          message.setText("本机记录读取失败，请检查磁盘空间和目录权限后重试。");
                          return;
                        }
                        current = query;
                        historyLoaded = true;
                        table.getItems().setAll(page.rows());
                        pagination.setText(
                            "第 " + (page.page() + 1) + " 页 · 共 " + page.total() + " 条");
                        previous.setDisable(page.page() == 0);
                        next.setDisable(!page.hasNext());
                        export.setDisable(page.total() == 0);
                        message.setText(
                            page.total() == 0
                                ? "本机暂无符合条件记录。可重置筛选查看全部历史；读取失败会单独提示，不会显示为零条。"
                                : "已读取本机历史。已点击提交、页面已推进均不代表平台确认保存。总分为空表示未获得可用评分，不能当作 0 分。");
                      }));
    } catch (RuntimeException error) {
      queryPending = false;
      readFailed = true;
      historyLoaded = false;
      logReadFailure("QUERY", error);
      message.setText("本机记录读取失败，请检查磁盘空间和目录权限后重试。");
    }
  }

  private void loadDetail(GradingRecords.Summary row) {
    long ticket = ++detailGeneration, accountTicket = generation;
    detailMessage(row == null ? "选择一条记录查看详情。" : "正在读取批改详情…");
    if (row == null) return;
    try {
      source
          .detail(row.attemptId())
          .thenApply(RecordDetailPresenter::present)
          .whenComplete(
              (document, error) ->
                  Platform.runLater(
                      () -> {
                        if (ticket != detailGeneration || accountTicket != generation) return;
                        if (error == null && document != null) showDetail(document);
                        else {
                          logReadFailure("DETAIL", error);
                          detailMessage("本机记录详情读取失败，请刷新并检查数据文件；分享导出文件前请注意学生隐私。");
                        }
                      }));
    } catch (RuntimeException error) {
      logReadFailure("DETAIL", error);
      detailMessage("记录详情读取失败。");
    }
  }

  private void detailMessage(String text) {
    Label placeholder = new Label(text);
    placeholder.setWrapText(true);
    detail.getChildren().setAll(placeholder);
    detailScroll.setVvalue(0);
  }

  private void showDetail(RecordDetailPresenter.Document document) {
    long accountTicket = generation, selectionTicket = detailGeneration;
    detail.getChildren().clear();
    for (var section : document.sections()) detail.getChildren().add(section(section));
    if (!diagnosticExports) {
      detailScroll.setVvalue(0);
      return;
    }
    Button copy = new Button("复制原始 JSON");
    copy.setId("records-copy-json");
    copy.setOnAction(
        event -> {
          if (accountTicket != generation || selectionTicket != detailGeneration) return;
          try {
            var clipboard = new ClipboardContent();
            clipboard.putString(document.rawJson());
            if (Clipboard.getSystemClipboard().setContent(clipboard))
              message.setText("原始 JSON 已复制。");
            else message.setText("复制未完成，可使用导出记录。");
          } catch (RuntimeException unavailable) {
            message.setText("剪贴板暂不可用，可使用导出记录。");
          }
        });
    VBox rawContent = new VBox(8, copy);
    TitledPane raw = new TitledPane("原始 JSON（核对与复制）", rawContent);
    raw.setId("records-raw-json");
    raw.setExpanded(false);
    raw.setAnimated(false);
    raw.expandedProperty()
        .addListener(
            (observable, before, expanded) -> {
              if (expanded && rawContent.getChildren().size() == 1) {
                TextArea text = new TextArea(document.rawJson());
                text.setEditable(false);
                text.setWrapText(false);
                text.setPrefRowCount(16);
                text.setStyle("-fx-font-family: Consolas, 'Microsoft YaHei'; -fx-font-size: 12px;");
                rawContent.getChildren().add(text);
              }
            });
    detail.getChildren().add(raw);
    detailScroll.setVvalue(0);
  }

  private static TitledPane section(RecordDetailPresenter.Section section) {
    VBox body = new VBox(10);
    body.setPadding(new Insets(10));
    for (var field : section.fields()) {
      Label label = new Label(field.label());
      label.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
      TextArea value = new TextArea(field.text());
      value.setEditable(false);
      value.setWrapText(true);
      value.setPrefRowCount(
          Math.max(
              1,
              Math.min(
                  7,
                  Math.max(
                      field.text().split("\\R", -1).length, (field.text().length() + 47) / 48))));
      value.setStyle("-fx-control-inner-background: #f8fafc; -fx-font-size: 13px;");
      body.getChildren().add(new VBox(4, label, value));
    }
    for (var child : section.children()) body.getChildren().add(section(child));
    TitledPane pane = new TitledPane(section.title(), body);
    pane.setExpanded(section.expanded());
    pane.setAnimated(false);
    return pane;
  }

  private void export() {
    if (!diagnosticExports) return;
    FileChooser chooser = new FileChooser();
    chooser.setTitle("导出当前筛选范围的记录");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 记录", "*.json"));
    chooser.setInitialFileName("deskgrade-records.json");
    var selected = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    if (selected == null) return;
    long ticket = generation;
    exportPending = true;
    export.setDisable(true);
    message.setText("正在导出当前筛选范围的全部记录…");
    try {
      source
          .exportJson(selected.toPath(), current)
          .whenComplete(
              (ignored, error) ->
                  Platform.runLater(
                      () -> {
                        if (ticket != generation) return;
                        exportPending = false;
                        export.setDisable(false);
                        if (error != null) logReadFailure("EXPORT", error);
                        message.setText(
                            error == null
                                ? "JSON 记录已导出；未包含答卷图片，请注意学生隐私。"
                                : "导出未完成，请确认本机记录和目标文件是否可写。");
                      }));
    } catch (RuntimeException error) {
      exportPending = false;
      logReadFailure("EXPORT", error);
      export.setDisable(false);
      message.setText("导出未完成。");
    }
  }

  private static void logReadFailure(String operation, Throwable failure) {
    LOG.atWarn()
        .addKeyValue("operation", operation)
        .addKeyValue("safe_code", failureCode(failure))
        .addKeyValue("exception_type", failureType(failure))
        .log("Local record operation failed");
  }

  private String navigationDisabledReason(boolean previousPage) {
    if (queryPending) return "正在读取本机记录，请稍候。";
    if (readFailed) return "本机记录读取失败，请先点击“查询”重试。";
    if (!historyLoaded) return "记录尚未读取，请等待启动完成或点击“查询”。";
    return previousPage ? "当前已经是第一页，没有上一页记录。" : "当前已经是最后一页，没有下一页记录。";
  }

  private String exportDisabledReason() {
    if (exportPending) return "正在导出本机记录，请等待完成。";
    if (queryPending) return "正在读取本机记录，完成后才能导出。";
    if (readFailed) return "本机记录读取失败，请先点击“查询”重试。";
    if (!historyLoaded) return "记录尚未读取，请等待启动完成或点击“查询”。";
    return "当前筛选范围没有记录，请重置筛选后再导出。";
  }

  static String failureCode(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof DeskGradeException known) return known.code().name();
      Throwable next = current.getCause();
      if (next == current) break;
      current = next;
    }
    return failure == null ? "MISSING_RECORD_RESULT" : ErrorCode.STORAGE_FAILURE.name();
  }

  static String failureType(Throwable failure) {
    if (failure == null) return "NONE";
    String name = failure.getClass().getName();
    return name.matches("[A-Za-z0-9_.$]{1,160}") ? name : "UNKNOWN";
  }
}
