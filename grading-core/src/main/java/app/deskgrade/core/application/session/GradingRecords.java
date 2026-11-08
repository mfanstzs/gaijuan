package app.deskgrade.core.application.session;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only records always require an explicit owner scope, including detail and export. */
public interface GradingRecords {
  Page queryRecords(Owner owner, Query query);

  String recordJson(Owner owner, UUID attemptId);

  record Owner(String gateway, String accountId) {
    public Owner {
      Objects.requireNonNull(gateway);
      Objects.requireNonNull(accountId);
      if (gateway.length() > 2048 || accountId.isBlank() || accountId.length() > 200)
        throw new IllegalArgumentException("Invalid record owner");
      if (!gateway.isEmpty()) {
        URI address = URI.create(gateway).normalize();
        if (address.getHost() == null
            || address.getUserInfo() != null
            || address.getQuery() != null
            || address.getFragment() != null)
          throw new IllegalArgumentException("Invalid record service");
        gateway = address.toString();
        while (gateway.endsWith("/")) gateway = gateway.substring(0, gateway.length() - 1);
      } else if (!accountId.equals("LOCAL_DEVELOPER") && !accountId.equals("LOCAL_USER")) {
        throw new IllegalArgumentException("Local records require local scope");
      }
    }

    /** Explicit developer access only; commercial accounts must never use this scope. */
    public static Owner localDeveloper() {
      return new Owner("", "LOCAL_DEVELOPER");
    }

    /** Local-only installation; never an authenticated commercial account. */
    public static Owner local() {
      return new Owner("", "LOCAL_USER");
    }
  }

  record Query(
      UUID runId,
      String answerId,
      String questionId,
      SessionState state,
      Instant fromInclusive,
      Instant toExclusive,
      int page,
      int pageSize) {
    public Query {
      answerId = answerId == null ? "" : answerId.strip();
      questionId = questionId == null ? "" : questionId.strip();
      if (answerId.length() > 200
          || questionId.length() > 200
          || page < 0
          || pageSize < 1
          || pageSize > 100
          || (long) page * pageSize > Integer.MAX_VALUE
          || (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)))
        throw new IllegalArgumentException("Invalid record query");
    }

    public static Query firstPage() {
      return new Query(null, "", "", null, null, null, 0, 25);
    }

    public Query atPage(int next) {
      return new Query(
          runId, answerId, questionId, state, fromInclusive, toExclusive, next, pageSize);
    }
  }

  record Summary(
      UUID attemptId,
      UUID runId,
      String answerId,
      String questionId,
      SessionState state,
      BigDecimal javaTotal,
      String submissionStatus,
      Instant capturedAt,
      int modelCalls) {}

  record Page(List<Summary> rows, long total, int page, int pageSize) {
    public Page {
      rows = List.copyOf(rows);
    }

    public boolean hasNext() {
      return ((long) page + 1) * pageSize < total;
    }
  }
}
