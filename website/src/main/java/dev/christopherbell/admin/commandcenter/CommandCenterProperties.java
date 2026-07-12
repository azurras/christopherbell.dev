package dev.christopherbell.admin.commandcenter;

import java.nio.file.Path;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Typed host settings for command-center sampling, history, and fixed actions. */
@Data
@Component
@ConfigurationProperties(prefix = "command-center")
public class CommandCenterProperties {
  private boolean enabled = true;
  private Duration sampleInterval = Duration.ofSeconds(5);
  private Duration historyDuration = Duration.ofMinutes(15);
  private Duration providerTimeout = Duration.ofSeconds(2);
  private Path logPath = Path.of("logs", "application.log");
  private int maxLogLines = 250;
  private int maxLogBytes = 65_536;
  private int productionPort = 8080;
  private String productionServiceName = "ChristopherBellDev";
  private String commitIdentifier = "unknown";
  private boolean sensorLibrariesEnabled;
  private Path sensorLibraryDirectory = Path.of("command-center-sensors");
  private final Actions actions = new Actions();
  private final Thresholds thresholds = new Thresholds();

  /** Controls whether host actions are simulated or delegated to Windows executables. */
  public enum ActionMode {
    SIMULATED,
    WINDOWS
  }

  /** Fixed executable and abuse-prevention settings for host actions. */
  @Data
  public static class Actions {
    private ActionMode mode = ActionMode.SIMULATED;
    private Path winSwExecutable = Path.of("ChristopherBellDev.exe");
    private Path shutdownExecutable = Path.of("shutdown.exe");
    private boolean powerActionsEnabled;
    private Duration challengeTtl = Duration.ofMinutes(2);
    private Duration cooldown = Duration.ofMinutes(2);
    private Duration powerDelay = Duration.ofSeconds(60);
    private int failedAttempts = 3;
    private Duration failedAttemptWindow = Duration.ofMinutes(15);
  }

  /** Warning thresholds used when evaluating sampled host metrics. */
  @Data
  public static class Thresholds {
    private double cpuWarningPercent = 90;
    private double cpuTemperatureWarningCelsius = 85;
    private double gpuTemperatureWarningCelsius = 80;
    private double diskFreeWarningPercent = 10;
  }
}
