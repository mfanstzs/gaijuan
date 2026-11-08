package app.deskgrade.adapters.windows;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClipboardNumberReadbackTest {
  @Test
  void freshNumericCopyIsAcceptedAndOriginalClipboardIsRestored() {
    FakeClipboard clipboard = new FakeClipboard("original user text");
    BigDecimal value =
        ClipboardNumberReadback.read(
            clipboard,
            () -> clipboard.copy(" 5.50\r\n", true),
            () -> {},
            () -> fail("Copy was immediate"));
    assertEquals(new BigDecimal("5.50"), value);
    assertEquals("original user text", clipboard.text);
    assertEquals(1, clipboard.restores);
  }

  @Test
  void staleNumericClipboardCannotBeMistakenForACopyThatNeverOccurred() {
    FakeClipboard clipboard = new FakeClipboard("5");
    AtomicInteger waits = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            ClipboardNumberReadback.read(
                clipboard, () -> {}, () -> {}, () -> waits.incrementAndGet()));
    assertEquals(30, waits.get());
    assertEquals("5", clipboard.text);
    assertEquals(1, clipboard.restores);
  }

  @Test
  void invalidControlTextIsRejectedWithoutExposingItInTheError() {
    FakeClipboard clipboard = new FakeClipboard("original");
    String copied = "private unrelated text score=5";
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                ClipboardNumberReadback.read(
                    clipboard, () -> clipboard.copy(copied, true), () -> {}, () -> {}));
    assertFalse(failure.getMessage().contains(copied));
    assertNull(failure.getCause());
    assertEquals("original", clipboard.text);
    assertEquals(1, clipboard.restores);
  }

  @Test
  void foreignClipboardUpdateIsNeverAcceptedOrOverwritten() {
    FakeClipboard clipboard = new FakeClipboard("original");
    assertThrows(
        IllegalStateException.class,
        () ->
            ClipboardNumberReadback.read(
                clipboard, () -> clipboard.copy("9", false), () -> {}, () -> {}));
    assertEquals("9", clipboard.text);
    assertEquals(0, clipboard.restores);
  }

  @Test
  void externalUpdateDuringSentinelInstallationPreventsCopyAndIsPreserved() {
    FakeClipboard clipboard = new FakeClipboard("original");
    AtomicInteger copies = new AtomicInteger();
    clipboard.onTextRead = () -> clipboard.copy("user copied during setup", false);
    assertThrows(
        IllegalStateException.class,
        () -> ClipboardNumberReadback.read(clipboard, copies::incrementAndGet, () -> {}, () -> {}));
    assertEquals(0, copies.get());
    assertEquals("user copied during setup", clipboard.text);
    assertEquals(0, clipboard.restores);
  }

  @Test
  void clipboardChangingWhileItsValueIsReadIsRejectedAndPreserved() {
    FakeClipboard clipboard = new FakeClipboard("original");
    clipboard.onTextRead =
        () -> {
          if (clipboard.targetOwner) clipboard.copy("new user data", false);
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            ClipboardNumberReadback.read(
                clipboard, () -> clipboard.copy("5", true), () -> {}, () -> {}));
    assertEquals("new user data", clipboard.text);
    assertEquals(0, clipboard.restores);
  }

  @Test
  void laterExternalClipboardDataIsNotOverwrittenDuringRestoration() {
    FakeClipboard clipboard = new FakeClipboard("original");
    AtomicInteger verifies = new AtomicInteger();
    BigDecimal value =
        ClipboardNumberReadback.read(
            clipboard,
            () -> clipboard.copy("5", true),
            () -> {
              if (verifies.incrementAndGet() == 4) clipboard.copy("later user data", false);
            },
            () -> {});
    assertEquals(new BigDecimal("5"), value);
    assertEquals("later user data", clipboard.text);
    assertEquals(0, clipboard.restores);
  }

  @Test
  void gateRevokedAfterSentinelPreventsCopyAndRestoresOriginal() {
    FakeClipboard clipboard = new FakeClipboard("original");
    AtomicInteger copies = new AtomicInteger(), verifies = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            ClipboardNumberReadback.read(
                clipboard,
                copies::incrementAndGet,
                () -> {
                  if (verifies.incrementAndGet() == 2)
                    throw new IllegalStateException("Gate closed");
                },
                () -> {}));
    assertEquals(0, copies.get());
    assertEquals("original", clipboard.text);
    assertEquals(1, clipboard.restores);
  }

  @Test
  void interruptedCopyWaitStopsAndPreservesTheInterruptFlag() {
    FakeClipboard clipboard = new FakeClipboard("original");
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              ClipboardNumberReadback.read(
                  clipboard,
                  () -> {},
                  () -> {},
                  () -> {
                    throw new InterruptedException();
                  }));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals("original", clipboard.text);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void onlyBoundedUnsignedPlainDecimalsAreAccepted() {
    assertEquals(BigDecimal.ZERO, ClipboardNumberReadback.parse("0"));
    assertEquals(new BigDecimal("12.25"), ClipboardNumberReadback.parse(" \t12.25\n"));
    for (String invalid :
        List.of(
            "",
            " ",
            "-1",
            "+1",
            "1e2",
            "NaN",
            "1,5",
            ".5",
            "5.",
            "5 / 10",
            "评分:5",
            "１２",
            "1\n2",
            "1".repeat(25),
            " ".repeat(64) + "5")) {
      assertThrows(IllegalStateException.class, () -> ClipboardNumberReadback.parse(invalid));
    }
    assertThrows(IllegalStateException.class, () -> ClipboardNumberReadback.parse(null));
  }

  private static final class FakeClipboard implements ClipboardNumberReadback.ClipboardAccess {
    String text;
    long sequence = 1;
    boolean targetOwner;
    int restores;
    Runnable onTextRead;

    FakeClipboard(String original) {
      text = original;
    }

    void copy(String copied, boolean target) {
      text = copied;
      sequence++;
      targetOwner = target;
    }

    @Override
    public Object snapshot() {
      return text;
    }

    @Override
    public long sequence() {
      return sequence;
    }

    @Override
    public void setText(String value) {
      copy(value, false);
    }

    @Override
    public String text() {
      String value = text;
      if (onTextRead != null) onTextRead.run();
      return value;
    }

    @Override
    public boolean ownedByTarget() {
      return targetOwner;
    }

    @Override
    public void restore(Object snapshot) {
      restores++;
      copy((String) snapshot, false);
    }
  }
}
