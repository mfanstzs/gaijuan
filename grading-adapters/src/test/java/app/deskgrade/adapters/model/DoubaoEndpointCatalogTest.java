package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class DoubaoEndpointCatalogTest {
  @Test
  void officialCandidateIsExactMetadataAndNotAnAccessOrCapabilityPromise() {
    assertEquals("doubao-seed-2-1-lite-260915", DoubaoEndpointCatalog.SEED_2_1_LITE);
    assertEquals(
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        DoubaoEndpointCatalog.ENDPOINT.toString());
    assertTrue(DoubaoEndpointCatalog.isOfficialDoubaoEndpoint(DoubaoEndpointCatalog.ENDPOINT));
    assertTrue(
        DoubaoEndpointCatalog.isOfficialDoubaoEndpoint(
            URI.create("https://ARK.CN-BEIJING.VOLCES.COM:443/api/v3/chat/completions")));
  }

  @Test
  void rejectsLookalikesRedirectHintsCredentialsAndDifferentApiSurfaces() {
    assertFalse(DoubaoEndpointCatalog.isOfficialDoubaoEndpoint(null));
    for (String url :
        List.of(
            "http://ark.cn-beijing.volces.com/api/v3/chat/completions",
            "https://ark.cn-beijing.volces.com.evil.example/api/v3/chat/completions",
            "https://ark.cn-beijing.volces.com:8443/api/v3/chat/completions",
            "https://user@ark.cn-beijing.volces.com/api/v3/chat/completions",
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions?redirect=elsewhere",
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions#fragment",
            "https://ark.cn-beijing.volces.com/api/v3/responses",
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions/"))
      assertFalse(DoubaoEndpointCatalog.isOfficialDoubaoEndpoint(URI.create(url)), url);
  }
}
