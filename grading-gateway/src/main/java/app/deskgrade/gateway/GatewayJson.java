package app.deskgrade.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class GatewayJson {
  static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(25)
                          .maxNumberLength(80)
                          .maxStringLength(14_000_000)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static JsonNode parse(byte[] bytes) {
    try {
      var value = MAPPER.readTree(bytes);
      if (value == null || !value.isObject()) throw new IllegalArgumentException();
      return value;
    } catch (Exception failure) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  static void fields(JsonNode node, String... required) {
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!node.isObject() || !actual.equals(Set.of(required)))
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
  }

  static String string(JsonNode node, String field, int min, int max) {
    var value = node.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().length() < min
        || value.textValue().length() > max)
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    return value.textValue();
  }

  static UUID uuid(JsonNode node, String field) {
    String value = string(node, field, 36, 36);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (Exception failure) {
      throw new GatewayException(GatewayException.Code.INVALID_REQUEST);
    }
  }

  private GatewayJson() {}
}
