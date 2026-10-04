package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkMusicProcessRunnerInterruptionTest {
  @TempDir Path tempDir;

  @Test
  void interruptionWhileDrainingOutputRemainsVisibleToCaller() throws Exception {
    Path childPidFile = tempDir.resolve("child.pid");
    Path childReadyFile = tempDir.resolve("child.ready");
    var wasInterrupted = new AtomicBoolean();
    var runner = new JdkMusicProcessRunner(Duration.ofSeconds(10), 256);
    var command = List.of(
        javaExecutable(),
        "-cp",
        classPath(),
        OutputPipeHolder.class.getName(),
        "parent",
        childPidFile.toString(),
        childReadyFile.toString());
    var caller = Thread.ofPlatform().unstarted(() -> {
      runner.run(command);
      wasInterrupted.set(Thread.currentThread().isInterrupted());
    });

    try {
      caller.start();
      assertThat(awaitFile(childPidFile)).isTrue();
      assertThat(awaitFile(childReadyFile)).isTrue();
      assertThat(awaitFutureWait(caller)).isTrue();

      caller.interrupt();
      terminateOwnedChild(childPidFile);
      caller.join(Duration.ofSeconds(5).toMillis());

      assertThat(caller.isAlive()).isFalse();
      assertThat(wasInterrupted.get()).isTrue();
    } finally {
      caller.interrupt();
      caller.join(Duration.ofSeconds(5).toMillis());
      terminateOwnedChild(childPidFile);
    }
  }

  private static String javaExecutable() {
    var executableName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
  }

  private static String classPath() throws URISyntaxException {
    var testClasses = Path.of(OutputPipeHolder.class.getProtectionDomain().getCodeSource()
        .getLocation().toURI());
    return testClasses.toString();
  }

  private static boolean awaitFile(Path path) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(path)) return true;
      Thread.sleep(10);
    }
    return false;
  }

  private static boolean awaitFutureWait(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline && thread.isAlive()) {
      boolean waitingOnFuture = java.util.Arrays.stream(thread.getStackTrace())
          .anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.FutureTask")
              && frame.getMethodName().equals("get"));
      if (waitingOnFuture) return true;
      Thread.sleep(10);
    }
    return false;
  }

  private static void terminateOwnedChild(Path pidFile) throws Exception {
    if (!Files.exists(pidFile)) return;
    long processId = Long.parseLong(Files.readString(pidFile));
    var child = ProcessHandle.of(processId);
    if (child.isPresent() && child.get().isAlive()) {
      child.get().destroyForcibly();
      child.get().onExit().get(5, TimeUnit.SECONDS);
    }
  }

  public static final class OutputPipeHolder {
    public static void main(String[] arguments) throws Exception {
      if (arguments[0].equals("parent")) {
        var child = new ProcessBuilder(
            javaExecutable(),
            "-cp",
            classPath(),
            OutputPipeHolder.class.getName(),
            "child",
            arguments[2]).inheritIO().start();
        Files.writeString(Path.of(arguments[1]), Long.toString(child.pid()));
        return;
      }
      Files.writeString(Path.of(arguments[1]), "ready");
      new CountDownLatch(1).await();
    }
  }
}
