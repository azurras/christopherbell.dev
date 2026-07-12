package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import io.github.pandalxb.jpowershell.PowerShell;
import jakarta.annotation.PreDestroy;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/** Owns a bounded PowerShell session and explicitly closes every native computer instance. */
@Component
public final class LibreHardwareCpuTemperatureClient implements CpuTemperatureSensorClient {
  private final SessionFactory sessionFactory;
  private final NativeLibraryResolver libraryResolver;
  private final boolean windows;
  private SensorSession session;
  private SecureNativeLibraryProvisioner.NativeLibraries libraries;

  public LibreHardwareCpuTemperatureClient(CommandCenterProperties properties) {
    this(
        () -> new JPowerShellSession(PowerShell.openSession().configuration(Map.of(
            "maxWait", Long.toString(Math.max(1, properties.getProviderTimeout().toMillis())),
            "waitPause", "5"))),
        () -> new SecureNativeLibraryProvisioner(properties.getSensorLibraryDirectory()).provision(),
        properties.isSensorLibrariesEnabled()
            && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
  }

  LibreHardwareCpuTemperatureClient(
      SessionFactory sessionFactory, NativeLibraryResolver libraryResolver, boolean windows) {
    this.sessionFactory = sessionFactory;
    this.libraryResolver = libraryResolver;
    this.windows = windows;
  }

  @Override
  public synchronized OptionalDouble readCelsius() {
    if (!windows) {
      return OptionalDouble.empty();
    }
    try {
      if (session == null) {
        if (libraries == null) {
          libraries = libraryResolver.resolve();
        }
        session = sessionFactory.open();
      }
      var result = session.execute(script(libraries.libreHardwareMonitor().toString()));
      if (result.error() || result.timeout()) {
        discardSession();
        return OptionalDouble.empty();
      }
      double value = Double.parseDouble(result.output().trim());
      return Double.isFinite(value) && value > 0 && value <= 125
          ? OptionalDouble.of(value) : OptionalDouble.empty();
    } catch (RuntimeException failure) {
      discardSession();
      return OptionalDouble.empty();
    }
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    discardSession();
    if (libraries != null) {
      libraries.close();
      libraries = null;
    }
  }

  private void discardSession() {
    if (session != null) {
      try {
        session.close();
      } finally {
        session = null;
      }
    }
  }

  private static String script(String dllPath) {
    String escaped = dllPath.replace("'", "''");
    return """
        $PC = $null
        try {
          Add-Type -Path '%s'
          $PC = New-Object LibreHardwareMonitor.Hardware.Computer
          $PC.IsCpuEnabled = $true
          $PC.Open()
          $values = @()
          foreach ($hw in $PC.Hardware) {
            if ($hw.HardwareType -eq [LibreHardwareMonitor.Hardware.HardwareType]::Cpu) {
              $hw.Update()
              $values += $hw.Sensors | Where-Object { $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature -and $null -ne $_.Value } | ForEach-Object { [double]$_.Value }
              foreach ($sub in $hw.SubHardware) {
                $sub.Update()
                $values += $sub.Sensors | Where-Object { $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature -and $null -ne $_.Value } | ForEach-Object { [double]$_.Value }
              }
            }
          }
          if ($values.Count -gt 0) { [Console]::Write(($values | Measure-Object -Maximum).Maximum.ToString([Globalization.CultureInfo]::InvariantCulture)) }
        } finally {
          if ($null -ne $PC) { $PC.Close() }
        }
        """.formatted(escaped);
  }

  interface SessionFactory {
    SensorSession open();
  }

  interface NativeLibraryResolver {
    SecureNativeLibraryProvisioner.NativeLibraries resolve();
  }

  interface SensorSession extends AutoCloseable {
    SensorResult execute(String script);
    @Override void close();
  }

  record SensorResult(String output, boolean error, boolean timeout) {}

  private record JPowerShellSession(PowerShell delegate) implements SensorSession {
    @Override
    public SensorResult execute(String script) {
      var response = delegate.executeCommand(script);
      return new SensorResult(response.getCommandOutput(), response.isError(), response.isTimeout());
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
