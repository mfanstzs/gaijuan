package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.application.session.SessionJournal;
import app.deskgrade.core.domain.GradingResponse;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Complete durable ledger contract shared by server storage and its authenticated desktop proxy.
 */
public interface PersistentSessionJournal extends SessionJournal, GradingRecords {
  record UnresolvedSubmission(
      RunRecord run, UUID attemptId, String answerId, String questionId, BigDecimal score) {}

  /** At most 100 outstanding attempts, oldest first, within the authenticated account. */
  default List<UnresolvedSubmission> unresolvedSubmissions(Owner owner, String targetIdentity) {
    throw new UnsupportedOperationException("Unresolved submission review is unavailable");
  }

  /** Explicit human disposition only; never records a successful platform submission. */
  default void abandonUnresolvedRun(
      Owner owner, UUID runId, String targetIdentity, UUID decisionId, Instant now) {
    throw new UnsupportedOperationException("Manual batch disposition is unavailable");
  }

  void completeModelCallMetrics(
      UUID callId,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      Duration elapsed,
      String safeCode,
      Instant now);

  String attemptAuditJson(UUID attemptId);

  default void exportJson(Path destination, Owner owner, Query query) {
    RecordsJsonExporter.write(destination, this, owner, query);
  }
}
