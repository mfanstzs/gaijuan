package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Explicit live-MySQL test; credentials are environment-only and never written to test output. */
class ManualSubmissionDeduplicationMySqlIT {
  @Test
  void repeatableReadClientsWithOldSnapshotsCanCommitOnlyOneSubmissionIntent() throws Exception {
    String url = System.getenv("DESKGRADE_TEST_MYSQL_URL");
    assumeTrue(
        url != null && url.startsWith("jdbc:mysql:"), "Live MySQL test configuration absent");
    var db = new MySqlSessionJournalTest.Database();
    String target = "manual-calibration:concurrent-" + UUID.randomUUID();
    String firstScope = db.alice.accountId().replace("-", "").repeat(2);
    String secondScope = "b".repeat(64);
    ManualSubmissionDeduplicationTest.RunAttempt left, right;
    try (var first = db.journal(db.alice);
        var second = new MySqlSessionJournal(db.connect(), db.alice, secondScope)) {
      left =
          ManualSubmissionDeduplicationTest.create(
              first, db.alice, target, "q", ManualSubmissionDeduplicationTest.HASH);
      right =
          ManualSubmissionDeduplicationTest.create(
              second, db.alice, target, "q", ManualSubmissionDeduplicationTest.HASH);
      grade(first, left);
      grade(second, right);
    }

    var bothHaveReadTheirSnapshot = new CountDownLatch(2);
    var barrierArrivals = new AtomicInteger();
    try (var first =
            new MySqlSessionJournal(
                synchronizeGuardRead(db.connect(), bothHaveReadTheirSnapshot, barrierArrivals),
                db.alice,
                firstScope);
        var second =
            new MySqlSessionJournal(
                synchronizeGuardRead(db.connect(), bothHaveReadTheirSnapshot, barrierArrivals),
                db.alice,
                secondScope)) {
      ManualSubmissionDeduplicationTest.compete(first, left, second, right);
      assertEquals(2, barrierArrivals.get());
    }

    // A third connection sees exactly one durable intent and both original grading responses.
    try (var reopened = db.journal(db.alice)) {
      var a = reopened.attempts(left.run()).getFirst();
      var b = reopened.attempts(right.run()).getFirst();
      assertEquals(1, (a.submissionPrepared() ? 1 : 0) + (b.submissionPrepared() ? 1 : 0));
      assertEquals(0, BigDecimal.ONE.compareTo(a.score()));
      assertEquals(0, BigDecimal.ONE.compareTo(b.score()));
      assertFalse(a.confirmed());
      assertFalse(b.confirmed());
      for (var ids : new ManualSubmissionDeduplicationTest.RunAttempt[] {left, right}) {
        var record = JournalJson.MAPPER.readTree(reopened.recordJson(db.alice, ids.attempt()));
        assertEquals(1, record.path("modelCalls").size());
        assertEquals("READBACK_MATCHED", record.at("/input/status").asText());
      }
    }
  }

  private static void grade(
      PersistentSessionJournal journal, ManualSubmissionDeduplicationTest.RunAttempt ids) {
    var response = H2TestFixtures.response(null);
    journal.beginModelCall(
        response.requestId(), ids.run(), ids.attempt(), "synthetic", H2TestFixtures.NOW);
    journal.completeModelCall(response.requestId(), response, null, H2TestFixtures.NOW);
    journal.saveGrade(ids.attempt(), response, BigDecimal.ONE);
    journal.recordScoreReadback(ids.attempt(), BigDecimal.ONE, H2TestFixtures.NOW);
  }

  private static Connection synchronizeGuardRead(
      Connection connection, CountDownLatch barrier, AtomicInteger arrivals) throws SQLException {
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    var firstGuard = new AtomicBoolean(true);
    return (Connection)
        Proxy.newProxyInstance(
            ManualSubmissionDeduplicationMySqlIT.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(connection, arguments);
                if (!method.getName().equals("prepareStatement")
                    || !(result instanceof PreparedStatement statement)) return result;
                statement.setQueryTimeout(10);
                String sql = (String) arguments[0];
                if (!sql.contains("SELECT id FROM journal_config_document WHERE id='storage-probe'")
                    || !sql.endsWith("FOR UPDATE")) return statement;
                return Proxy.newProxyInstance(
                    ManualSubmissionDeduplicationMySqlIT.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (statementProxy, operation, parameters) -> {
                      if (operation.getName().equals("executeQuery")
                          && firstGuard.compareAndSet(true, false)) {
                        // Establish each old RR snapshot before the transaction-wide guard.
                        try (var snapshot = connection.createStatement();
                            var rows =
                                snapshot.executeQuery(
                                    "SELECT COUNT(*) FROM journal_grading_attempt")) {
                          assertTrue(rows.next());
                        }
                        arrivals.incrementAndGet();
                        barrier.countDown();
                        try {
                          if (!barrier.await(10, TimeUnit.SECONDS))
                            throw new SQLException("Concurrent guard test barrier timed out");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new SQLException("Concurrent guard test interrupted");
                        }
                      }
                      try {
                        return operation.invoke(statement, parameters);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
