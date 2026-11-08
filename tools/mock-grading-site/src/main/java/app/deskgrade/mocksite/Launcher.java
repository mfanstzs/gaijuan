package app.deskgrade.mocksite;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.application.Application;

public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) throws Exception {
    List<String> options = Arrays.asList(args);
    if (options.contains("--window")) {
      Application.launch(MockPlatformWindow.class, args);
      return;
    }
    MockPlatformServer server = createServer(options);
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mock-platform-shutdown"));
    System.out.println("Synthetic mock platform: " + server.baseUri());
    new CountDownLatch(1).await();
  }

  static MockPlatformServer createServer(List<String> options) throws Exception {
    int port = Integer.parseInt(option(options, "port", "8765"));
    AnswerSet answerSet = AnswerSet.parse(option(options, "answer-set", "synthetic"));
    int count =
        Integer.parseInt(option(options, "count", answerSet == AnswerSet.SYNTHETIC ? "20" : "6"));
    SubmissionMode mode = SubmissionMode.parse(option(options, "mode", "submit-and-advance"));
    return new MockPlatformServer(port, count, mode, options.contains("--test-ledger"), answerSet);
  }

  private static String option(List<String> options, String key, String fallback) {
    String prefix = "--" + key + "=";
    return options.stream()
        .filter(value -> value.startsWith(prefix))
        .map(value -> value.substring(prefix.length()))
        .findFirst()
        .orElse(fallback);
  }
}
