package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.SessionState;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Own JavaFX controls only; no native input, real model, screenshots or user files. */
@EnabledOnOs(OS.WINDOWS)
class RecordsViewHistoryWindowsIT {
  @BeforeAll
  static void startToolkit() throws Exception {
    var started = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          started.countDown();
        });
    assertTrue(started.await(15, TimeUnit.SECONDS));
  }

  @AfterAll
  static void stopToolkit() {
    Platform.exit();
  }

  @Test
  @Timeout(30)
  void readySignalRequeriesWholeHistoryAfterAnEarlyReadWasUnavailable() throws Exception {
    var source = new Source();
    source.first = CompletableFuture.failedFuture(new IllegalStateException("not initialized"));
    var view = fx(() -> new RecordsView(source, true));
    fx(
        () -> {
          view.refresh();
          return null;
        });
    fx(() -> null);
    assertEquals(0, fx(() -> table(view).getItems().size()));
    fx(
        () -> {
          view.initializeHistory();
          return null;
        });
    fx(() -> null);
    assertEquals(2, source.queries.size());
    assertEquals(1, fx(() -> table(view).getItems().size()));
    var query = source.queries.getLast();
    assertNull(query.runId());
    assertNull(query.fromInclusive());
    assertNull(query.toExclusive());
    assertNull(query.state());
    assertEquals(0, query.page());
  }

  @Test
  @Timeout(30)
  void lateEarlyFailureCannotEraseHistoryLoadedAfterReady() throws Exception {
    var source = new Source();
    source.first = new CompletableFuture<>();
    var view = fx(() -> new RecordsView(source, true));
    fx(
        () -> {
          view.refresh();
          view.initializeHistory();
          return null;
        });
    fx(() -> null);
    assertEquals(1, fx(() -> table(view).getItems().size()));
    source.first.completeExceptionally(new IllegalStateException("late unavailable"));
    fx(() -> null);
    assertEquals(1, fx(() -> table(view).getItems().size()));
  }

  @Test
  @Timeout(30)
  void initialHistoryAndResetDoNotLeaveSessionDateOrStateFiltersApplied() throws Exception {
    var source = new Source();
    source.first = CompletableFuture.completedFuture(Source.PAGE);
    var view = fx(() -> new RecordsView(source, true));
    fx(
        () -> {
          var top = (VBox) view.getTop();
          var filters =
              top.getChildren().stream()
                  .filter(FlowPane.class::isInstance)
                  .map(FlowPane.class::cast)
                  .findFirst()
                  .orElseThrow();
          for (var node : filters.getChildren()) {
            if (node instanceof TextField input) input.setText("synthetic-filter");
            if (node instanceof DatePicker date) date.setValue(LocalDate.of(2026, 10, 21));
            if (node instanceof ComboBox<?> state) state.getSelectionModel().selectLast();
          }
          view.initializeHistory();
          return null;
        });
    fx(() -> null);
    var query = source.queries.getFirst();
    assertEquals("", query.answerId());
    assertEquals("", query.questionId());
    assertNull(query.runId());
    assertNull(query.fromInclusive());
    assertNull(query.toExclusive());
    assertNull(query.state());
    assertEquals(1, fx(() -> table(view).getItems().size()));
  }

  @SuppressWarnings("unchecked")
  private static TableView<GradingRecords.Summary> table(RecordsView view) {
    return (TableView<GradingRecords.Summary>) ((SplitPane) view.getCenter()).getItems().getFirst();
  }

  private static <T> T fx(Callable<T> operation) throws Exception {
    var task = new FutureTask<T>(operation);
    Platform.runLater(task);
    return task.get(15, TimeUnit.SECONDS);
  }

  private static final class Source implements RecordsView.DataSource {
    static final GradingRecords.Page PAGE =
        new GradingRecords.Page(
            List.of(
                new GradingRecords.Summary(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "synthetic-answer",
                    "synthetic-question",
                    SessionState.COMPLETED,
                    BigDecimal.ONE,
                    "VISUALLY_ADVANCED",
                    Instant.parse("2025-01-01T00:00:00Z"),
                    1)),
            1,
            0,
            25);
    final AtomicInteger calls = new AtomicInteger();
    final List<GradingRecords.Query> queries = new CopyOnWriteArrayList<>();
    CompletableFuture<GradingRecords.Page> first;

    @Override
    public CompletionStage<GradingRecords.Page> query(GradingRecords.Query query) {
      queries.add(query);
      return calls.getAndIncrement() == 0 ? first : CompletableFuture.completedFuture(PAGE);
    }

    @Override
    public CompletionStage<String> detail(UUID attemptId) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<Void> exportJson(Path destination, GradingRecords.Query query) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
  }
}
