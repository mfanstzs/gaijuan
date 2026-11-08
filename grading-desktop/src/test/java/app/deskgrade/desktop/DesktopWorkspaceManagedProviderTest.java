package app.deskgrade.desktop;

import static org.junit.jupiter.api.Assertions.*;

import app.deskgrade.core.domain.GradingRequest;
import app.deskgrade.core.domain.GradingResponse;
import app.deskgrade.core.port.CancellationToken;
import app.deskgrade.core.port.FallbackGradingProvider;
import app.deskgrade.core.port.GradingProvider;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopWorkspaceManagedProviderTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void managedWrapperPreservesFallbackCapabilityAndDispatch() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    var delegate = new FakeFallback();
    AutoCloseable wrapper = wrapper(workspace, delegate);
    try (workspace;
        wrapper) {
      var fallback = assertInstanceOf(FallbackGradingProvider.class, wrapper);
      assertEquals(Optional.of("fallback-profile"), fallback.fallbackProfileId());
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  fallback
                      .gradeFallback(UUID.randomUUID(), null, CancellationToken.none())
                      .toCompletableFuture()
                      .get());
      assertSame(delegate.marker, failure.getCause());
      assertTrue(delegate.fallbackCalled.get());
    }
    workspace.awaitClosed().get();
    assertTrue(delegate.closed);
  }

  @Test
  void nonFallbackDelegateCannotInventAServerFallback() throws Exception {
    var workspace = new DesktopWorkspace(directory, true);
    var delegate = new PlainProvider();
    AutoCloseable wrapper = wrapper(workspace, delegate);
    try (workspace;
        wrapper) {
      var fallback = assertInstanceOf(FallbackGradingProvider.class, wrapper);
      assertTrue(fallback.fallbackProfileId().isEmpty());
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  fallback
                      .gradeFallback(UUID.randomUUID(), null, CancellationToken.none())
                      .toCompletableFuture()
                      .get());
      assertTrue(failure.getCause().getMessage().contains("unavailable"));
    }
    workspace.awaitClosed().get();
  }

  private static AutoCloseable wrapper(DesktopWorkspace workspace, GradingProvider delegate)
      throws Exception {
    Class<?> type = Class.forName(DesktopWorkspace.class.getName() + "$ManagedProvider");
    Constructor<?> constructor =
        type.getDeclaredConstructor(
            DesktopWorkspace.class,
            GradingProvider.class,
            app.deskgrade.adapters.commercial.CommercialClient.class,
            long.class);
    constructor.setAccessible(true);
    return (AutoCloseable) constructor.newInstance(workspace, delegate, null, -1L);
  }

  private static final class FakeFallback implements FallbackGradingProvider, AutoCloseable {
    final RuntimeException marker = new RuntimeException("synthetic fallback marker");
    final AtomicBoolean fallbackCalled = new AtomicBoolean();
    boolean closed;

    @Override
    public CompletionStage<GradingResponse> grade(GradingRequest request, CancellationToken token) {
      return CompletableFuture.failedFuture(new AssertionError("primary dispatch not expected"));
    }

    @Override
    public Optional<String> fallbackProfileId() {
      return Optional.of("fallback-profile");
    }

    @Override
    public CompletionStage<GradingResponse> gradeFallback(
        UUID primaryRequestId, GradingRequest request, CancellationToken token) {
      fallbackCalled.set(true);
      return CompletableFuture.failedFuture(marker);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class PlainProvider implements GradingProvider, AutoCloseable {
    @Override
    public CompletionStage<GradingResponse> grade(GradingRequest request, CancellationToken token) {
      return CompletableFuture.failedFuture(new AssertionError("primary dispatch not expected"));
    }

    @Override
    public void close() {}
  }
}
