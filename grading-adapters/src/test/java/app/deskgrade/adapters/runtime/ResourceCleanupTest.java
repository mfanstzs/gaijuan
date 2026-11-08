package app.deskgrade.adapters.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceCleanupTest {
  @Test
  void firstFailureDoesNotPreventRemainingResourcesFromClosing() {
    List<Integer> closed = new ArrayList<>();
    IOException first = new IOException("first");
    IllegalStateException second = new IllegalStateException("second");
    Exception reported =
        assertThrows(
            Exception.class,
            () ->
                ResourceCleanup.closeAll(
                    () -> {
                      closed.add(1);
                      throw first;
                    },
                    () -> {
                      closed.add(2);
                      throw second;
                    },
                    () -> closed.add(3)));
    assertSame(first, reported);
    assertEquals(List.of(1, 2, 3), closed);
    assertArrayEquals(new Throwable[] {second}, reported.getSuppressed());
  }

  @Test
  void absentResourcesAndRepeatedExceptionDoNotShortCircuitCleanup() {
    List<Integer> closed = new ArrayList<>();
    Exception repeated = new Exception("same instance");
    Exception reported =
        assertThrows(
            Exception.class,
            () ->
                ResourceCleanup.closeAll(
                    null,
                    () -> {
                      throw repeated;
                    },
                    () -> {
                      throw repeated;
                    },
                    () -> closed.add(3)));
    assertSame(repeated, reported);
    assertEquals(List.of(3), closed);
    assertEquals(0, reported.getSuppressed().length);
  }

  @Test
  void successfulCloseAllPreservesOrder() {
    List<Integer> closed = new ArrayList<>();
    assertDoesNotThrow(
        () -> ResourceCleanup.closeAll(() -> closed.add(1), null, () -> closed.add(2)));
    assertEquals(List.of(1, 2), closed);
    assertDoesNotThrow(() -> ResourceCleanup.closeAll());
  }

  @Test
  void nativeLinkageFailureStillAttemptsLaterResource() {
    List<Integer> closed = new ArrayList<>();
    LinkageError nativeFailure = new LinkageError("native close failed");
    assertSame(
        nativeFailure,
        assertThrows(
            LinkageError.class,
            () ->
                ResourceCleanup.closeAll(
                    () -> {
                      throw nativeFailure;
                    },
                    () -> closed.add(2))));
    assertEquals(List.of(2), closed);
  }
}
