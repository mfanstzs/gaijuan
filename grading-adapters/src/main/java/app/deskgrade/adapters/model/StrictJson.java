package app.deskgrade.adapters.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;

final class StrictJson {
  static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(30)
                          .maxNumberLength(100)
                          .maxStringLength(262144)
                          .build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private StrictJson() {}

  static JsonNode parse(String json) {
    try {
      if (json == null || json.length() > 1048576) throw new IllegalArgumentException();
      JsonNode value = MAPPER.readTree(json);
      if (value == null) throw new IllegalArgumentException();
      return value;
    } catch (Exception e) {
      throw new ProviderException(ProviderException.Failure.INVALID_JSON);
    }
  }

  static void fields(JsonNode node, Set<String> expected) {
    if (!node.isObject()) bad();
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) bad();
  }

  static String string(JsonNode node, int min, int max, boolean nullable) {
    if (node == null) {
      bad();
    }
    if (nullable && node.isNull()) return null;
    if (!node.isTextual()) bad();
    String s = node.textValue();
    int len = s.codePointCount(0, s.length());
    if (len < min || len > max) bad();
    return s;
  }

  static void bad() {
    throw new ProviderException(ProviderException.Failure.INVALID_RESULT);
  }
}
