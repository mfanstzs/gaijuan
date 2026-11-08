package app.deskgrade.adapters.persistence;

import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local H2 remains the authoritative transactional ledger. This decorator additionally writes a
 * metadata-only daily JSONL timeline. A failed append is never silently ignored: it prevents the
 * caller from proceeding to the next platform side effect. Neither log API accepts images, request
 * bodies, student text, endpoint addresses, or credentials.
 */
public final class LocalLoggingSessionJournal implements PersistentSessionJournal {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Logger LOG = LoggerFactory.getLogger(LocalLoggingSessionJournal.class);
  private final PersistentSessionJournal delegate;
  private final LocalGradingLog log;
  private final Path performanceDirectory;
  private LocalPerformanceLog performanceLog;
  private boolean performanceWarningEmitted;
  private boolean closed;
  private final Map<String, String> models = new HashMap<>();
  private final Map<UUID, CallContext> inFlight = new HashMap<>();

  private record CallContext(UUID runId, UUID attemptId, Instant startedAt) {}

  public enum UtilityPurpose {
    RUBRIC_GENERATION,
    CAPABILITY_CHECK
  }

  public LocalLoggingSessionJournal(PersistentSessionJournal delegate, Path logDirectory) {
    this.delegate = Objects.requireNonNull(delegate);
    performanceDirectory = logDirectory.toAbsolutePath().normalize().resolveSibling("performance");
    try {
      log = new LocalGradingLog(logDirectory);
    } catch (RuntimeException failure) {
      try {
        delegate.close();
      } catch (RuntimeException ignored) {
        // Preserve the safe storage failure; never leak a cleanup exception or leave H2 locked.
      }
      throw failure;
    }
  }

  @Override
  public boolean performanceEnabled() {
    return true;
  }

  /** Optional observability must never change a stored grade or repeat a submitted action. */
  @Override
  public synchronized void recordPerformance(AttemptPerformance summary) {
    if (closed) return;
    try {
      if (performanceLog == null) performanceLog = new LocalPerformanceLog(performanceDirectory);
      performanceLog.append(summary);
      performanceWarningEmitted = false;
    } catch (RuntimeException | LinkageError failure) {
      if (performanceLog != null) {
        try {
          performanceLog.close();
        } catch (RuntimeException | LinkageError ignored) {
        }
        performanceLog = null;
      }
      if (!performanceWarningEmitted) {
        performanceWarningEmitted = true;
        try {
          LOG.atWarn()
              .addKeyValue("event", "local_performance_log_unavailable")
              .addKeyValue("safe_code", "PERFORMANCE_LOG_WRITE_FAILED")
              .log("Local performance summary could not be stored; grading ledger unchanged");
        } catch (RuntimeException | LinkageError ignored) {
          // Neither diagnostic storage nor its warning backend may affect grading authority.
        }
      }
    }
  }

  /** Stores only the public profile/model identifiers, never endpoint or credential reference. */
  public synchronized void registerProfile(ProviderProfile profile) {
    Objects.requireNonNull(profile);
    models.put(profile.id(), profile.modelId());
  }

  /** Must complete durably before dispatching a non-grading model request. */
  public synchronized void beginUtilityModelCall(
      UUID callId, UtilityPurpose purpose, ProviderProfile profile, Instant now) {
    registerProfile(profile);
    var event = event("UTILITY_MODEL_STARTED", null, null, Objects.requireNonNull(callId), now);
    event.put("purpose", Objects.requireNonNull(purpose).name());
    putProfile(event, profile.id());
    log.append(event);
  }

  public synchronized void recordUtilityModelCall(
      UUID callId,
      UtilityPurpose purpose,
      ProviderProfile profile,
      GradingResponse.Usage usage,
      Duration elapsed,
      String safeError,
      Instant now) {
    registerProfile(profile);
    var event = event("UTILITY_MODEL_COMPLETED", null, null, Objects.requireNonNull(callId), now);
    event.put("purpose", Objects.requireNonNull(purpose).name());
    putProfile(event, profile.id());
    putMetrics(event, GradingResponse.Source.LIVE, usage, elapsed, safeError);
    log.append(event);
  }

  @Override
  public synchronized void assertWritable() {
    delegate.assertWritable();
    log.assertWritable();
  }

  @Override
  public boolean hasUnknownSubmission(String targetIdentity) {
    return delegate.hasUnknownSubmission(targetIdentity);
  }

