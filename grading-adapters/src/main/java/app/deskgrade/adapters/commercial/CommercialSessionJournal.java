package app.deskgrade.adapters.commercial;

import app.deskgrade.adapters.persistence.JournalJson;
import app.deskgrade.adapters.persistence.PersistentSessionJournal;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Synchronous worker-thread proxy. Each write must be committed by MySQL before returning. */
public final class CommercialSessionJournal implements PersistentSessionJournal {
  private final CommercialClient client;
  private final long generation;
  private final Owner owner;
  private volatile boolean closed;

  /** Create after successful login/restore; discard on logout or account change. */
  public CommercialSessionJournal(CommercialClient client) {
    this.client = Objects.requireNonNull(client);
    generation = client.sessionGeneration();
    owner = client.journalOwner(generation);
  }

  private JsonNode call(String operation, Object... pairs) {
    if (closed) throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    var command =
        JournalJson.MAPPER.createObjectNode().put("schemaVersion", 1).put("operation", operation);
    var args = command.putObject("arguments");
    for (int i = 0; i < pairs.length; i += 2)
      args.set((String) pairs[i], JournalJson.MAPPER.valueToTree(pairs[i + 1]));
    try {
      return client.journalCommand(command, generation).toCompletableFuture().join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof GatewayException gateway) {
        if (gateway.code() == GatewayException.Code.DUPLICATE_SUBMISSION)
          throw new DeskGradeException(ErrorCode.DUPLICATE_SUBMISSION);
        throw gateway;
      }
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    }
  }

  private <T> T decode(JsonNode result, Class<T> type) {
    try {
      return JournalJson.MAPPER.treeToValue(result, type);
    } catch (Exception failure) {
      throw new GatewayException(GatewayException.Code.INVALID_RESPONSE);
    }
  }

  private <T> List<T> list(JsonNode result, Class<T> type) {
    if (!result.isArray() || result.size() > 10000)
      throw new GatewayException(GatewayException.Code.INVALID_RESPONSE);
    var rows = new java.util.ArrayList<T>();
    result.forEach(row -> rows.add(decode(row, type)));
    return List.copyOf(rows);
  }

  private void owner(Owner requested) {
    if (!owner.equals(requested)) throw new GatewayException(GatewayException.Code.FORBIDDEN);
  }

  @Override
  public void assertWritable() {
    call("assertWritable");
  }

  @Override
  public boolean hasUnknownSubmission(String targetIdentity) {
    return decode(call("hasUnknownSubmission", "targetIdentity", targetIdentity), Boolean.class);
  }

  @Override
  public List<UnresolvedSubmission> unresolvedSubmissions(Owner requested, String targetIdentity) {
    owner(requested);
    var result = call("unresolvedSubmissions", "targetIdentity", targetIdentity);
    if (result.size() > 100) throw new GatewayException(GatewayException.Code.INVALID_RESPONSE);
    return list(result, UnresolvedSubmission.class);
  }

  @Override
  public void abandonUnresolvedRun(
      Owner requested, UUID runId, String targetIdentity, UUID decisionId, Instant now) {
    owner(requested);
    Objects.requireNonNull(now);
    call(
        "abandonUnresolvedRun",
        "runId",
        runId,
        "targetIdentity",
        targetIdentity,
        "decisionId",
        decisionId);
  }

  @Override
  public void beginRun(UUID runId, String targetIdentity, SessionSettings settings, Instant now) {
    call(
        "beginRun",
        "runId",
        runId,
        "targetIdentity",
        targetIdentity,
        "settings",
        settings,
        "now",
        now);
  }

  @Override
  public void recordConfiguration(UUID runId, SessionSettings settings, Instant now) {
    call("recordConfiguration", "runId", runId, "settings", settings, "now", now);
  }

  @Override
  public void beginAttempt(
      UUID runId,
      UUID attemptId,
      int ordinal,
      SessionPlatform.View view,
      long epoch,
      String evidencePath,
      SessionPlatform.Capture capture,
      SessionSettings settings) {
    call(
        "beginAttempt",
        "runId",
        runId,
        "attemptId",
        attemptId,
        "ordinal",
        ordinal,
        "view",
        view,
        "epoch",
        epoch,
        "evidencePath",
        evidencePath,
        "sha256",
        capture.sha256(),
        "capturedAt",
        capture.capturedAt(),
        "settings",
        settings);
  }

  @Override
  public void transition(
      UUID runId, UUID attemptId, SessionState state, PauseReason reason, Instant now) {
    call(
        "transition",
        "runId",
        runId,
        "attemptId",
        attemptId,
        "state",
        state,
        "reason",
        reason,
        "now",
        now);
  }

  @Override
  public void beginModelCall(
      UUID callId, UUID runId, UUID attemptId, String profileId, Instant now) {
    call(
        "beginModelCall",
        "callId",
        callId,
        "runId",
        runId,
        "attemptId",
        attemptId,
        "profileId",
        profileId,
        "now",
        now);
  }

  @Override
  public void completeModelCall(
      UUID callId, GradingResponse response, String safeCode, Instant now) {
    call(
        "completeModelCall",
        "callId",
        callId,
        "response",
        response,
        "safeCode",
        safeCode,
        "now",
        now);
  }

  @Override
  public void completeModelCallMetrics(
      UUID callId,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      Duration elapsed,
      String safeCode,
      Instant now) {
    call(
        "completeModelCallMetrics",
        "callId",
        callId,
        "source",
        source,
        "usage",
        usage,
        "elapsed",
        elapsed,
        "safeCode",
        safeCode,
        "now",
        now);
  }

  @Override
  public void saveGrade(UUID attemptId, GradingResponse response, BigDecimal total) {
    call("saveGrade", "attemptId", attemptId, "response", response, "total", total);
  }

  @Override
  public void recordScoreReadback(UUID attemptId, BigDecimal observedScore, Instant now) {
    call("recordScoreReadback", "attemptId", attemptId, "observedScore", observedScore, "now", now);
  }

  @Override
  public void confirmSubmission(UUID runId, UUID attemptId, Instant now) {
    call("confirmSubmission", "runId", runId, "attemptId", attemptId, "now", now);
  }

  @Override
  public void confirmSubmission(
      UUID runId, UUID attemptId, Instant now, SessionPlatform.ConfirmationKind confirmationKind) {
    call(
        "confirmSubmission",
        "runId",
        runId,
        "attemptId",
        attemptId,
        "now",
        now,
        "confirmationKind",
        Objects.requireNonNull(confirmationKind));
  }

  @Override
  public List<RunRecord> recoverInterrupted(Instant now) {
    return list(call("recoverInterrupted", "now", now), RunRecord.class);
  }

  @Override
  public List<RunRecord> runs() {
    return list(call("runs"), RunRecord.class);
  }

  @Override
  public List<AttemptRecord> attempts(UUID runId) {
    return list(call("attempts", "runId", runId), AttemptRecord.class);
  }

  @Override
  public Page queryRecords(Owner owner, Query query) {
    owner(owner);
    return decode(call("queryRecords", "query", query), Page.class);
  }

  @Override
  public String recordJson(Owner owner, UUID attemptId) {
    owner(owner);
    return decode(call("recordJson", "attemptId", attemptId), String.class);
  }

  @Override
  public String attemptAuditJson(UUID attemptId) {
    return recordJson(owner, attemptId);
  }

  @Override
  public void close() {
    closed = true;
  }
}
