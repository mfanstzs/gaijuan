package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeskGradeShellLimitTest {
  @Test
  void acceptsOnlyAOneToTenThousandSubmissionLimit() {
    assertEquals(1, DeskGradeShell.parseSubmissionLimit("1"));
    assertEquals(5, DeskGradeShell.parseSubmissionLimit("5"));
    assertEquals(10_000, DeskGradeShell.parseSubmissionLimit("10000"));
    assertEquals(1, DeskGradeShell.parseSubmissionLimit("00001"));

    assertEquals(0, DeskGradeShell.parseSubmissionLimit(null));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit(""));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit("0"));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit("10001"));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit("-1"));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit("5.0"));
    assertEquals(0, DeskGradeShell.parseSubmissionLimit("五"));
  }
}
