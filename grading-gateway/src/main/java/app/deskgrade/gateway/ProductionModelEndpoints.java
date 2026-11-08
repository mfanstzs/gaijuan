package app.deskgrade.gateway;

import java.net.URI;
import java.util.Set;

/** Exact destinations authorized for the gateway's supplier credentials. */
final class ProductionModelEndpoints {
  private static final Set<String> ALLOWED =
      Set.of(
          "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
          "https://api.openai.com/v1/chat/completions");

  private ProductionModelEndpoints() {}

  static void requireAllowed(URI endpoint) {
    // Do not normalize or resolve aliases: user-info, ports, encoded paths and even empty
    // query/fragment components must not broaden the exact configured destinations.
    if (endpoint == null || !ALLOWED.contains(endpoint.toString()))
      throw new IllegalArgumentException("The model endpoint is not allowed for this gateway");
  }
}
