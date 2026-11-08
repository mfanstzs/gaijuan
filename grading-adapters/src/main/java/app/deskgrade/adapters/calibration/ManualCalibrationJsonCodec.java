package app.deskgrade.adapters.calibration;

import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict position data. No persisted HWND, PID, target identity or permission fields are accepted.
 */
public final class ManualCalibrationJsonCodec {
  public static final int MAX_BYTES = 64 * 1024;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(1000)
                          .maxNumberLength(50)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public ManualPlatformCalibration read(String json) {
    try {
      if (json == null
          || json.length() > MAX_BYTES
          || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
        throw invalid();
      JsonNode root = MAPPER.readTree(json);
      int schemaVersion = integer(root.get("schemaVersion"));
      if (schemaVersion == 1) {
        fields(
            root,
            "schemaVersion",
            "id",
            "revision",
            "displayName",
            "layout",
            "answerRegion",
            "scoreRegion",
            "submitRegion",
            "nextRegion",
            "savedAt");
      } else if (schemaVersion == 2) {
        fields(
            root,
            "schemaVersion",
            "id",
            "revision",
            "displayName",
            "layout",
            "identityRegion",
            "answerRegion",
            "scoreRegion",
            "submitRegion",
            "nextRegion",
            "navigationMode",
            "savedAt");
      } else if (schemaVersion == 3) {
        fields(
            root,
            "schemaVersion",
            "id",
            "revision",
            "displayName",
            "layout",
            "identityRegion",
            "answerRegion",
            "scoreRegion",
            "submitRegion",
            "submissionAckRegion",
            "nextRegion",
            "navigationMode",
            "savedAt");
      } else if (schemaVersion == 4) {
        fields(
            root,
            "schemaVersion",
            "id",
            "revision",
            "displayName",
            "layout",
            "answerRegion",
            "scoreRegion",
            "submitRegion",
            "submissionAckRegion",
            "nextRegion",
            "navigationMode",
            "savedAt");
      } else throw invalid();
      JsonNode layout = root.get("layout");
      fields(layout, "clientWidthPixels", "clientHeightPixels", "dpi", "windowClass");
      LogicalRect next = root.get("nextRegion").isNull() ? null : rect(root.get("nextRegion"));
      LogicalRect legacyIdentity =
          (schemaVersion == 2 || schemaVersion == 3) && !root.get("identityRegion").isNull()
              ? rect(root.get("identityRegion"))
              : null;
      var parsed =
          new ManualPlatformCalibration(
              text(root.get("id"), 100),
              number(root.get("revision")),
              text(root.get("displayName"), 200),
              new ManualPlatformCalibration.WindowLayout(
                  integer(layout.get("clientWidthPixels")),
                  integer(layout.get("clientHeightPixels")),
                  integer(layout.get("dpi")),
                  text(layout.get("windowClass"), 256)),
              legacyIdentity,
              rect(root.get("answerRegion")),
              rect(root.get("scoreRegion")),
              rect(root.get("submitRegion")),
              schemaVersion < 3 || root.get("submissionAckRegion").isNull()
                  ? null
                  : rect(root.get("submissionAckRegion")),
              next,
              schemaVersion == 1
                  ? (next == null
                      ? ManualPlatformCalibration.NavigationMode.AUTO_AFTER_SUBMIT
                      : ManualPlatformCalibration.NavigationMode.CLICK_NEXT)
                  : ManualPlatformCalibration.NavigationMode.valueOf(
                      text(root.get("navigationMode"), 40)),
              Instant.parse(text(root.get("savedAt"), 50)));
      if (legacyIdentity == null) return parsed;
      return new ManualPlatformCalibration(
          parsed.id(),
          parsed.revision(),
          parsed.displayName(),
          parsed.layout(),
          null,
          parsed.answerRegion(),
          parsed.scoreRegion(),
          parsed.submitRegion(),
          parsed.submissionAckRegion(),
          parsed.nextRegion(),
          parsed.navigationMode(),
          parsed.savedAt());
    } catch (Exception failure) {
      throw invalid();
    }
  }

  public String write(ManualPlatformCalibration value) {
    if (value == null) throw invalid();
    var root =
        MAPPER
            .createObjectNode()
            .put("schemaVersion", 4)
            .put("id", value.id())
            .put("revision", value.revision())
            .put("displayName", value.displayName());
    root.putObject("layout")
        .put("clientWidthPixels", value.layout().clientWidthPixels())
        .put("clientHeightPixels", value.layout().clientHeightPixels())
        .put("dpi", value.layout().dpi())
        .put("windowClass", value.layout().windowClass());
    root.set("answerRegion", rect(value.answerRegion()));
    root.set("scoreRegion", rect(value.scoreRegion()));
    root.set("submitRegion", rect(value.submitRegion()));
    if (value.submissionAckRegion() == null) root.putNull("submissionAckRegion");
    else root.set("submissionAckRegion", rect(value.submissionAckRegion()));
    if (value.nextRegion() == null) root.putNull("nextRegion");
    else root.set("nextRegion", rect(value.nextRegion()));
    root.put("navigationMode", value.navigationMode().name());
    root.put("savedAt", value.savedAt().toString());
    return root.toPrettyString();
  }

  private static LogicalRect rect(JsonNode json) {
    fields(json, "x", "y", "width", "height");
    return new LogicalRect(
        decimal(json.get("x")),
        decimal(json.get("y")),
        decimal(json.get("width")),
        decimal(json.get("height")));
  }

  private static ObjectNode rect(LogicalRect value) {
    return MAPPER
        .createObjectNode()
        .put("x", value.x())
        .put("y", value.y())
        .put("width", value.width())
        .put("height", value.height());
  }

  private static void fields(JsonNode json, String... expected) {
    if (json == null || !json.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    json.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(expected))) throw invalid();
  }

  private static String text(JsonNode json, int max) {
    if (json == null
        || !json.isTextual()
        || json.textValue().isBlank()
        || json.textValue().length() > max) throw invalid();
    return json.textValue();
  }

  private static long number(JsonNode json) {
    if (json == null || !json.isIntegralNumber() || !json.canConvertToLong()) throw invalid();
    return json.longValue();
  }

  private static int integer(JsonNode json) {
    long value = number(json);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid();
    return (int) value;
  }

  private static double decimal(JsonNode json) {
    if (json == null || !json.isNumber() || !Double.isFinite(json.doubleValue())) throw invalid();
    return json.doubleValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("标定文件格式、版本或区域不合法；请重新标定。");
  }
}
