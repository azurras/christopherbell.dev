package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerShellCpuTemperatureProbeTest {
  @TempDir Path tempDir;
  @Test
  void usesOnlyFixedPowerShellArgumentsAndParsesSaneTemperature() {
    var process = FakeManagedProcess.completed("64.25", "", 0);
    var factory = new FakeProcessFactory(process);
    var probe = probe(factory);

    assertThat(probe.readCelsius()).hasValue(64.25);
    assertThat(factory.command()).containsExactly(
        PowerShellCpuTemperatureProbe.WINDOWS_POWERSHELL, "-NoLogo", "-NoProfile", "-NonInteractive",
        "-ExecutionPolicy", "Bypass", "-File", "C:\\trusted\\cpu-temperature.ps1",
        "-LibreHardwareMonitorPath", "C:\\trusted\\LibreHardwareMonitorLib.dll");
    assertThat(process.terminateCalls()).isEqualTo(1);
  }

  @Test
  void timeoutTerminatesDescendantsAndRootAndReturnsUnavailable() {
    var process = FakeManagedProcess.timedOut();

    assertThat(probe(new FakeProcessFactory(process)).readCelsius()).isEmpty();
    assertThat(process.terminateCalls()).isEqualTo(1);
    assertThat(process.forcefulTerminationRequested()).isTrue();
  }

  @Test
  void rejectsEmptyZeroMalformedOversizedProviderUnavailableAndNonZeroResults() {
    assertThat(read(FakeManagedProcess.completed("", "", 0))).isEmpty();
    assertThat(read(FakeManagedProcess.completed("0", "", 0))).isEmpty();
    assertThat(read(FakeManagedProcess.completed("NaN", "", 0))).isEmpty();
    assertThat(read(FakeManagedProcess.completed(
        "", "PawnIO 2.2.0.0 is unavailable.", 3))).isEmpty();
    assertThat(read(FakeManagedProcess.completed("64", "failure", 1))).isEmpty();
    assertThat(read(FakeManagedProcess.completed("64", "failure", 0))).isEmpty();
    assertThat(read(FakeManagedProcess.truncated("64"))).isEmpty();
    assertThat(read(FakeManagedProcess.completed("126", "", 0))).isEmpty();
  }

  @Test
  void closeIsIdempotentAndRemovesProvisionedResources() throws Exception {
    var process = FakeManagedProcess.completed("60", "", 0);
    Path library = Files.writeString(tempDir.resolve("LibreHardwareMonitorLib.dll"), "library");
    Path script = Files.writeString(tempDir.resolve("cpu-temperature.ps1"), "script");
    var libraries = SecureNativeLibraryProvisioner.NativeLibraries.owned(tempDir, library, script);
    var probe = new PowerShellCpuTemperatureProbe(
        new FakeProcessFactory(process), () -> libraries, Duration.ofSeconds(20));

    assertThat(probe.readCelsius()).hasValue(60);
    probe.close();
    probe.close();

    assertThat(tempDir).doesNotExist();
  }

  private static java.util.OptionalDouble read(FakeManagedProcess process) {
    return probe(new FakeProcessFactory(process)).readCelsius();
  }

  private static PowerShellCpuTemperatureProbe probe(FakeProcessFactory factory) {
    return new PowerShellCpuTemperatureProbe(factory, PowerShellCpuTemperatureProbeTest::libraries,
        Duration.ofSeconds(20));
  }

  private static SecureNativeLibraryProvisioner.NativeLibraries libraries() {
    return new SecureNativeLibraryProvisioner.NativeLibraries(
        Path.of("C:\\trusted"),
        Path.of("C:\\trusted\\LibreHardwareMonitorLib.dll"),
        Path.of("C:\\trusted\\cpu-temperature.ps1"));
  }

  private static final class FakeProcessFactory
      implements PowerShellCpuTemperatureProbe.ProcessFactory {
    private final FakeManagedProcess process;
    private List<String> command;
    private FakeProcessFactory(FakeManagedProcess process) { this.process = process; }
    @Override public PowerShellCpuTemperatureProbe.ManagedProcess start(List<String> command) {
      this.command = List.copyOf(command);
      return process;
    }
    List<String> command() { return command; }
  }

  private static final class FakeManagedProcess
      implements PowerShellCpuTemperatureProbe.ManagedProcess {
    private final PowerShellCpuTemperatureProbe.ProcessResult result;
    private int terminateCalls;

    private FakeManagedProcess(PowerShellCpuTemperatureProbe.ProcessResult result) {
      this.result = result;
    }
    static FakeManagedProcess completed(String stdout, String stderr, int exitCode) {
      return new FakeManagedProcess(new PowerShellCpuTemperatureProbe.ProcessResult(
          stdout, stderr, exitCode, false, false));
    }
    static FakeManagedProcess timedOut() {
      return new FakeManagedProcess(new PowerShellCpuTemperatureProbe.ProcessResult(
          "", "", -1, true, false));
    }
    static FakeManagedProcess truncated(String stdout) {
      return new FakeManagedProcess(new PowerShellCpuTemperatureProbe.ProcessResult(
          stdout, "", 0, false, true));
    }
    @Override public PowerShellCpuTemperatureProbe.ProcessResult await(Duration timeout) {
      return result;
    }
    @Override public void terminateTree() { terminateCalls++; }
    int terminateCalls() { return terminateCalls; }
    boolean forcefulTerminationRequested() { return result.timedOut(); }
  }

}
