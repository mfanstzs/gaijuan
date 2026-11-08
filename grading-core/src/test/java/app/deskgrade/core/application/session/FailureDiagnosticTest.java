package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class FailureDiagnosticTest {
  private static final String PRIVATE_TEXT =
      "private-student-answer secret-key https://private.example";

  @Test
  void legacyConstructorStillHasNoLocalDifferenceMetadata() {
    var diagnostic = new FailureDiagnostic(SessionState.FILLING, List.of("LOCAL_OPERATION_FAILED"));
    assertNull(diagnostic.screenChange());
  }

  @Test
  void typedMetadataSurvivesBoundedCauseTraversalWithoutRetainingExceptionText() {
    var metadata =
        ScreenChangeDiagnosticTest.diagnostic(
            5, 100, ScreenChangeDiagnostic.ChangeScope.EDGE_ONLY, 200);
    var failure = new ScreenFailure(metadata);
    var diagnostic =
        FailureDiagnostic.from(SessionState.VERIFYING_SCORE, new CompletionException(failure));
    assertSame(metadata, diagnostic.screenChange());
    assertEquals(List.of("ANSWER_CHANGED_WITHOUT_ACTION"), diagnostic.codes());
    assertFalse(diagnostic.toString().contains(PRIVATE_TEXT));
    assertFalse(diagnostic.toString().contains("private.example"));
  }

  @Test
  void cyclesAndDeepChainsCannotMakeDiagnosticExtractionUnbounded() {
    var metadata =
        ScreenChangeDiagnosticTest.diagnostic(
            5, 100, ScreenChangeDiagnostic.ChangeScope.LOCALIZED, 200);
    var first = new ScreenFailure(metadata);
    var second = new IllegalStateException(PRIVATE_TEXT, first);
    first.initCause(second);
    assertSame(
        metadata, FailureDiagnostic.from(SessionState.VERIFYING_SCORE, second).screenChange());
    Throwable wrapped = new ScreenFailure(metadata);
    for (int depth = 0; depth < 20; depth++) wrapped = new CompletionException(wrapped);
    var diagnostic = FailureDiagnostic.from(SessionState.VERIFYING_SCORE, wrapped);
    assertNull(diagnostic.screenChange());
    assertEquals(List.of("LOCAL_OPERATION_FAILED"), diagnostic.codes());
  }

  @Test
  void optionalMetadataGetterFailureCannotReplaceTheOriginalFailureCode() {
    for (boolean linkage : List.of(false, true)) {
      var diagnostic =
          assertDoesNotThrow(
              () ->
                  FailureDiagnostic.from(
                      SessionState.VERIFYING_SCORE, new BrokenMetadataFailure(linkage)));
      assertEquals(List.of("ANSWER_CHANGED_WITHOUT_ACTION"), diagnostic.codes());
      assertNull(diagnostic.screenChange());
      assertFalse(diagnostic.toString().contains(PRIVATE_TEXT));
    }
  }

  private static class ScreenFailure extends IllegalStateException
      implements SafeFailureCode, SafeScreenChangeFailure {
    private final ScreenChangeDiagnostic metadata;

    ScreenFailure(ScreenChangeDiagnostic metadata) {
      super(PRIVATE_TEXT);
      this.metadata = metadata;
    }

    @Override
    public String diagnosticCode() {
      return "ANSWER_CHANGED_WITHOUT_ACTION";
    }

    @Override
    public ScreenChangeDiagnostic screenChangeDiagnostic() {
      return metadata;
    }
  }

  private static final class BrokenMetadataFailure extends ScreenFailure {
    private final boolean linkage;

    BrokenMetadataFailure(boolean linkage) {
      super(null);
      this.linkage = linkage;
    }

    @Override
    public ScreenChangeDiagnostic screenChangeDiagnostic() {
      if (linkage) throw new LinkageError(PRIVATE_TEXT);
      throw new IllegalStateException(PRIVATE_TEXT);
    }
  }
}
