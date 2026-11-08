package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DesktopWorkspaceAnswerStabilityTest {
  @Test
  void acceptsDistinctBuffersOnlyWhenTheirBytesAndSha256AreExactlyEqual() {
    byte[] first = "student-answer".getBytes(StandardCharsets.UTF_8);
    byte[] second = Arrays.copyOf(first, first.length);

    assertDoesNotThrow(
        () -> DesktopWorkspace.requireStableAnswer(first, sha(first), second, sha(second)));
  }

  @Test
  void rejectsChangedAnswerBytesEvenWhenEachCaptureHasItsOwnValidSha256() {
    byte[] first = "student-answer-a".getBytes(StandardCharsets.UTF_8);
    byte[] second = "student-answer-b".getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalStateException.class,
        () -> DesktopWorkspace.requireStableAnswer(first, sha(first), second, sha(second)));
  }

  @Test
  void rejectsMissingMalformedOrMismatchedSha256Evidence() {
    byte[] answer = "student-answer".getBytes(StandardCharsets.UTF_8);
    String correct = sha(answer);
    String stale = sha("different-answer".getBytes(StandardCharsets.UTF_8));

    assertThrows(
        IllegalStateException.class,
        () -> DesktopWorkspace.requireStableAnswer(answer, null, answer, correct));
    assertThrows(
        IllegalStateException.class,
        () -> DesktopWorkspace.requireStableAnswer(answer, "not-a-sha256", answer, correct));
    assertThrows(
        IllegalStateException.class,
        () -> DesktopWorkspace.requireStableAnswer(answer, stale, answer, stale));
    assertThrows(
        IllegalStateException.class,
        () -> DesktopWorkspace.requireStableAnswer(answer, correct, answer, stale));
  }

  @Test
  void rejectsMissingCaptureBytes() {
    byte[] answer = "student-answer".getBytes(StandardCharsets.UTF_8);
    String correct = sha(answer);

    assertThrows(
        NullPointerException.class,
        () -> DesktopWorkspace.requireStableAnswer(null, correct, answer, correct));
    assertThrows(
        NullPointerException.class,
        () -> DesktopWorkspace.requireStableAnswer(answer, correct, null, correct));
  }

  private static String sha(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
