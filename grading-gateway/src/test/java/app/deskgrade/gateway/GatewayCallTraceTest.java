package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.port.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayCallTraceTest {
  @TempDir Path temp;

  @Test
  void enrichmentKeepsFinalEnvelopeBoundedAndRejectsOversizedBase() throws Exception {
    var command = GatewayJson.MAPPER.createObjectNode().put("operation", "recordJson");
    var record = GatewayJson.MAPPER.createObjectNode().put("padding", "");
    record.putArray("modelCalls").addObject().put("requestId", UUID.randomUUID().toString());
    var reply = GatewayJson.MAPPER.createObjectNode().put("result", record.toString());
    int max = 8 * 1024 * 1024;
    int overhead = reply.toString().getBytes(StandardCharsets.UTF_8).length;
    record.put("padding", "x".repeat(max - overhead - 85));
    reply.put("result", record.toString());
    try (var store = new GatewayStore(temp.resolve("bounded"), Clock.systemUTC())) {
      var user = store.register("reader", GatewayFixtures.password());
      var enriched = GatewayCallRecordEnricher.enrich(store, user.id(), command, reply);
      assertTrue(enriched.toString().getBytes(StandardCharsets.UTF_8).length <= max);
      assertTrue(enriched.path("result").asText().contains("serverCallsTruncated"));
      record.put("padding", "x".repeat(max));
      reply.put("result", record.toString());
      assertEquals(
          GatewayException.Code.INVALID_REQUEST,
          assertThrows(
                  GatewayException.class,
                  () -> GatewayCallRecordEnricher.enrich(store, user.id(), command, reply))
              .code());
    }
  }

  @Test
  void liveAdapterPersistsExactTextAndInvalidOutputAndReplaysWithoutDispatch() throws Exception {
    var content =
        new AtomicReference<>(
            """
        {"status":"SCORED","readability":"CLEAR","studentText":"2", "criteria":[
        {"criterionId":"c1","awarded":1,"evidence":"2","reason":"Matches reference"}],"issues":[]}
        """);
    var count = new AtomicInteger();
    var received = new AtomicReference<String>();
    var supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    supplier.createContext(
        "/v1/chat/completions",
        exchange -> {
          count.incrementAndGet();
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          var wire = GatewayJson.MAPPER.createObjectNode();
          wire.putArray("choices")
              .addObject()
              .put("finish_reason", "stop")
              .putObject("message")
              .put("content", content.get());
          byte[] bytes = wire.toString().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    supplier.start();
    try (var store = new GatewayStore(temp.resolve("trace"), Clock.systemUTC())) {
      var original = GatewayFixtures.profile();
      var profile =
          new ProviderProfile(
              original.id(),
              original.revision(),
              original.displayName(),
              URI.create(
                  "http://127.0.0.1:" + supplier.getAddress().getPort() + "/v1/chat/completions"),
              original.modelId(),
              original.credentialRef(),
              original.structuredMode(),
              original.parameters(),
              original.allowedParameters(),
              original.connectTimeout(),
              original.requestTimeout(),
              null,
              null);
      profile =
          profile.withVerification(
              new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, true, true));
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      var user = store.register("alice", GatewayFixtures.password());
      var other = store.register("bob", GatewayFixtures.password());
      store.adjust(admin.id(), UUID.randomUUID(), user.id(), 3, "synthetic allocation");
      GatewayFixtures.activate(store, admin.id(), profile);
      var secrets =
          new SecretStore() {
            public void put(String id, char[] secret) {
              throw new UnsupportedOperationException();
            }

            public Optional<char[]> read(String id) {
              return Optional.of("synthetic-fixture-key".toCharArray());
            }

            public void delete(String id) {
              throw new UnsupportedOperationException();
            }
          };
      var service =
          GatewayModelService.live(store, List.of(profile), secrets, true, Clock.systemUTC());
      UUID goodId = UUID.randomUUID();
      var good = GatewayFixtures.body(goodId);
      assertEquals(200, service.grade(user.id(), good).status());
      var detail = store.modelCallDetail(user.id(), goodId, "GRADING");
      var snapshot = GatewayJson.parse(detail.requestJson().getBytes(StandardCharsets.UTF_8));
      var wireRequest = GatewayJson.parse(received.get().getBytes(StandardCharsets.UTF_8));
      assertEquals(
          wireRequest.path("messages").get(0),
          snapshot.path("providerRequest").path("messages").get(0));
      assertEquals(
          wireRequest.path("messages").get(1).path("content").get(0),
          snapshot.path("providerRequest").path("messages").get(1).path("content").get(0));
      assertEquals("2", snapshot.path("question").path("standardAnswer").asText());
      assertFalse(detail.requestJson().contains(good.path("imageBase64").asText()));
      assertFalse(detail.requestJson().contains("synthetic-fixture-key"));
      assertEquals(content.get(), detail.modelOutput());
      content.set("{invalid model JSON");
      UUID invalidId = UUID.randomUUID();
      var bad = GatewayFixtures.body(invalidId);
      assertEquals(502, service.grade(user.id(), bad).status());
      assertEquals(
          content.get(), store.modelCallDetail(user.id(), invalidId, "GRADING").modelOutput());
      assertTrue(service.grade(user.id(), bad).body().path("replayed").asBoolean());
      assertEquals(2, count.get());
      assertEquals(1, store.account(user.id()).balance());
      assertEquals(2, store.adminModelCalls(admin.id(), user.id(), "", 1, 20).total());

      var command = GatewayJson.MAPPER.createObjectNode().put("operation", "recordJson");
      var record = GatewayJson.MAPPER.createObjectNode();
      record
          .putArray("modelCalls")
          .addObject()
          .put("requestId", goodId.toString())
          .put("profileId", "private-profile");
      record.putObject("configuration").putObject("binding").put("title", "private-window-title");
      var reply = GatewayJson.MAPPER.createObjectNode().put("result", record.toString());
      var enriched = GatewayCallRecordEnricher.enrich(store, user.id(), command, reply);
      var own =
          GatewayJson.parse(enriched.path("result").asText().getBytes(StandardCharsets.UTF_8));
      assertEquals(
          "2",
          own.path("serverCalls")
              .get(0)
              .path("request")
              .path("question")
              .path("standardAnswer")
              .asText());
      assertTrue(own.at("/serverCalls/0/request/providerRequest/model").isMissingNode());
      assertFalse(own.toString().contains(original.modelId()));
      assertFalse(own.toString().contains("private-profile"));
      assertFalse(own.toString().contains("private-window-title"));
      var forbidden = GatewayCallRecordEnricher.enrich(store, other.id(), command, reply);
      assertFalse(forbidden.toString().contains("Matches reference"));
      assertTrue(forbidden.toString().contains("NO_SERVER_RECORD"));
    } finally {
      supplier.stop(0);
    }
  }
}
