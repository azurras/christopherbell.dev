package dev.christopherbell.admin.commandcenter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Typed host settings for command-center sampling, history, and fixed actions. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "command-center")
public class CommandCenterProperties {
  private boolean enabled = true;
  @NotNull
  @DurationMin(seconds = 1)
  @DurationMax(minutes = 5)
  private Duration sampleInterval = Duration.ofSeconds(5);
  @NotNull
  @DurationMin(minutes = 1)
  @DurationMax(days = 1)
  private Duration historyDuration = Duration.ofMinutes(15);
  @NotNull
  @DurationMin(millis = 100)
  @DurationMax(seconds = 30)
  private Duration providerTimeout = Duration.ofSeconds(2);
  @NotNull
  @DurationMin(seconds = 1)
  @DurationMax(minutes = 10)
  private Duration cpuTemperatureRefreshInterval = Duration.ofSeconds(30);
  @NotNull
  @DurationMin(seconds = 1)
  @DurationMax(minutes = 2)
  private Duration cpuTemperatureProcessTimeout = Duration.ofSeconds(20);
  @NotNull
  private Path logPath = Path.of("logs", "application.log");
  @Min(1)
  @Max(10_000)
  private int maxLogLines = 250;
  @Min(1024)
  @Max(16_777_216)
  private int maxLogBytes = 65_536;
  @Min(1)
  @Max(65_535)
  private int productionPort = 8080;
  @NotBlank
  @Size(max = 128)
  private String productionServiceName = "ChristopherBellDev";
  @NotBlank
  @Size(max = 128)
  private String commitIdentifier = "unknown";
  private boolean sensorLibrariesEnabled;
  @NotNull
  private Path sensorLibraryDirectory = Path.of("command-center-sensors");
  @Valid
  @NotNull
  private final Actions actions = new Actions();
  @Valid
  @NotNull
  private final Thresholds thresholds = new Thresholds();

  @AssertTrue(message = "history duration must be at least the sample interval")
  public boolean isHistoryWindowValid() {
    return historyDuration == null || sampleInterval == null
        || historyDuration.compareTo(sampleInterval) >= 0;
  }

  @AssertTrue(message = "provider timeout must not exceed the sample interval")
  public boolean isProviderTimeoutWithinSampleInterval() {
    return providerTimeout == null || sampleInterval == null
        || providerTimeout.compareTo(sampleInterval) <= 0;
  }

  @AssertTrue(message = "CPU process timeout must not exceed the refresh interval")
  public boolean isCpuProcessTimeoutWithinRefreshInterval() {
    return cpuTemperatureProcessTimeout == null || cpuTemperatureRefreshInterval == null
        || cpuTemperatureProcessTimeout.compareTo(cpuTemperatureRefreshInterval) <= 0;
  }

  @AssertTrue(message = "log path and sensor library directory must not be empty")
  public boolean isConfiguredPathsNonEmpty() {
    return isNullOrNonEmpty(logPath) && isNullOrNonEmpty(sensorLibraryDirectory);
  }

  /** Controls whether host actions are simulated or delegated to Windows executables. */
  public enum ActionMode {
    SIMULATED,
    WINDOWS
  }

  /** Fixed executable and abuse-prevention settings for host actions. */
  @Data
  public static class Actions {
    @NotNull
    private ActionMode mode = ActionMode.SIMULATED;
    @NotNull
    private Path winSwExecutable = Path.of("ChristopherBellDev.exe");
    @NotNull
    private Path shutdownExecutable = Path.of("shutdown.exe");
    private boolean powerActionsEnabled;
    @NotNull
    @DurationMin(seconds = 5)
    @DurationMax(minutes = 15)
    private Duration challengeTtl = Duration.ofMinutes(2);
    @NotNull
    @DurationMin(seconds = 1)
    @DurationMax(hours = 24)
    private Duration cooldown = Duration.ofMinutes(2);
    @NotNull
    @DurationMin(seconds = 1)
    @DurationMax(minutes = 10)
    private Duration powerDelay = Duration.ofSeconds(60);
    @NotNull
    @DurationMin(millis = 100)
    @DurationMax(seconds = 30)
    private Duration commandResultTimeout = Duration.ofSeconds(5);
    @Min(1)
    @Max(10)
    private int failedAttempts = 3;
    @NotNull
    @DurationMin(minutes = 1)
    @DurationMax(hours = 24)
    private Duration failedAttemptWindow = Duration.ofMinutes(15);
    @Min(1)
    @Max(64)
    private int maxChallengesPerActor = 8;
    @Min(1)
    @Max(1024)
    private int maxChallengesTotal = 64;

    @AssertTrue(message = "power delay must use whole seconds")
    public boolean isPowerDelayUsesWholeSeconds() {
      return powerDelay == null || powerDelay.equals(Duration.ofSeconds(powerDelay.toSeconds()));
    }

    @AssertTrue(message = "per-actor challenge limit must not exceed total challenge limit")
    public boolean isChallengeLimitsOrdered() {
      return maxChallengesPerActor <= maxChallengesTotal;
    }

    @AssertTrue(message = "Windows action mode requires absolute executable paths")
    public boolean isWindowsExecutablePathsAbsolute() {
      if (mode == null || mode == ActionMode.SIMULATED) {
        return true;
      }
      return isAbsoluteNonEmpty(winSwExecutable) && isAbsoluteNonEmpty(shutdownExecutable);
    }

    private static boolean isAbsoluteNonEmpty(Path path) {
      return path != null && path.isAbsolute() && !path.toString().isBlank();
    }
  }

  /** Warning thresholds used when evaluating sampled host metrics. */
  @Data
  public static class Thresholds {
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private double cpuWarningPercent = 90;
    @DecimalMin("1.0")
    @DecimalMax("150.0")
    private double cpuTemperatureWarningCelsius = 85;
    @DecimalMin("1.0")
    @DecimalMax("150.0")
    private double gpuTemperatureWarningCelsius = 80;
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private double diskFreeWarningPercent = 10;

    @AssertTrue(message = "warning thresholds must be finite")
    public boolean isFinite() {
      return Double.isFinite(cpuWarningPercent)
          && Double.isFinite(cpuTemperatureWarningCelsius)
          && Double.isFinite(gpuTemperatureWarningCelsius)
          && Double.isFinite(diskFreeWarningPercent);
    }
  }

  private static boolean isNullOrNonEmpty(Path path) {
    return path == null || !path.toString().isBlank();
  }
}
