package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.SessionJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class RecordsCsvExporter {
  private RecordsCsvExporter() {}

  public static void write(Path target, List<SessionJournal.AttemptRecord> rows)
      throws IOException {
    StringBuilder csv = new StringBuilder("\uFEFF答卷编号,题号,分数,状态,提交意图,已确认提交,截图,评分数据\r\n");
    for (var row : rows) {
      csv.append(cell(row.answerId()))
          .append(',')
          .append(cell(row.questionId()))
          .append(',')
          .append(cell(row.score() == null ? "" : row.score().toPlainString()))
          .append(',')
          .append(cell(row.state().name()))
          .append(',')
          .append(row.submissionPrepared())
          .append(',')
          .append(row.confirmed())
          .append(',')
          .append(cell(row.evidencePath()))
          .append(',')
          .append(cell(row.resultJson()))
          .append("\r\n");
    }
    Files.writeString(target, csv, StandardCharsets.UTF_8);
  }

  public static String cell(String value) {
    String text = value == null ? "" : value;
    int first = 0;
    while (first < text.length()) {
      int codePoint = text.codePointAt(first);
      if (!(Character.isWhitespace(codePoint)
          || Character.isSpaceChar(codePoint)
          || Character.isISOControl(codePoint)
          || codePoint == 0xfeff
          || codePoint == 0x200b)) break;
      first += Character.charCount(codePoint);
    }
    if (text.indexOf('\t') == 0
        || text.indexOf('\r') == 0
        || text.indexOf('\n') == 0
        || (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0)) {
      text = "'" + text;
    }
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
