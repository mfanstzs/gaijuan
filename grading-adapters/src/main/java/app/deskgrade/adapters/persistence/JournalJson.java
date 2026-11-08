package app.deskgrade.adapters.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/** A bounded, fixed JSON wire format. No polymorphic types or image bytes are accepted. */
public final class JournalJson {
  public static final int MAX_BYTES = 1024 * 1024;
  public static final ObjectMapper MAPPER = create();

  private JournalJson() {}

  private static ObjectMapper create() {
    var module = new SimpleModule();
    temporal(module, Instant.class, Instant::parse);
    temporal(module, Duration.class, Duration::parse);
    return new ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(30)
                        .maxStringLength(MAX_BYTES)
                        .maxNumberLength(100)
                        .build())
                .build())
        .registerModule(module)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static <T> void temporal(SimpleModule module, Class<T> type, Function<String, T> parse) {
    module.addSerializer(
        type,
        new JsonSerializer<T>() {
          @Override
          public void serialize(T value, JsonGenerator out, SerializerProvider provider)
              throws IOException {
            out.writeString(value.toString());
          }
        });
    module.addDeserializer(
        type,
        new JsonDeserializer<T>() {
          @Override
          public T deserialize(JsonParser input, DeserializationContext context)
              throws IOException {
            if (!input.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING))
              throw new IOException("Invalid journal time");
            try {
              return parse.apply(input.getText());
            } catch (RuntimeException invalid) {
              throw new IOException("Invalid journal time");
            }
          }
        });
  }
}
