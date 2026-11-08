package app.deskgrade.adapters.persistence;

import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Local-only JSONL metadata, forced to disk before returning. Files roll at 16 MiB without deleting
 * history. Only typed local record/performance adapters are event producers; HTTP bodies are never
 * accepted by those adapters.
 */
final class LocalGradingLog implements AutoCloseable {
  static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
  static final int MAX_EVENT_BYTES = 16 * 1024;
  private static final int MAX_SEQUENCE = 999999;
  private final Path directory;
  private final Clock clock;
  private final long maxFileBytes;
  private final String family;
  private LocalDate activeDay;
  private Path activeFile;
  private int activeSequence;
  private boolean failed;
  private boolean closed;

  LocalGradingLog(Path directory) {
    this(directory, Clock.systemDefaultZone(), MAX_FILE_BYTES);
  }

  LocalGradingLog(Path directory, Clock clock, long maxFileBytes) {
    this(directory, clock, maxFileBytes, "grading");
  }

  LocalGradingLog(Path directory, Clock clock, long maxFileBytes, String family) {
    this.directory = directory.toAbsolutePath().normalize();
    this.clock = java.util.Objects.requireNonNull(clock);
    this.maxFileBytes = maxFileBytes;
    if (!java.util.Set.of("grading", "performance").contains(family)) throw storageFailure();
    this.family = family;
    if (maxFileBytes < 1 || maxFileBytes > MAX_FILE_BYTES) throw storageFailure();
    try {
      rejectLinkedAncestors(this.directory);
      Files.createDirectories(this.directory);
      rejectLinkedAncestors(this.directory);
      // The mandatory grading ledger probes its append target eagerly. Optional performance
      // summaries use one force per actual summary, without a second empty-file force first.
      if ("grading".equals(family)) assertWritable();
    } catch (Exception failure) {
      failed = true;
      throw storageFailure();
    }
  }

  synchronized void assertWritable() {
    checkAvailable();
    try {
      selectFile(LocalDate.now(clock));
      try (FileChannel channel = open()) {
        channel.force(true);
      }
    } catch (Exception failure) {
      failed = true;
      throw storageFailure();
    }
  }

  synchronized void append(ObjectNode event) {
    checkAvailable();
    try {
      byte[] encoded = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
      if (encoded.length > MAX_EVENT_BYTES || encoded.length > maxFileBytes) throw storageFailure();
      selectFile(LocalDate.now(clock));
      // A prior process can have died after writing a partial line. Preserve that file and start a
      // new segment, rather than completing/corrupting it or truncating diagnostic evidence.
      if (!endsInNewline(activeFile) || Files.size(activeFile) + encoded.length > maxFileBytes)
        advanceFile();
      try (FileChannel channel = open();
          var lock = channel.lock()) {
        ByteBuffer bytes = ByteBuffer.wrap(encoded);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
    } catch (Exception failure) {
      failed = true;
      throw storageFailure();
    }
  }

  private void selectFile(LocalDate day) throws Exception {
    rejectLinkedAncestors(directory);
    if (day.equals(activeDay) && activeFile != null) {
      requireRegularFile(activeFile);
      return;
    }
    activeDay = day;
    activeSequence = 0;
    String prefix = family + "-" + day + "-";
    try (var files = Files.newDirectoryStream(directory, prefix + "*.jsonl")) {
      int count = 0;
      for (Path file : files) {
        if (++count > MAX_SEQUENCE) throw storageFailure();
        String name = file.getFileName().toString();
        if (!name.matches(java.util.regex.Pattern.quote(prefix) + "[0-9]{6}\\.jsonl")) continue;
        requireRegularFile(file);
        activeSequence =
            Math.max(
                activeSequence,
                Integer.parseInt(name.substring(prefix.length(), prefix.length() + 6)));
      }
    }
    if (activeSequence == 0) activeSequence = 1;
    activeFile = fileFor(activeSequence);
    createOrCheck(activeFile);
  }

  private void advanceFile() throws Exception {
    do {
      if (activeSequence >= MAX_SEQUENCE) throw storageFailure();
      activeFile = fileFor(++activeSequence);
      if (!Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS)) {
        Files.createFile(activeFile);
        return;
      }
      requireRegularFile(activeFile);
    } while (true);
  }

  private Path fileFor(int sequence) {
    return directory.resolve(
        family + "-" + activeDay + "-" + String.format(Locale.ROOT, "%06d", sequence) + ".jsonl");
  }

  private static void createOrCheck(Path file) throws Exception {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.createFile(file);
    requireRegularFile(file);
  }

  private FileChannel open() throws Exception {
    rejectLinkedAncestors(directory);
    requireRegularFile(activeFile);
    return FileChannel.open(
        activeFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean endsInNewline(Path file) throws Exception {
    requireRegularFile(file);
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long size = channel.size();
      if (size == 0) return true;
      var last = ByteBuffer.allocate(1);
      if (channel.read(last, size - 1) != 1) throw storageFailure();
      return last.array()[0] == '\n';
    }
  }

  private static void requireRegularFile(Path file) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw storageFailure();
  }

  private static void rejectLinkedAncestors(Path path) {
    for (Path current = path; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) throw storageFailure();
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
          && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw storageFailure();
    }
  }

  private void checkAvailable() {
    if (failed || closed) throw storageFailure();
  }

  @Override
  public synchronized void close() {
    closed = true;
  }

  private static DeskGradeException storageFailure() {
    return new DeskGradeException(ErrorCode.STORAGE_FAILURE);
  }
}
