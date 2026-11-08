package app.deskgrade.gateway;

import com.sun.net.httpserver.Headers;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Explicit single-loopback-proxy boundary; forwarded headers never select the public origin. */
final class GatewayProxyConfiguration {
  static final String PUBLIC_ORIGIN_PROPERTY = "deskgrade.gateway.publicOrigin";
  static final String PUBLIC_ORIGIN_ENV = "DESKGRADE_PUBLIC_ORIGIN";
  private final URI publicOrigin;

  private GatewayProxyConfiguration(URI publicOrigin) {
    this.publicOrigin = publicOrigin;
  }

  static GatewayProxyConfiguration load() {
    return load(System::getProperty, System::getenv);
  }

  static GatewayProxyConfiguration load(
      Function<String, String> properties, Function<String, String> environment) {
    String configured = properties.apply(PUBLIC_ORIGIN_PROPERTY);
    if (configured == null) configured = environment.apply(PUBLIC_ORIGIN_ENV);
    return parse(configured);
  }

  static GatewayProxyConfiguration parse(String configured) {
    if (configured == null) return new GatewayProxyConfiguration(null);
    try {
      URI value = URI.create(configured);
      if (!"https".equals(value.getScheme())
          || value.getHost() == null
          || value.getUserInfo() != null
          || value.getRawQuery() != null
          || value.getRawFragment() != null
          || !(value.getRawPath().isEmpty() || value.getRawPath().equals("/"))
          || value.getPort() == 0
          || value.getPort() > 65535) throw new IllegalArgumentException();
      String host = value.getHost().toLowerCase(Locale.ROOT);
      int port = value.getPort() == 443 ? -1 : value.getPort();
      URI canonical = new URI("https", null, host, port, null, null, null);
      return new GatewayProxyConfiguration(canonical);
    } catch (Exception invalid) {
      throw new IllegalArgumentException(
          "The public gateway origin must be an absolute HTTPS origin");
    }
  }

  String journalOrigin(int port) {
    return publicOrigin == null ? "http://127.0.0.1:" + port : publicOrigin.toString();
  }

  /** Validates the complete ingress boundary before returning an address used by login budgets. */
  String validate(Headers headers, InetAddress peer, int port, String method, String path) {
    if (publicOrigin == null) {
      String host = headers.getFirst("Host");
      if (!List.of("127.0.0.1:" + port, "localhost:" + port).contains(host)) throw forbidden();
      validateOrigin(headers, method, "http://" + host);
      return peer.getHostAddress();
    }
    // The gateway listener remains loopback-only. A remote peer must never obtain proxy trust.
    if (peer == null || !peer.isLoopbackAddress()) throw forbidden();
    if (!single(headers, "Host").equals("127.0.0.1:" + port)) throw forbidden();
    if (headers.containsKey("Forwarded")) throw forbidden();
    boolean forwarded =
        headers.containsKey("X-Forwarded-For")
            || headers.containsKey("X-Forwarded-Host")
            || headers.containsKey("X-Forwarded-Proto");
    // Local process supervision can probe health without impersonating an external client.
    if (!forwarded && method.equals("GET") && path.equals("/health")) return peer.getHostAddress();
    if (!single(headers, "X-Forwarded-Host").equals(publicOrigin.getRawAuthority())
        || !single(headers, "X-Forwarded-Proto").equals("https")) throw forbidden();
    String address = strictAddress(single(headers, "X-Forwarded-For"));
    validateOrigin(headers, method, publicOrigin.toString());
    return address;
  }

  private static void validateOrigin(Headers headers, String method, String expected) {
    if (method.equals("GET")) return;
    List<String> origins = headers.get("Origin");
    if (origins != null && (origins.size() != 1 || !origins.getFirst().equals(expected)))
      throw forbidden();
  }

  private static String single(Headers headers, String name) {
    List<String> values = headers.get(name);
    if (values == null || values.size() != 1 || values.getFirst() == null) throw forbidden();
    return values.getFirst();
  }

  private static String strictAddress(String value) {
    if (value == null || value.isEmpty() || value.length() > 45) throw forbidden();
    if (value.contains(":")) {
      // Only literal hex/colon input reaches getByName: no DNS, zone ID, port, or hop list.
      if (!value.matches("[0-9A-Fa-f:]+")) throw forbidden();
      try {
        InetAddress parsed = InetAddress.getByName(value);
        if (!(parsed instanceof Inet6Address)) throw forbidden();
        return parsed.getHostAddress();
      } catch (java.net.UnknownHostException invalid) {
        throw forbidden();
      }
    }
    if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw forbidden();
    for (String octet : value.split("\\.")) if (Integer.parseInt(octet) > 255) throw forbidden();
    return value;
  }

  private static GatewayException forbidden() {
    return new GatewayException(GatewayException.Code.FORBIDDEN);
  }
}
