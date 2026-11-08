package app.deskgrade.adapters.model;

import static app.deskgrade.adapters.model.ProviderException.Diagnostic.*;

import app.deskgrade.core.error.DeskGradeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/** Classifies bounded causal chains without retaining any upstream exception or its text. */
final class ProviderFailureClassifier {
  private ProviderFailureClassifier() {}

  static ProviderException sanitize(Throwable failure) {
    List<Throwable> chain = causalChain(failure);
    for (Throwable current : chain) {
      if (current instanceof ProviderException known) return known;
    }
    for (Throwable current : chain) {
      if (current instanceof DeskGradeException known) {
        return new ProviderException(
            switch (known.code()) {
              case CANCELLED -> ProviderException.Failure.CANCELLED;
              case CONFIGURATION_REQUIRED -> ProviderException.Failure.UNVERIFIED_CONFIGURATION;
              case SECRET_STORE_FAILURE -> ProviderException.Failure.MISSING_CREDENTIAL;
              default -> ProviderException.Failure.INTERNAL_ERROR;
            });
      }
    }
    // A typed outer transport exception carries more meaning than its low-level cause.
    // For example HttpConnectTimeoutException can wrap ConnectException, and a request timeout
    // may wrap a cancellation. Never discard the former while walking to the deepest cause.
    for (Throwable current : chain) {
      if (current instanceof HttpConnectTimeoutException) return MODEL_CONNECT_TIMEOUT.exception();
      if (current instanceof HttpTimeoutException) return MODEL_REQUEST_TIMEOUT.exception();
      if (current instanceof SocketTimeoutException) return MODEL_SOCKET_TIMEOUT.exception();
    }
    for (Throwable current : chain) {
      if (current instanceof CancellationException || current instanceof InterruptedException)
        return MODEL_CANCELLED.exception();
    }
    for (Throwable current : chain) {
      if (current instanceof UnknownHostException || current instanceof UnresolvedAddressException)
        return MODEL_DNS_FAILURE.exception();
    }
    for (Throwable current : chain) {
      if (current instanceof SSLHandshakeException) return MODEL_TLS_HANDSHAKE_FAILED.exception();
    }
    for (Throwable current : chain) {
      if (current instanceof SSLException) return MODEL_TLS_FAILURE.exception();
      if (current instanceof NoRouteToHostException) return MODEL_NETWORK_UNREACHABLE.exception();
      if (current instanceof ConnectException) {
        return (knownMessage(
                    current,
                    "connection refused",
                    "connection refused: no further information",
                    "connection refused (connection refused)")
                ? MODEL_CONNECTION_REFUSED
                : MODEL_CONNECT_FAILED)
            .exception();
      }
      if (current instanceof SocketException) {
        if (knownMessage(
            current,
            "connection reset",
            "connection reset by peer",
            "an existing connection was forcibly closed by the remote host"))
          return MODEL_CONNECTION_RESET.exception();
        if (knownMessage(current, "socket closed", "socket is closed", "broken pipe"))
          return MODEL_CONNECTION_CLOSED.exception();
        return MODEL_SOCKET_FAILURE.exception();
      }
      if (current instanceof EOFException || current instanceof ClosedChannelException)
        return MODEL_CONNECTION_CLOSED.exception();
    }
    for (Throwable current : chain) {
      if (current instanceof IOException) return MODEL_IO_FAILURE.exception();
    }
    return MODEL_INTERNAL_ERROR.exception();
  }

  private static List<Throwable> causalChain(Throwable failure) {
    var chain = new ArrayList<Throwable>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable current = failure;
    while (current != null && chain.size() < 16 && seen.add(current)) {
      chain.add(current);
      current = current.getCause();
    }
    return chain;
  }

  private static boolean knownMessage(Throwable failure, String... messages) {
    String message = failure.getMessage();
    if (message == null || message.length() > 256) return false;
    String normalized = message.trim().toLowerCase(Locale.ROOT);
    for (String known : messages) {
      if (known.equals(normalized)) return true;
    }
    return false;
  }
}
