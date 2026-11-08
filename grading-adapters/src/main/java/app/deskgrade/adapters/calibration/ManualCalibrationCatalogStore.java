package app.deskgrade.adapters.calibration;

import app.deskgrade.core.domain.desktop.ManualPlatformCalibration;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Atomic, account-keyed collection of named position-only website calibrations. */
public final class ManualCalibrationCatalogStore {
  public static final int MAX_BYTES = 512 * 1024;
  public static final int MAX_PROFILES = 100;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(10)
                          .maxStringLength(2000)
                          .maxNumberLength(50)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final Path file;
  private final String ownerKey;
  private final ManualCalibrationJsonCodec calibrationCodec = new ManualCalibrationJsonCodec();

  public ManualCalibrationCatalogStore(Path file, String ownerKey) {
    if (file == null
        || file.getFileName() == null
        || !file.getFileName().toString().endsWith(".json")
        || ownerKey == null
        || !ownerKey.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("标定目录或登录用户标识不合法。");
    this.file = file.toAbsolutePath().normalize();
    this.ownerKey = ownerKey;
    if (this.file.getParent() == null) throw new IllegalArgumentException("标定目录不合法。");
  }

  public synchronized Snapshot load() {
    try {
      rejectLinks(file);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
        return new Snapshot(ownerKey, null, List.of(), false);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw storageFailure();
      try (var stream =
          Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw storageFailure();
        String json =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return decode(json);
      }
    } catch (IOException failure) {
      throw storageFailure();
    }
  }

  public synchronized Snapshot save(ManualPlatformCalibration value) {
    Objects.requireNonNull(value, "标定配置不能为空。");
    return mutate(
        current -> {
          var profiles = new ArrayList<>(current.profiles());
          int existingIndex = -1;
          for (int index = 0; index < profiles.size(); index++) {
            var candidate = profiles.get(index);
            if (candidate.id().equals(value.id())) existingIndex = index;
            else if (normalizedName(candidate.displayName())
                .equals(normalizedName(value.displayName())))
              throw new IllegalArgumentException("同一登录用户下的标定名称不能重复。");
          }
          if (existingIndex < 0) {
            if (value.revision() != 1) throw new IllegalArgumentException("新标定必须从版本 1 开始。");
            if (profiles.size() >= MAX_PROFILES)
              throw new IllegalArgumentException("每个登录用户最多保存 100 个网站标定。");
            profiles.add(value);
          } else {
            var previous = profiles.get(existingIndex);
            if (previous.equals(value)) {
              return Objects.equals(current.selectedId(), value.id())
                  ? current
                  : new Snapshot(ownerKey, value.id(), profiles, current.legacyImportComplete());
            }
            if (previous.revision() == Long.MAX_VALUE
                || value.revision() != previous.revision() + 1)
              throw new IllegalArgumentException("标定版本已变化；请重新加载后保存。");
            profiles.set(existingIndex, value);
          }
          profiles.sort(
              (left, right) ->
                  normalizedName(left.displayName())
                      .compareTo(normalizedName(right.displayName())));
          return new Snapshot(ownerKey, value.id(), profiles, current.legacyImportComplete());
        });
  }

  public synchronized Snapshot select(String id) {
    return mutate(
        current -> {
          if (id == null || current.profiles().stream().noneMatch(value -> value.id().equals(id)))
            throw new IllegalArgumentException("所选网站标定不存在。");
          return Objects.equals(current.selectedId(), id)
              ? current
              : new Snapshot(ownerKey, id, current.profiles(), current.legacyImportComplete());
        });
  }

  public synchronized Snapshot delete(String id) {
    return mutate(
        current -> {
          if (id == null) throw new IllegalArgumentException("请选择要删除的网站标定。");
          var profiles =
              current.profiles().stream().filter(value -> !value.id().equals(id)).toList();
          if (profiles.size() == current.profiles().size())
            throw new IllegalArgumentException("所选网站标定不存在。");
          return new Snapshot(
              ownerKey,
              id.equals(current.selectedId()) ? null : current.selectedId(),
              profiles,
              current.legacyImportComplete());
        });
  }

  public synchronized Snapshot completeLegacyImport() {
    return mutate(
        current ->
            current.legacyImportComplete()
                ? current
                : new Snapshot(ownerKey, current.selectedId(), current.profiles(), true));
  }

  private Snapshot mutate(UnaryOperator<Snapshot> change) {
    Path temporary = null;
    try {
      rejectLinks(file);
      Files.createDirectories(file.getParent());
      rejectLinks(file);
      Path locking = file.resolveSibling(file.getFileName() + ".lock");
      rejectLinks(locking);
      try (var lockChannel =
              FileChannel.open(
                  locking,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS);
          var processLock = lockChannel.tryLock()) {
        if (processLock == null) throw storageFailure();
        Snapshot current = load();
        Snapshot next = Objects.requireNonNull(change.apply(current));
        if (!ownerKey.equals(next.ownerKey())) throw storageFailure();
        if (current.equals(next)) return current;
        byte[] bytes = encode(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw storageFailure();
        temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (var channel =
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) channel.write(buffer);
          channel.force(true);
        }
        rejectLinks(file);
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        temporary = null;
        return next;
      }
    } catch (IOException | java.nio.channels.OverlappingFileLockException failure) {
      throw storageFailure();
    } finally {
      if (temporary != null
          && temporary.getParent().equals(file.getParent())
          && temporary.getFileName().toString().startsWith(file.getFileName() + ".")) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private Snapshot decode(String json) {
    try {
      if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
      JsonNode root = MAPPER.readTree(json);
      fields(root, "schemaVersion", "ownerKey", "selectedId", "profiles", "legacyImportComplete");
      if (!root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").intValue() != 1
          || !root.path("ownerKey").isTextual()
          || !ownerKey.equals(root.path("ownerKey").textValue())
          || !root.path("legacyImportComplete").isBoolean()
          || !root.path("profiles").isArray()
          || root.path("profiles").size() > MAX_PROFILES) throw invalid();
      String selected =
          root.path("selectedId").isNull() ? null : requiredText(root.path("selectedId"), 100);
      var profiles = new ArrayList<ManualPlatformCalibration>();
      root.path("profiles").forEach(node -> profiles.add(calibrationCodec.read(node.toString())));
      return new Snapshot(
          ownerKey, selected, profiles, root.path("legacyImportComplete").booleanValue());
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private String encode(Snapshot snapshot) {
    try {
      var root =
          MAPPER
              .createObjectNode()
              .put("schemaVersion", 1)
              .put("ownerKey", ownerKey)
              .put("legacyImportComplete", snapshot.legacyImportComplete());
      if (snapshot.selectedId() == null) root.putNull("selectedId");
      else root.put("selectedId", snapshot.selectedId());
      var profiles = root.putArray("profiles");
      for (var calibration : snapshot.profiles())
        profiles.add(MAPPER.readTree(calibrationCodec.write(calibration)));
      return root.toPrettyString();
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static void fields(JsonNode json, String... expected) {
    if (json == null || !json.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    json.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(expected))) throw invalid();
  }

  private static String requiredText(JsonNode value, int max) {
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > max) throw invalid();
    return value.textValue();
  }

  private static String normalizedName(String value) {
    return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  private static void rejectLinks(Path path) throws IOException {
    Path current = path;
    while (current != null) {
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        BasicFileAttributes attributes =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) throw storageFailure();
      }
      current = current.getParent();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("网站标定目录格式、归属用户或内容不合法；不会加载执行权限。");
  }

  private static IllegalStateException storageFailure() {
    return new IllegalStateException("无法安全读写当前登录用户的网站标定目录；原配置未获得执行权限。");
  }

  public record Snapshot(
      String ownerKey,
      String selectedId,
      List<ManualPlatformCalibration> profiles,
      boolean legacyImportComplete) {
    public Snapshot {
      if (ownerKey == null || !ownerKey.matches("[a-f0-9]{64}") || profiles == null)
        throw invalid();
      profiles = List.copyOf(profiles);
      if (profiles.size() > MAX_PROFILES) throw invalid();
      var ids = new HashSet<String>();
      var names = new HashSet<String>();
      for (var profile : profiles) {
        if (profile == null
            || !ids.add(profile.id())
            || !names.add(normalizedName(profile.displayName()))) throw invalid();
      }
      if (selectedId != null && !ids.contains(selectedId)) throw invalid();
    }
  }
}
