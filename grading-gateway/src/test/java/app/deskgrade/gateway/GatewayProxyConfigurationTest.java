package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Headers;
import java.net.InetAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayProxyConfigurationTest {
  @Test
  void onlyExplicitHttpsOriginsEnableProxyModeAndJournalOriginIsCanonical() throws Exception {
    assertEquals(
        "http://127.0.0.1:8787", GatewayProxyConfiguration.parse(null).journalOrigin(8787));
    assertEquals(
        "https://grade.example.com",
        GatewayProxyConfiguration.parse("https://GRADE.example.com:443/").journalOrigin(8787));
    assertEquals(
        "https://203.0.113.10:8443",
        GatewayProxyConfiguration.parse("https://203.0.113.10:8443").journalOrigin(8787));
    for (String invalid :
        new String[] {
          "",
          "http://grade.example.com",
          "https://user@grade.example.com",
          "https://grade.example.com/path",
          "https://grade.example.com?",
          "https://grade.example.com#",
          "https://grade.example.com:0",
          "https://grade.example.com:65536",
          "https://grade.example.com,evil.example"
        })
      assertThrows(IllegalArgumentException.class, () -> GatewayProxyConfiguration.parse(invalid));
    var environment = Map.of(GatewayProxyConfiguration.PUBLIC_ORIGIN_ENV, "https://env.example");
    assertEquals(
        "https://env.example",
        GatewayProxyConfiguration.load(key -> null, environment::get).journalOrigin(8787));
    assertEquals(
        "https://property.example",
        GatewayProxyConfiguration.load(key -> "https://property.example", environment::get)
            .journalOrigin(8787));
  }

  @Test
  void localModePreservesLocalHostAndOriginBoundary() throws Exception {
    var config = GatewayProxyConfiguration.parse(null);
    var headers = new Headers();
    headers.set("Host", "localhost:8787");
    headers.set("Origin", "http://localhost:8787");
    assertEquals("127.0.0.1", config.validate(headers, loopback(), 8787, "POST", "/v1/auth/login"));
    headers.set("Origin", "https://grade.example.com");
    forbidden(() -> config.validate(headers, loopback(), 8787, "POST", "/v1/auth/login"));
    headers.set("Host", "evil.example:8787");
    forbidden(() -> config.validate(headers, loopback(), 8787, "GET", "/health"));
  }

  @Test
  void proxyModeRequiresOneTrustedHopExactHostProtocolAndBrowserOrigin() throws Exception {
    var config = GatewayProxyConfiguration.parse("https://grade.example.com");
    var headers = proxyHeaders("203.0.113.10");
    assertEquals(
        "203.0.113.10", config.validate(headers, loopback(), 8787, "POST", "/v1/auth/login"));
    forbidden(
        () ->
            config.validate(
                headers,
                InetAddress.getByAddress(new byte[] {10, 0, 0, 1}),
                8787,
                "POST",
                "/v1/auth/login"));
    for (String header :
        new String[] {"Host", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-For"}) {
      var missing = proxyHeaders("203.0.113.10");
      missing.remove(header);
      forbidden(() -> config.validate(missing, loopback(), 8787, "POST", "/v1/auth/login"));
      var duplicate = proxyHeaders("203.0.113.10");
      duplicate.add(header, duplicate.getFirst(header));
      forbidden(() -> config.validate(duplicate, loopback(), 8787, "POST", "/v1/auth/login"));
    }
    for (var override :
        Map.of(
                "Host", "grade.example.com",
                "X-Forwarded-Host", "evil.example",
                "X-Forwarded-Proto", "http",
                "Origin", "https://evil.example")
            .entrySet()) {
      var invalid = proxyHeaders("203.0.113.10");
      invalid.set(override.getKey(), override.getValue());
      forbidden(() -> config.validate(invalid, loopback(), 8787, "POST", "/v1/auth/login"));
    }
    headers.set("Forwarded", "for=203.0.113.10;proto=https");
    forbidden(() -> config.validate(headers, loopback(), 8787, "POST", "/v1/auth/login"));
  }

  @Test
  void forwardedClientAddressIsLiteralSingleAndNormalizedWithoutDns() throws Exception {
    var config = GatewayProxyConfiguration.parse("https://grade.example.com");
    for (String valid : new String[] {"203.0.113.10", "2001:db8::10", "::1"})
      assertEquals(
          InetAddress.getByName(valid).getHostAddress(),
          config.validate(proxyHeaders(valid), loopback(), 8787, "GET", "/admin"));
    for (String invalid :
        new String[] {
          "203.0.113.10, 203.0.113.11",
          "203.0.113.10 ",
          " 203.0.113.10",
          "client.example",
          "256.1.1.1",
          "01.2.3.4",
          "127.1",
          "2130706433",
          "203.0.113.10:443",
          "[2001:db8::10]",
          "fe80::1%eth0",
          "2001::db8::10",
          "",
          "unknown"
        })
      forbidden(() -> config.validate(proxyHeaders(invalid), loopback(), 8787, "GET", "/admin"));
  }

  @Test
  void onlyLocalHealthCanBypassForwardedHeaders() throws Exception {
    var config = GatewayProxyConfiguration.parse("https://grade.example.com");
    var headers = new Headers();
    headers.set("Host", "127.0.0.1:8787");
    assertEquals("127.0.0.1", config.validate(headers, loopback(), 8787, "GET", "/health"));
    forbidden(() -> config.validate(headers, loopback(), 8787, "GET", "/admin"));
    forbidden(() -> config.validate(headers, loopback(), 8787, "POST", "/health"));
    headers.set("X-Forwarded-For", "203.0.113.10");
    forbidden(() -> config.validate(headers, loopback(), 8787, "GET", "/health"));
  }

  private static Headers proxyHeaders(String client) {
    var headers = new Headers();
    headers.set("Host", "127.0.0.1:8787");
    headers.set("X-Forwarded-Host", "grade.example.com");
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-For", client);
    headers.set("Origin", "https://grade.example.com");
    return headers;
  }

  private static InetAddress loopback() throws Exception {
    return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
  }

  private static void forbidden(org.junit.jupiter.api.function.Executable operation) {
    assertEquals(
        GatewayException.Code.FORBIDDEN, assertThrows(GatewayException.class, operation).code());
  }
}
