package app.deskgrade.adapters.persistence;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGradingLogTest {
  @TempDir Path directory;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.ofHours(8));
  private static final String PREFIX = "grading-2026-10-21-";

  @Test
  void sizeRotationPreservesAllSegmentsAndReopenAppendsToLatest() throws Exception {
    Path logs = directory.resolve("records");
    var event = new ObjectMapper().createObjectNode().put("event", "TEST");
    try (var log = new LocalGradingLog(logs, CLOCK, 40)) {
      for (int i = 0; i < 5; i++) log.append(event);
    }
    List<Path> initial;
    try (var files = Files.list(logs)) {
      initial = files.sorted().toList();
    }
    assertEquals(3, initial.size());
    String preservedFirst = Files.readString(initial.getFirst());
    try (var reopened = new LocalGradingLog(logs, CLOCK, 40)) {
      reopened.append(event);
    }
    assertEquals(preservedFirst, Files.readString(initial.getFirst()));
    int lines = 0;
    try (var files = Files.list(logs)) {
      for (Path file : files.toList()) {
        assertTrue(Files.size(file) <= 40);
        lines += Files.readAllLines(file).size();
      }
    }
    assertEquals(6, lines);
  }

  @Test
  void truncatedOldLineIsPreservedAndNewEventsStartInNewSegment() throws Exception {
    Path logs = directory.resolve("records");
    Files.createDirectories(logs);
    Path incomplete = logs.resolve(PREFIX + "000001.jsonl");
    Files.writeString(incomplete, "{\"interrupted\":");
    try (var log = new LocalGradingLog(logs, CLOCK, 200)) {
      log.append(new ObjectMapper().createObjectNode().put("event", "RECOVERED"));
    }
    assertEquals("{\"interrupted\":", Files.readString(incomplete));
    assertEquals(
        "RECOVERED",
        new ObjectMapper()
            .readTree(Files.readString(logs.resolve(PREFIX + "000002.jsonl")))
            .path("event")
            .asText());
  }

  @Test
  void oversizedEventFailsClosedAndDoesNotWriteAPartialLine() throws Exception {
    Path logs = directory.resolve("records");
    try (var log = new LocalGradingLog(logs, CLOCK, LocalGradingLog.MAX_FILE_BYTES)) {
      var event =
          new ObjectMapper()
              .createObjectNode()
              .put("unsafe", "x".repeat(LocalGradingLog.MAX_EVENT_BYTES));
      assertEquals(
          ErrorCode.STORAGE_FAILURE,
          assertThrows(DeskGradeException.class, () -> log.append(event)).code());
      assertEquals(0, Files.size(logs.resolve(PREFIX + "000001.jsonl")));
      assertThrows(DeskGradeException.class, log::assertWritable);
    }
  }

  @Test
  void aDirectoryCannotMasqueradeAsDailyLog() throws Exception {
    Path logs = directory.resolve("records");
    Files.createDirectories(logs.resolve(PREFIX + "000001.jsonl"));
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(DeskGradeException.class, () -> new LocalGradingLog(logs, CLOCK, 200)).code());
  }

  @Test
  void closedWriterRejectsFurtherWrites() {
    var log = new LocalGradingLog(directory.resolve("records"), CLOCK, 200);
    log.close();
    assertThrows(DeskGradeException.class, log::assertWritable);
    assertThrows(DeskGradeException.class, () -> log.append(new ObjectMapper().createObjectNode()));
  }

  @Test
  void performanceFamilyNeverChangesAnExistingGradingTimeline() throws Exception {
    Path logs = directory.resolve("logs");
    var event = new ObjectMapper().createObjectNode().put("event", "SYNTHETIC");
    try (var grading = new LocalGradingLog(logs, CLOCK, 200)) {
      grading.append(event);
    }
    Path gradingFile = logs.resolve("grading-2026-10-21-000001.jsonl");
    byte[] original = Files.readAllBytes(gradingFile);
    try (var performance = new LocalGradingLog(logs, CLOCK, 200, "performance")) {
      performance.append(event);
    }
    try (var reopened = new LocalGradingLog(logs, CLOCK, 200, "performance")) {
      reopened.append(event);
    }
    assertArrayEquals(original, Files.readAllBytes(gradingFile));
    assertEquals(2, Files.readAllLines(logs.resolve("performance-2026-10-21-000001.jsonl")).size());
  }

  @Test
  void logFamilyCannotInjectAPathOrAnUnboundedName() {
    assertThrows(
        DeskGradeException.class,
        () -> new LocalGradingLog(directory.resolve("logs"), CLOCK, 200, "../../outside"));
    assertThrows(
        DeskGradeException.class,
        () -> new LocalGradingLog(directory.resolve("logs"), CLOCK, 200, "x".repeat(1000)));
  }
}
