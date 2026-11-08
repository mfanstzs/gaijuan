package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.commercial.CommercialClient;
import app.deskgrade.adapters.commercial.GatewayGradingProvider;
import app.deskgrade.adapters.model.ProviderException;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.port.CancellationToken;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual desktop transport and grading adapter against the real loopback gateway, synthetic engine.
 */
class CommercialClientGatewayIntegrationTest {
  @TempDir Path temp;

  @Test
  void commercialClientRegistersOrdersGradesChargedSuccessAndFailureThenLogsOut() throws Exception {
    try (var store = new GatewayStore(temp.resolve("integrated"), Clock.systemUTC())) {
      var admin = store.bootstrapAdmin("operator", GatewayFixtures.password());
      GatewayFixtures.activate(store, admin.id(), GatewayFixtures.profile());
      var calls = new AtomicInteger();
      var modelService =
          new GatewayModelService(
              store,
              List.of(GatewayFixtures.profile()),
              (profile, rubric, request, hook) -> {
                hook.run();
                if (calls.incrementAndGet() == 2)
                  return CompletableFuture.failedFuture(
                      new ProviderException(ProviderException.Failure.RATE_LIMIT));
                return CompletableFuture.completedFuture(GatewayFixtures.response(request));
              });
      try (var server = new GatewayServer(0, store, modelService, Clock.systemUTC())) {
        server.start();
        try (var client =
            new CommercialClient(
                URI.create("http://127.0.0.1:" + server.port()), Clock.systemUTC())) {
          var account =
              client
                  .register("desktop-user", GatewayFixtures.password())
                  .toCompletableFuture()
                  .get(15, TimeUnit.SECONDS);
          assertEquals(0, account.credits());
          assertEquals("USER", account.role());
          UUID accountId = UUID.fromString(account.accountId());
          store.adjust(
              admin.id(), UUID.randomUUID(), accountId, 3, "synthetic client integration quota");
          assertEquals(3, client.me().toCompletableFuture().get(10, TimeUnit.SECONDS).credits());
          var packages = client.packages().toCompletableFuture().get(10, TimeUnit.SECONDS);
          assertEquals(3, packages.size());
          assertFalse(packages.getFirst().paymentEnabled());
          assertNull(packages.getFirst().priceMinor());
          UUID orderId = UUID.randomUUID();
          var order =
              client
                  .createOrder(orderId, packages.getFirst().id())
                  .toCompletableFuture()
                  .get(10, TimeUnit.SECONDS);
          assertEquals("PENDING_PAYMENT", order.status());
          assertFalse(order.paymentEnabled());
          assertEquals(
              order,
              client
                  .createOrder(orderId, packages.getFirst().id())
                  .toCompletableFuture()
                  .get(10, TimeUnit.SECONDS));
          assertEquals(1, client.orders().toCompletableFuture().get(10, TimeUnit.SECONDS).size());
          assertEquals(3, client.me().toCompletableFuture().get(10, TimeUnit.SECONDS).credits());
          var profiles = client.models().toCompletableFuture().get(10, TimeUnit.SECONDS);
          assertEquals(1, profiles.size());
          assertTrue(profiles.getFirst().isVerified());
          try (var provider =
              new GatewayGradingProvider(
                  client, GatewayFixtures.rubric(), profiles.getFirst().id())) {
            UUID firstId = UUID.randomUUID();
            var first = request(firstId);
            assertEquals(
                firstId,
                provider
                    .grade(first, CancellationToken.none())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                    .requestId());
            assertEquals(2, client.me().toCompletableFuture().get(10, TimeUnit.SECONDS).credits());
            assertEquals(
                firstId,
                provider
                    .grade(first, CancellationToken.none())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                    .requestId());
            assertEquals(1, calls.get());
            UUID failureId = UUID.randomUUID();
            var failure =
                assertThrows(
                    ExecutionException.class,
                    () ->
                        provider
                            .grade(request(failureId), CancellationToken.none())
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS));
            var gatewayFailure =
                assertInstanceOf(
                    app.deskgrade.adapters.commercial.GatewayException.class, failure.getCause());
            assertEquals(
                app.deskgrade.adapters.commercial.GatewayException.Code.MODEL_FAILED,
                gatewayFailure.code());
            assertEquals(1, gatewayFailure.chargedCredits());
            assertEquals(failureId, gatewayFailure.requestId());
            assertEquals(1, client.me().toCompletableFuture().get(10, TimeUnit.SECONDS).credits());
            assertEquals(2, calls.get());
            var status =
                client.gradeStatus(failureId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(CommercialClient.GradeState.FAILED, status.state());
            assertEquals(1, status.chargedCredits());
          }
          client.logout().toCompletableFuture().get(10, TimeUnit.SECONDS);
          assertFalse(client.isAuthenticated());
          assertEquals(
              1,
              client
                  .login("desktop-user", GatewayFixtures.password())
                  .toCompletableFuture()
                  .get(15, TimeUnit.SECONDS)
                  .credits());
          client.logout().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
      }
      assertEquals(2, calls.get());
      assertEquals(
          2,
          store.ledger(admin.id(), null, 100).stream()
              .filter(e -> e.kind().equals("MODEL_DISPATCH"))
              .count());
    }
  }

  private static GradingRequest request(UUID id) {
    var body = GatewayFixtures.body(id);
    return new GradingRequest(
        id,
        GatewayFixtures.rubric().question(),
        Base64.getDecoder().decode(body.get("imageBase64").textValue()));
  }
}
