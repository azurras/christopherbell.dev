package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Runs the privileged CPU sensor as a bounded one-shot PowerShell process. */
@Component
final class PowerShellCpuTemperatureProbe
    implements LibreHardwareCpuTemperatureClient.TemperatureProbe {
  private static final Logger LOGGER = LoggerFactory.getLogger(PowerShellCpuTemperatureProbe.class);
  private static final int MAX_OUTPUT_BYTES = 8_192;
  private static final Duration TERMINATION_GRACE = Duration.ofSeconds(1);
  private final ProcessFactory processFactory;
  private final NativeLibraryResolver libraryResolver;
  private final Duration timeout;
  private final AtomicReference<ManagedProcess> active = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private SecureNativeLibraryProvisioner.NativeLibraries libraries;

  @Autowired
  PowerShellCpuTemperatureProbe(CommandCenterProperties properties) {
    this(
        command -> JdkManagedProcess.start(command, MAX_OUTPUT_BYTES),
        () -> new SecureNativeLibraryProvisioner(properties.getSensorLibraryDirectory()).provision(),
        properties.getCpuTemperatureProcessTimeout());
  }

  PowerShellCpuTemperatureProbe(
      ProcessFactory processFactory, NativeLibraryResolver libraryResolver, Duration timeout) {
    this.processFactory = processFactory;
    this.libraryResolver = libraryResolver;
    this.timeout = timeout;
  }

  @Override
  public OptionalDouble readCelsius() {
    if (closed.get()) return OptionalDouble.empty();
    ManagedProcess process = null;
    try {
      var resources = resources();
      var command = List.of(
          "powershell.exe",
          "-NoLogo",
          "-NoProfile",
          "-NonInteractive",
          "-ExecutionPolicy",
          "Bypass",
          "-File",
          resources.cpuTemperatureScript().toString(),
          "-LibreHardwareMonitorPath",
          resources.libreHardwareMonitor().toString());
      process = processFactory.start(command);
      if (closed.get() || !active.compareAndSet(null, process)) {
        process.terminateTree();
        return OptionalDouble.empty();
      }
      var result = process.await(timeout);
      if (result.timedOut()) {
        LOGGER.warn("CPU temperature probe exceeded its {} ms timeout.", timeout.toMillis());
        return OptionalDouble.empty();
      }
      if (result.exitCode() != 0 || result.outputTruncated()) {
        LOGGER.warn("CPU temperature probe returned an unusable bounded response.");
        return OptionalDouble.empty();
      }
      double value = Double.parseDouble(result.stdout().trim());
      return Double.isFinite(value) && value > 0 && value <= 125
          ? OptionalDouble.of(value)
          : OptionalDouble.empty();
    } catch (IOException | RuntimeException failure) {
      LOGGER.warn("CPU temperature probe could not be sampled: {}", failure.getClass().getSimpleName());
      return OptionalDouble.empty();
    } finally {
      if (process != null) {
        active.compareAndSet(process, null);
        process.terminateTree();
      }
    }
  }

  private synchronized SecureNativeLibraryProvisioner.NativeLibraries resources() {
    if (closed.get()) throw new IllegalStateException("CPU temperature probe is closed.");
    if (libraries == null) libraries = libraryResolver.resolve();
    return libraries;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    var process = active.getAndSet(null);
    if (process != null) process.terminateTree();
    synchronized (this) {
      if (libraries != null) {
        libraries.close();
        libraries = null;
      }
    }
  }

  @FunctionalInterface
  interface ProcessFactory {
    ManagedProcess start(List<String> command) throws IOException;
  }

  @FunctionalInterface
  interface NativeLibraryResolver {
    SecureNativeLibraryProvisioner.NativeLibraries resolve();
  }

  interface ManagedProcess {
    ProcessResult await(Duration timeout);
    void terminateTree();
  }

  record ProcessResult(
      String stdout,
      String stderr,
      int exitCode,
      boolean timedOut,
      boolean outputTruncated) {}

  private static final class JdkManagedProcess implements ManagedProcess {
    private final Process process;
    private final int maxOutputBytes;
    private final AtomicBoolean terminated = new AtomicBoolean();

    private JdkManagedProcess(Process process, int maxOutputBytes) {
      this.process = process;
      this.maxOutputBytes = maxOutputBytes;
    }

    static JdkManagedProcess start(List<String> command, int maxOutputBytes) throws IOException {
      return new JdkManagedProcess(new ProcessBuilder(command).start(), maxOutputBytes);
    }

    @Override
    public ProcessResult await(Duration timeout) {
      try (var readers = Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("cpu-temperature-output-", 0).factory())) {
        Future<BoundedOutput> stdout = readers.submit(
            () -> readBounded(process.getInputStream(), maxOutputBytes));
        Future<BoundedOutput> stderr = readers.submit(
            () -> readBounded(process.getErrorStream(), maxOutputBytes));
        boolean completed = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!completed) terminateTree();
        var out = output(stdout);
        var err = output(stderr);
        return new ProcessResult(
            out.text(),
            err.text(),
            completed ? process.exitValue() : -1,
            !completed,
            out.truncated() || err.truncated());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        terminateTree();
        return new ProcessResult("", "", -1, true, false);
      }
    }

    private static BoundedOutput output(Future<BoundedOutput> output) {
      try {
        return output.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
      } catch (Exception failure) {
        output.cancel(true);
        return new BoundedOutput("", true);
      }
    }

    @Override
    public void terminateTree() {
      if (!terminated.compareAndSet(false, true)) return;
      var descendants = process.descendants().toList();
      descendants.reversed().forEach(ProcessHandle::destroy);
      process.destroy();
      try {
        process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      descendants.reversed().stream().filter(ProcessHandle::isAlive)
          .forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) process.destroyForcibly();
    }

    private static BoundedOutput readBounded(InputStream input, int limit) throws IOException {
      var captured = new ByteArrayOutputStream(Math.min(limit, 1024));
      var buffer = new byte[1024];
      boolean truncated = false;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        int remaining = limit - captured.size();
        if (remaining > 0) captured.write(buffer, 0, Math.min(remaining, read));
        if (read > remaining) truncated = true;
      }
      return new BoundedOutput(captured.toString(StandardCharsets.UTF_8), truncated);
    }
  }

  private record BoundedOutput(String text, boolean truncated) {}
}
