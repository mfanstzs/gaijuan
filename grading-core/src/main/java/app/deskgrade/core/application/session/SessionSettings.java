package app.deskgrade.core.application.session;

import app.deskgrade.core.domain.QuestionDefinition;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Configuration snapshot contains no credentials. Zero count limits mean unlimited. */
public record SessionSettings(
    QuestionDefinition question,
    String modelProfileId,
    long modelRevision,
    long platformRevision,
    String configurationJson,
    int maxSubmissions,
    int maxCalls,
    int maxRetries,
    BigDecimal maxCost,
    Duration pollInterval,
    Duration pageTimeout) {
  public static final int MAX_CONFIGURATION_BYTES = 1024 * 1024;

  public SessionSettings {
    Objects.requireNonNull(question);
    Objects.requireNonNull(modelProfileId);
    Objects.requireNonNull(configurationJson);
    Objects.requireNonNull(pollInterval);
    Objects.requireNonNull(pageTimeout);
    if (!modelProfileId.matches("[A-Za-z0-9_-]{1,100}")
        || !utf8LengthAtMost(configurationJson, MAX_CONFIGURATION_BYTES)
        || modelRevision < 1
        || platformRevision < 1
        || maxSubmissions < 0
        || maxCalls < 0
        || maxRetries < 0
        || maxRetries > 1
        || pollInterval.compareTo(Duration.ofMillis(1)) < 0
        || pageTimeout.isNegative()
        || pageTimeout.isZero()
        || (maxCost != null && maxCost.signum() <= 0)) {
      throw new IllegalArgumentException("Invalid session limits or revisions");
    }
  }

  private static boolean utf8LengthAtMost(String value, int maximum) {
    long bytes = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      if (bytes > maximum) return false;
      offset += Character.charCount(codePoint);
    }
    return true;
  }

  public boolean submissionLimitReached(int completed) {
    return maxSubmissions > 0 && completed >= maxSubmissions;
  }

  public boolean callLimitReached(int dispatched) {
    return maxCalls > 0 && dispatched >= maxCalls;
  }
}
