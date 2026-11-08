package app.deskgrade.adapters.commercial;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;

final class CommercialJson {
  static final int MAX_RESPONSE = 1024 * 1024;
  static final int MAX_RECORD_RESPONSE = 8 * 1024 * 1024;
  static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(30)
                          .maxNumberLength(100)
                          .maxStringLength(MAX_RECORD_RESPONSE)
                          .build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private CommercialJson() {}

  static JsonNode parse(byte[] bytes) {
    return parse(bytes, MAX_RESPONSE);
  }

  static JsonNode parse(byte[] bytes, int maximum) {
    try {
      if ((maximum != MAX_RESPONSE && maximum != MAX_RECORD_RESPONSE)
          || bytes == null
          || bytes.length == 0
          || bytes.length > maximum) throw invalid();
      JsonNode node = MAPPER.readTree(bytes);
      if (node == null) throw invalid();
      return node;
    } catch (Exception failure) {
      throw invalid();
    }
  }

  static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  static void allowedFields(JsonNode node, Set<String> required, Set<String> allowed) {
    if (node == null || !node.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) throw invalid();
  }

  static String text(JsonNode node, int min, int max) {
    if (node == null || !node.isTextual()) throw invalid();
    String value = node.textValue();
    int count = value.codePointCount(0, value.length());
    if (count < min || count > max) throw invalid();
    return value;
  }

  static long nonnegative(JsonNode node) {
    if (node == null
        || !node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() < 0) throw invalid();
    return node.longValue();
  }

  static Long optionalLong(JsonNode node) {
    return node == null || node.isNull() ? null : nonnegative(node);
  }

  static boolean bool(JsonNode node) {
    if (node == null || !node.isBoolean()) throw invalid();
    return node.booleanValue();
  }

  static GatewayException invalid() {
    return new GatewayException(GatewayException.Code.INVALID_RESPONSE);
  }
}
