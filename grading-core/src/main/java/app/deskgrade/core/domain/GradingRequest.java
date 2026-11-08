package app.deskgrade.core.domain;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.util.UUID;

/** M0 request boundary. Image bytes must contain only the selected answer region. */
public record GradingRequest(UUID requestId, QuestionDefinition question, byte[] answerImage) {
  public GradingRequest {
    if (requestId == null || question == null || answerImage == null || answerImage.length == 0) {
      throw new DeskGradeException(ErrorCode.INVALID_ARGUMENT);
    }
    answerImage = answerImage.clone();
  }

  @Override
  public byte[] answerImage() {
    return answerImage.clone();
  }
}
