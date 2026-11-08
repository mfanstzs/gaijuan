package app.deskgrade.adapters.windows.mock;

import app.deskgrade.adapters.windows.CoordinateMapper;
import app.deskgrade.adapters.windows.WindowRegionCapture;
import app.deskgrade.adapters.windows.WindowSafety;
import app.deskgrade.adapters.windows.WindowsInputDriver;
import app.deskgrade.adapters.windows.WindowsWindowService;
import app.deskgrade.core.application.session.ActionPermission;
import app.deskgrade.core.application.session.SessionPlatform;
import app.deskgrade.core.domain.desktop.LogicalRect;
import app.deskgrade.core.domain.desktop.TargetBinding;
import app.deskgrade.core.domain.desktop.WindowSnapshot;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Input is enabled only for the dedicated local synthetic tool and its checked visible protocol.
 */
public final class NativeMockPlatformAdapter implements SessionPlatform {
  /** Physical Windows acceptance currently covers only 96 DPI / 100% scaling. */
  public static boolean isValidatedAutomaticDisplay(WindowSnapshot window) {
    return window.dpi() == 96
        && window.display().scaleX() == 1.0
        && window.display().scaleY() == 1.0;
  }

  public static final String WINDOW_TITLE = "DeskGrade Mock Platform · 合成答卷测试";
  public static final LogicalRect ANSWER_REGION = new LogicalRect(16, 144, 720, 220);
  // DGM1 bytes 4..9 visibly encode answer sequence + question. This narrow band excludes the
  // score bytes, so filling the input cannot mutate identity evidence; identical answer images
  // still remain distinct test answers.
  private static final LogicalRect IDENTITY_REGION = new LogicalRect(50, 18, 48, 24);
  private static final LogicalRect STATE_REGION = new LogicalRect(16, 16, 260, 28);
  private static final LogicalRect SCORE_CONTROL = new LogicalRect(16, 402, 160, 44);
  private static final LogicalRect SUBMIT_CONTROL = new LogicalRect(192, 402, 144, 44);
  private static final LogicalRect NEXT_CONTROL = new LogicalRect(352, 402, 144, 44);
  private final WindowsWindowService windows;
  private final TargetBinding answer;
  private final TargetBinding identity;
  private final TargetBinding state;
  private final WindowRegionCapture capture;
  private final WindowsInputDriver input;
  private final String expectedWindowTitle;
  private final MockActionGuard actions = new MockActionGuard();

  public NativeMockPlatformAdapter(
      WindowsWindowService windows, TargetBinding binding, WindowRegionCapture capture) {
    this(windows, binding, capture, WINDOW_TITLE);
  }

  public NativeMockPlatformAdapter(
      WindowsWindowService windows,
      TargetBinding binding,
      WindowRegionCapture capture,
      String expectedWindowTitle) {
    this.windows = Objects.requireNonNull(windows);
    this.answer = Objects.requireNonNull(binding);
    this.capture = Objects.requireNonNull(capture);
    this.expectedWindowTitle = Objects.requireNonNull(expectedWindowTitle);
    if (expectedWindowTitle.isBlank()
        || !expectedWindowTitle.equals(binding.calibratedWindow().title()))
      throw new IllegalArgumentException(
          "Synthetic window title does not match its authorization.");
    CoordinateMapper mapper = new CoordinateMapper();
    if (!ANSWER_REGION.equals(binding.answerRegion()))
      throw new IllegalArgumentException("Mock tool requires its fixed synthetic answer region.");
    state =
        new TargetBinding(
            binding.calibratedWindow(),
            STATE_REGION,
            mapper.toScreen(STATE_REGION, binding.calibratedWindow()));
    identity =
        new TargetBinding(
            binding.calibratedWindow(),
            IDENTITY_REGION,
            mapper.toScreen(IDENTITY_REGION, binding.calibratedWindow()));
    input = new WindowsInputDriver(windows);
  }

  @Override
  public String targetIdentity() {
    var identity = answer.calibratedWindow().identity();
    return "synthetic-window:"
        + identity.hwnd()
        + ":"
        + identity.processId()
        + ":"
        + identity.processStartedAt();
  }

