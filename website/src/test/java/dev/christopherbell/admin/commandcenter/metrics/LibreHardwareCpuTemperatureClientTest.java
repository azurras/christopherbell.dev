package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibreHardwareCpuTemperatureClientTest {
  @TempDir java.nio.file.Path tempDir;
  @Test
  void acceptsOnlyFinitePhysicallySaneTemperature() {
    var session = new FakeSession("64.25", false, false);
    var client = new LibreHardwareCpuTemperatureClient(
        () -> session, () -> libraries("C:\\fixed\\Libre.dll"), true);

    assertThat(client.readCelsius()).hasValue(64.25);
  }

  @Test
  void timeoutClosesAndDiscardsSessionSoNextReadCanRecover() {
    var sessions = new ArrayDeque<LibreHardwareCpuTemperatureClient.SensorSession>();
    var timedOut = new FakeSession("", false, true);
    var recovered = new FakeSession("55.0", false, false);
    sessions.add(timedOut);
    sessions.add(recovered);
    var client = new LibreHardwareCpuTemperatureClient(
        sessions::remove, () -> libraries("C:\\fixed\\Libre.dll"), true);

    assertThat(client.readCelsius()).isEmpty();
    assertThat(timedOut.closed).isTrue();
    assertThat(client.readCelsius()).hasValue(55.0);
  }

  @Test
  void unsupportedPlatformAndStartupFailureDegradeWithoutThrowing() {
    var unsupported = new LibreHardwareCpuTemperatureClient(
        () -> { throw new AssertionError("must not open"); }, () -> libraries("unused"), false);
    var failed = new LibreHardwareCpuTemperatureClient(
        () -> { throw new IllegalStateException("PowerShell missing"); },
        () -> { throw new SecurityException("ACL unavailable"); }, true);

    assertThat(unsupported.readCelsius()).isEmpty();
    assertThat(failed.readCelsius()).isEmpty();
  }

  @Test
  void closeIsIdempotentAndClosesOwnedSession() {
    var session = new FakeSession("60", false, false);
    var client = new LibreHardwareCpuTemperatureClient(
        () -> session, () -> libraries("C:\\fixed\\Libre.dll"), true);
    client.readCelsius();

    client.close();
    client.close();

    assertThat(session.closeCalls).isEqualTo(1);
  }

  @Test
  void loadsOnlyTheChecksumVerifiedFreshLibraryPath() throws Exception {
    byte[] bytes = "verified-library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String hash = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var provisioner = new SecureNativeLibraryProvisioner(
        tempDir,
        java.util.List.of(new SecureNativeLibraryProvisioner.ResourceSpec(
            "LibreHardwareMonitorLib.dll", hash,
            () -> new java.io.ByteArrayInputStream(bytes))),
        path -> {}, () -> "load-trust");
    var session = new FakeSession("60", false, false);
    var client = new LibreHardwareCpuTemperatureClient(
        () -> session, provisioner::provision, true);

    assertThat(client.readCelsius()).hasValue(60);
    assertThat(session.script).contains(
        tempDir.resolve("jlibre-1.0.6-load-trust/LibreHardwareMonitorLib.dll").toString());
    assertThat(session.script).doesNotContain("user.dir", "..\\");
    client.close();
  }

  private static SecureNativeLibraryProvisioner.NativeLibraries libraries(String path) {
    return new SecureNativeLibraryProvisioner.NativeLibraries(
        java.nio.file.Path.of(path).getParent(), java.nio.file.Path.of(path));
  }

  private static final class FakeSession implements LibreHardwareCpuTemperatureClient.SensorSession {
    private final String output;
    private final boolean error;
    private final boolean timeout;
    private boolean closed;
    private int closeCalls;
    private String script;

    private FakeSession(String output, boolean error, boolean timeout) {
      this.output = output;
      this.error = error;
      this.timeout = timeout;
    }

    @Override
    public LibreHardwareCpuTemperatureClient.SensorResult execute(String script) {
      this.script = script;
      assertThat(script).contains("LibreHardwareMonitor.Hardware.Computer", ".Close()");
      return new LibreHardwareCpuTemperatureClient.SensorResult(output, error, timeout);
    }

    @Override
    public void close() {
      closed = true;
      closeCalls++;
    }
  }
}
