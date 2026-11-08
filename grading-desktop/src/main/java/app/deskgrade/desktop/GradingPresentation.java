package app.deskgrade.desktop;

import app.deskgrade.core.application.session.SessionController;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingResponse;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Display data after Java validation and persistence, bound to the originating UI authority. */
record GradingPresentation(
    UUID runId,
    GradingResponse response,
    BigDecimal validatedTotal,
    long actionTicket,
    long accountTicket) {
  GradingPresentation {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(response);
    if (response.result().status() == GradeResult.Status.SCORED && validatedTotal == null)
      throw new IllegalArgumentException("A scored presentation requires the Java validated total");
    if (response.result().status() == GradeResult.Status.UNABLE && validatedTotal != null)
      throw new IllegalArgumentException("An unable result must not contain a numeric total");
  }

  static Optional<GradingPresentation> automatic(
      SessionController.Status event,
      SessionController.Status current,
      GradingResponse response,
      long actionTicket,
      long accountTicket) {
    // BEFORE_INPUT is reached only after validator and journal.saveGrade both succeeded.
    if (event.state() != SessionState.BEFORE_INPUT
        || event.lastScore() == null
        || !event.equals(current)
        || response == null
        || response.result().status() != GradeResult.Status.SCORED) return Optional.empty();
    return Optional.of(
        new GradingPresentation(
            event.runId(), response, event.lastScore(), actionTicket, accountTicket));
  }

  boolean belongsTo(long action, long account) {
    return actionTicket == action && accountTicket == account;
  }
}
