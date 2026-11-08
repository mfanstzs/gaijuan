package app.deskgrade.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import java.util.Set;

/** Public service origin supplied by the release build; never contains credentials. */
final class ServiceEndpoint {
  static final String RESOURCE = "/app/deskgrade/desktop/service-endpoint.properties";
  private static final URI DEVELOPMENT_SERVICE = URI.create("http://127.0.0.1:8787");

  private ServiceEndpoint() {}

  static URI bundled() {
    try (InputStream source = ServiceEndpoint.class.getResourceAsStream(RESOURCE)) {
      return source == null ? DEVELOPMENT_SERVICE : read(source);
    } catch (IOException | IllegalArgumentException failure) {
      throw new IllegalStateException("Invalid bundled service configuration", failure);
    }
  }

  static URI read(InputStream source) throws IOException {
    byte[] bytes = source.readNBytes(4097);
    if (bytes.length > 4096) throw new IllegalArgumentException("Service configuration too large");
    Properties properties = new Properties();
    properties.load(new java.io.ByteArrayInputStream(bytes));
    if (!properties.stringPropertyNames().equals(Set.of("service.url")))
      throw new IllegalArgumentException("Unexpected service configuration");
    return validate(properties.getProperty("service.url"));
  }

  static URI validate(String value) {
    if (value == null || value.isEmpty() || !value.equals(value.trim()) || value.length() > 2048)
      throw new IllegalArgumentException("Invalid service origin");
    URI origin = URI.create(value);
    if (origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
        || origin.getPort() < -1
        || origin.getPort() == 0
        || origin.getPort() > 65535
        || !("https".equals(origin.getScheme())
            || ("http".equals(origin.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(origin.getHost()))))
      throw new IllegalArgumentException("Service origin requires HTTPS or exact local HTTP");
    return origin;
  }
}
