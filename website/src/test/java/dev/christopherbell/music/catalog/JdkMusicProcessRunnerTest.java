package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class JdkMusicProcessRunnerTest {
  @Test
  void interruptionWhileCollectingOutputReachesTheProcessOwner() {
    var outputTask = pendingOutputTask();
    Thread.currentThread().interrupt();

    try {
      assertThatThrownBy(() -> JdkMusicProcessRunner.awaitOutput(outputTask))
          .isInstanceOf(InterruptedException.class);
      assertThat(outputTask.isCancelled()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void outputWaitFailuresKeepReturningEmptyTruncatedOutput() throws InterruptedException {
    var failedOutputTask = new FutureTask<JdkMusicProcessRunner.BoundedOutput>(
        () -> { throw new IOException("reader failed"); });
    failedOutputTask.run();
    assertEmptyTruncatedOutput(failedOutputTask);

    assertEmptyTruncatedOutput(pendingOutputTask());

    var cancelledOutputTask = pendingOutputTask();
    cancelledOutputTask.cancel(true);
    assertEmptyTruncatedOutput(cancelledOutputTask);
  }

  private static FutureTask<JdkMusicProcessRunner.BoundedOutput> pendingOutputTask() {
    return new FutureTask<>(() -> new JdkMusicProcessRunner.BoundedOutput("output", false));
  }

  private static void assertEmptyTruncatedOutput(
      FutureTask<JdkMusicProcessRunner.BoundedOutput> outputTask) throws InterruptedException {
    var output = JdkMusicProcessRunner.awaitOutput(outputTask);
    assertThat(output.text()).isEmpty();
    assertThat(output.truncated()).isTrue();
  }
}
