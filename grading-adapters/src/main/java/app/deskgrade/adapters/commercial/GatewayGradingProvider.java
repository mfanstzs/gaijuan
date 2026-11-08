package app.deskgrade.adapters.commercial;

import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.model.Rubric;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** A single fixed rubric/model selection, permanently bound to the originating gateway login. */
public final class GatewayGradingProvider implements FallbackGradingProvider, AutoCloseable {
  private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  private final CommercialClient client;
  private final Rubric rubric;
  private final CommercialClient.Binding binding;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final java.util.Set<CompletableFuture<GradingResponse>> pending =
      ConcurrentHashMap.newKeySet();

  public GatewayGradingProvider(CommercialClient client, Rubric rubric, String serverProfileId) {
    this.client = Objects.requireNonNull(client);
    this.rubric = Objects.requireNonNull(rubric);
    rubric.validateReady();
    binding = client.bindProfile(serverProfileId);
  }

  @Override
  public CompletionStage<GradingResponse> grade(GradingRequest request, CancellationToken token) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(token);
    if (closed.get() || token.isCancellationRequested())
      return failed(GatewayException.Code.CANCELLED);
    if (!rubric.question().equals(request.question()))
      return failed(GatewayException.Code.INVALID_REQUEST);
    byte[] png = request.answerImage();
    if (!validPng(png)) return failed(GatewayException.Code.INVALID_REQUEST);
    var response =
        client
            .grade(
                binding,
                rubric,
                request,
                png,
                () -> closed.get() || token.isCancellationRequested())
            .toCompletableFuture();
    pending.add(response);
    response.whenComplete((value, failure) -> pending.remove(response));
    if (closed.get())
      response.completeExceptionally(new GatewayException(GatewayException.Code.CANCELLED));
    return response;
  }

  @Override
  public Optional<String> fallbackProfileId() {
    return binding.fallbackProfileId();
  }

  @Override
  public CompletionStage<GradingResponse> gradeFallback(
      UUID primaryRequestId, GradingRequest request, CancellationToken token) {
    Objects.requireNonNull(primaryRequestId);
    Objects.requireNonNull(request);
    Objects.requireNonNull(token);
    if (closed.get() || token.isCancellationRequested())
      return failed(GatewayException.Code.CANCELLED);
    if (fallbackProfileId().isEmpty()) return failed(GatewayException.Code.MODEL_UNAVAILABLE);
    if (!request.requestId().equals(FallbackGradingProvider.fallbackRequestId(primaryRequestId)))
      return failed(GatewayException.Code.INVALID_REQUEST);
    if (!rubric.question().equals(request.question()))
      return failed(GatewayException.Code.INVALID_REQUEST);
    byte[] png = request.answerImage();
    if (!validPng(png)) return failed(GatewayException.Code.INVALID_REQUEST);
    var response =
        client
            .gradeFallback(
                binding,
                primaryRequestId,
                rubric,
                request,
                png,
                () -> closed.get() || token.isCancellationRequested())
            .toCompletableFuture();
    pending.add(response);
    response.whenComplete((value, failure) -> pending.remove(response));
    if (closed.get())
      response.completeExceptionally(new GatewayException(GatewayException.Code.CANCELLED));
    return response;
  }

  @Override
  public void close() {
    closed.set(true);
    for (var response : pending)
      response.completeExceptionally(new GatewayException(GatewayException.Code.CANCELLED));
    pending.clear();
  }

  private static CompletionStage<GradingResponse> failed(GatewayException.Code code) {
    return CompletableFuture.failedFuture(new GatewayException(code));
  }

  static boolean validPng(byte[] png) {
    if (png.length < 45
        || png.length > 10 * 1024 * 1024
        || !Arrays.equals(SIGNATURE, Arrays.copyOf(png, SIGNATURE.length))) return false;
    try {
      int offset = 8;
      boolean first = true, data = false, end = false;
      int width = 0, height = 0;
      while (offset < png.length) {
        if (png.length - offset < 12) return false;
        int length = ByteBuffer.wrap(png, offset, 4).getInt();
        if (length < 0 || length > png.length - offset - 12) return false;
        int type = ByteBuffer.wrap(png, offset + 4, 4).getInt();
        CRC32 crc = new CRC32();
        crc.update(png, offset + 4, length + 4);
        long checksum =
            Integer.toUnsignedLong(ByteBuffer.wrap(png, offset + 8 + length, 4).getInt());
        if (checksum != crc.getValue()) return false;
        if (first) {
          if (type != 0x49484452 || length != 13) return false; // IHDR
          width = ByteBuffer.wrap(png, offset + 8, 4).getInt();
          height = ByteBuffer.wrap(png, offset + 12, 4).getInt();
          if (width < 1 || height < 1 || (long) width * height > 16_000_000) return false;
          first = false;
        } else if (type == 0x49484452) return false;
        if (type == 0x49444154) data = true; // IDAT
        offset += length + 12;
        if (type == 0x49454e44) { // IEND
          if (length != 0 || offset != png.length || !data) return false;
          end = true;
          break;
        }
      }
      if (!end) return false;
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
        var readers = ImageIO.getImageReadersByFormatName("PNG");
        if (!readers.hasNext()) return false;
        var reader = readers.next();
        try {
          reader.setInput(input, true, true);
          var decoded = reader.read(0);
          if (decoded == null) return false;
          try {
            return decoded.getWidth() == width && decoded.getHeight() == height;
          } finally {
            decoded.flush();
          }
        } finally {
          reader.dispose();
        }
      }
    } catch (Exception invalid) {
      return false;
    }
  }
}
