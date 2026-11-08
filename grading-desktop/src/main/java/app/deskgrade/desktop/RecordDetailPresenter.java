package app.deskgrade.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Translates saved facts to customer-safe plain text. It never regenerates a historic request. */
final class RecordDetailPresenter {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final int MAX_CALLS = 20;
  private static final int MAX_CRITERIA = 100;
  static final String MISSING_PROMPT = "该历史记录未保存实际提示词。";
  static final String LIMITED_DETAIL = "该记录内容较长，部分调用明细未展开；可向管理员提供请求编号协助核对。";
  static final String NO_SCORE = "未获得可用评分（不是 0 分）";

  record Field(String label, String text) {}

  record Section(String title, List<Field> fields, List<Section> children, boolean expanded) {
    Section {
      fields = List.copyOf(fields);
      children = List.copyOf(children);
    }
  }

  record Document(List<Section> sections, String rawJson) {
    Document {
      sections = List.copyOf(sections);
    }
  }

  private RecordDetailPresenter() {}

  static Document present(String rawJson) {
    try {
      if (rawJson == null || rawJson.length() > 8 * 1024 * 1024)
        throw new IllegalArgumentException();
      JsonNode root = JSON.readTree(rawJson);
      if (root == null || !root.isObject()) throw new IllegalArgumentException();
      JsonNode question = embedded(root.path("questionSnapshot"));
      JsonNode rubric = embedded(root.path("rubricSnapshot"));
      if (!question.isObject()) question = rubric.path("question");
      JsonNode result = result(root.path("gradingResult"));
      var sections = new ArrayList<Section>();
      sections.add(
          section(
              "记录概况",
              false,
              List.of(
                  field("答卷编号", root.path("answerId")),
                  field("题号", root.path("questionId")),
                  field("批次编号", root.path("runId")),
                  field("记录编号", root.path("attemptId")),
                  field("捕获时间", root.path("capturedAt")),
                  new Field("记录状态", RecordsView.stateLabel(text(root.path("state")))),
                  new Field(
                      "提交 / 页面状态",
                      RecordsView.submissionLabel(text(root.path("submission").path("status")))),
                  new Field("旧批次人工处置", manualDisposition(root)),
                  new Field(
                      "分数框实际读回", number(root.path("input").path("readbackScore"), "未保存匹配的读回分数"))),
              List.of()));
      sections.add(
          section(
              "批改结果",
              true,
              List.of(
                  new Field("Java 校验总分", number(root.path("javaValidatedTotal"), NO_SCORE)),
                  new Field("学生回答", value(result.path("studentText"), "未保存学生回答")),
                  new Field("评分判读", resultStatus(text(result.path("status")))),
                  new Field("答题可读性", readability(text(result.path("readability")))),
                  new Field("问题说明", lines(result.path("issues"), "无已保存的问题说明"))),
              criteria(result.path("criteria"), question)));
      sections.add(calls(root, question));
      sections.add(
          section(
              "题目、标准答案与评分规则",
              true,
              List.of(
                  new Field("题目", value(question.path("prompt"), "未保存题目")),
                  new Field(
                      "标准答案",
                      value(
                          first(root.path("standardAnswer"), question.path("standardAnswer")),
                          "未保存标准答案")),
                  new Field(
                      "题目满分",
                      number(
                          first(question.path("maximumScore"), question.path("maxScore")),
                          "未保存满分")),
                  new Field("原始评分规则", value(rubric.path("rawRubric"), "未保存原始评分规则文本"))),
              savedRules(question)));
      return new Document(sections, rawJson);
    } catch (Exception invalid) {
      throw new IllegalArgumentException("记录格式无法解析，请保留原始导出并联系管理员。");
    }
  }

  private static String manualDisposition(JsonNode root) {
    for (JsonNode event : array(root.path("events"))) {
      if ("MANUAL_BATCH_ABANDONED".equals(text(event.path("state"))))
        return "已人工结束旧批次，提交结果仍未知；原评分与防重复记录保留。\n处置时间：" + value(event.path("at"), "未记录");
    }
    return "未记录人工结束决定";
  }

