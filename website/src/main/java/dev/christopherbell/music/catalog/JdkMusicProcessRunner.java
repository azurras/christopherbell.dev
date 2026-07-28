package dev.christopherbell.music.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** JDK process boundary that drains both streams and caps retained output. */
public final class JdkMusicProcessRunner implements MusicProcessRunner {
  private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
  private final Duration timeout;
  private final int maxOutputBytes;

  public JdkMusicProcessRunner(Duration timeout, int maxOutputBytes) {
    this.timeout = timeout;
    this.maxOutputBytes = maxOutputBytes;
  }

  @Override
  public MusicProcessResult run(List<String> command) {
    if (command == null || command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
      throw new MusicProbeException("Media command is invalid.");
    }
    Process process;
    try {
      process = new ProcessBuilder(command).start();
    } catch (IOException failure) {
      throw new MusicProbeException("Media process could not start.", failure);
    }

    try (var readers = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("music-process-output-", 0).factory())) {
      Future<BoundedOutput> stdout = readers.submit(
          () -> readBounded(process.getInputStream(), maxOutputBytes));
      Future<BoundedOutput> stderr = readers.submit(
          () -> readBounded(process.getErrorStream(), maxOutputBytes));
      boolean completed = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      if (!completed) terminate(process);
      var out = result(stdout);
      var err = result(stderr);
      return new MusicProcessResult(
          out.text(), err.text(), completed ? process.exitValue() : -1,
          !completed, out.truncated() || err.truncated());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      terminate(process);
      return new MusicProcessResult("", "", -1, true, false);
    }
  }

  private BoundedOutput result(Future<BoundedOutput> output) {
    try {
      return output.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception failure) {
      output.cancel(true);
      return new BoundedOutput("", true);
    }
  }

  private static BoundedOutput readBounded(InputStream input, int limit) throws IOException {
    try (input; var output = new ByteArrayOutputStream(Math.min(limit, 8192))) {
      var buffer = new byte[8192];
      int total = 0;
      boolean truncated = false;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        int retained = Math.min(read, Math.max(0, limit - total));
        if (retained > 0) output.write(buffer, 0, retained);
        total += retained;
        if (retained < read) truncated = true;
      }
      return new BoundedOutput(output.toString(StandardCharsets.UTF_8), truncated);
    }
  }

  private static void terminate(Process process) {
    try {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
    } catch (RuntimeException ignored) {
      // The root process still receives a forceful termination below.
    } finally {
      process.destroyForcibly();
    }
  }

  private record BoundedOutput(String text, boolean truncated) {
  }
}