  @Override
  public synchronized void beginRun(
      UUID runId, String targetIdentity, SessionSettings settings, Instant now) {
    delegate.beginRun(runId, targetIdentity, settings, now);
    var event = event("RUN_STARTED", runId, null, null, now);
    putProfile(event, settings.modelProfileId());
    event.put("state", SessionState.IDLE.name());
    log.append(event);
  }

  @Override
  public synchronized void recordConfiguration(UUID runId, SessionSettings settings, Instant now) {
    delegate.recordConfiguration(runId, settings, now);
    var event = event("CONFIGURATION_SELECTED", runId, null, null, now);
    putProfile(event, settings.modelProfileId());
    log.append(event);
  }

  @Override
  public synchronized void beginAttempt(
      UUID runId,
      UUID attemptId,
      int ordinal,
      SessionPlatform.View view,
      long epoch,
      String evidencePath,
      SessionPlatform.Capture capture,
      SessionSettings settings) {
    delegate.beginAttempt(runId, attemptId, ordinal, view, epoch, evidencePath, capture, settings);
    var event = event("ANSWER_CAPTURED", runId, attemptId, null, capture.capturedAt());
    event.put("ordinal", ordinal);
    event.put("state", SessionState.CAPTURING.name());
    log.append(event);
  }

  @Override
  public synchronized void transition(
      UUID runId, UUID attemptId, SessionState state, PauseReason reason, Instant now) {
    delegate.transition(runId, attemptId, state, reason, now);
    if (runId == null) return;
    var event = event("SESSION_STATE", runId, attemptId, null, now);
    event.put("state", state.name());
    event.put("reason", reason.name());
    log.append(event);
  }

  @Override
  public synchronized void beginModelCall(
      UUID callId, UUID runId, UUID attemptId, String profileId, Instant now) {
    delegate.beginModelCall(callId, runId, attemptId, profileId, now);
    inFlight.put(callId, new CallContext(runId, attemptId, now));
    var event = event("MODEL_CALL_STARTED", runId, attemptId, callId, now);
    putProfile(event, profileId);
    log.append(event);
  }

  @Override
  public synchronized void completeModelCall(
      UUID callId, GradingResponse response, String safeErrorCode, Instant now) {
    delegate.completeModelCall(callId, response, safeErrorCode, now);
    var context = inFlight.get(callId);
    var event = completedCallEvent(callId, now, context);
    putMetrics(
        event,
        response == null ? null : response.source(),
        response == null ? null : response.usage(),
        response == null ? elapsedUntil(context, now) : response.elapsed(),
        safeErrorCode);
    if (response != null) {
      event.put("gradingStatus", response.result().status().name());
      event.put("readability", response.result().readability().name());
    }
    log.append(event);
    inFlight.remove(callId);
  }

  @Override
  public synchronized void completeModelCallMetrics(
      UUID callId,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      Duration elapsed,
      String safeCode,
      Instant now) {
    delegate.completeModelCallMetrics(callId, source, usage, elapsed, safeCode, now);
    var event = completedCallEvent(callId, now, inFlight.get(callId));
    putMetrics(event, source, usage, elapsed, safeCode);
    log.append(event);
    inFlight.remove(callId);
  }

  @Override
  public synchronized void saveGrade(UUID attemptId, GradingResponse response, BigDecimal total) {
    delegate.saveGrade(attemptId, response, total);
    var event =
        event("GRADE_VALIDATED", null, attemptId, response.requestId(), response.completedAt());
    event.put("gradingStatus", response.result().status().name());
    event.put("javaValidatedTotal", total);
    log.append(event);
  }

  @Override
  public synchronized void recordScoreReadback(
      UUID attemptId, BigDecimal observedScore, Instant now) {
    delegate.recordScoreReadback(attemptId, observedScore, now);
    var event = event("SCORE_READBACK_VERIFIED", null, attemptId, null, now);
    event.put("score", observedScore);
    log.append(event);
  }

  @Override
  public synchronized void confirmSubmission(UUID runId, UUID attemptId, Instant now) {
    confirmSubmission(runId, attemptId, now, SessionPlatform.ConfirmationKind.PLATFORM_ACK);
  }

  @Override
  public synchronized void confirmSubmission(
      UUID runId, UUID attemptId, Instant now, SessionPlatform.ConfirmationKind kind) {
    delegate.confirmSubmission(runId, attemptId, now, kind);
    var event = event("PLATFORM_PROGRESS_RECORDED", runId, attemptId, null, now);
    event.put("confirmationKind", kind.name());
    log.append(event);
  }

