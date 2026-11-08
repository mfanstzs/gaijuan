package app.deskgrade.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.ProviderProfile;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAICompatibleVisionProviderTest {
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private final List<OpenAICompatibleVisionProvider> providers = new ArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> responseBody = new AtomicReference<>();
  private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
  private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger delayMs = new AtomicInteger();
  private final CountDownLatch arrived = new CountDownLatch(1);
  private URI endpoint;
  private ProviderProfile configured;
  private byte[] png;
  private final SecretStore secrets =
      new SecretStore() {
        @Override
        public void put(String id, char[] secret) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Optional<char[]> read(String id) {
          return Optional.of("synthetic-fixture-key".toCharArray());
        }

        @Override
        public void delete(String id) {
          throw new UnsupportedOperationException();
        }
      };

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          arrived.countDown();
          capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          capturedRequest.set(
              StrictJson.parse(
                  new String(
                      exchange.getRequestBody().readNBytes(2000000), StandardCharsets.UTF_8)));
          try {
            if (delayMs.get() > 0) Thread.sleep(delayMs.get());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
          try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
          } catch (java.io.IOException ignored) {
            /* Expected after client cancellation. */
          } finally {
            exchange.close();
          }
        });
    server.start();
    endpoint =
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    configured = ProfileAndRubricCodecTest.profile(endpoint);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", bytes);
    png = bytes.toByteArray();
    responseBody.set(wire(GradeResultJsonCodecTest.VALID, "stop", null));
  }

  @AfterEach
  void close() {
    providers.forEach(OpenAICompatibleVisionProvider::close);
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void recordsTheActualTextAndUnparsedOutputWithoutImagesOrCredentials() throws Exception {
    var requestAudit = new AtomicReference<JsonNode>();
    var outputAudit = new AtomicReference<String>();
    var profile =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var trace =
        new ModelCallTrace() {
          @Override
          public void requestPrepared(String json) {
            requestAudit.set(StrictJson.parse(json));
          }

          @Override
          public void outputReceived(String output) {
            outputAudit.set(output);
          }
        };
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            auditRubric(),
            secrets,
            Clock.systemUTC(),
            () -> assertNotNull(requestAudit.get()),
            trace);
    providers.add(provider);
    provider
        .grade(
            new GradingRequest(UUID.randomUUID(), auditRubric().question(), png),
            CancellationToken.none())
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
    assertEquals(
        capturedRequest.get().path("messages").get(0), requestAudit.get().path("messages").get(0));
    assertEquals(
        capturedRequest.get().path("messages").get(1).path("content").get(0),
        requestAudit.get().path("messages").get(1).path("content").get(0));
    assertEquals(
        "image",
        requestAudit.get().path("messages").get(1).path("content").get(1).path("type").asText());
    assertEquals(
        64,
        requestAudit
            .get()
            .path("messages")
            .get(1)
            .path("content")
            .get(1)
            .path("sha256")
            .asText()
            .length());
    assertFalse(requestAudit.get().toString().contains(Base64.getEncoder().encodeToString(png)));
    assertFalse(requestAudit.get().toString().contains("synthetic-fixture-key"));
    assertEquals(GradeResultJsonCodecTest.VALID, outputAudit.get());
  }

  @Test
  void failedRequestAuditPreventsAuthorizationAndNetworkDispatch() {
    var authorized = new AtomicBoolean();
    var profile =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            auditRubric(),
            secrets,
            Clock.systemUTC(),
            () -> authorized.set(true),
            new ModelCallTrace() {
              @Override
              public void requestPrepared(String json) {
                throw new IllegalStateException("unavailable");
              }
            });
    providers.add(provider);
    var error =
        assertThrows(
            CompletionException.class,
            () ->
                provider
                    .grade(
                        new GradingRequest(UUID.randomUUID(), auditRubric().question(), png),
                        CancellationToken.none())
                    .toCompletableFuture()
                    .join());
    var sanitized = assertInstanceOf(ProviderException.class, error.getCause());
    assertEquals("MODEL_INTERNAL_ERROR", sanitized.diagnosticCode());
    assertNull(sanitized.getCause());
    assertFalse(authorized.get());
    assertEquals(0, calls.get());
  }

  @Test
  void retainsTruncatedModelTextBeforeRejectingItAsAGrade() {
    String partial = "{\"studentText\":\"未完成";
    responseBody.set(wire(partial, "length", null));
    var output = new AtomicReference<String>();
    var profile =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            auditRubric(),
            secrets,
            Clock.systemUTC(),
            () -> {},
            new ModelCallTrace() {
              @Override
              public void outputReceived(String text) {
                output.set(text);
              }
            });
    providers.add(provider);
    assertThrows(
        CompletionException.class,
        () ->
            provider
                .grade(
                    new GradingRequest(UUID.randomUUID(), auditRubric().question(), png),
                    CancellationToken.none())
                .toCompletableFuture()
                .join());
    assertEquals(partial, output.get());
  }

  private Rubric auditRubric() {
    return new Rubric(GradeResultJsonCodecTest.QUESTION, "合成原始规则", BigDecimal.ONE, true, false);
  }

  private OpenAICompatibleVisionProvider provider(ProviderProfile profile, boolean verified) {
    if (verified)
      profile =
          profile.withVerification(
              new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            new Rubric(GradeResultJsonCodecTest.QUESTION, "合成原始规则", BigDecimal.ONE, true, false),
            secrets,
            Clock.systemUTC());
    providers.add(provider);
    return provider;
  }

  private GradingRequest request() {
    return new GradingRequest(UUID.randomUUID(), GradeResultJsonCodecTest.QUESTION, png);
  }

  private OpenAICompatibleVisionProvider providerWithDispatch(Runnable beforeDispatch) {
    var verified =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            verified,
            new Rubric(GradeResultJsonCodecTest.QUESTION, "合成原始规则", BigDecimal.ONE, true, false),
            secrets,
            Clock.systemUTC(),
            beforeDispatch);
    providers.add(provider);
    return provider;
  }

  @Test
  void failedDispatchAuthorizationPreventsOutboundHttp() {
    var charges = new AtomicInteger();
    var provider =
        providerWithDispatch(
            () -> {
              charges.incrementAndGet();
              throw new IllegalStateException("Synthetic quota refusal");
            });
    failure(provider, CancellationToken.none());
    assertEquals(1, charges.get());
    assertEquals(0, calls.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 401, 429, 500})
  void dispatchAuthorizationRunsOnceBeforeHttpEvenWhenProviderFails(int responseStatus) {
    status.set(responseStatus);
    var charges = new AtomicInteger();
    var provider =
        providerWithDispatch(
            () -> {
              assertEquals(0, calls.get(), "Debit must precede provider HTTP");
              charges.incrementAndGet();
            });
    if (responseStatus == 200)
      provider.grade(request(), CancellationToken.none()).toCompletableFuture().join();
    else failure(provider, CancellationToken.none());
    assertEquals(1, charges.get());
    assertEquals(1, calls.get());
  }

  @Test
  void invalidImageAndPreCancelledRequestNeverAuthorizeDispatch() {
    var charges = new AtomicInteger();
    var provider = providerWithDispatch(charges::incrementAndGet);
    var invalid =
        new GradingRequest(UUID.randomUUID(), GradeResultJsonCodecTest.QUESTION, new byte[] {1, 2});
    assertThrows(
        CompletionException.class,
        () -> provider.grade(invalid, CancellationToken.none()).toCompletableFuture().join());
    failure(provider, () -> true);
    assertEquals(0, charges.get());
    assertEquals(0, calls.get());
  }

  private static String wire(String content, String finish, JsonNode usage) {
    var root = StrictJson.MAPPER.createObjectNode();
    var choice = root.putArray("choices").addObject();
    choice.put("finish_reason", finish);
    choice.putObject("message").put("role", "assistant").put("content", content);
    if (usage == null) root.putNull("usage");
    else root.set("usage", usage);
    return root.toString();
  }

  private ProviderException failure(
      OpenAICompatibleVisionProvider provider, CancellationToken token) {
    var error =
        assertThrows(
            CompletionException.class,
            () -> provider.grade(request(), token).toCompletableFuture().join());
    return assertInstanceOf(ProviderException.class, error.getCause());
  }

  @Test
  void sendsActualPngAndFixedFreshMessagesWithoutUndeclaredParameters() {
    var request = request();
    var response =
        provider(configured, true)
            .grade(request, CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertEquals(request.requestId(), response.requestId());
    assertEquals(GradingResponse.Source.LIVE, response.source());
    assertNull(response.usage());
    assertNotNull(response.elapsed());
    JsonNode body = capturedRequest.get();
    assertEquals(2, body.get("messages").size());
    assertEquals("synthetic-fixture-model", body.get("model").textValue());
    assertEquals("json_object", body.path("response_format").path("type").asText());
    assertFalse(body.has("tools"));
    assertFalse(body.has("temperature"));
    assertFalse(body.has("enable_thinking"));
    assertFalse(body.has("max_tokens"));
    String systemText = body.path("messages").get(0).path("content").asText();
    assertTrue(systemText.contains("邻题残片"), systemText);
    assertTrue(systemText.contains("不向你提供题目原文"), systemText);
    String userText = body.path("messages").get(1).path("content").get(0).path("text").asText();
    assertTrue(userText.contains("用户手动框选并确认"), userText);
    assertTrue(userText.contains("合成标准"), userText);
    assertTrue(userText.contains("满足条件得1分"), userText);
    assertFalse(userText.contains("合成原文"), userText);
    assertFalse(userText.contains("合成原始规则"), userText);
    assertFalse(userText.contains("sourceText"), userText);
    assertFalse(userText.contains("rawRubric"), userText);
    assertFalse(userText.contains("requiresSourceText"), userText);
    assertFalse(userText.contains("missingSourceAccepted"), userText);
    var data =
        body.path("messages")
            .get(1)
            .path("content")
            .get(1)
            .path("image_url")
            .path("url")
            .textValue();
    assertTrue(data.startsWith("data:image/png;base64,"));
    assertArrayEquals(png, Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)));
    assertEquals("Bearer synthetic-fixture-key", capturedAuthorization.get());
    assertEquals(1, calls.get());
  }

  @Test
  void conciseOutputPolicyPreservesFullInputContractAndNeverTruncatesModelFields() {
    String reference = "仅在条件甲成立时，不能据此认定所有情况均适用；条件乙不成立时不得作上述结论。".repeat(20);
    String firstRule = "表达条件甲成立才适用的限定，并保留不能推广至所有情况的否定含义得1分，否则0分。";
    String secondRule = "明确条件乙不成立时不能作该结论得1分，省略条件或否定含义得0分。";
    var question =
        new QuestionDefinition(
            "synthetic-context",
            1,
            "不向模型提交的合成题干",
            reference,
            new BigDecimal("2"),
            List.of(
                new ScoringCriterion("P1", firstRule, List.of(BigDecimal.ZERO, BigDecimal.ONE)),
                new ScoringCriterion("P2", secondRule, List.of(BigDecimal.ZERO, BigDecimal.ONE))));
    String studentUnit = "只有条件甲成立时才适用，不能认定所有情况均适用；若条件乙不成立，则……不能作上述结论。\n";
    String student = studentUnit.repeat(30);
    String evidence = studentUnit.repeat(8);
    String reason = "本点需结合否定词、适用范围与转折后的条件共同判断，不能只摘取肯定词。".repeat(10);
    var content = StrictJson.MAPPER.createObjectNode();
    content.put("status", "SCORED");
    content.put("readability", "CLEAR");
    content.put("studentText", student);
    var criteria = content.putArray("criteria");
    var first = criteria.addObject();
    first.put("criterionId", "P1");
    first.put("awarded", 1);
    first.put("evidence", evidence);
    first.put("reason", reason);
    var second = criteria.addObject();
    second.put("criterionId", "P2");
    second.put("awarded", 0);
    second.putNull("evidence");
    second.put("reason", "该点未满足，不能以其他点的分数代替本点判断。");
    content.putArray("issues");
    responseBody.set(wire(content.toString(), "stop", null));
    var profile =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            new Rubric(question, "不向模型提交的合成原始规则", BigDecimal.ONE, false, true),
            secrets,
            Clock.systemUTC());
    providers.add(provider);

    var response =
        provider
            .grade(new GradingRequest(UUID.randomUUID(), question, png), CancellationToken.none())
            .toCompletableFuture()
            .join();

    // Conciseness is a model instruction, never an output truncator or a stricter field validator.
    assertEquals(student, response.result().studentText());
    assertEquals(evidence, response.result().criteria().getFirst().evidence());
    assertEquals(reason, response.result().criteria().getFirst().reason());
    assertEquals(
        List.of("P1", "P2"),
        response.result().criteria().stream()
            .map(GradeResult.CriterionResult::criterionId)
            .toList());
    assertEquals(BigDecimal.ZERO, response.result().criteria().getLast().awarded());
    var body = capturedRequest.get();
    String system = body.path("messages").get(0).path("content").asText();
    for (String required :
        List.of(
            "评分准确性优先",
            "完整、忠实",
            "否定、限定、条件、主体、指代、转折",
            "必要上下文",
            "不重复整段标准答案",
            "允许补充足够说明",
            "不得省略未得分的评分点",
            "保留 issues")) assertTrue(system.contains(required), required);
    assertTrue(system.contains("不可信的待评分材料"));
    assertTrue(system.contains("每个 criterionId 必须且只出现一次"));
    assertTrue(system.contains("证据和依据优先简洁"));
    assertTrue(system.contains("关键否定、主体、条件、因果、转折及必要上下文"));
    assertTrue(system.contains("不得为压缩成一句话而改变含义或遗漏判断依据"));
    assertFalse(system.contains("只保留简短学生原文证据和一句话依据"));
    for (String required :
        List.of(
            "deskgrade-grading-v6-score-boundaries-preserve-context",
            "明确的零分条件、排除条件和前置要求",
            "区分“不得满分”和“不得分”",
            "只有规则明确规定跨点评分前置关系时",
            "allowedScores 和步长只限定可返回的数值",
            "实际满足和未满足的具体要求",
            "不得违反明确零分条件",
            "不得擅自把既有允许中间分的规则改成只有零分或满分",
            "不得另加原规则没有的必答条件")) assertTrue(system.contains(required), required);
    assertFalse(system.contains("deskgrade-grading-v5"));
    String user = body.path("messages").get(1).path("content").get(0).path("text").asText();
    String inputMarker = "评分输入（仅标准答案与结构化评分点）：\n";
    String schemaMarker = "\nJSON契约：\n";
    int inputStart = user.indexOf(inputMarker) + inputMarker.length();
    int schemaStart = user.indexOf(schemaMarker, inputStart);
    JsonNode input = StrictJson.parse(user.substring(inputStart, schemaStart));
    assertEquals(reference, input.path("referenceAnswer").asText());
    assertEquals(2, input.path("criteria").size());
    assertEquals("P1", input.path("criteria").get(0).path("id").asText());
    assertEquals(firstRule, input.path("criteria").get(0).path("rule").asText());
    assertEquals(secondRule, input.path("criteria").get(1).path("rule").asText());
    assertEquals(StrictJson.parse("[0,1]"), input.path("criteria").get(0).path("allowedScores"));
    assertEquals(StrictJson.parse("[0,1]"), input.path("criteria").get(1).path("allowedScores"));
    assertEquals(
        new GradeResultJsonCodec().providerSchema(),
        StrictJson.parse(user.substring(schemaStart + schemaMarker.length())));
    assertEquals("json_object", body.path("response_format").path("type").asText());
    assertFalse(body.path("stream").asBoolean());
    assertFalse(body.has("max_tokens"));
    assertFalse(body.has("temperature"));
    assertFalse(body.has("enable_thinking"));
    assertFalse(user.contains("不向模型提交的合成题干"));
    assertFalse(user.contains("不向模型提交的合成原始规则"));
    assertEquals(1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"BLANK", "PARTIAL_UNABLE", "UNREADABLE"})
  void conciseOutputPolicyPreservesBlankAndUnableDiagnosticsWithoutCoercingScores(String kind) {
    boolean blank = kind.equals("BLANK");
    boolean partial = kind.equals("PARTIAL_UNABLE");
    String issue = "当前答题区第二行中部的否定词无法辨认，不能确定该条件下的结论。";
    String student = partial ? "只有条件甲成立时，[中部否定词无法辨认]得出该结论。" : null;
    var content = StrictJson.MAPPER.createObjectNode();
    content.put("status", blank ? "SCORED" : "UNABLE");
    content.put("readability", blank ? "CLEAR" : partial ? "PARTIAL" : "UNREADABLE");
    if (blank) content.put("studentText", "");
    else if (partial) content.put("studentText", student);
    else content.putNull("studentText");
    var criterion = content.putArray("criteria").addObject();
    criterion.put("criterionId", "a");
    if (blank) criterion.put("awarded", 0);
    else criterion.putNull("awarded");
    criterion.putNull("evidence");
    criterion.put("reason", blank ? "答题区完整清晰但未作答。" : issue);
    var issues = content.putArray("issues");
    if (!blank) issues.add(issue);
    responseBody.set(wire(content.toString(), "stop", null));

    var result =
        provider(configured, true)
            .grade(request(), CancellationToken.none())
            .toCompletableFuture()
            .join()
            .result();

    assertEquals(blank ? GradeResult.Status.SCORED : GradeResult.Status.UNABLE, result.status());
    assertEquals(
        blank
            ? GradeResult.Readability.CLEAR
            : partial ? GradeResult.Readability.PARTIAL : GradeResult.Readability.UNREADABLE,
        result.readability());
    assertEquals(blank ? "" : student, result.studentText());
    assertEquals(blank ? List.of() : List.of(issue), result.issues());
    if (blank) assertEquals(BigDecimal.ZERO, result.criteria().getFirst().awarded());
    else assertNull(result.criteria().getFirst().awarded());
    String user =
        capturedRequest.get().path("messages").get(1).path("content").get(0).path("text").asText();
    assertTrue(user.contains("绝不能据此补0"));
    assertTrue(user.contains("任一评分点不允许0，不得虚构0分"));
    assertTrue(user.contains("不要求四条黑框边均入图"));
    assertTrue(user.contains("本请求按设计不提供题目原文"));
    assertEquals(1, calls.get());
  }

  @Test
  void rubricGenerationIsTextOnlyAuthorizedOnceAndLocallyValidated() {
    responseBody.set(
        wire(
            """
            {"scoreStep":1,"criteria":[
              {"id":"P1","rule":"表达第一层意思得1分。","allowedScores":[0,1]},
              {"id":"P2","rule":"表达其余三层意思，每层1分。","allowedScores":[0,1,2,3]}]}
            """,
            "stop",
            StrictJson.parse("{\"prompt_tokens\":20,\"completion_tokens\":10}")));
    var charges = new AtomicInteger();
    var verified =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var generator =
        new OpenAICompatibleVisionProvider(
            verified, secrets, Clock.systemUTC(), charges::incrementAndGet);
    providers.add(generator);

    var generated =
        generator
            .generateRubric("历史", "标准答案内容", new BigDecimal("4"), CancellationToken.none())
            .toCompletableFuture()
            .join();

    assertEquals(BigDecimal.ONE, generated.scoreStep());
    assertEquals(2, generated.criteria().size());
    assertEquals(new BigDecimal("3"), generated.criteria().get(1).allowedScores().getLast());
    assertEquals(20L, generated.usage().inputTokens());
    assertEquals(1, charges.get());
    assertEquals(1, calls.get());
    JsonNode body = capturedRequest.get();
    assertTrue(body.path("messages").get(0).path("content").asText().contains("评分规则生成组件"));
    assertFalse(body.path("messages").get(0).path("content").asText().contains("输出表达策略"));
    JsonNode content = body.path("messages").get(1).path("content");
    assertEquals(1, content.size(), body.toPrettyString());
    String text = content.get(0).path("text").asText();
    assertTrue(text.contains("历史"), text);
    assertTrue(text.contains("标准答案内容"), text);
    assertTrue(text.contains("\"maxScore\" : 4"), text);
  }

  @Test
  void rubricGenerationPreservesSparseScoreGridAndRequestsSourceBasedScoreBoundaries() {
    String reference = "合成参考：方法甲或方法乙均可，任选其一并说明条件即可得2分；仅有结论不得分。" + "示例不是唯一答案，意思相同即可。步长1分，总分2分。";
    String rule = "2分：选用方法甲或方法乙之一并说明适用条件，其他等价合理表达也可；" + "0分：未满足上述要求，或仅有结论没有条件。不要求两种方法都答。";
    var generatedContent = StrictJson.MAPPER.createObjectNode();
    generatedContent.put("scoreStep", 1);
    var item = generatedContent.putArray("criteria").addObject();
    item.put("id", "P1");
    item.put("rule", rule);
    item.putArray("allowedScores").add(0).add(2);
    responseBody.set(wire(generatedContent.toString(), "stop", null));
    var verified =
        configured.withVerification(
            new ProviderProfile.Verification(
                configured.configDigest(), Instant.EPOCH, false, true));
    var generator =
        new OpenAICompatibleVisionProvider(verified, secrets, Clock.systemUTC(), () -> {});
    providers.add(generator);

    var generated =
        generator
            .generateRubric("合成学科", reference, new BigDecimal("2"), CancellationToken.none())
            .toCompletableFuture()
            .join();

    // This checks the sent policy and unchanged wire data, not real-model scoring accuracy.
    assertEquals(BigDecimal.ONE, generated.scoreStep());
    assertEquals(
        List.of(BigDecimal.ZERO, new BigDecimal("2")),
        generated.criteria().getFirst().allowedScores());
    assertEquals(rule, generated.criteria().getFirst().description());
    JsonNode body = capturedRequest.get();
    String system = body.path("messages").get(0).path("content").asText();
    for (String required :
        List.of(
            "deskgrade-rubric-generation-v2-score-boundaries",
            "示例非唯一、等价表达、或、任选/任答",
            "不得把可替代要点变成全部必答",
            "不得把明确不得分改成可得部分分",
            "区分不得满分与不得分",
            "每个允许分值都须在本项rule中给出可判定的条件",
            "不得另加输入未要求的必答内容",
            "scoreStep只表示分值粒度",
            "不得仅因步长为1而加入没有评分依据的中间分")) assertTrue(system.contains(required), required);
    assertFalse(system.contains("deskgrade-rubric-generation-v1"));
    assertFalse(system.contains("输出表达策略"));
    JsonNode content = body.path("messages").get(1).path("content");
    assertEquals(1, content.size());
    String user = content.get(0).path("text").asText();
    String inputMarker = "输入数据：\n";
    String schemaMarker = "\n输出必须严格符合JSON契约：\n";
    int inputStart = user.indexOf(inputMarker) + inputMarker.length();
    JsonNode input =
        StrictJson.parse(user.substring(inputStart, user.indexOf(schemaMarker, inputStart)));
    assertEquals(reference, input.path("referenceAnswer").asText());
    assertEquals("合成学科", input.path("subject").asText());
    assertEquals(2, input.path("maxScore").asInt());
    assertFalse(body.toString().contains("image_url"));
    assertEquals(1, calls.get());
  }

  @Test
  void textOnlyVerificationAllowsRuleGenerationButNeverUnlocksImageGrading() {
    responseBody.set(
        wire(
            "{\"result\":5}",
            "stop",
            StrictJson.parse("{\"prompt_tokens\":9,\"completion_tokens\":3}")));
    var unchecked = provider(configured, false);
    var tested =
        unchecked.textCapabilityTestDetailed(CancellationToken.none()).toCompletableFuture().join();
    var textOnly = configured.withVerification(tested.verification());
    assertTrue(textOnly.isStructuredVerified());
    assertFalse(textOnly.isVerified());
    assertFalse(tested.verification().imagePassed());
    assertEquals(9L, tested.usage().inputTokens());
    assertFalse(capturedRequest.get().toString().contains("image_url"));
    assertEquals(
        ProviderException.Failure.UNVERIFIED_CONFIGURATION,
        failure(provider(textOnly, false), CancellationToken.none()).failure());
    assertEquals(1, calls.get(), "Text verification must not authorize a student-image request");
    responseBody.set(
        wire(
            "{\"scoreStep\":1,\"criteria\":[{\"id\":\"P1\",\"rule\":\"表达正确意思得1分\",\"allowedScores\":[0,1]}]}",
            "stop",
            null));
    var generated =
        provider(textOnly, false)
            .generateRubric("语文", "标准答案", BigDecimal.ONE, CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertEquals(1, generated.criteria().size());
    assertEquals(2, calls.get());
    assertFalse(capturedRequest.get().toString().contains("image_url"));
  }

  @Test
  void failedTextTestOrStaleDigestCannotAuthorizeRuleGeneration() {
    responseBody.set(wire("{\"result\":4}", "stop", null));
    var proof =
        provider(configured, false)
            .textCapabilityTest(CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertFalse(proof.imagePassed());
    assertFalse(proof.structuredOutputPassed());
    var failed = configured.withVerification(proof);
    assertFalse(failed.isStructuredVerified());
    assertThrows(
        CompletionException.class,
        () ->
            provider(failed, false)
                .generateRubric("语文", "标准答案", BigDecimal.ONE, CancellationToken.none())
                .toCompletableFuture()
                .join());
    var stale =
        configured.withVerification(
            new ProviderProfile.Verification("0".repeat(64), Instant.EPOCH, false, true));
    assertFalse(stale.isStructuredVerified());
    assertThrows(
        CompletionException.class,
        () ->
            provider(stale, false)
                .generateRubric("语文", "标准答案", BigDecimal.ONE, CancellationToken.none())
                .toCompletableFuture()
                .join());
    assertEquals(1, calls.get());
  }

  @Test
  void rubricGenerationRejectsInvalidScoreGridsAndInputBeforeDispatch() {
    var verified =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var charges = new AtomicInteger();
    var generator =
        new OpenAICompatibleVisionProvider(
            verified, secrets, Clock.systemUTC(), charges::incrementAndGet);
    providers.add(generator);

    var localFailure =
        assertThrows(
            CompletionException.class,
            () ->
                generator
                    .generateRubric(" ", "标准答案", BigDecimal.ONE, CancellationToken.none())
                    .toCompletableFuture()
                    .join());
    assertEquals(
        ProviderException.Failure.INVALID_RESULT,
        assertInstanceOf(ProviderException.class, localFailure.getCause()).failure());
    assertEquals(0, charges.get());
    assertEquals(0, calls.get());

    for (String invalid :
        List.of(
            "{\"scoreStep\":1,\"criteria\":[{\"id\":\"P1\",\"rule\":\"规则\",\"allowedScores\":[1,4]}]}",
            "{\"scoreStep\":1,\"criteria\":[{\"id\":\"P1\",\"rule\":\"规则\",\"allowedScores\":[0,2,1,4]}]}",
            "{\"scoreStep\":0.5,\"criteria\":[{\"id\":\"P1\",\"rule\":\"规则\",\"allowedScores\":[0,1.2,4]}]}",
            "{\"scoreStep\":1,\"criteria\":[{\"id\":\"P1\",\"rule\":\"规则\",\"allowedScores\":[0,3]}]}")) {
      responseBody.set(wire(invalid, "stop", null));
      var failure =
          assertThrows(
              CompletionException.class,
              () ->
                  generator
                      .generateRubric("语文", "标准答案", new BigDecimal("4"), CancellationToken.none())
                      .toCompletableFuture()
                      .join());
      assertEquals(
          ProviderException.Failure.INVALID_RESULT,
          assertInstanceOf(ProviderException.class, failure.getCause()).failure());
    }
    assertEquals(4, charges.get());
    assertEquals(4, calls.get());
  }

  @Test
  void exactUsageAndOptionalPricingRemainDistinctFromUnknown() {
    responseBody.set(
        wire(
            GradeResultJsonCodecTest.VALID,
            "stop",
            StrictJson.parse("{\"prompt_tokens\":120,\"completion_tokens\":30}")));
    var response =
        provider(configured, true)
            .grade(request(), CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertEquals(120L, response.usage().inputTokens());
    assertEquals(30L, response.usage().outputTokens());
    assertNull(response.usage().estimatedCost());
    var priced =
        new ProviderProfile(
            configured.id(),
            configured.revision(),
            configured.displayName(),
            endpoint,
            configured.modelId(),
            configured.credentialRef(),
            configured.structuredMode(),
            Map.of(),
            Set.of(),
            configured.connectTimeout(),
            configured.requestTimeout(),
            new ProviderProfile.Pricing("CNY", new BigDecimal("2"), new BigDecimal("4")),
            null);
    var withPrice =
        provider(priced, true)
            .grade(request(), CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertEquals(new BigDecimal("0.000360"), withPrice.usage().estimatedCost());
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 429, 500, 503, 400, 302})
  void httpErrorsAreClassifiedNotRetriedAndNeverExposeBodies(int code) {
    status.set(code);
    responseBody.set("synthetic-private-upstream-key");
    var error = failure(provider(configured, true), CancellationToken.none());
    assertEquals(code, error.httpStatus());
    assertEquals(code == 429 || code >= 500, error.retryable());
    assertEquals(1, calls.get());
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains("private"));
  }

  @Test
  void emptyChoicesRefusalTruncationAndWrongJsonReject() {
    var provider = provider(configured, true);
    for (String body :
        List.of(
            "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"no\",\"content\":null}}]}",
            wire(GradeResultJsonCodecTest.VALID, "length", null),
            wire("", "stop", null),
            wire("```json {} ```", "stop", null),
            wire("{}", "stop", null))) {
      responseBody.set(body);
      failure(provider, CancellationToken.none());
    }
    assertEquals(6, calls.get());
  }

  @Test
  void timeoutIsAsynchronousAndDoesNotRetry() {
    delayMs.set(800);
    var p =
        new ProviderProfile(
            configured.id(),
            1,
            configured.displayName(),
            endpoint,
            configured.modelId(),
            configured.credentialRef(),
            configured.structuredMode(),
            Map.of(),
            Set.of(),
            Duration.ofMillis(200),
            Duration.ofMillis(200),
            null,
            null);
    var error = failure(provider(p, true), CancellationToken.none());
    assertEquals(ProviderException.Failure.TIMEOUT, error.failure());
    assertEquals("MODEL_REQUEST_TIMEOUT", error.diagnosticCode());
    assertEquals(1, calls.get());
  }

  @Test
  void asynchronousUnexpectedLocalFailureIsSanitizedRatherThanExposedAsANetworkFailure() {
    var profile =
        configured.withVerification(
            new ProviderProfile.Verification(configured.configDigest(), Instant.EPOCH, true, true));
    var instants = new AtomicInteger();
    Clock failingClock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            if (instants.incrementAndGet() > 1)
              throw new IllegalStateException("student-private-answer bearer synthetic-secret");
            return Instant.EPOCH;
          }
        };
    var provider =
        new OpenAICompatibleVisionProvider(profile, auditRubric(), secrets, failingClock);
    providers.add(provider);
    var sanitized = failure(provider, CancellationToken.none());
    assertEquals(1, calls.get());
    assertEquals(ProviderException.Failure.INTERNAL_ERROR, sanitized.failure());
    assertEquals("MODEL_INTERNAL_ERROR", sanitized.diagnosticCode());
    assertNull(sanitized.getCause());
    assertFalse(sanitized.getMessage().contains("synthetic-secret"));
    assertFalse(sanitized.fallbackEligible());
  }

  @Test
  void preCancelledNeverSendsAndInflightCancellationDiscardResults() throws Exception {
    var provider = provider(configured, true);
    assertEquals(ProviderException.Failure.CANCELLED, failure(provider, () -> true).failure());
    assertEquals(0, calls.get());
    delayMs.set(1000);
    var cancel = new AtomicBoolean();
    var future = provider.grade(request(), cancel::get).toCompletableFuture();
    assertTrue(arrived.await(2, TimeUnit.SECONDS));
    cancel.set(true);
    var error =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
    assertEquals(
        ProviderException.Failure.CANCELLED,
        assertInstanceOf(ProviderException.class, error.getCause()).failure());
    assertEquals(1, calls.get());
  }

  @Test
  void unverifiedAndInvalidPngNeverReachHttp() {
    assertEquals(
        ProviderException.Failure.UNVERIFIED_CONFIGURATION,
        failure(provider(configured, false), CancellationToken.none()).failure());
    png = new byte[] {1, 2, 3};
    assertEquals(
        ProviderException.Failure.INVALID_IMAGE,
        failure(provider(configured, true), CancellationToken.none()).failure());
    assertEquals(0, calls.get());
  }

  @Test
  void oversizedBodyIsBoundedAndStops() {
    responseBody.set("x".repeat(1100000));
    assertEquals(
        ProviderException.Failure.RESPONSE_TOO_LARGE,
        failure(provider(configured, true), CancellationToken.none()).failure());
  }

  @Test
  void capabilityTestRequiresReadingImageNotHelloAndDoesNotInventSuccess() {
    responseBody.set(wire("{\"observed\":\"ABCDEF\"}", "stop", null));
    var proof =
        provider(configured, false)
            .capabilityTest(CancellationToken.none())
            .toCompletableFuture()
            .join();
    assertFalse(proof.imagePassed());
    assertTrue(proof.structuredOutputPassed());
    assertFalse(configured.withVerification(proof).isVerified());
    assertTrue(
        capturedRequest.get().path("messages").get(0).path("content").asText().contains("图像能力测试"));
    String text =
        capturedRequest.get().path("messages").get(1).path("content").get(0).path("text").asText();
    assertFalse(java.util.regex.Pattern.compile("\\d{6}").matcher(text).find());
    assertTrue(
        capturedRequest
            .get()
            .path("messages")
            .get(1)
            .path("content")
            .get(1)
            .path("image_url")
            .path("url")
            .asText()
            .startsWith("data:image/png;base64,"));
  }

  @Test
  void schemaAndThinkingSentOnlyWhenExplicitlyConfigured() {
    var p =
        new ProviderProfile(
            configured.id(),
            1,
            configured.displayName(),
            endpoint,
            configured.modelId(),
            configured.credentialRef(),
            ProviderProfile.StructuredMode.JSON_SCHEMA,
            Map.of("enable_thinking", "false", "temperature", "0.2", "max_tokens", "1000"),
            Set.of("enable_thinking", "temperature", "max_tokens"),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    provider(p, true).grade(request(), CancellationToken.none()).toCompletableFuture().join();
    var body = capturedRequest.get();
    assertEquals("json_schema", body.path("response_format").path("type").asText());
    assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
    assertFalse(body.path("enable_thinking").asBoolean());
    assertEquals(1000, body.path("max_tokens").intValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"off", "low", "medium", "xhigh"})
  void explicitQwenThinkingModeIsSentUnchangedForGradingVerificationAndGeneration(String mode) {
    Map<String, String> parameters =
        mode.equals("off")
            ? Map.of("enable_thinking", "false")
            : Map.of("enable_thinking", "true", "reasoning_effort", mode);
    var profile =
        new ProviderProfile(
            configured.id(),
            configured.revision(),
            configured.displayName(),
            endpoint,
            "qwen3.8-flash",
            configured.credentialRef(),
            configured.structuredMode(),
            parameters,
            Set.of("enable_thinking", "reasoning_effort"),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    var provider = provider(profile, true);

    provider.grade(request(), CancellationToken.none()).toCompletableFuture().join();
    assertExplicitThinkingRequest(capturedRequest.get(), mode);

    responseBody.set(wire("{\"observed\":\"ABCDEF\"}", "stop", null));
    provider.capabilityTest(CancellationToken.none()).toCompletableFuture().join();
    assertExplicitThinkingRequest(capturedRequest.get(), mode);

    responseBody.set(wire("{\"result\":5}", "stop", null));
    provider.textCapabilityTest(CancellationToken.none()).toCompletableFuture().join();
    assertExplicitThinkingRequest(capturedRequest.get(), mode);

    responseBody.set(
        wire(
            "{\"scoreStep\":1,\"criteria\":[{\"id\":\"P1\",\"rule\":\"表达正确意思得1分\",\"allowedScores\":[0,1]}]}",
            "stop",
            null));
    provider
        .generateRubric("语文", "合成标准答案", BigDecimal.ONE, CancellationToken.none())
        .toCompletableFuture()
        .join();
    assertExplicitThinkingRequest(capturedRequest.get(), mode);
    assertEquals(4, calls.get());
  }

  private static void assertExplicitThinkingRequest(JsonNode body, String mode) {
    assertEquals("qwen3.8-flash", body.path("model").asText());
    assertTrue(body.path("enable_thinking").isBoolean());
    assertEquals(!mode.equals("off"), body.path("enable_thinking").asBoolean());
    if (mode.equals("off")) assertFalse(body.has("reasoning_effort"));
    else {
      assertTrue(body.path("reasoning_effort").isTextual());
      assertEquals(mode, body.path("reasoning_effort").asText());
    }
    assertFalse(body.has("thinking_budget"));
    assertFalse(body.has("max_tokens"));
    assertFalse(body.has("temperature"));
    assertFalse(body.has("extra_body"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"JSON_OBJECT", "JSON_SCHEMA"})
  void doubaoSeedLiteUsesExistingImageAndStructuredWireWithoutQwenOrFastTierOptions(String mode) {
    var profile =
        new ProviderProfile(
            "doubao_fixture",
            1,
            "豆包合成兼容配置",
            endpoint,
            DoubaoEndpointCatalog.SEED_2_1_LITE,
            "doubao_synthetic_ref",
            ProviderProfile.StructuredMode.valueOf(mode),
            Map.of(),
            Set.of(),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    var response =
        (ObjectNode)
            StrictJson.parse(
                wire(
                    GradeResultJsonCodecTest.VALID,
                    "stop",
                    StrictJson.parse(
                        "{\"prompt_tokens\":33,\"completion_tokens\":19,\"total_tokens\":52,\"completion_tokens_details\":{\"reasoning_tokens\":13}}")));
    response.put("id", "synthetic-ark-response");
    response.put("object", "chat.completion");
    response.put("model", DoubaoEndpointCatalog.SEED_2_1_LITE);
    response.put("service_tier", "default");
    ((ObjectNode) response.path("choices").get(0).path("message"))
        .put("reasoning_content", "合成思考字段不是可提交评分，也不作为最终内容读取。");
    responseBody.set(response.toString());

    var result =
        provider(profile, true)
            .grade(request(), CancellationToken.none())
            .toCompletableFuture()
            .join();

    assertEquals(GradeResult.Status.SCORED, result.result().status());
    assertEquals(33L, result.usage().inputTokens());
    assertEquals(19L, result.usage().outputTokens());
    assertNull(result.usage().estimatedCost());
    var body = capturedRequest.get();
    assertEquals(DoubaoEndpointCatalog.SEED_2_1_LITE, body.path("model").asText());
    assertEquals(
        mode.equals("JSON_SCHEMA") ? "json_schema" : "json_object",
        body.path("response_format").path("type").asText());
    if (mode.equals("JSON_SCHEMA")) {
      assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
      assertEquals(
          new GradeResultJsonCodec().providerSchema(),
          body.path("response_format").path("json_schema").path("schema"));
    }
    String image =
        body.path("messages").get(1).path("content").get(1).path("image_url").path("url").asText();
    assertTrue(image.startsWith("data:image/png;base64,"));
    assertArrayEquals(png, Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1)));
    assertFalse(body.has("enable_thinking"));
    assertFalse(body.has("reasoning_effort"));
    assertFalse(body.has("thinking_budget"));
    assertFalse(body.has("thinking"));
    assertFalse(body.has("service_tier"));
    assertFalse(body.has("max_tokens"));
    assertFalse(body.path("stream").asBoolean());
    assertEquals(1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"enable_thinking", "reasoning_effort"})
  void officialDoubaoPresetRejectsUntranslatedQwenFieldsBeforeKeyLookupAndDispatch(
      String parameter) {
    var reads = new AtomicInteger();
    var dispatches = new AtomicInteger();
    var sends = new AtomicInteger();
    SecretStore countedSecrets =
        new SecretStore() {
          public void put(String id, char[] value) {
            throw new UnsupportedOperationException();
          }

          public void delete(String id) {
            throw new UnsupportedOperationException();
          }

          public Optional<char[]> read(String id) {
            reads.incrementAndGet();
            return Optional.of("synthetic-fixture-key".toCharArray());
          }
        };
    var parameters = Map.of(parameter, parameter.equals("enable_thinking") ? "false" : "low");
    var profile =
        new ProviderProfile(
            "doubao_guard_fixture",
            1,
            "豆包参数隔离合成配置",
            DoubaoEndpointCatalog.ENDPOINT,
            DoubaoEndpointCatalog.SEED_2_1_LITE,
            "doubao_synthetic_ref",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            parameters,
            parameters.keySet(),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    profile =
        profile.withVerification(
            new ProviderProfile.Verification(profile.configDigest(), Instant.EPOCH, true, true));
    var provider =
        new OpenAICompatibleVisionProvider(
            profile,
            auditRubric(),
            countedSecrets,
            Clock.systemUTC(),
            dispatches::incrementAndGet,
            ModelCallTrace.NONE,
            new OpenAICompatibleVisionProvider.TransportControl(
                (unusedRequest, unusedHandler) -> {
                  sends.incrementAndGet();
                  return java.util.concurrent.CompletableFuture.failedFuture(
                      new IllegalStateException("No network fixture"));
                },
                (unusedOperation, unusedDelay) -> () -> {},
                System::nanoTime));
    providers.add(provider);

    assertEquals(
        ProviderException.Failure.UNVERIFIED_CONFIGURATION,
        failure(provider, CancellationToken.none()).failure());
    for (var future :
        List.of(
            provider.capabilityTest(CancellationToken.none()),
            provider.textCapabilityTest(CancellationToken.none()),
            provider.generateRubric("语文", "合成标准答案", BigDecimal.ONE, CancellationToken.none()))) {
      var error =
          assertThrows(CompletionException.class, () -> future.toCompletableFuture().join());
      assertEquals(
          ProviderException.Failure.UNVERIFIED_CONFIGURATION,
          assertInstanceOf(ProviderException.class, error.getCause()).failure());
    }
    assertEquals(0, reads.get());
    assertEquals(0, dispatches.get());
    assertEquals(0, sends.get());
    assertEquals(0, calls.get());
  }

  @Test
  void doubaoReasoningTextIsNotAReplacementForMissingFinalGradingContent() {
    var profile =
        new ProviderProfile(
            "doubao_empty_fixture",
            1,
            "豆包最终内容合成配置",
            endpoint,
            DoubaoEndpointCatalog.SEED_2_1_LITE,
            "doubao_synthetic_ref",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            Map.of(),
            Set.of(),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    var response =
        (ObjectNode) StrictJson.parse(wire(GradeResultJsonCodecTest.VALID, "stop", null));
    var message = (ObjectNode) response.path("choices").get(0).path("message");
    message.put("reasoning_content", GradeResultJsonCodecTest.VALID);
    message.putNull("content");
    responseBody.set(response.toString());
    assertEquals(
        ProviderException.Failure.EMPTY_RESPONSE,
        failure(provider(profile, true), CancellationToken.none()).failure());
    assertEquals(1, calls.get());
  }

  @Test
  void doubaoUnableTruncationAndFilteringKeepExistingFailClosedSemantics() {
    var profile =
        new ProviderProfile(
            "doubao_safety_fixture",
            1,
            "豆包安全语义合成配置",
            endpoint,
            DoubaoEndpointCatalog.SEED_2_1_LITE,
            "doubao_synthetic_ref",
            ProviderProfile.StructuredMode.JSON_OBJECT,
            Map.of(),
            Set.of(),
            configured.connectTimeout(),
            configured.requestTimeout(),
            null,
            null);
    var provider = provider(profile, true);
    responseBody.set(
        wire(
            """
        {"status":"UNABLE","readability":"PARTIAL","studentText":"可见部分作答",
         "criteria":[{"criterionId":"a","awarded":null,"evidence":null,"reason":"本题中部关键否定词无法辨认"}],
         "issues":["本题中部关键否定词无法辨认"]}
        """,
            "stop",
            null));
    var unable = provider.grade(request(), CancellationToken.none()).toCompletableFuture().join();
    assertEquals(GradeResult.Status.UNABLE, unable.result().status());
    assertNull(unable.result().criteria().getFirst().awarded());
    assertEquals(List.of("本题中部关键否定词无法辨认"), unable.result().issues());
    responseBody.set(wire(GradeResultJsonCodecTest.VALID, "length", null));
    assertEquals(
        ProviderException.Failure.TRUNCATED, failure(provider, CancellationToken.none()).failure());
    responseBody.set(wire(GradeResultJsonCodecTest.VALID, "content_filter", null));
    assertEquals(
        ProviderException.Failure.REFUSED, failure(provider, CancellationToken.none()).failure());
    assertEquals(3, calls.get(), "No provider-level fallback or replay for these responses");
  }

  @Test
  void syntheticCaptureMustPersistBeforeCapabilityRequestAndCannotBeMutatedByRecorder() {
    var provider = provider(configured, false);
    var failed =
        provider.capabilityTestDetailed(
            CancellationToken.none(),
            png -> {
              throw new app.deskgrade.core.error.DeskGradeException(
                  app.deskgrade.core.error.ErrorCode.STORAGE_FAILURE);
            });
    assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join());
    assertEquals(0, calls.get());
    responseBody.set(wire("{\"observed\":\"ABCDEF\"}", "stop", null));
    var recorded = new AtomicReference<byte[]>();
    var detail =
        provider
            .capabilityTestDetailed(
                CancellationToken.none(),
                png -> {
                  recorded.set(png.clone());
                  java.util.Arrays.fill(png, (byte) 0);
                })
            .toCompletableFuture()
            .join();
    assertArrayEquals(recorded.get(), detail.png());
    String data =
        capturedRequest
            .get()
            .path("messages")
            .get(1)
            .path("content")
            .get(1)
            .path("image_url")
            .path("url")
            .asText();
    assertArrayEquals(
        recorded.get(), Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)));
    byte[] mutable = detail.png();
    mutable[0] = 0;
    assertNotEquals(0, detail.png()[0]);
  }
}
