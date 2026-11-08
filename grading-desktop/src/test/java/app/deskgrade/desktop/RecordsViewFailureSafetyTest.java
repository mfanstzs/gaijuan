package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class RecordsViewFailureSafetyTest {
  @Test
  void recordFailuresUseFixedCodesAndTypesWithoutExceptionMessages() {
    var failure =
        new CompletionException(
            "PRIVATE-STUDENT-AND-KEY-CANARY", new DeskGradeException(ErrorCode.STORAGE_FAILURE));
    assertEquals("STORAGE_FAILURE", RecordsView.failureCode(failure));
    assertEquals("java.util.concurrent.CompletionException", RecordsView.failureType(failure));
    assertEquals(
        "STORAGE_FAILURE",
        RecordsView.failureCode(new RuntimeException("PRIVATE-STUDENT-AND-KEY-CANARY")));
    assertEquals("MISSING_RECORD_RESULT", RecordsView.failureCode(null));
    assertEquals("NONE", RecordsView.failureType(null));
  }

  @Test
  void circularCausalChainCannotMakeErrorLoggingHang() {
    var first = new RuntimeException("PRIVATE-FIRST");
    var second = new RuntimeException("PRIVATE-SECOND");
    first.initCause(second);
    second.initCause(first);
    assertEquals("STORAGE_FAILURE", RecordsView.failureCode(first));
  }
}
