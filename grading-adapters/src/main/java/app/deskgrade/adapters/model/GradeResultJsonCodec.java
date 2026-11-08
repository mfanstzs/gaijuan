package app.deskgrade.adapters.model;

import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.GradeResult;
import app.deskgrade.core.domain.QuestionDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Implements contracts/grade-result.schema.json, plus stricter business invariants. */
public final class GradeResultJsonCodec {
  public GradeResult read(String json, QuestionDefinition question) {
    JsonNode root = StrictJson.parse(json);
    StrictJson.fields(root, Set.of("status", "readability", "studentText", "criteria", "issues"));
    try {
      var status = GradeResult.Status.valueOf(StrictJson.string(root.get("status"), 1, 6, false));
      var readability =
          GradeResult.Readability.valueOf(StrictJson.string(root.get("readability"), 1, 10, false));
      String text = StrictJson.string(root.get("studentText"), 0, 16000, true);
      JsonNode criteriaNode = root.get("criteria");
      JsonNode issuesNode = root.get("issues");
      if (!criteriaNode.isArray()
          || criteriaNode.size() < 1
          || criteriaNode.size() > 100
          || !issuesNode.isArray()
          || issuesNode.size() > 20) StrictJson.bad();
      List<GradeResult.CriterionResult> criteria = new ArrayList<>();
      for (JsonNode criterion : criteriaNode) {
        StrictJson.fields(criterion, Set.of("criterionId", "awarded", "evidence", "reason"));
        var awarded = criterion.get("awarded");
        if (!(awarded.isNull() || awarded.isNumber())
            || (awarded.isNumber() && awarded.decimalValue().signum() < 0)) StrictJson.bad();
        criteria.add(
            new GradeResult.CriterionResult(
                StrictJson.string(criterion.get("criterionId"), 1, 100, false),
                awarded.isNull() ? null : awarded.decimalValue(),
                StrictJson.string(criterion.get("evidence"), 0, 1000, true),
                StrictJson.string(criterion.get("reason"), 1, 500, false)));
      }
      List<String> issues = new ArrayList<>();
      for (var issue : issuesNode) issues.add(StrictJson.string(issue, 1, 500, false));
      var result = new GradeResult(status, readability, text, criteria, issues);
      GradeValidator.validate(question, result);
      return result;
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Failure.INVALID_RESULT);
    }
  }

  /** Provider schema subset; local conditional and bounds checks always remain mandatory. */
  public JsonNode providerSchema() {
    return StrictJson.parse(
        """
        {"type":"object","additionalProperties":false,
         "required":["status","readability","studentText","criteria","issues"],
         "properties":{
           "status":{"type":"string","enum":["SCORED","UNABLE"]},
           "readability":{"type":"string","enum":["CLEAR","PARTIAL","UNREADABLE"]},
           "studentText":{"type":["string","null"]},
           "criteria":{"type":"array","items":{"type":"object","additionalProperties":false,
             "required":["criterionId","awarded","evidence","reason"],
             "properties":{"criterionId":{"type":"string"},"awarded":{"type":["number","null"]},
             "evidence":{"type":["string","null"]},"reason":{"type":"string"}}}},
           "issues":{"type":"array","items":{"type":"string"}}}}
        """);
  }
}
