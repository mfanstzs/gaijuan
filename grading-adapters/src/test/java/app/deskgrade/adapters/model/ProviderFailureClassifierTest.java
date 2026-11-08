package app.deskgrade.adapters.model;

import static app.deskgrade.adapters.model.ProviderException.Diagnostic.*;
import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.application.session.FailureDiagnostic;
import app.deskgrade.core.application.session.SessionState;
import app.deskgrade.core.domain.model.ModelCallException;
import app.deskgrade.core.error.DeskGradeException;
import app.deskgrade.core.error.ErrorCode;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ProviderFailureClassifierTest {
  private static final String PRIVATE =
      "student-private-answer bearer secret-token example.private";

  @Test
  void classifiesDnsConnectTimeoutAndRequestTimeoutWithoutLosingTypedWrappers() {
    assertCode(MODEL_DNS_FAILURE, new UnknownHostException(PRIVATE));
    assertCode(MODEL_DNS_FAILURE, new UnresolvedAddressException());
    var connectTimeout = new HttpConnectTimeoutException(PRIVATE);
    connectTimeout.initCause(new ConnectException(PRIVATE));
    assertCode(
        MODEL_CONNECT_TIMEOUT, new CompletionException(new ExecutionException(connectTimeout)));
    var requestTimeout = new HttpTimeoutException(PRIVATE);
    requestTimeout.initCause(new CancellationException(PRIVATE));
    assertCode(MODEL_REQUEST_TIMEOUT, new CompletionException(requestTimeout));
    assertCode(MODEL_SOCKET_TIMEOUT, new SocketTimeoutException(PRIVATE));
  }

  @Test
  void classifiesTlsHandshakeSeparatelyFromOtherTlsFailures() {
    var handshake = new SSLHandshakeException(PRIVATE);
    handshake.initCause(new IOException(PRIVATE));
    assertCode(MODEL_TLS_HANDSHAKE_FAILED, new CompletionException(handshake));
    assertCode(MODEL_TLS_FAILURE, new SSLException(PRIVATE));
  }

  @Test
  void classifiesConnectionErrorsOnlyWhenKnownRatherThanGuessingFromArbitraryText() {
    assertCode(MODEL_CONNECTION_REFUSED, new ConnectException("Connection refused"));
    assertCode(MODEL_CONNECT_FAILED, new ConnectException(PRIVATE));
    assertCode(MODEL_NETWORK_UNREACHABLE, new NoRouteToHostException(PRIVATE));
    assertCode(MODEL_CONNECTION_RESET, new SocketException("Connection reset by peer"));
    assertCode(MODEL_CONNECTION_CLOSED, new SocketException("Broken pipe"));
    assertCode(MODEL_CONNECTION_CLOSED, new EOFException(PRIVATE));
    assertCode(MODEL_CONNECTION_CLOSED, new ClosedChannelException());
    assertCode(MODEL_SOCKET_FAILURE, new SocketException(PRIVATE + " connection reset"));
    assertCode(MODEL_IO_FAILURE, new IOException(PRIVATE));
  }

  @Test
  void interruptionAndCancellationRemainNonRetryableAndNeverFallbackEligible() {
    for (Throwable failure :
        new Throwable[] {new InterruptedException(PRIVATE), new CancellationException(PRIVATE)}) {
      var sanitized = assertCode(MODEL_CANCELLED, new CompletionException(failure));
      assertFalse(sanitized.retryable());
      assertFalse(sanitized.fallbackEligible());
    }
  }

  @Test
  void unknownRuntimeFailureIsNotMisreportedAsNetworkOrModelContractFailure() {
    var sanitized = assertCode(MODEL_INTERNAL_ERROR, new IllegalStateException(PRIVATE));
    assertEquals(ProviderException.Failure.INTERNAL_ERROR, sanitized.failure());
    assertEquals(ModelCallException.Kind.INTERNAL_ERROR, sanitized.kind());
    assertFalse(sanitized.retryable());
    assertFalse(sanitized.fallbackEligible());
    assertCode(MODEL_INTERNAL_ERROR, new DeskGradeException(ErrorCode.STORAGE_FAILURE));
  }

  @Test
  void existingProviderFailuresPreserveHttpStatusAndFallbackSemantics() {
    var existing = new ProviderException(ProviderException.Failure.SERVER_ERROR, 503);
    assertSame(existing, ProviderFailureClassifier.sanitize(new CompletionException(existing)));
    assertEquals(503, existing.httpStatus());
    assertEquals("MODEL_SERVER_ERROR", existing.diagnosticCode());
    assertTrue(existing.retryable());
    assertFalse(existing.fallbackEligible());
    var invalid = new ProviderException(ProviderException.Failure.INVALID_RESULT);
    assertTrue(invalid.retryable());
    assertTrue(invalid.fallbackEligible());
    assertSame(invalid, ProviderFailureClassifier.sanitize(invalid));
  }

  @Test
  void constructorRejectsDiagnosticThatDoesNotMatchItsBroadFailure() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderException(
                ProviderException.Failure.INVALID_RESULT, null, MODEL_CONNECTION_RESET));
    for (var failure : ProviderException.Failure.values()) {
      var sanitized = new ProviderException(failure);
      assertEquals("MODEL_" + failure.name(), sanitized.diagnosticCode());
    }
  }

  @Test
  void boundedTraversalHandlesCyclesAndCannotRetainUnexpectedCauseText() {
    var first = new IOException(PRIVATE);
    var second = new IOException(PRIVATE, first);
    first.initCause(second);
    assertCode(MODEL_IO_FAILURE, first);
    Throwable nested = new IllegalStateException(PRIVATE);
    for (int depth = 0; depth < 100; depth++) nested = new CompletionException(nested);
    assertCode(MODEL_INTERNAL_ERROR, nested);
    assertCode(MODEL_INTERNAL_ERROR, null);
  }

  private static ProviderException assertCode(
      ProviderException.Diagnostic expected, Throwable failure) {
    var sanitized = ProviderFailureClassifier.sanitize(failure);
    assertEquals(expected, sanitized.diagnostic());
    assertEquals(expected.name(), sanitized.diagnosticCode());
    assertNull(sanitized.getCause());
    assertEquals(0, sanitized.getSuppressed().length);
    assertFalse(sanitized.toString().contains(PRIVATE));
    var stack = new StringWriter();
    sanitized.printStackTrace(new PrintWriter(stack));
    assertFalse(stack.toString().contains(PRIVATE));
    assertEquals(
        java.util.List.of(expected.name()),
        FailureDiagnostic.from(SessionState.FAILED, sanitized).codes());
    return sanitized;
  }
}