  private static Section calls(JsonNode root, JsonNode question) {
    var serverById = new LinkedHashMap<String, JsonNode>();
    for (JsonNode server : array(root.path("serverCalls"))) {
      String requestId = text(server.path("requestId"));
      if (!requestId.isBlank()) serverById.putIfAbsent(requestId, server);
    }
    var entries = new ArrayList<Section>();
    for (JsonNode call : array(root.path("modelCalls"))) {
      if (entries.size() >= MAX_CALLS) break;
      String requestId = text(first(call.path("requestId"), call.path("callId")));
      JsonNode server = serverById.remove(requestId);
      entries.add(call(entries.size() + 1, call, server, question));
    }
    for (JsonNode server : serverById.values()) {
      if (entries.size() >= MAX_CALLS) break;
      entries.add(call(entries.size() + 1, JSON.createObjectNode(), server, question));
    }
    var fields = new ArrayList<Field>();
    if (entries.isEmpty()) fields.add(new Field("请求记录", "未保存逐次在线评分请求与返回记录。"));
    if (root.path("serverCallsTruncated").asBoolean(false))
      fields.add(new Field("调用明细显示范围", LIMITED_DETAIL));
    if (root.path("modelCalls").size() > MAX_CALLS || root.path("serverCalls").size() > MAX_CALLS)
      fields.add(new Field("显示范围", "最多展示前 20 次请求；其余内容可由管理员按请求编号核对。"));
    return section("在线评分请求与返回（" + entries.size() + " 次）", true, fields, entries);
  }

  private static Section call(
      int ordinal, JsonNode call, JsonNode savedServer, JsonNode snapshotQuestion) {
    JsonNode server = savedServer == null ? JSON.createObjectNode() : savedServer;
    JsonNode request = embedded(server.path("request"));
    JsonNode actualQuestion =
        request.path("question").isObject() ? request.path("question") : snapshotQuestion;
    String requestId =
        text(first(call.path("requestId"), call.path("callId"), server.path("requestId")));
    boolean accepted = call.path("acceptedAsAttemptGrade").asBoolean(false);
    String error = text(first(call.path("errorCode"), server.path("errorCode")));
    String state = accepted ? "已采用" : !error.isBlank() ? "调用失败" : "未作为最终评分采用";
    var fields = new ArrayList<Field>();
    fields.add(new Field("请求编号", requestId.isBlank() ? "未记录" : requestId));
    fields.add(new Field("评分服务", "在线评分"));
    fields.add(new Field("请求结果", state));
    fields.add(
        new Field("开始时间", value(first(call.path("startedAt"), server.path("createdAt")), "未记录")));
    fields.add(new Field("返回时间", value(call.path("completedAt"), "未记录")));
    fields.add(new Field("本次 Java 校验总分", number(call.path("javaValidatedTotal"), NO_SCORE)));
    fields.add(new Field("校验结果", validation(text(call.path("validationStatus")))));
    if (!error.isBlank()) fields.add(new Field("失败原因", "本次请求未获得可用结果"));
    if (server.path("charged").isBoolean())
      fields.add(new Field("额度扣次", server.path("charged").asBoolean() ? "已扣 1 次" : "未扣次"));
    if (call.path("requestIdMatched").isBoolean())
      fields.add(
          new Field("返回请求编号核对", call.path("requestIdMatched").asBoolean() ? "一致" : "不一致，不能采用"));
    JsonNode modelResult = result(call.path("modelResult"));
    if (!modelResult.isObject()) modelResult = result(embedded(server.path("response")));
    String originalOutput = text(server.path("modelOutput"));
    if (!modelResult.isObject() && !originalOutput.isBlank())
      modelResult = result(embedded(server.path("modelOutput")));
    var children = new ArrayList<Section>();
    children.add(actualPrompt(request, server));
    if (modelResult.isObject()) {
      children.add(
          section(
              "评分返回内容",
              true,
              List.of(
                  new Field("学生回答", value(modelResult.path("studentText"), "未返回学生回答")),
                  new Field("判读状态", resultStatus(text(modelResult.path("status")))),
                  new Field("答题可读性", readability(text(modelResult.path("readability")))),
                  new Field("问题说明", lines(modelResult.path("issues"), "无已保存的问题说明"))),
              criteria(modelResult.path("criteria"), actualQuestion)));
    } else
      children.add(
          section("评分返回内容", true, List.of(new Field("结构化结果", "没有可展示的结构化评分结果。")), List.of()));
    if (!originalOutput.isBlank())
      children.add(
          section(
              "服务原始输出",
              !modelResult.isObject(),
              List.of(new Field("服务返回的原始文字", originalOutput)),
              List.of()));
    return section("第 " + ordinal + " 次 · 在线评分 · " + state, false, fields, children);
  }

