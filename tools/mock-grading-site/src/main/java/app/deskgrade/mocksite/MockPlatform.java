package app.deskgrade.mocksite;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local synthetic platform. All state transitions are serialized independently of the client. */
public final class MockPlatform {
  public record Reply(int status, String outcome, boolean dropReceipt, PlatformState state) {}

  private final int count;
  private final SubmissionMode mode;
  private final Clock clock;
  private final List<LedgerEntry> ledger = new ArrayList<>();
  private final Map<String, Integer> received = new HashMap<>();
  private final Set<String> committed = new HashSet<>();
  private Faults faults = Faults.NONE;
  private int current = 1;
  private int ackSequence;
  private int visibleConfirmed;
  private int revision = 1;
  private int pendingAck;
  private boolean submitted;

  public MockPlatform(int count, SubmissionMode mode, Clock clock) {
    if (count < 1 || count > 10000) throw new IllegalArgumentException("count must be 1..10000");
    this.count = count;
    this.mode = java.util.Objects.requireNonNull(mode);
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  public synchronized Faults faults() {
    return faults;
  }

  public synchronized void configure(Faults update) {
    boolean releaseReceipt = faults.dropReceipt() && !update.dropReceipt() && pendingAck > 0;
    faults = java.util.Objects.requireNonNull(update);
    revision++;
    if (releaseReceipt) publishCommit(pendingAck);
  }

  public synchronized PlatformState state() {
    boolean complete = current > count;
    int state = faults.loginExpired() ? 5 : faults.popup() ? 6 : complete ? 3 : submitted ? 2 : 1;
    int sequence = complete ? 0 : current;
    String image =
        complete
            ? ""
            : "/answer/"
                + (faults.identicalImage() ? 1 : current)
                + ".png?blank="
                + faults.blankImage();
    String message =
        switch (state) {
          case 2 -> "提交已确认，请点击下一份";
          case 3 -> "全部答题图片已完成";
          case 5 -> "模拟登录已失效";
          case 6 -> "模拟弹窗阻挡：关闭故障后继续";
          default -> "当前答卷已就绪";
        };
    return new PlatformState(
        complete ? "" : answerId(current),
        sequence,
        faults.questionChanged() ? 2 : 1,
        state,
        mode.code(),
        ackSequence,
        visibleConfirmed,
        count,
        revision,
        faults.flags(),
        image,
        message);
  }

  public synchronized Reply submit(String answerId, String scoreText) {
    String id = answerId == null ? "" : answerId;
    String score = scoreText == null ? "" : scoreText;
    int receivedCount = received.merge(id, 1, Integer::sum);
    boolean duplicate = committed.contains(id);
    int status;
    String outcome;
    if (duplicate) {
      status = 409;
      outcome = "DUPLICATE_SUBMISSION";
    } else if (faults.loginExpired()) {
      status = 401;
      outcome = "LOGIN_EXPIRED";
    } else if (faults.popup()) {
      status = 409;
      outcome = "POPUP_BLOCKING";
    } else if (current > count || !answerId(current).equals(id)) {
      status = 409;
      outcome = "ANSWER_MISMATCH";
    } else if (!validScore(score) || faults.rejectScore()) {
      status = 422;
      outcome = "SCORE_REJECTED";
    } else if (faults.submitFailure()) {
      status = 503;
      outcome = "SUBMIT_FAILED";
    } else {
      status = 200;
      outcome = "COMMITTED";
    }
    boolean accepted = status == 200;
    ledger.add(
        new LedgerEntry(
            ledger.size() + 1,
            id,
            score,
            receivedCount,
            accepted,
            duplicate,
            outcome,
            clock.instant().toString()));
    if (accepted) {
      committed.add(id);
      if (faults.dropReceipt()) pendingAck = current;
      else publishCommit(current);
    }
    return new Reply(status, outcome, accepted && faults.dropReceipt(), state());
  }

  private static boolean validScore(String score) {
    try {
      BigDecimal value = new BigDecimal(score);
      return value.compareTo(BigDecimal.ZERO) >= 0
          && value.compareTo(BigDecimal.valueOf(4)) <= 0
          && value.stripTrailingZeros().scale() <= 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private void publishCommit(int sequence) {
    ackSequence = sequence;
    visibleConfirmed = committed.size();
    pendingAck = 0;
    submitted = true;
    if (mode == SubmissionMode.SUBMIT_AND_ADVANCE) advance();
    else revision++;
  }

  public synchronized Reply next() {
    if (faults.loginExpired()
        || faults.popup()
        || !submitted
        || mode != SubmissionMode.SUBMIT_THEN_NEXT) {
      return new Reply(409, "NEXT_NOT_ALLOWED", false, state());
    }
    advance();
    return new Reply(200, "ADVANCED", false, state());
  }

  /** Explicit test/control action to reproduce a page changing during grading. */
  public synchronized PlatformState forceAdvance() {
    if (current <= count) advance();
    return state();
  }

  /** Starts a fresh grading run from the first answer and clears the previous run's records. */
  public synchronized PlatformState restart() {
    ledger.clear();
    received.clear();
    committed.clear();
    faults = Faults.NONE;
    current = 1;
    ackSequence = 0;
    visibleConfirmed = 0;
    pendingAck = 0;
    submitted = false;
    revision++;
    return state();
  }

  public synchronized List<LedgerEntry> acceptedScores() {
    return ledger.stream().filter(LedgerEntry::accepted).toList();
  }

  private void advance() {
    current++;
    submitted = false;
    revision++;
  }

  public synchronized List<LedgerEntry> ledger() {
    return List.copyOf(ledger);
  }

  public int count() {
    return count;
  }

  public static String answerId(int sequence) {
    return "SYN-" + String.format(java.util.Locale.ROOT, "%04d", sequence);
  }
}
