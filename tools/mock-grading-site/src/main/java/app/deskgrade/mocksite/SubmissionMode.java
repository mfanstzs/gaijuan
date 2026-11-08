package app.deskgrade.mocksite;

public enum SubmissionMode {
  SUBMIT_AND_ADVANCE("submit-and-advance", 1),
  SUBMIT_THEN_NEXT("submit-then-next", 2);

  private final String option;
  private final int code;

  SubmissionMode(String option, int code) {
    this.option = option;
    this.code = code;
  }

  public String option() {
    return option;
  }

  public int code() {
    return code;
  }

  public static SubmissionMode parse(String value) {
    for (var mode : values()) {
      if (mode.option.equals(value)) return mode;
    }
    throw new IllegalArgumentException("Unknown submission mode");
  }
}
