package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class LibreHardwareCpuTemperatureClientTest {
  @Test
  void acceptsOnlyFinitePhysicallySaneTemperature() {
    var session = new FakeSession("64.25", false, false);
    var client = new LibreHardwareCpuTemperatureClient(() -> session, () -> "C:\\fixed\\Libre.dll", true);

    assertThat(client.readCelsius()).hasValue(64.25);
  }

  @Test
  void timeoutClosesAndDiscardsSessionSoNextReadCanRecover() {
    var sessions = new ArrayDeque<LibreHardwareCpuTemperatureClient.SensorSession>();
    var timedOut = new FakeSession("", false, true);
    var recovered = new FakeSession("55.0", false, false);
    sessions.add(timedOut);
    sessions.add(recovered);
    var client = new LibreHardwareCpuTemperatureClient(sessions::remove, () -> "C:\\fixed\\Libre.dll", true);

    assertThat(client.readCelsius()).isEmpty();
    assertThat(timedOut.closed).isTrue();
    assertThat(client.readCelsius()).hasValue(55.0);
  }

  @Test
  void unsupportedPlatformAndStartupFailureDegradeWithoutThrowing() {
    var unsupported = new LibreHardwareCpuTemperatureClient(
        () -> { throw new AssertionError("must not open"); }, () -> "unused", false);
    var failed = new LibreHardwareCpuTemperatureClient(
        () -> { throw new IllegalStateException("PowerShell missing"); }, () -> "unused", true);

    assertThat(unsupported.readCelsius()).isEmpty();
    assertThat(failed.readCelsius()).isEmpty();
  }

  @Test
  void closeIsIdempotentAndClosesOwnedSession() {
    var session = new FakeSession("60", false, false);
    var client = new LibreHardwareCpuTemperatureClient(() -> session, () -> "C:\\fixed\\Libre.dll", true);
    client.readCelsius();

    client.close();
    client.close();

    assertThat(session.closeCalls).isEqualTo(1);
  }

  private static final class FakeSession implements LibreHardwareCpuTemperatureClient.SensorSession {
    private final String output;
    private final boolean error;
    private final boolean timeout;
    private boolean closed;
    private int closeCalls;

    private FakeSession(String output, boolean error, boolean timeout) {
      this.output = output;
      this.error = error;
      this.timeout = timeout;
    }

    @Override
    public LibreHardwareCpuTemperatureClient.SensorResult execute(String script) {
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
