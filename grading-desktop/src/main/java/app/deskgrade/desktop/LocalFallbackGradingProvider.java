package app.deskgrade.desktop;

import app.deskgrade.adapters.model.OpenAICompatibleVisionProvider;
import app.deskgrade.core.application.grading.GradeValidator;
import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.domain.QuestionDefinition;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.GradingProvider;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * One explicitly selected alternate model, on the same immutable answer, after invalid content
 * only.
 */
final class LocalFallbackGradingProvider implements FallbackGradingProvider, AutoCloseable {
  private final GradingProvider primary;
  private final GradingProvider fallback;
  private final String fallbackId;
  private Receipt last;
  private boolean closed;

  private static final class Receipt {
    final UUID id;
    final QuestionDefinition question;
    final byte[] digest;
    boolean complete;
    boolean eligible;
    boolean fallbackUsed;

    Receipt(GradingRequest request) {
      id = request.requestId();
      question = request.question();
      digest = digest(request.answerImage());
    }
  }

  LocalFallbackGradingProvider(
      OpenAICompatibleVisionProvider primary, OpenAICompatibleVisionProvider fallback) {
    this(primary, fallback, fallback == null ? null : fallback.profile().id());
  }

  LocalFallbackGradingProvider(
      GradingProvider primary, GradingProvider fallback, String fallbackId) {
    this.primary = Objects.requireNonNull(primary);
    this.fallback = fallback;
    this.fallbackId = fallbackId;
    if ((fallback == null) != (fallbackId == null))
      throw new IllegalArgumentException("Fallback profile required");
  }

  @Override
  public synchronized CompletionStage<GradingResponse> grade(
      GradingRequest request, CancellationToken token) {
    if (closed || token.isCancellationRequested())
      return CompletableFuture.failedFuture(
          new IllegalStateException("Local provider is closed or cancelled"));
    if (last != null && !last.complete)
      return CompletableFuture.failedFuture(
          new IllegalStateException("A local grading request is still running"));
    Receipt receipt = new Receipt(request);
    last = receipt;
    CompletionStage<GradingResponse> result;
    try {
      result = primary.grade(request, token);
    } catch (RuntimeException failure) {
      result = CompletableFuture.failedFuture(failure);
    }
    return result.whenComplete(
        (response, failure) -> {
          synchronized (LocalFallbackGradingProvider.this) {
            receipt.eligible = fallbackEligible(request, response, failure);
            receipt.complete = true;
          }
        });
  }

  @Override
  public Optional<String> fallbackProfileId() {
    return Optional.ofNullable(fallbackId);
  }

  @Override
  public synchronized CompletionStage<GradingResponse> gradeFallback(
      UUID primaryRequestId, GradingRequest request, CancellationToken token) {
    Receipt receipt = last;
    if (closed
        || token.isCancellationRequested()
        || fallback == null
        || receipt == null
        || !receipt.complete
        || !receipt.eligible
        || receipt.fallbackUsed
        || !receipt.id.equals(primaryRequestId)
        || !FallbackGradingProvider.fallbackRequestId(primaryRequestId).equals(request.requestId())
        || !receipt.question.equals(request.question())
        || !MessageDigest.isEqual(receipt.digest, digest(request.answerImage())))
      return CompletableFuture.failedFuture(
          new IllegalStateException("Local fallback is not authorized for this answer"));
    receipt.fallbackUsed = true;
    receipt.complete = false;
    CompletionStage<GradingResponse> result;
    try {
      result = fallback.grade(request, token);
    } catch (RuntimeException failure) {
      result = CompletableFuture.failedFuture(failure);
    }
    return result.whenComplete(
        (response, failure) -> {
          synchronized (LocalFallbackGradingProvider.this) {
            receipt.complete = true;
          }
        });
  }

  private static boolean fallbackEligible(
      GradingRequest request, GradingResponse response, Throwable failure) {
    if (failure != null) {
      Throwable cause = failure;
      for (int depth = 0;
          depth < 10 && cause.getCause() != null && cause.getCause() != cause;
          depth++) {
        if (cause instanceof ModelCallException model) return model.fallbackEligible();
        cause = cause.getCause();
      }
      return cause instanceof ModelCallException model && model.fallbackEligible();
    }
    if (response == null || !request.requestId().equals(response.requestId())) return true;
    try {
      return GradeValidator.validate(request.question(), response.result()).totalScore() == null;
    } catch (RuntimeException invalidResult) {
      return true;
    }
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    last = null;
    app.deskgrade.adapters.runtime.ResourceCleanup.closeAll(
        primary instanceof AutoCloseable closeable ? closeable : null,
        fallback instanceof AutoCloseable closeable ? closeable : null);
  }
}
