package app.deskgrade.adapters.model;

import app.deskgrade.core.port.CancellationToken;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One logical grading request; only proven pre-body connection timeouts may reconnect. */
final class SafeConnectRetryTransport {
  static final int MAX_ATTEMPTS = 3;
  static final Duration RETRY_DELAY = Duration.ofSeconds(20);
  private static final Duration MAX_LOGICAL_BUDGET = Duration.ofMinutes(10);

  @FunctionalInterface
  interface Sender {
    CompletableFuture<HttpResponse<byte[]>> send(
        HttpRequest request, HttpResponse.BodyHandler<byte[]> handler);
  }

  @FunctionalInterface
  interface ScheduledRetry {
    void cancel();
  }

  @FunctionalInterface
  interface Scheduler {
    ScheduledRetry schedule(Runnable operation, Duration delay);
  }

  private final HttpRequest original;
  private final HttpRequest.BodyPublisher originalBody;
  private final Duration requestTimeout;
  private final Duration budget;
  private final CancellationToken cancellation;
  private final ModelCallTrace trace;
  private final Sender sender;
  private final Scheduler scheduler;
  private final LongSupplier nanoTime;
  private final HttpResponse.BodyHandler<byte[]> bodyHandler;
  private final AtomicBoolean bodyEverSubscribed = new AtomicBoolean();
  private final AtomicBoolean responseObserved = new AtomicBoolean();
  private final CompletableFuture<HttpResponse<byte[]>> result =
      new CompletableFuture<>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          // Linearize cancellation with the send decision. An already committed send cannot be
          // recalled, but cancellation must not finish before a later retry starts sending.
          synchronized (SafeConnectRetryTransport.this) {
            return super.cancel(mayInterruptIfRunning);
          }
        }
      };
  private final long started;
  private CompletableFuture<HttpResponse<byte[]>> network;
  private ScheduledRetry scheduled;
  private Throwable retryFailure;

  SafeConnectRetryTransport(
      HttpRequest original,
      Duration connectTimeout,
      Duration requestTimeout,
      CancellationToken cancellation,
      ModelCallTrace trace,
      Sender sender,
      Scheduler scheduler,
      LongSupplier nanoTime,
      HttpResponse.BodyHandler<byte[]> bodyHandler) {
    this.original = Objects.requireNonNull(original);
    this.originalBody = original.bodyPublisher().orElseThrow();
    this.requestTimeout = Objects.requireNonNull(requestTimeout);
    this.budget = logicalBudget(connectTimeout, requestTimeout);
    this.cancellation = Objects.requireNonNull(cancellation);
    this.trace = Objects.requireNonNull(trace);
    this.sender = Objects.requireNonNull(sender);
    this.scheduler = Objects.requireNonNull(scheduler);
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.bodyHandler = Objects.requireNonNull(bodyHandler);
    started = nanoTime.getAsLong();
    result.whenComplete((ignored, failure) -> cleanup());
  }

  static Duration logicalBudget(Duration connectTimeout, Duration requestTimeout) {
    Objects.requireNonNull(connectTimeout);
    Objects.requireNonNull(requestTimeout);
    if (connectTimeout.isZero()
        || connectTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.isNegative())
      throw new IllegalArgumentException("Invalid transport budget");
    Duration connect =
        connectTimeout.compareTo(requestTimeout) < 0 ? connectTimeout : requestTimeout;
    Duration total = requestTimeout.plus(connect.multipliedBy(2)).plus(RETRY_DELAY.multipliedBy(2));
    return total.compareTo(MAX_LOGICAL_BUDGET) > 0 ? MAX_LOGICAL_BUDGET : total;
  }

  CompletableFuture<HttpResponse<byte[]>> start() {
    attempt(1);
    return result;
  }

  CompletableFuture<HttpResponse<byte[]>> future() {
    return result;
  }

  private void attempt(int number) {
    try {
      synchronized (this) {
        scheduled = null;
        if (result.isDone()) return;
        checkCancellation();
        if (!retryBoundaryStillSafe(number)) return;
      }
      // Authorization may consult durable storage; do not block cancellation while it runs.
      if (number > 1) trace.authorizeConnectRetry(number);
      synchronized (this) {
        if (result.isDone()) return;
        checkCancellation();
        if (!retryBoundaryStillSafe(number)) return;
        Duration remaining = remaining();
        if (remaining.isZero())
          throw new HttpTimeoutException("Logical model request deadline exceeded");
        Duration timeout = requestTimeout.compareTo(remaining) < 0 ? requestTimeout : remaining;
        HttpRequest request =
            HttpRequest.newBuilder(original, (name, value) -> true)
                .timeout(timeout)
                .method(original.method(), trackedBody())
                .build();
        network =
            sender.send(
                request,
                info -> {
                  responseObserved.set(true);
                  return bodyHandler.apply(info);
                });
        network.whenComplete((response, failure) -> completed(number, response, failure));
      }
    } catch (RuntimeException | LinkageError | HttpTimeoutException failure) {
      result.completeExceptionally(failure);
    }
  }

  private synchronized void completed(
      int number, HttpResponse<byte[]> response, Throwable failure) {
    if (result.isDone()) return;
    try {
      checkCancellation();
      if (failure == null) {
        result.complete(response);
        return;
      }
      if (number < MAX_ATTEMPTS
          && isConnectTimeout(failure)
          && !bodyEverSubscribed.get()
          && !responseObserved.get()
          && remaining().compareTo(RETRY_DELAY) > 0) {
        int next = number + 1;
        retryFailure = failure;
        trace.connectRetryScheduled(next, RETRY_DELAY);
        checkCancellation();
        scheduled = scheduler.schedule(() -> attempt(next), RETRY_DELAY);
      } else result.completeExceptionally(failure);
    } catch (RuntimeException | LinkageError invalidated) {
      result.completeExceptionally(invalidated);
    }
  }

  private HttpRequest.BodyPublisher trackedBody() {
    return new HttpRequest.BodyPublisher() {
      @Override
      public long contentLength() {
        return originalBody.contentLength();
      }

      @Override
      public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        // Sticky across the complete logical call, including JDK-internal exchanges/retries.
        // A late connect timeout must not conceal an earlier attempt which exposed request data.
        bodyEverSubscribed.set(true);
        originalBody.subscribe(subscriber);
      }
    };
  }

  private static boolean isConnectTimeout(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (current instanceof HttpConnectTimeoutException) return true;
      if (!(current instanceof CompletionException || current instanceof ExecutionException))
        return false;
      current = current.getCause();
    }
    return false;
  }

  private Duration remaining() {
    long elapsed = Math.max(0, nanoTime.getAsLong() - started);
    long nanos = Math.max(0, budget.toNanos() - Math.min(elapsed, budget.toNanos()));
    return Duration.ofNanos(nanos);
  }

  private void checkCancellation() {
    if (cancellation.isCancellationRequested())
      throw new ProviderException(ProviderException.Failure.CANCELLED);
  }

  private boolean retryBoundaryStillSafe(int number) {
    if (number == 1 || (!bodyEverSubscribed.get() && !responseObserved.get())) return true;
    // A late callback from the previous exchange invalidates its earlier pre-body observation.
    // Preserve the original timeout classification rather than disguising it as a new failure.
    result.completeExceptionally(
        retryFailure != null
            ? retryFailure
            : new ProviderException(ProviderException.Failure.INTERNAL_ERROR));
    return false;
  }

  private synchronized void cleanup() {
    if (scheduled != null) scheduled.cancel();
    scheduled = null;
    retryFailure = null;
    if (network != null && !network.isDone()) network.cancel(true);
  }
}
