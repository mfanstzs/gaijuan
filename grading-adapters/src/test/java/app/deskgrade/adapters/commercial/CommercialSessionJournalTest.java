package app.deskgrade.adapters.commercial;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.persistence.JournalJson;
import app.deskgrade.adapters.persistence.MetadataOnlyEvidenceStore;
import app.deskgrade.adapters.persistence.PersistentSessionJournal;
import app.deskgrade.core.application.session.*;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CommercialSessionJournalTest {
  @Test
  void metadataOnlyJournalUsesBearerAndBindsTheOriginalLogin() throws Exception {
    try (var server = new Server();
        var client = new CommercialClient(server.base, Clock.systemUTC())) {
      assertThrows(GatewayException.class, () -> new CommercialSessionJournal(client));
      client.login("alice", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      var journal = new CommercialSessionJournal(client);
      var question =
          new QuestionDefinition(
              "q",
              1,
              "question",
              "answer",
              BigDecimal.ONE,
              List.of(
                  new ScoringCriterion(
                      "a", "criterion", List.of(BigDecimal.ZERO, BigDecimal.ONE))));
      var settings =
          new SessionSettings(
              question,
              "synthetic",
              1,
              1,
              "{}",
              0,
              0,
              0,
              null,
              Duration.ofMillis(20),
              Duration.ofSeconds(5));
      journal.beginAttempt(
          UUID.randomUUID(),
          UUID.randomUUID(),
          1,
          new SessionPlatform.View(
              "answer-id", "q", 1, SessionPlatform.Page.READY, null, 0, null, false),
          1,
          MetadataOnlyEvidenceStore.NO_IMAGE_STORED,
          new SessionPlatform.Capture(
              new byte[] {12, 34, 56}, "0".repeat(64), Instant.parse("2026-09-11T00:00:00Z")),
          settings);
      assertEquals("Bearer synthetic-token-alice", server.authorization);
      var sent = server.commands.getFirst();
      assertEquals("beginAttempt", sent.path("operation").asText());
      assertFalse(sent.path("arguments").has("png"));
      assertFalse(sent.toString().contains("DCI4"));
      assertFalse(sent.path("arguments").has("owner"));
      assertEquals("2026-09-11T00:00:00Z", sent.path("arguments").path("capturedAt").asText());
      client.logout().toCompletableFuture().get();
      client.login("bravo", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      assertEquals(
          GatewayException.Code.SESSION_CHANGED,
          assertThrows(GatewayException.class, journal::assertWritable).code());
      assertEquals(1, server.commands.size());
      new CommercialSessionJournal(client).assertWritable();
      assertEquals("Bearer synthetic-token-bravo", server.authorization);
    }
  }

  @Test
  void confirmationKindIsSentExplicitlyWithoutChangingLegacyCalls() throws Exception {
    try (var server = new Server();
        var client = new CommercialClient(server.base, Clock.systemUTC())) {
      client.login("alice", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      var journal = new CommercialSessionJournal(client);
      UUID run = UUID.randomUUID(), attempt = UUID.randomUUID();
      Instant at = Instant.parse("2026-09-11T00:00:00Z");
      for (SessionPlatform.ConfirmationKind kind : SessionPlatform.ConfirmationKind.values()) {
        journal.confirmSubmission(run, attempt, at, kind);
        JsonNode sent = server.commands.getLast();
        assertEquals("confirmSubmission", sent.path("operation").asText());
        assertEquals(kind.name(), sent.at("/arguments/confirmationKind").asText());
        assertEquals(at.toString(), sent.at("/arguments/now").asText());
        assertFalse(sent.path("arguments").has("owner"));
        assertEquals("Bearer synthetic-token-alice", server.authorization);
      }
      journal.confirmSubmission(run, attempt, at);
      assertFalse(server.commands.getLast().path("arguments").has("confirmationKind"));
      assertEquals(4, server.commands.size());
    }
  }

  @Test
  void serverStorageFailureDoesNotRetryOrReturnSuccess() throws Exception {
    try (var server = new Server();
        var client = new CommercialClient(server.base, Clock.systemUTC())) {
      client.login("alice", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      var journal = new CommercialSessionJournal(client);
      server.fail = true;
      assertEquals(
          GatewayException.Code.STORE_UNAVAILABLE,
          assertThrows(GatewayException.class, journal::assertWritable).code());
      assertEquals(1, server.commands.size());
    }
  }

  @Test
  void duplicateSubmissionIsPreservedAcrossBearerRpcWithoutRetry() throws Exception {
    try (var server = new Server();
        var client = new CommercialClient(server.base, Clock.systemUTC())) {
      client.login("alice", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      var journal = new CommercialSessionJournal(client);
      server.duplicate = true;
      var failure =
          assertThrows(
              app.deskgrade.core.error.DeskGradeException.class,
              () ->
                  journal.beginModelCall(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      "synthetic",
                      Instant.now()));
      assertEquals(app.deskgrade.core.error.ErrorCode.DUPLICATE_SUBMISSION, failure.code());
      assertEquals(1, server.commands.size());
    }
  }

  @Test
  void explicitOldBatchReviewUsesBearerOwnerAndNeverSendsAClientDecisionTimestamp()
      throws Exception {
    try (var server = new Server();
        var client = new CommercialClient(server.base, Clock.systemUTC())) {
      client.login("alice", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      var journal = new CommercialSessionJournal(client);
      var owner = new GradingRecords.Owner(server.base.toString(), "alice");
      UUID runId = UUID.randomUUID(), attemptId = UUID.randomUUID(), decisionId = UUID.randomUUID();
      var record =
          new PersistentSessionJournal.UnresolvedSubmission(
              new SessionJournal.RunRecord(
                  runId,
                  "manual-calibration:test",
                  SessionState.SUBMISSION_UNKNOWN,
                  PauseReason.SUBMISSION_UNCERTAIN,
                  1,
                  0,
                  Instant.parse("2026-09-11T00:00:00Z")),
              attemptId,
              "screen:synthetic",
              "q",
              BigDecimal.ONE);
      server.journalResult = JournalJson.MAPPER.valueToTree(List.of(record));
      assertEquals(
          List.of(record), journal.unresolvedSubmissions(owner, "manual-calibration:test"));
      assertEquals("Bearer synthetic-token-alice", server.authorization);
      assertFalse(server.commands.getLast().path("arguments").has("owner"));
      server.journalResult = null;
      for (int retry = 0; retry < 2; retry++) {
        journal.abandonUnresolvedRun(
            owner, runId, "manual-calibration:test", decisionId, Instant.EPOCH);
        JsonNode args = server.commands.getLast().path("arguments");
        assertEquals(decisionId.toString(), args.path("decisionId").asText());
        assertFalse(args.has("owner"));
        assertFalse(args.has("now"));
      }
      int before = server.commands.size();
      assertThrows(
          GatewayException.class,
          () ->
              journal.abandonUnresolvedRun(
                  new GradingRecords.Owner(server.base.toString(), "bravo"),
                  runId,
                  "manual-calibration:test",
                  decisionId,
                  Instant.now()));
      assertEquals(before, server.commands.size());
      client.logout().toCompletableFuture().get();
      client.login("bravo", "synthetic-password-only".toCharArray()).toCompletableFuture().get();
      assertThrows(
          GatewayException.class,
          () -> journal.unresolvedSubmissions(owner, "manual-calibration:test"));
      assertEquals(before, server.commands.size());
    }
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final URI base;
    final List<JsonNode> commands = new CopyOnWriteArrayList<>();
    volatile boolean fail;
    volatile boolean duplicate;
    volatile String authorization;
    volatile JsonNode journalResult;

    Server() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      server.createContext(
          "/",
          exchange -> {
            int status = 200;
            JsonNode response;
            try {
              String path = exchange.getRequestURI().getPath();
              if (path.equals("/v1/auth/logout")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
              }
              byte[] body = exchange.getRequestBody().readNBytes(JournalJson.MAX_BYTES + 1);
              if (body.length > JournalJson.MAX_BYTES) throw new IllegalArgumentException();
              var request = JournalJson.MAPPER.readTree(body);
              if (path.equals("/v1/auth/login")) {
                String name = request.path("username").asText();
                var auth =
                    JournalJson.MAPPER
                        .createObjectNode()
                        .put("token", "synthetic-token-" + name)
                        .put("expiresAt", Instant.now().plusSeconds(600).toString());
                auth.putObject("account")
                    .put("id", name)
                    .put("username", name)
                    .put("role", "USER")
                    .put("credits", 7);
                response = auth;
              } else if (path.equals("/v1/journal")) {
                if (commands.size() >= 20)
                  throw new IllegalStateException("Bounded test loop guard");
                commands.add(request);
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (fail || duplicate) {
                  status = duplicate ? 409 : 503;
                  var error = JournalJson.MAPPER.createObjectNode().put("chargedCredits", 0);
                  error
                      .putObject("error")
                      .put("code", duplicate ? "DUPLICATE_SUBMISSION" : "STORE_UNAVAILABLE")
                      .put("message", "Storage unavailable");
                  response = error;
                } else {
                  var reply = JournalJson.MAPPER.createObjectNode();
                  if (journalResult == null) reply.putNull("result");
                  else reply.set("result", journalResult);
                  response = reply;
                }
              } else throw new IllegalArgumentException();
              byte[] encoded = JournalJson.MAPPER.writeValueAsBytes(response);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, encoded.length);
              exchange.getResponseBody().write(encoded);
            } catch (Exception failure) {
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