  @Override
  public synchronized List<RunRecord> recoverInterrupted(Instant now) {
    var result = delegate.recoverInterrupted(now);
    var event = event("LOCAL_RECOVERY_CHECKED", null, null, null, now);
    event.put("runCount", result.size());
    log.append(event);
    return result;
  }

  @Override
  public List<RunRecord> runs() {
    return delegate.runs();
  }

  @Override
  public List<AttemptRecord> attempts(UUID runId) {
    return delegate.attempts(runId);
  }

  @Override
  public List<UnresolvedSubmission> unresolvedSubmissions(Owner owner, String targetIdentity) {
    return delegate.unresolvedSubmissions(owner, targetIdentity);
  }

  @Override
  public synchronized void abandonUnresolvedRun(
      Owner owner, UUID runId, String targetIdentity, UUID decisionId, Instant now) {
    delegate.abandonUnresolvedRun(owner, runId, targetIdentity, decisionId, now);
    var event = event("UNKNOWN_BATCH_CLOSED_BY_USER", runId, null, null, now);
    event.put("decisionId", decisionId.toString());
    log.append(event);
  }

  @Override
  public Page queryRecords(Owner owner, Query query) {
    return delegate.queryRecords(owner, query);
  }

  @Override
  public String recordJson(Owner owner, UUID attemptId) {
    return delegate.recordJson(owner, attemptId);
  }

  @Override
  public String attemptAuditJson(UUID attemptId) {
    return delegate.attemptAuditJson(attemptId);
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (performanceLog != null) {
      try {
        performanceLog.close();
      } catch (RuntimeException | LinkageError ignored) {
      }
    }
    try {
      log.close();
    } finally {
      delegate.close();
    }
  }

  private static ObjectNode event(String type, UUID run, UUID attempt, UUID call, Instant at) {
    var node = JSON.createObjectNode();
    node.put("schemaVersion", 1);
    node.put("storage", "LOCAL_ONLY");
    node.put("event", type);
    node.put("at", Objects.requireNonNull(at).toString());
    node.put("runId", run == null ? null : run.toString());
    node.put("attemptId", attempt == null ? null : attempt.toString());
    node.put("callId", call == null ? null : call.toString());
    return node;
  }

  private static ObjectNode completedCallEvent(UUID callId, Instant now, CallContext context) {
    return event(
        "MODEL_CALL_COMPLETED",
        context == null ? null : context.runId(),
        context == null ? null : context.attemptId(),
        callId,
        now);
  }

  private static Duration elapsedUntil(CallContext context, Instant now) {
    if (context == null || now.isBefore(context.startedAt())) return null;
    return Duration.between(context.startedAt(), now);
  }

  private void putProfile(ObjectNode event, String profileId) {
    // profileId is configuration metadata, not an arbitrary error/request string.
    if (profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,100}"))
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    event.put("profileId", profileId);
    event.put("model", models.get(profileId));
  }

  private static void putMetrics(
      ObjectNode event,
      GradingResponse.Source source,
      GradingResponse.Usage usage,
      Duration elapsed,
      String safeError) {
    if (elapsed != null && elapsed.isNegative())
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    event.put("source", source == null ? null : source.name());
    event.put("inputTokens", usage == null ? null : usage.inputTokens());
    event.put("outputTokens", usage == null ? null : usage.outputTokens());
    event.put("estimatedCost", usage == null ? null : usage.estimatedCost());
    try {
      event.put("elapsedMs", elapsed == null ? null : elapsed.toMillis());
    } catch (ArithmeticException invalid) {
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    }
    event.put("safeErrorCode", knownError(safeError));
    event.put("outcome", safeError == null ? "COMPLETED" : "FAILED");
  }

  private static String knownError(String code) {
    if (code == null) return null;
    for (ErrorCode value : ErrorCode.values()) if (value.name().equals(code)) return code;
    for (ProviderException.Diagnostic value : ProviderException.Diagnostic.values())
      if (value.name().equals(code)) return code;
    for (ModelCallException.Kind value : ModelCallException.Kind.values())
      if (value.name().equals(code) || ("MODEL_" + value.name()).equals(code)) return code;
    return "UNRECOGNIZED_FAILURE";
  }
}