  private static Section actualPrompt(JsonNode request, JsonNode server) {
    if ("DETAIL_SIZE_LIMIT".equals(text(server.path("traceUnavailable"))))
      return section("实际提示词与调用明细", false, List.of(new Field("显示范围", LIMITED_DETAIL)), List.of());
    var messages = new ArrayList<Section>();
    JsonNode provider = request.path("providerRequest");
    for (JsonNode message : array(provider.path("messages"))) {
      if (messages.size() >= 20) break;
      var fields = new ArrayList<Field>();
      JsonNode content = message.path("content");
      if (content.isTextual()) fields.add(new Field("实际发送文字", content.textValue()));
      else
        for (JsonNode part : array(content)) {
          if (fields.size() >= 100) break;
          if ("text".equals(text(part.path("type"))) && part.path("text").isTextual())
            fields.add(new Field("实际发送文字", part.path("text").textValue()));
          else if ("image".equals(text(part.path("type"))))
            fields.add(
                new Field(
                    "答题图片",
                    "实际请求包含图片；记录仅保存摘要，不保存或展示图片。\nSHA-256："
                        + value(part.path("sha256"), "未记录")
                        + "\n字节数："
                        + value(part.path("bytes"), "未记录")));
        }
      messages.add(section(role(text(message.path("role"))), true, fields, List.of()));
    }
    if (messages.isEmpty())
      return section(
          "实际提示词",
          false,
          List.of(
              new Field(
                  "历史请求",
                  MISSING_PROMPT
                      + ("NO_SERVER_RECORD".equals(text(server.path("traceUnavailable")))
                          ? "未找到对应的服务端请求记录。"
                          : ""))),
          List.of());
    return section("实际提示词（已保存的请求原文）", false, List.of(new Field("请求类型", "在线评分")), messages);
  }

  private static List<Section> criteria(JsonNode results, JsonNode question) {
    Map<String, JsonNode> rules = ruleMap(question);
    var entries = new ArrayList<Section>();
    for (JsonNode criterion : array(results)) {
      if (entries.size() >= MAX_CRITERIA) break;
      String id = value(first(criterion.path("criterionId"), criterion.path("id")), "未记录编号");
      JsonNode rule = rules.get(id);
      entries.add(
          section(
              "评分点 " + id + " · " + number(criterion.path("awarded"), "未给出分数"),
              true,
              List.of(
                  new Field("评分规则", rule == null ? "该评分点没有对应的已保存规则" : ruleDescription(rule)),
                  new Field("得分", number(criterion.path("awarded"), "未给出分数（不是 0 分）")),
                  new Field("学生原文依据", value(criterion.path("evidence"), "未提供学生原文依据")),
                  new Field("得分理由", value(criterion.path("reason"), "未提供得分理由"))),
              List.of()));
    }
    return entries;
  }

