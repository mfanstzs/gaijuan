package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.SessionJournal;
import app.deskgrade.core.application.session.SessionState;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordsCsvExporterTest {
  @TempDir Path directory;

  @Test
  void formulaMarkersAfterAsciiUnicodeWhitespaceOrBomAreEscaped() {
    for (String value :
        List.of(
            "=1+1",
            "+1",
            "-1",
            "@SUM(A1)",
            "  =1",
            "\t=1",
            "\r\n+1",
            "\u00a0=1",
            "\u2003@x",
            "\ufeff=1",
            "\u200b=1")) {
      assertTrue(RecordsCsvExporter.cell(value).startsWith("\"'"), value);
    }
  }

  @Test
  void quoteCommaAndNewlineRemainSingleQuotedCell() {
    assertEquals("\"a,\"\"quoted\"\"\ntext\"", RecordsCsvExporter.cell("a,\"quoted\"\ntext"));
    assertEquals("\"\"", RecordsCsvExporter.cell(null));
    assertEquals("\"正常答案\"", RecordsCsvExporter.cell("正常答案"));
  }

  @Test
  void exportPreservesUnknownScoresAndConfirmedStatusWithoutImpliedSuccess() throws Exception {
    Path target = directory.resolve("records.csv");
    var unknown =
        new SessionJournal.AttemptRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            " =1+1",
            "题1",
            SessionState.SUBMISSION_UNKNOWN,
            null,
            true,
            false,
            "截图.png",
            "{}");
    var scored =
        new SessionJournal.AttemptRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "answer2",
            "题1",
            SessionState.WAIT_NEXT,
            new BigDecimal("0.10"),
            true,
            true,
            "other.png",
            "{}");
    RecordsCsvExporter.write(target, List.of(unknown, scored));
    String csv = Files.readString(target);
    assertTrue(csv.startsWith("\ufeff"));
    assertTrue(csv.contains("\"' =1+1\",\"题1\",\"\",\"SUBMISSION_UNKNOWN\",true,false"));
    assertTrue(csv.contains("\"0.10\",\"WAIT_NEXT\",true,true"));
  }
}
