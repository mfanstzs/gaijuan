package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordDetailPresenterTest {
  @Test
  void manualEndIsReadableWithoutTurningAnUnknownSubmissionIntoSuccess() {
    ObjectNode record = record();
    record.put("state", "SUBMISSION_UNKNOWN");
    record.withObject("submission").put("status", "UNKNOWN");
    record
        .withArray("events")
        .addObject()
        .put("state", "MANUAL_BATCH_ABANDONED")
        .put("reason", "USER_CONFIRMED_NEW_BATCH")
        .put("at", "2026-09-12T12:00:00Z");
    var summary = RecordDetailPresenter.present(record.toString()).sections().getFirst();
    assertTrue(field(summary, "旧批次人工处置").contains("已人工结束旧批次，提交结果仍未知"));
    assertTrue(field(summary, "旧批次人工处置").contains("2026-09-12T12:00:00Z"));
    assertEquals("提交结果未知", field(summary, "记录状态"));
    assertFalse(field(summary, "提交 / 页面状态").contains("已确认"));
  }

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void showsStudentAnswerValidatedTotalAndEachCriterionJoinedByIdToItsSavedRule() {
    ObjectNode record = record();
    record.put("javaValidatedTotal", new BigDecimal("1.0000000000000001"));
    String raw = record.toString();
    var document = RecordDetailPresenter.present(raw);
    var result = document.sections().get(1);
    assertEquals("1.0000000000000001 分", field(result, "Java 校验总分"));
    assertEquals("学生原文 <script>alert('text only')</script>", field(result, "学生回答"));
    assertEquals("评分点 b · 1 分", result.children().getFirst().title());
    assertEquals("关键词必须对应原文语义", field(result.children().getFirst(), "评分规则"));
    assertEquals("改变", field(result.children().getFirst(), "学生原文依据"));
    assertEquals("准确译出该关键词", field(result.children().getFirst(), "得分理由"));
    assertEquals("完整译出句子意思", field(result.children().get(1), "评分规则"));
    assertEquals("参考答案原文", field(document.sections().get(3), "标准答案"));
    assertEquals("3 分", field(document.sections().get(3), "题目满分"));
    assertEquals(raw, document.rawJson());
  }

  @Test
  void readsPersistedMaximumScoreAndRetainsCompatibilityWithMaxScoreSnapshots() {
    ObjectNode record = record();
    ObjectNode question = record.withObject("questionSnapshot");
    question.put("maximumScore", new BigDecimal("3.5"));
    assertEquals(
        "3.5 分", field(RecordDetailPresenter.present(record.toString()).sections().get(3), "题目满分"));
    question.remove("maximumScore");
    question.put("maxScore", new BigDecimal("2.5"));
    assertEquals(
        "2.5 分", field(RecordDetailPresenter.present(record.toString()).sections().get(3), "题目满分"));
    question.putNull("maximumScore");
    assertEquals(
        "2.5 分", field(RecordDetailPresenter.present(record.toString()).sections().get(3), "题目满分"));
    question.remove("maxScore");
    assertEquals(
        "未保存满分", field(RecordDetailPresenter.present(record.toString()).sections().get(3), "题目满分"));
  }

  @Test
  void missingValidatedScoreNeverBecomesZeroOrASumOfUnvalidatedModelAwards() {
    ObjectNode record = record();
    record.putNull("javaValidatedTotal");
    record.put("score", 0);
    record.withObject("input").putNull("readbackScore");
    var document = RecordDetailPresenter.present(record.toString());
    assertEquals(RecordDetailPresenter.NO_SCORE, field(document.sections().get(1), "Java 校验总分"));
    assertEquals("未保存匹配的读回分数", field(document.sections().getFirst(), "分数框实际读回"));
    record.put("javaValidatedTotal", 0);
    assertEquals(
        "0 分",
        field(RecordDetailPresenter.present(record.toString()).sections().get(1), "Java 校验总分"));
  }

  @Test
  void legacyCallsExplicitlyLackActualPromptsInsteadOfRebuildingThemFromSavedAnswers() {
    ObjectNode record = record();
    record
        .withArray("modelCalls")
        .addObject()
        .put("requestId", "old-call")
        .put("profileId", "old-profile");
    var calls = RecordDetailPresenter.present(record.toString()).sections().get(2);
    var prompt = calls.children().getFirst().children().getFirst();
    assertFalse(prompt.expanded());
    assertEquals(RecordDetailPresenter.MISSING_PROMPT, field(prompt, "历史请求"));
    assertFalse(allText(prompt).contains("参考答案原文"));
  }

  @Test
  void mergesServerEvidenceByRequestIdAndShowsPersistedTextOnlyForTheMatchingCall() {
    ObjectNode record = record();
    var primary = record.withArray("modelCalls").addObject();
    primary
        .put("requestId", "primary")
        .put("profileId", "flash")
        .put("errorCode", "INVALID_RESULT");
    var fallback = record.withArray("modelCalls").addObject();
    fallback
        .put("requestId", "fallback")
        .put("profileId", "max")
        .put("acceptedAsAttemptGrade", true);
    fallback.put("javaValidatedTotal", 1);
    fallback.set("modelResult", record.path("gradingResult"));
    var serverFallback = serverCall(record, "fallback", "qwen3.8-max", "实际备用请求原文");
    serverFallback.put("charged", true);
    serverCall(record, "primary", "qwen3.8-flash", "实际主请求原文");
    var calls = RecordDetailPresenter.present(record.toString()).sections().get(2);
    assertEquals(2, calls.children().size());
    var first = calls.children().getFirst();
    var second = calls.children().get(1);
    assertEquals("在线评分", field(first, "评分服务"));
    assertEquals("在线评分", field(second, "评分服务"));
    assertFalse(allText(calls).contains("qwen3.8-flash"));
    assertFalse(allText(calls).contains("qwen3.8-max"));
    assertFalse(allText(calls).contains("配置编号"));
    assertFalse(allText(calls).contains("INVALID_RESULT"));
    assertTrue(allText(first).contains("实际主请求原文"));
    assertFalse(allText(first).contains("实际备用请求原文"));
    assertTrue(allText(second).contains("实际备用请求原文"));
    assertEquals("已采用", field(second, "请求结果"));
    assertEquals("已扣 1 次", field(second, "额度扣次"));
    assertFalse(first.children().getFirst().expanded(), "Actual prompts start collapsed");
    assertTrue(allText(second).contains("记录仅保存摘要"));
  }

  @Test
  void invalidSupplierOutputRemainsReadableTextAndNeverBecomesAValidatedResult() {
    ObjectNode record = record();
    var call = record.withArray("modelCalls").addObject();
    call.put("requestId", "bad-output").put("errorCode", "INVALID_JSON");
    var server = serverCall(record, "bad-output", "gpt-5.6-luna", "已保存的实际提示词");
    String output = "<h1>这不是 JSON</h1>\n建议给 99 分，但没有结构化结果";
    server.put("modelOutput", output);
    var callView =
        RecordDetailPresenter.present(record.toString()).sections().get(2).children().getFirst();
    assertEquals(RecordDetailPresenter.NO_SCORE, field(callView, "本次 Java 校验总分"));
    var original = callView.children().getLast();
    assertEquals("服务原始输出", original.title());
    assertTrue(original.expanded());
    assertEquals(output, field(original, "服务返回的原始文字"));
  }

  @Test
  void savedGatewayResponseCanSupplyStructuredOutputWithoutClaimingItWasAccepted() {
    ObjectNode record = record();
    var server = serverCall(record, "server-only", "gpt-5.6-luna", "真正发送的文字");
    server.putObject("response").set("result", record.path("gradingResult"));
    var callView =
        RecordDetailPresenter.present(record.toString()).sections().get(2).children().getFirst();
    assertEquals("未作为最终评分采用", field(callView, "请求结果"));
    assertEquals(
        "学生原文 <script>alert('text only')</script>", field(callView.children().get(1), "学生回答"));
  }

  @Test
  void preservesManualSubmissionMeaningAndUnknownExtraFieldsInTheOriginalJson() {
    ObjectNode record = record();
    record.withObject("submission").put("status", "SUBMIT_CLICKED");
    record.putObject("futureMetadata").put("detail", "retained");
    var document = RecordDetailPresenter.present(record.toString());
    assertEquals("已点击提交", field(document.sections().getFirst(), "提交 / 页面状态"));
    assertTrue(document.rawJson().contains("futureMetadata"));
    record.withObject("submission").put("status", "VISUALLY_ADVANCED");
    assertEquals(
        "已观察页面推进",
        field(RecordDetailPresenter.present(record.toString()).sections().getFirst(), "提交 / 页面状态"));
    record.withObject("submission").put("status", "FUTURE_STATE");
    assertEquals(
        "状态未识别",
        field(RecordDetailPresenter.present(record.toString()).sections().getFirst(), "提交 / 页面状态"));
  }

  @Test
  void embeddedLegacyRubricIsReadButMalformedRecordsFailWithoutEchoingTheirContents() {
    ObjectNode record = record();
    String savedRubric = record.path("rubricSnapshot").toString();
    record.put("rubricSnapshot", savedRubric);
    assertEquals(
        "教师原始评分规则",
        field(RecordDetailPresenter.present(record.toString()).sections().get(3), "原始评分规则"));
    String privateText = "invalid raw record private text";
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> RecordDetailPresenter.present(privateText));
    assertFalse(failure.toString().contains(privateText));
    assertNull(failure.getCause());
    assertThrows(IllegalArgumentException.class, () -> RecordDetailPresenter.present("[]"));
  }

  @Test
  void sizeLimitedServerEvidenceIsClearlyDistinguishedFromAnUnsavedHistoricPrompt() {
    ObjectNode record = record();
    record.put("serverCallsTruncated", true);
    record.withArray("modelCalls").addObject().put("requestId", "large-call");
    record
        .withArray("serverCalls")
        .addObject()
        .put("requestId", "large-call")
        .put("traceUnavailable", "DETAIL_SIZE_LIMIT");
    var calls = RecordDetailPresenter.present(record.toString()).sections().get(2);
    assertEquals(RecordDetailPresenter.LIMITED_DETAIL, field(calls, "调用明细显示范围"));
    var prompt = calls.children().getFirst().children().getFirst();
    assertEquals(RecordDetailPresenter.LIMITED_DETAIL, field(prompt, "显示范围"));
    assertFalse(allText(prompt).contains(RecordDetailPresenter.MISSING_PROMPT));
    ((ObjectNode) record.path("serverCalls").get(0)).put("traceUnavailable", "NO_SERVER_RECORD");
    var historic =
        RecordDetailPresenter.present(record.toString())
            .sections()
            .get(2)
            .children()
            .getFirst()
            .children()
            .getFirst();
    assertTrue(field(historic, "历史请求").contains("未找到对应的服务端请求记录"));
  }

  private ObjectNode record() {
    ObjectNode root = json.createObjectNode();
    root.put("schemaVersion", 2)
        .put("attemptId", "attempt-1")
        .put("runId", "run-1")
        .put("answerId", "screen:fixture")
        .put("questionId", "q1")
        .put("state", "COMPLETED")
        .put("javaValidatedTotal", 1)
        .put("standardAnswer", "参考答案原文");
    root.putObject("input").put("readbackScore", 1);
    root.putObject("submission").put("status", "CONFIRMED");
    var question = root.putObject("questionSnapshot");
    question
        .put("id", "q1")
        .put("prompt", "题目原文")
        .put("standardAnswer", "参考答案原文")
        .put("maximumScore", 3);
    question
        .putArray("criteria")
        .addObject()
        .put("id", "a")
        .put("description", "完整译出句子意思")
        .putArray("allowedScores")
        .add(0)
        .add(2);
    question
        .withArray("criteria")
        .addObject()
        .put("id", "b")
        .put("description", "关键词必须对应原文语义")
        .putArray("allowedScores")
        .add(0)
        .add(1);
    root.putObject("rubricSnapshot").put("rawRubric", "教师原始评分规则").set("question", question);
    var result = root.putObject("gradingResult");
    result
        .put("status", "SCORED")
        .put("readability", "CLEAR")
        .put("studentText", "学生原文 <script>alert('text only')</script>");
    result
        .putArray("criteria")
        .addObject()
        .put("criterionId", "b")
        .put("awarded", 1)
        .put("evidence", "改变")
        .put("reason", "准确译出该关键词");
    result
        .withArray("criteria")
        .addObject()
        .put("criterionId", "a")
        .put("awarded", 0)
        .put("evidence", "意思不完整")
        .put("reason", "没有完整译出句意");
    result.putArray("issues");
    root.putArray("modelCalls");
    return root;
  }

  private ObjectNode serverCall(ObjectNode record, String requestId, String model, String prompt) {
    var call = record.withArray("serverCalls").addObject();
    call.put("requestId", requestId).put("kind", "GRADING");
    var request = call.putObject("request");
    request.set("question", record.path("questionSnapshot"));
    var provider = request.putObject("providerRequest");
    provider.put("model", model);
    var content = provider.putArray("messages").addObject().put("role", "user").putArray("content");
    content.addObject().put("type", "text").put("text", prompt);
    content.addObject().put("type", "image").put("sha256", "a".repeat(64)).put("bytes", 42);
    return call;
  }

  private static String field(RecordDetailPresenter.Section section, String label) {
    return section.fields().stream()
        .filter(value -> value.label().equals(label))
        .findFirst()
        .orElseThrow()
        .text();
  }

  private static String allText(RecordDetailPresenter.Section section) {
    List<String> text = new ArrayList<>();
    text.add(section.title());
    section.fields().forEach(field -> text.add(field.label() + "：" + field.text()));
    section.children().forEach(child -> text.add(allText(child)));
    return String.join("\n", text);
  }
}