  private static List<Section> savedRules(JsonNode question) {
    var entries = new ArrayList<Section>();
    for (var entry : ruleMap(question).entrySet()) {
      entries.add(
          section(
              "评分点 " + entry.getKey(),
              false,
              List.of(
                  new Field("评分规则", ruleDescription(entry.getValue())),
                  new Field("允许分数", lines(entry.getValue().path("allowedScores"), "未保存允许分数"))),
              List.of()));
    }
    return entries;
  }

  private static Map<String, JsonNode> ruleMap(JsonNode question) {
    var rules = new LinkedHashMap<String, JsonNode>();
    for (JsonNode rule : array(question.path("criteria"))) {
      if (rules.size() >= MAX_CRITERIA) break;
      String id = text(rule.path("id"));
      if (!id.isBlank()) rules.putIfAbsent(id, rule);
    }
    return rules;
  }

  private static String ruleDescription(JsonNode rule) {
    return value(first(rule.path("description"), rule.path("rule")), "未保存该评分点规则");
  }

  private static JsonNode result(JsonNode node) {
    node = embedded(node);
    for (int depth = 0; depth < 4 && node.isObject(); depth++) {
      if (node.has("criteria") || node.has("studentText")) return node;
      node = embedded(node.path("result"));
    }
    return JSON.nullNode();
  }

  private static JsonNode embedded(JsonNode node) {
    if (node.isTextual()) {
      try {
        JsonNode parsed = JSON.readTree(node.textValue());
        return parsed == null ? node : parsed;
      } catch (Exception invalid) {
        return node;
      }
    }
    return node;
  }

  private static Iterable<JsonNode> array(JsonNode node) {
    return node.isArray() ? node : List.of();
  }

  private static JsonNode first(JsonNode... nodes) {
    for (JsonNode node : nodes) if (!node.isMissingNode() && !node.isNull()) return node;
    return JSON.nullNode();
  }

  private static String text(JsonNode node) {
    return node.isValueNode() && !node.isNull() ? node.asText() : "";
  }

  private static String value(JsonNode node, String fallback) {
    String value = text(node);
    return value.isBlank() ? fallback : value;
  }

  private static String number(JsonNode node, String fallback) {
    return node.isNumber()
        ? node.decimalValue().stripTrailingZeros().toPlainString() + " 分"
        : fallback;
  }

  private static String lines(JsonNode node, String fallback) {
    if (!node.isArray() || node.isEmpty()) return fallback;
    var values = new ArrayList<String>();
    for (JsonNode item : node)
      if (values.size() < 100 && item.isValueNode()) values.add(text(item));
    return values.isEmpty() ? fallback : String.join("\n", values);
  }

  private static Field field(String label, JsonNode node) {
    return new Field(label, value(node, "未记录"));
  }

  private static Section section(
      String title, boolean expanded, List<Field> fields, List<Section> children) {
    return new Section(title, fields, children, expanded);
  }

  private static String role(String role) {
    return switch (role) {
      case "system" -> "系统指令";
      case "user" -> "用户请求";
      case "assistant" -> "助手上下文";
      default -> "请求消息";
    };
  }

  private static String resultStatus(String status) {
    return switch (status) {
      case "SCORED" -> "已获得评分";
      case "UNABLE" -> "无法可靠评分";
      default -> "未保存判读状态";
    };
  }

  private static String readability(String value) {
    return switch (value) {
      case "CLEAR" -> "清晰";
      case "PARTIAL" -> "部分可辨认";
      case "UNREADABLE" -> "无法辨认";
      default -> "未保存可读性判断";
    };
  }

  private static String validation(String value) {
    return switch (value) {
      case "VALID", "VALIDATED", "SCORED" -> "通过校验";
      case "INVALID", "INVALID_RESULT" -> "未通过校验";
      case "UNABLE" -> "无法可靠评分";
      case "" -> "未保存校验结果";
      default -> value;
    };
  }
}
