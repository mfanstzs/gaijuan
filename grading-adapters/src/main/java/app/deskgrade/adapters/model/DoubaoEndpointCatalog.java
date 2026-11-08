package app.deskgrade.adapters.model;

import java.net.URI;
import java.time.LocalDate;

/** Official metadata only: selecting a candidate never implies account access or verification. */
public final class DoubaoEndpointCatalog {
  public static final URI ENDPOINT =
      URI.create("https://ark.cn-beijing.volces.com/api/v3/chat/completions");
  public static final String SEED_2_1_LITE = "doubao-seed-2-1-lite-260915";
  public static final LocalDate DOCUMENTATION_CHECKED_ON = LocalDate.of(2026, 10, 21);
  public static final URI CHAT_DOCUMENTATION =
      URI.create("https://docs.volcengine.com/docs/ark/chat-api?lang=zh&redirect=1");
  public static final URI MODEL_DOCUMENTATION =
      URI.create("https://docs.volcengine.com/docs/ark/model-list?lang=zh&redirect=1");
  public static final URI STRUCTURED_OUTPUT_DOCUMENTATION =
      URI.create("https://docs.volcengine.com/docs/ark/structured-output-beta?lang=zh");

  private DoubaoEndpointCatalog() {}

  public static boolean isOfficialDoubaoEndpoint(URI endpoint) {
    return endpoint != null
        && "https".equalsIgnoreCase(endpoint.getScheme())
        && "ark.cn-beijing.volces.com".equalsIgnoreCase(endpoint.getHost())
        && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
        && "/api/v3/chat/completions".equals(endpoint.getRawPath())
        && endpoint.getRawUserInfo() == null
        && endpoint.getRawQuery() == null
        && endpoint.getRawFragment() == null;
  }
}
