package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ServiceEndpointTest {
  @Test
  void acceptsPublicHttpsOriginAndLocalDevelopmentOrigin() {
    assertEquals(
        "https://203.0.113.10", ServiceEndpoint.validate("https://203.0.113.10").toString());
    assertEquals(
        "https://example.com:8443/",
        ServiceEndpoint.validate("https://example.com:8443/").toString());
    assertEquals(
        "http://127.0.0.1:8787", ServiceEndpoint.validate("http://127.0.0.1:8787").toString());
  }

  @Test
  void rejectsCredentialsPathsQueriesAndPlaintextRemoteService() {
    for (String invalid :
        new String[] {
          "http://203.0.113.10",
          "https://user:password@example.com",
          "https://example.com/api",
          "https://example.com?token=x",
          "https://example.com#part",
          "file:///service",
          " https://example.com",
          "https://example.com:0",
          "https://example.com:65536",
          "https://example.com:-2"
        })
      assertThrows(
          IllegalArgumentException.class, () -> ServiceEndpoint.validate(invalid), invalid);
  }

  @Test
  void readsOnlyPublicEndpointAndRejectsUnexpectedConfiguration() throws Exception {
    assertEquals(
        "https://example.com",
        ServiceEndpoint.read(source("service.url=https://example.com\n")).toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceEndpoint.read(source("service.url=https://example.com\napi.key=private\n")));
    assertThrows(
        IllegalArgumentException.class, () -> ServiceEndpoint.read(source("service.url=\n")));
    assertThrows(
        IllegalArgumentException.class, () -> ServiceEndpoint.read(source("#".repeat(4097))));
  }

  private static ByteArrayInputStream source(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
  }
}
