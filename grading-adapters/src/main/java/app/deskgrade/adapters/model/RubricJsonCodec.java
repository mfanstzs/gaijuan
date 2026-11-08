package app.deskgrade.adapters.model;

import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.ScoringCriterion;
import app.deskgrade.core.domain.model.Rubric;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;

/** File format intentionally keeps rule text, source and allowed values separate. */
public final class RubricJsonCodec {
  public String write(Rubric rubric) {
    var root = StrictJson.MAPPER.createObjectNode();
    var q = rubric.question();
    boolean sourceMissing = sourceMissing(q.prompt());
    root.put("schemaVersion", 1);
    root.put("questionId", q.id());
    root.put("revision", q.revision());
    root.put("sourceText", q.prompt());
    root.put("referenceAnswer", q.standardAnswer());
    root.put("maxScore", q.maximumScore());
    root.put("scoreStep", rubric.scoreStep());
    root.put("rawRubric", rubric.rawRubric());
    root.put("requiresSourceText", sourceMissing ? false : rubric.requiresSourceText());
    root.put("missingSourceAccepted", sourceMissing ? true : rubric.missingSourceAccepted());
    var criteria = root.putArray("criteria");
    for (var criterion : q.criteria()) {
      var c = criteria.addObject();
      c.put("id", criterion.id());
      c.put("rule", criterion.description());
      var allowed = c.putArray("allowedScores");
      criterion.allowedScores().forEach(allowed::add);
    }
    return root.toPrettyString();
  }

  public Rubric read(String json) {
    JsonNode root = StrictJson.parse(json);
    try {
      if (!root.isObject()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").intValue() != 1) StrictJson.bad();
      Set<String> known =
          Set.of(
              "schemaVersion",
              "questionId",
              "revision",
              "sourceText",
              "referenceAnswer",
              "maxScore",
              "scoreStep",
              "rawRubric",
              "requiresSourceText",
              "missingSourceAccepted",
              "criteria",
              "title",
              "enabled",
              "gradingMode",
              "note");
      root.fieldNames()
          .forEachRemaining(
              k -> {
                if (!known.contains(k)) StrictJson.bad();
              });
      if (!root.path("revision").isIntegralNumber()
          || !root.path("maxScore").isNumber()
          || !root.path("scoreStep").isNumber()
          || !root.path("requiresSourceText").isBoolean()
          || !root.path("criteria").isArray()) StrictJson.bad();
      String source = StrictJson.string(root.get("sourceText"), 0, 64000, true);
      var criteria = new ArrayList<ScoringCriterion>();
      for (var c : root.get("criteria")) {
        Set<String> knownCriterion = Set.of("id", "name", "maxScore", "allowedScores", "rule");
        c.fieldNames()
            .forEachRemaining(
                k -> {
                  if (!knownCriterion.contains(k)) StrictJson.bad();
                });
        if (!c.path("allowedScores").isArray()) StrictJson.bad();
        var values = new ArrayList<BigDecimal>();
        for (var v : c.get("allowedScores")) {
          if (!v.isNumber()) StrictJson.bad();
          values.add(v.decimalValue());
        }
        if (c.has("maxScore")
            && (!c.get("maxScore").isNumber()
                || values.stream()
                        .max(BigDecimal::compareTo)
                        .orElseThrow()
                        .compareTo(c.get("maxScore").decimalValue())
                    != 0)) StrictJson.bad();
        criteria.add(
            new ScoringCriterion(
                StrictJson.string(c.get("id"), 1, 100, false),
                StrictJson.string(c.get("rule"), 1, 16000, false),
                values));
      }
      if (root.has("missingSourceAccepted") && !root.get("missingSourceAccepted").isBoolean())
        StrictJson.bad();
      boolean sourceMissing = sourceMissing(source);
      return new Rubric(
          new QuestionDefinition(
              StrictJson.string(root.get("questionId"), 1, 100, false),
              root.get("revision").longValue(),
              source == null || source.isBlank() ? "[原文缺失]" : source,
              StrictJson.string(root.get("referenceAnswer"), 0, 64000, false),
              root.get("maxScore").decimalValue(),
              criteria),
          StrictJson.string(root.get("rawRubric"), 1, 64000, false),
          root.get("scoreStep").decimalValue(),
          sourceMissing ? false : root.get("requiresSourceText").booleanValue(),
          sourceMissing ? true : root.path("missingSourceAccepted").asBoolean(false));
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Failure.INVALID_RESULT);
    }
  }

  private static boolean sourceMissing(String source) {
    return source == null || source.isBlank() || "[原文缺失]".equals(source.trim());
  }
}
