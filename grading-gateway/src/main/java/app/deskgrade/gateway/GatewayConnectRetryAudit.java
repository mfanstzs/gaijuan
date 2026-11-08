package app.deskgrade.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Per-owner bounded ordering and safe server-log audit; never stores request or exception text. */
final class GatewayConnectRetryAudit {
  private final UUID requestId;
  private final Consumer<String> output;
  private int authorizedAttempts;
  private int scheduledRetries;
  private int pendingAttempt;
  private boolean finished;
  private Outcome outcome = Outcome.NOT_FINALIZED;

  enum Outcome {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    NOT_DISPATCHED,
    NOT_FINALIZED
  }

  GatewayConnectRetryAudit(UUID requestId) {
    this(requestId, System.err::println);
  }

  GatewayConnectRetryAudit(UUID requestId, Consumer<String> output) {
    this.requestId = Objects.requireNonNull(requestId);
    this.output = Objects.requireNonNull(output);
  }

  synchronized void initialDispatchAuthorized() {
    require(!finished && authorizedAttempts == 0 && pendingAttempt == 0);
    authorizedAttempts = 1;
  }

  synchronized void scheduled(int attemptNumber, Duration delay) {
    require(
        !finished
            && (attemptNumber == 2 || attemptNumber == 3)
            && authorizedAttempts == attemptNumber - 1
            && pendingAttempt == 0
            && Duration.ofSeconds(20).equals(delay));
    scheduledRetries++;
    pendingAttempt = attemptNumber;
    emit("SCHEDULED", attemptNumber, 20_000L);
  }

  synchronized void authorize(int attemptNumber, Runnable authorization) {
    require(
        !finished
            && (attemptNumber == 2 || attemptNumber == 3)
            && pendingAttempt == attemptNumber
            && authorizedAttempts == attemptNumber - 1);
    authorization.run();
    authorizedAttempts = attemptNumber;
    pendingAttempt = 0;
    emit("AUTHORIZED", attemptNumber, 0);
  }

  synchronized boolean hasScheduledRetry() {
    return scheduledRetries > 0;
  }

  synchronized void completed(String receiptState) {
    outcome = Outcome.valueOf(receiptState);
  }

  synchronized void finish() {
    if (finished) return;
    finished = true;
    emit("FINAL", authorizedAttempts, 0);
  }

  private void emit(String phase, int attempt, long delayMs) {
    var node = GatewayJson.MAPPER.createObjectNode();
    node.put("event", "gateway_connect_retry_audit");
    node.put("requestId", requestId.toString());
    node.put("phase", phase);
    node.put("attempt", attempt);
    node.put("authorizedAttempts", authorizedAttempts);
    node.put("scheduledRetries", scheduledRetries);
    node.put("delayMs", delayMs);
    node.put("outcome", outcome.name());
    output.accept(node.toString());
  }

  private static void require(boolean condition) {
    if (!condition) throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
  }
}
