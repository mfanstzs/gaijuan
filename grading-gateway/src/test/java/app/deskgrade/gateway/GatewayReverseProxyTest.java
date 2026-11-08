package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayReverseProxyTest {
  @TempDir Path temp;

  @Test
  void httpsAdminLoginWorksAndRegistrationBudgetsArePerForwardedAddress() throws Exception {
    try (var store = new GatewayStore(temp.resolve("proxy"), Clock.systemUTC());
        var client = HttpClient.newHttpClient();
        var server =
            new GatewayServer(
                0,
                store,
                new GatewayModelService(
                    store,
                    List.of(),
                    (p, r, q, h) -> {
                      throw new AssertionError();
                    }),
                Clock.systemUTC(),
                null,
                new GatewayCapacity.Configuration(1, 1, 1000),
                Duration.ofSeconds(30),
                2,
                GatewayProxyConfiguration.parse("https://grade.example.com"))) {
      store.bootstrapAdmin("operator", GatewayFixtures.password());
      server.start();
      assertEquals(200, request(client, server, "GET", "/health", null, null, null).statusCode());
      assertEquals(403, request(client, server, "GET", "/admin", null, null, null).statusCode());
      assertEquals(
          200, request(client, server, "GET", "/admin", "203.0.113.10", null, null).statusCode());
      String credentials =
          "{\"username\":\"operator\",\"password\":\"synthetic test password 42\"}";
      assertEquals(
          403,
          request(
                  client,
                  server,
                  "POST",
                  "/v1/auth/login",
                  "203.0.113.10",
                  "https://evil.example",
                  credentials)
              .statusCode());
      var login =
          request(
              client,
              server,
              "POST",
              "/v1/auth/login",
              "203.0.113.10",
              "https://grade.example.com",
              credentials);
      assertEquals(200, login.statusCode());
      assertEquals(
          "ADMIN", GatewayJson.MAPPER.readTree(login.body()).path("account").path("role").asText());
      for (int i = 0; i < LoginLimiter.REGISTRATION_ADDRESS_LIMIT; i++)
        assertEquals(
            400,
            request(client, server, "POST", "/v1/auth/register", "203.0.113.10", null, "{}")
                .statusCode());
      assertEquals(
          429,
          request(client, server, "POST", "/v1/auth/register", "203.0.113.10", null, "{}")
              .statusCode());
      assertEquals(
          400,
          request(client, server, "POST", "/v1/auth/register", "203.0.113.11", null, "{}")
              .statusCode());
      assertEquals(
          403,
          request(
                  client,
                  server,
                  "POST",
                  "/v1/auth/register",
                  "203.0.113.11, 203.0.113.12",
                  null,
                  "{}")
              .statusCode());
    }
  }

  private static HttpResponse<String> request(
      HttpClient client,
      GatewayServer server,
      String method,
      String path,
      String address,
      String origin,
      String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (address != null)
      builder
          .header("X-Forwarded-Host", "grade.example.com")
          .header("X-Forwarded-Proto", "https")
          .header("X-Forwarded-For", address);
    if (origin != null) builder.header("Origin", origin);
    if (body != null) builder.header("Content-Type", "application/json");
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
