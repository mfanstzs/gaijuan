package app.deskgrade.adapters.persistence;

import app.deskgrade.core.application.session.GradingRecords;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Streams every matching page as fixed JSON, never reads or copies a captured image. */
public final class RecordsJsonExporter {
  private RecordsJsonExporter() {}

  public static void write(
      Path destination,
      GradingRecords records,
      GradingRecords.Owner owner,
      GradingRecords.Query query) {
    Path temporary = null;
    try {
      Path target = destination.toAbsolutePath().normalize();
      if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
          || !Files.isDirectory(target.getParent())
          || Files.isSymbolicLink(target)
          || Files.isDirectory(target)) throw new IllegalArgumentException();
      temporary =
          target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
      try (FileChannel channel =
              FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          var writer =
              new java.io.BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
        writer.write("{\"schemaVersion\":2,\"imageBytesIncluded\":false,\"records\":[");
        boolean first = true;
        for (int page = 0; ; page++) {
          var result = records.queryRecords(owner, query.atPage(page));
          for (var row : result.rows()) {
            if (!first) writer.write(',');
            first = false;
            writer.write(records.recordJson(owner, row.attemptId()));
          }
          if (!result.hasNext()) break;
        }
        writer.write("]}");
        writer.flush();
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
    } catch (Exception failure) {
      throw new DeskGradeException(ErrorCode.STORAGE_FAILURE);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (Exception ignored) {
        }
    }
  }
}
