package app.deskgrade.desktop;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Explicit, documentation-confirmed Qwen choices; never infers capability from a Key or name. */
final class QwenThinkingOptions {
  enum Mode {
    KEEP("保持当前设置 / 供应商默认"),
    NONE("不思考"),
    LOW("少思考"),
    HIGH("多思考");

    private final String label;

    Mode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  record Parameters(Map<String, String> values, Set<String> allowed) {
    Parameters {
      values = Map.copyOf(values);
      allowed = Set.copyOf(allowed);
    }
  }

  private static final URI CONFIRMED_ENDPOINT =
      URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
  private static final Set<String> CONFIRMED_MODELS =
      Set.of("qwen3.8-flash", "qwen3.8-max", "qwen3.8-max-0902");

  private QwenThinkingOptions() {}

  static boolean supported(URI endpoint, String model) {
    return CONFIRMED_ENDPOINT.equals(endpoint) && model != null && CONFIRMED_MODELS.contains(model);
  }

  static boolean supported(String endpoint, String model) {
    try {
      return supported(URI.create(endpoint.strip()), model.strip());
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  static Parameters apply(
      URI endpoint, String model, Map<String, String> existing, Set<String> allowed, Mode mode) {
    Objects.requireNonNull(mode);
    if (mode == Mode.KEEP) return new Parameters(existing, allowed);
    if (!supported(endpoint, model)) throw new IllegalArgumentException("此接口或模型尚未确认支持千问三档思考设置。");
    var parameters = new TreeMap<>(existing);
    var names = new TreeSet<>(allowed);
    names.add("enable_thinking");
    parameters.put("enable_thinking", mode == Mode.NONE ? "false" : "true");
    if (mode == Mode.NONE) {
      parameters.remove("reasoning_effort");
      names.remove("reasoning_effort");
    } else {
      names.add("reasoning_effort");
      parameters.put("reasoning_effort", mode == Mode.LOW ? "low" : "xhigh");
    }
    return new Parameters(parameters, names);
  }

  static String summary(URI endpoint, String model, Map<String, String> parameters) {
    if (!supported(endpoint, model)) return "";
    if ("false".equals(parameters.get("enable_thinking"))) return "不思考";
    return switch (parameters.getOrDefault("reasoning_effort", "")) {
      case "low" -> "少思考";
      case "xhigh" -> "多思考";
      case "medium" -> "已有中档思考（保持当前）";
      default ->
          "true".equals(parameters.get("enable_thinking")) ? "已开启思考（强度使用供应商默认）" : "供应商默认（不额外指定档位）";
    };
  }
}
