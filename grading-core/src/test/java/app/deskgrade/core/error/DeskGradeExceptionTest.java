package app.deskgrade.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DeskGradeExceptionTest {
  @Test
  void failuresExposeOnlyTheBoundedCodeAndFixedMessage() {
    for (ErrorCode code : ErrorCode.values()) {
      DeskGradeException error = new DeskGradeException(code);

      assertEquals(code, error.code());
      assertEquals(code.safeMessage(), error.getMessage());
      assertNull(error.getCause());
    }
  }
}
