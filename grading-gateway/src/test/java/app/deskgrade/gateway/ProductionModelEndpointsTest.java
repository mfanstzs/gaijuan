package app.deskgrade.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionModelEndpointsTest {
  @Test
  void acceptsExactOpenAiAndExistingDashScopeEndpoints() {
    for (String endpoint :
        List.of(
            "https://api.openai.com/v1/chat/completions",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")) {
      assertDoesNotThrow(() -> ProductionModelEndpoints.requireAllowed(URI.create(endpoint)));
    }
  }

  @Test
  void rejectsLookalikeHostsUserInfoSchemesAndExplicitPorts() {
    for (String endpoint :
        List.of(
            "https://api.openai.com.example.org/v1/chat/completions",
            "https://sub.api.openai.com/v1/chat/completions",
            "https://api.openai.com./v1/chat/completions",
            "https://api.openai.com@other.example/v1/chat/completions",
            "https://operator@api.openai.com/v1/chat/completions",
            "https://operator:example-password@api.openai.com/v1/chat/completions",
            "https://api.openai.com:443/v1/chat/completions",
            "https://api.openai.com:8443/v1/chat/completions",
            "http://api.openai.com/v1/chat/completions",
            "http://127.0.0.1/v1/chat/completions",
            "https://localhost/v1/chat/completions",
            "https://dashscope.aliyuncs.com.example.org/compatible-mode/v1/chat/completions",
            "https://operator@dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "https://dashscope.aliyuncs.com:443/compatible-mode/v1/chat/completions",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProductionModelEndpoints.requireAllowed(URI.create(endpoint)),
          endpoint);
    }
  }

  @Test
  void rejectsAlternatePathsEncodedAliasesAndQueryOrFragmentComponents() {
    for (String endpoint :
        List.of(
            "https://api.openai.com/v1/responses",
            "https://api.openai.com/v1/chat/completions/",
            "https://api.openai.com/v1/other/../chat/completions",
            "https://api.openai.com/v1//chat/completions",
            "https://api.openai.com/v1/%63hat/completions",
            "https://api.openai.com/v1/chat%2Fcompletions",
            "https://api.openai.com/v1/chat/completions?",
            "https://api.openai.com/v1/chat/completions?key=example",
            "https://api.openai.com/v1/chat/completions#",
            "https://api.openai.com/v1/chat/completions#anchor",
            "//api.openai.com/v1/chat/completions",
            "/v1/chat/completions",
            "https://dashscope.aliyuncs.com/v1/chat/completions",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions/",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions?",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions#")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProductionModelEndpoints.requireAllowed(URI.create(endpoint)),
          endpoint);
    }
  }

  @Test
  void rejectsMissingEndpointAndDoesNotEchoRejectedCredentialComponents() {
    var missing =
        assertThrows(
            IllegalArgumentException.class, () -> ProductionModelEndpoints.requireAllowed(null));
    var rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProductionModelEndpoints.requireAllowed(
                    URI.create(
                        "https://operator:example-password@api.openai.com/v1/chat/completions")));
    assertEquals(missing.getMessage(), rejected.getMessage());
    assertFalse(rejected.getMessage().contains("example-password"));
    assertFalse(rejected.getMessage().contains("operator"));
  }
}
