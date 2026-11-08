package app.deskgrade.adapters.model;

import java.time.Duration;

/** Durable, text-only observation of an actual supplier exchange; never receives credentials. */
public interface ModelCallTrace {
  ModelCallTrace NONE = new ModelCallTrace() {};

  /** Called before dispatch authorization. Failure prevents the request from being sent. */
  default void requestPrepared(String requestJson) {}

  /**
   * Called before interpreting/validating model content, including invalid or truncated content.
   */
  default void outputReceived(String modelOutput) {}

  /** Before a scheduled connect-only retry, revalidate the existing paid logical request. */
  default void authorizeConnectRetry(int attemptNumber) {}

  /** Bounded audit metadata only; attemptNumber is 2 or 3 and delay is 20 seconds. */
  default void connectRetryScheduled(int attemptNumber, Duration delay) {}
}