  @Override
  public boolean supportsUnattended() {
    try {
      View view = observe();
      return view.page() == Page.READY
          || view.page() == Page.SUBMITTED
          || view.page() == Page.COMPLETED;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  @Override
  public View observe() {
    requireSyntheticWindow();
    try {
      var png = capture.capture(state);
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png.png()))) {
        return MockCanvasProtocol.decode(ImageIO.read(input));
      }
    } catch (Exception failure) {
      throw new IllegalStateException("Synthetic platform screen state could not be verified.");
    }
  }

  @Override
  public Capture capture(View expected) {
    verify(expected);
    if (expected.page() != Page.READY) throw new IllegalStateException("Answer is not ready.");
    var image = capture.capture(answer);
    verify(expected);
    return new Capture(image.png(), image.sha256(), image.capturedAt());
  }

  @Override
  public IdentityCapture captureIdentity(View expected) {
    verify(expected);
    if (expected.page() != Page.READY) throw new IllegalStateException("Answer is not ready.");
    var image = capture.capture(identity);
    verify(expected);
    return new IdentityCapture(
        targetIdentity(),
        expected.answerId(),
        expected.questionId(),
        expected.pageRevision(),
        identityRegionIdentity(),
        new Capture(image.png(), image.sha256(), image.capturedAt()));
  }

  @Override
  public void verify(View expected) {
    View current = observe();
    if (!MockActionGuard.samePage(expected, current))
      throw new IllegalStateException("Synthetic answer binding changed.");
  }

  @Override
  public void fillScore(View expected, BigDecimal score, ActionPermission permission) {
    if (expected.page() != Page.READY)
      throw new IllegalStateException("Answer is not ready for input.");
    actions.beginFill();
    verify(expected);
    input.fillNumber(
        answer,
        SCORE_CONTROL,
        score,
        () -> {
          verify(expected);
          permission.verify();
        });
    actions.recordFilled(expected, score);
  }

  @Override
  public void submit(View expected, ActionPermission permission) {
    if (expected.page() != Page.READY)
      throw new IllegalStateException("Answer is not ready for submission.");
    verify(expected);
    actions.claimSubmit(expected, observe());
    input.click(
        answer,
        SUBMIT_CONTROL,
        () -> {
          actions.verifyScore(expected, observe());
          permission.verify();
        });
  }

  @Override
  public void next(View submitted, ActionPermission permission) {
    if (submitted.page() != Page.SUBMITTED
        || !submitted.separateNext()
        || !Objects.equals(submitted.answerId(), submitted.acknowledgedAnswerId())) {
      throw new IllegalStateException("Next requires explicit current-answer acknowledgement.");
    }
    verify(submitted);
    actions.claimNext(submitted, observe());
    input.click(
        answer,
        NEXT_CONTROL,
        () -> {
          actions.verifyNext(submitted, observe());
          permission.verify();
        });
  }

  private void requireSyntheticWindow() {
    var now = windows.snapshot(answer.calibratedWindow().identity().hwnd());
    if (!expectedWindowTitle.equals(now.title())
        || !isValidatedAutomaticDisplay(now)
        || !now.windowClass().startsWith("GlassWndClass")
        || !now.windowClass().equals(answer.calibratedWindow().windowClass())
        || windows.check(answer) != WindowSafety.Failure.NONE
        || windows.check(identity) != WindowSafety.Failure.NONE
        || windows.check(state) != WindowSafety.Failure.NONE) {
      throw new IllegalStateException(
          "Only the explicitly bound synthetic test window permits automatic input.");
    }
  }

  private String identityRegionIdentity() {
    var logical = identity.answerRegion();
    var physical = identity.calibratedRegion();
    return targetIdentity()
        + ":state:"
        + Double.toHexString(logical.x())
        + ","
        + Double.toHexString(logical.y())
        + ","
        + Double.toHexString(logical.width())
        + ","
        + Double.toHexString(logical.height())
        + ":"
        + physical.x()
        + ","
        + physical.y()
        + ","
        + physical.width()
        + ","
        + physical.height();
  }
}
