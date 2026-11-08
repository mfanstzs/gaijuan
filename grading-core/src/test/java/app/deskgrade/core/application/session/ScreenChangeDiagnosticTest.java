package app.deskgrade.core.application.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ScreenChangeDiagnosticTest {
  @Test
  void acceptsOnlyBoundedCountsAndConsistentBuckets() {
    for (var scope :
        Set.of(
            ScreenChangeDiagnostic.ChangeScope.EDGE_ONLY,
            ScreenChangeDiagnostic.ChangeScope.LOCALIZED,
            ScreenChangeDiagnostic.ChangeScope.BROAD)) {
      var diagnostic = diagnostic(3, 100, scope, 250);
      assertEquals(3, diagnostic.changedPixels());
      assertEquals(100, diagnostic.totalPixels());
      assertEquals(scope, diagnostic.changeScope());
    }
    assertDoesNotThrow(
        () -> diagnostic(0, 100, ScreenChangeDiagnostic.ChangeScope.NO_PIXEL_CHANGE, -1));
    assertDoesNotThrow(
        () -> diagnostic(-1, -1, ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE, -1));
    assertDoesNotThrow(
        () -> diagnostic(-1, -1, ScreenChangeDiagnostic.ChangeScope.DIMENSIONS_CHANGED, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(101, 100, ScreenChangeDiagnostic.ChangeScope.BROAD, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(-1, 100, ScreenChangeDiagnostic.ChangeScope.BROAD, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(0, 100, ScreenChangeDiagnostic.ChangeScope.EDGE_ONLY, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(1, 100, ScreenChangeDiagnostic.ChangeScope.NO_PIXEL_CHANGE, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(0, 0, ScreenChangeDiagnostic.ChangeScope.NO_PIXEL_CHANGE, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            diagnostic(
                1,
                ScreenChangeDiagnostic.MAX_TOTAL_PIXELS + 1,
                ScreenChangeDiagnostic.ChangeScope.BROAD,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(0, 100, ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> diagnostic(-1, -1, ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE, -2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            diagnostic(
                -1,
                -1,
                ScreenChangeDiagnostic.ChangeScope.UNAVAILABLE,
                ScreenChangeDiagnostic.MAX_MILLIS_SINCE_FILL + 1));
  }

  @Test
  void checkpointCopiesKeepMetadataImmutableAndContainNoOpenEndedFields() {
    var original = diagnostic(3, 100, ScreenChangeDiagnostic.ChangeScope.LOCALIZED, 250);
    var copy = original.withCheckpoint(ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY);
    assertEquals(ScreenChangeDiagnostic.Checkpoint.BEFORE_READBACK, original.checkpoint());
    assertEquals(ScreenChangeDiagnostic.Checkpoint.AFTER_READBACK_VERIFY, copy.checkpoint());
    assertEquals(original.changedPixels(), copy.changedPixels());
    assertEquals(original.totalPixels(), copy.totalPixels());
    assertEquals(original.millisSinceFill(), copy.millisSinceFill());
    for (var component : ScreenChangeDiagnostic.class.getRecordComponents()) {
      assertTrue(component.getType() == long.class || component.getType().isEnum());
    }
    assertFalse(java.io.Serializable.class.isAssignableFrom(ScreenChangeDiagnostic.class));
    assertThrows(NullPointerException.class, () -> original.withCheckpoint(null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ScreenChangeDiagnostic(
                ScreenChangeDiagnostic.Checkpoint.OBSERVE,
                1,
                100,
                null,
                ScreenChangeDiagnostic.TripleConsistency.COHERENT,
                -1));
  }

  static ScreenChangeDiagnostic diagnostic(
      long changed, long total, ScreenChangeDiagnostic.ChangeScope scope, long elapsed) {
    return new ScreenChangeDiagnostic(
        ScreenChangeDiagnostic.Checkpoint.BEFORE_READBACK,
        changed,
        total,
        scope,
        ScreenChangeDiagnostic.TripleConsistency.COHERENT,
        elapsed);
  }
}
