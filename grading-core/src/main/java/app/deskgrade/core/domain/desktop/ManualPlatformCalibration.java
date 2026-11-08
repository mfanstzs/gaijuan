package app.deskgrade.core.domain.desktop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable positions, never a target binding or an input authorization. All rectangles use 96-DPI
 * logical coordinates relative to the client area. HWND/PID are intentionally absent.
 */
public record ManualPlatformCalibration(
    String id,
    long revision,
    String displayName,
    WindowLayout layout,
    LogicalRect identityRegion,
    LogicalRect answerRegion,
    LogicalRect scoreRegion,
    LogicalRect submitRegion,
    LogicalRect submissionAckRegion,
    LogicalRect nextRegion,
    NavigationMode navigationMode,
    Instant savedAt) {
  public ManualPlatformCalibration {
    if (id == null
        || !id.matches("[A-Za-z0-9_-]{1,100}")
        || revision < 1
        || displayName == null
        || displayName.isBlank()
        || displayName.length() > 200
        || displayName.chars().anyMatch(Character::isISOControl)
        || navigationMode == null
        || savedAt == null) throw new IllegalArgumentException("标定名称、标识、版本、导航方式或保存时间不合法。");
    if (layout == null || answerRegion == null || scoreRegion == null || submitRegion == null)
      throw new IllegalArgumentException("请框选答题区域、分数输入框和提交区域。");
    if (navigationMode == NavigationMode.AUTO_AFTER_SUBMIT
        && (submissionAckRegion != null || nextRegion != null))
      throw new IllegalArgumentException("自动跳转模式不能保存提交成功提示区或下一题/下一页按钮。");
    var regions = new ArrayList<LogicalRect>(List.of(answerRegion, scoreRegion, submitRegion));
    if (identityRegion != null) regions.add(identityRegion);
    int acknowledgementIndex = -1;
    int nextIndex = -1;
    if (submissionAckRegion != null) {
      acknowledgementIndex = regions.size();
      regions.add(submissionAckRegion);
    }
    if (nextRegion != null) {
      nextIndex = regions.size();
      regions.add(nextRegion);
    }
    var pixels = new ArrayList<PixelRect>();
    for (int index = 0; index < regions.size(); index++) {
      PixelRect area = physical(regions.get(index), layout);
      if (area.width() < 16 || area.height() < 16)
        throw new IllegalArgumentException("每个标定区域至少需要 16×16 物理像素。");
      long areaPixels = (long) area.width() * area.height();
      if (index == 0 && areaPixels > 16_000_000)
        throw new IllegalArgumentException("答题区域不能超过 1600 万像素。");
      if (index > 0
          && areaPixels > (long) layout.clientWidthPixels() * layout.clientHeightPixels() / 4)
        throw new IllegalArgumentException("分数框及按钮区域不能超过客户区面积的四分之一。");
      for (int prior = 0; prior < pixels.size(); prior++) {
        boolean sameAcknowledgementAndNextControl =
            prior == acknowledgementIndex
                && index == nextIndex
                && submissionAckRegion.equals(nextRegion)
                && acknowledgementIndex >= 0;
        if (!sameAcknowledgementAndNextControl
            && (pixels.get(prior).intersects(area)
                || intersects(regions.get(prior), regions.get(index))))
          throw new IllegalArgumentException("答题区域、分数框和按钮区域之间不能重叠。");
      }
      pixels.add(area);
    }
  }

  /** Backward-compatible construction for version-1 position files and tests. */
  public ManualPlatformCalibration(
      String id,
      long revision,
      String displayName,
      WindowLayout layout,
      LogicalRect answerRegion,
      LogicalRect scoreRegion,
      LogicalRect submitRegion,
      LogicalRect nextRegion,
      Instant savedAt) {
    this(
        id,
        revision,
        displayName,
        layout,
        null,
        answerRegion,
        scoreRegion,
        submitRegion,
        null,
        nextRegion,
        nextRegion == null ? NavigationMode.AUTO_AFTER_SUBMIT : NavigationMode.CLICK_NEXT,
        savedAt);
  }

  public static ManualPlatformCalibration capture(
      String id,
      long revision,
      String displayName,
      WindowSnapshot target,
      LogicalRect answer,
      LogicalRect score,
      LogicalRect submit,
      LogicalRect next,
      Instant savedAt) {
    return capture(
        id,
        revision,
        displayName,
        target,
        null,
        answer,
        score,
        submit,
        null,
        next,
        next == null ? NavigationMode.AUTO_AFTER_SUBMIT : NavigationMode.CLICK_NEXT,
        savedAt);
  }

  public static ManualPlatformCalibration capture(
      String id,
      long revision,
      String displayName,
      WindowSnapshot target,
      LogicalRect identity,
      LogicalRect answer,
      LogicalRect score,
      LogicalRect submit,
      LogicalRect submissionAck,
      LogicalRect next,
      NavigationMode navigationMode,
      Instant savedAt) {
    if (target == null) throw new IllegalArgumentException("请先选择当前目标窗口。");
    return new ManualPlatformCalibration(
        id,
        revision,
        displayName,
        new WindowLayout(
            target.clientBounds().width(),
            target.clientBounds().height(),
            target.dpi(),
            target.windowClass()),
        identity,
        answer,
        score,
        submit,
        submissionAck,
        next,
        navigationMode,
        savedAt);
  }

  /**
   * Legacy schema compatibility only. New configurations never persist a separate identity area.
   */
  @Deprecated(forRemoval = false)
  public boolean hasIdentityRegion() {
    return identityRegion != null;
  }

  public boolean hasRequiredNavigationRegions() {
    return navigationMode == NavigationMode.AUTO_AFTER_SUBMIT
        || submissionAckRegion != null && nextRegion != null;
  }

  /** Matching geometry permits review/reuse only; it never proves identity or a current page. */
  public boolean matchesLayout(WindowSnapshot current) {
    return current != null
        && layout.clientWidthPixels() == current.clientBounds().width()
        && layout.clientHeightPixels() == current.clientBounds().height()
        && layout.dpi() == current.dpi()
        && layout.windowClass().equals(current.windowClass());
  }

  /**
   * Evidence absent from the saved geometry itself. This is not a runtime gate for an explicitly
   * bound coordinate adapter, which reports input/screen evidence without claiming a business ACK.
   */
  public List<ReadinessGap> readinessGaps() {
    return List.of(ReadinessGap.values());
  }

  private static PixelRect physical(LogicalRect rect, WindowLayout layout) {
    double scale = layout.dpi() / 96.0;
    double right = (rect.x() + rect.width()) * scale;
    double bottom = (rect.y() + rect.height()) * scale;
    if (!Double.isFinite(right)
        || !Double.isFinite(bottom)
        || right > Integer.MAX_VALUE
        || bottom > Integer.MAX_VALUE) throw new IllegalArgumentException("标定区域坐标溢出。");
    long leftPx = Math.round(rect.x() * scale), topPx = Math.round(rect.y() * scale);
    long rightPx = Math.round(right), bottomPx = Math.round(bottom);
    if (leftPx < 0
        || topPx < 0
        || rightPx > layout.clientWidthPixels()
        || bottomPx > layout.clientHeightPixels()
        || leftPx >= rightPx
        || topPx >= bottomPx) throw new IllegalArgumentException("标定区域必须完全位于目标窗口客户区内。");
    return new PixelRect(
        (int) leftPx, (int) topPx, (int) (rightPx - leftPx), (int) (bottomPx - topPx));
  }

  private static boolean intersects(LogicalRect first, LogicalRect second) {
    return first.x() < second.x() + second.width()
        && first.x() + first.width() > second.x()
        && first.y() < second.y() + second.height()
        && first.y() + first.height() > second.y();
  }

  public record WindowLayout(
      int clientWidthPixels, int clientHeightPixels, int dpi, String windowClass) {
    public WindowLayout {
      if (clientWidthPixels < 64
          || clientHeightPixels < 64
          || clientWidthPixels > 32768
          || clientHeightPixels > 32768
          || dpi < 48
          || dpi > 768
          || windowClass == null
          || windowClass.isBlank()
          || windowClass.length() > 256
          || windowClass.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("客户区尺寸、DPI 或窗口类型不合法。");
    }
  }

  public enum ReadinessGap {
    CURRENT_WINDOW_BINDING("须明确绑定当前窗口和进程；保存的位置不保留旧窗口执行权限"),
    ANSWER_REGION_CONTINUITY("须在模型返回、填分和提交前逐像素复核当前答题区域"),
    PAGE_READY_STATE("业务页面的就绪、加载、登录或错误语义仍须专门识别"),
    STABLE_ANSWER_ID("需要可读且稳定的当前题号和答卷标识，图片哈希不能替代答卷标识"),
    SCORE_READBACK("需要从分数控件精确读回数值，并与 Java 求和结果核对"),
    SUBMISSION_ACK("平台收分成功需要与当前答卷绑定的业务回执；坐标点击单独记录"),
    NEXT_ANSWER_EVIDENCE("画面切换可作为坐标推进证据，不能代替平台答卷身份与业务确认"),
    END_STATE_EVIDENCE("需要平台明确的全部结束标志，不能把等待超时当作结束");

    private final String description;

    ReadinessGap(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
  }

  public enum NavigationMode {
    AUTO_AFTER_SUBMIT("提交后页面自动跳转"),
    CLICK_NEXT("提交后点击下一题/下一页");

    private final String description;

    NavigationMode(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
  }
}
