package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.adapters.commercial.GatewayException;
import app.deskgrade.core.application.session.FailureDiagnostic;
import app.deskgrade.core.application.session.SafeFailureCode;
import app.deskgrade.core.application.session.SafeScreenChangeFailure;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.ChangeScope;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.Checkpoint;
import app.deskgrade.core.application.session.ScreenChangeDiagnostic.TripleConsistency;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeInputExceptionTest {
  @Test
  void gatewayCodeSurvivesWrappingWithoutRetainingOriginalException() {
    GatewayException original = new GatewayException(GatewayException.Code.STORE_UNAVAILABLE);
    SafeInputException wrapped = SafeInputException.stage("INPUT_PERMISSION", original);
    assertEquals(
        List.of("INPUT_PERMISSION", "GATEWAY_STORE_UNAVAILABLE"),
        FailureDiagnostic.from(SessionState.FILLING, wrapped).codes());
    assertNotSame(original, wrapped.getCause());
    assertNull(wrapped.getCause().getCause());
  }

  @Test
  void cancellationAndModelKindSurviveAsSafeEnumCodesOnly() {
    assertEquals(
        List.of("INPUT_PERMISSION", "CANCELLED"),
        FailureDiagnostic.from(
                SessionState.FILLING,
                SafeInputException.stage(
                    "INPUT_PERMISSION", new DeskGradeException(ErrorCode.CANCELLED)))
            .codes());
    assertEquals(
        List.of("INPUT_PERMISSION", "MODEL_NETWORK"),
        FailureDiagnostic.from(
                SessionState.FILLING,
                SafeInputException.stage(
                    "INPUT_PERMISSION",
                    new ModelCallException(ModelCallException.Kind.NETWORK, null)))
            .codes());
  }

  @Test
  void arbitraryMessagesAndInvalidDiagnosticTextAreNotRetained() {
    String privateText = "student text secret-key C:/private/path";
    RuntimeException original = new IllegalStateException(privateText);
    SafeInputException wrapped = SafeInputException.stage("INPUT_PERMISSION", original);
    assertNotSame(original, wrapped.getCause());
    assertFalse(wrapped.getCause().getMessage().contains(privateText));
    assertFalse(
        FailureDiagnostic.from(SessionState.FILLING, wrapped).toString().contains(privateText));
    class InvalidCodeFailure extends RuntimeException implements SafeFailureCode {
      public String diagnosticCode() {
        return privateText;
      }
    }
    SafeInputException invalid =
        SafeInputException.stage("INPUT_PERMISSION", new InvalidCodeFailure());
    assertEquals(
        List.of("INPUT_PERMISSION"), FailureDiagnostic.from(SessionState.FILLING, invalid).codes());
    assertFalse(invalid.getCause().getMessage().contains(privateText));
  }

  @Test
  void boundedScreenChangeMetadataSurvivesSafeStageWrapping() {
    var screenChange =
        new ScreenChangeDiagnostic(
            Checkpoint.AFTER_FILL_CHECK,
            12,
            400,
            ChangeScope.LOCALIZED,
            TripleConsistency.COHERENT,
            237);
    SafeInputException changed =
        SafeInputException.screenChange("ANSWER_CHANGED_WITHOUT_ACTION", screenChange);
    SafeInputException wrapped = SafeInputException.stage("MANUAL_OBSERVE", changed);

    assertEquals(screenChange, wrapped.screenChangeDiagnostic());
    assertEquals(
        screenChange, FailureDiagnostic.from(SessionState.VERIFYING_SCORE, wrapped).screenChange());
    assertEquals(
        List.of("MANUAL_OBSERVE", "ANSWER_CHANGED_WITHOUT_ACTION"),
        FailureDiagnostic.from(SessionState.VERIFYING_SCORE, wrapped).codes());
    assertFalse(wrapped.toString().contains("student"));
  }

  @Test
  void brokenDiagnosticGetterCannotObscureTheOriginalFailClosedCode() {
    class BrokenDiagnosticFailure extends RuntimeException
        implements SafeFailureCode, SafeScreenChangeFailure {
      @Override
      public String diagnosticCode() {
        return "ANSWER_CHANGED_WITHOUT_ACTION";
      }

      @Override
      public ScreenChangeDiagnostic screenChangeDiagnostic() {
        throw new IllegalStateException("diagnostic unavailable");
      }
    }

    SafeInputException wrapped =
        assertDoesNotThrow(
            () -> SafeInputException.stage("MANUAL_OBSERVE", new BrokenDiagnosticFailure()));

    assertNull(wrapped.screenChangeDiagnostic());
    assertEquals(
        List.of("MANUAL_OBSERVE", "ANSWER_CHANGED_WITHOUT_ACTION"),
        FailureDiagnostic.from(SessionState.VERIFYING_SCORE, wrapped).codes());
  }
}
