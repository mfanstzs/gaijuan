package app.deskgrade.core.port;

import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * A server-authorized, single alternate-model attempt for an already charged primary request.
 *
 * <p>The derived request id is an idempotency key, not an authentication token. Implementations
 * must still prove that the primary receipt, evidence, rubric and server policy are eligible.
 */
public interface FallbackGradingProvider extends GradingProvider {
  Optional<String> fallbackProfileId();

  CompletionStage<GradingResponse> gradeFallback(
      UUID primaryRequestId, GradingRequest fallbackRequest, CancellationToken cancellationToken);

  static UUID fallbackRequestId(UUID primaryRequestId) {
    if (primaryRequestId == null) throw new IllegalArgumentException("Primary request is required");
    byte[] digest;
    try {
      var sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update("deskgrade:model-fallback:v1\0".getBytes(StandardCharsets.UTF_8));
      digest = sha256.digest(primaryRequestId.toString().getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    byte[] id = Arrays.copyOf(digest, 16);
    Arrays.fill(digest, (byte) 0);
    id[6] = (byte) ((id[6] & 0x0f) | 0x80); // RFC 9562 UUIDv8 custom payload.
    id[8] = (byte) ((id[8] & 0x3f) | 0x80);
    var bytes = ByteBuffer.wrap(id);
    UUID result = new UUID(bytes.getLong(), bytes.getLong());
    Arrays.fill(id, (byte) 0);
    return result;
  }
}
