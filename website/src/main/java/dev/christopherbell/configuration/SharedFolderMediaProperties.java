package dev.christopherbell.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import dev.christopherbell.sharedfolder.media.MediaJobStatus;

/** Bounded admission and progressive-delivery settings for shared-folder media jobs. */
@Validated
@ConfigurationProperties("app.shared-folder.media")
public record SharedFolderMediaProperties(
    @Min(1) int queueCapacity,
    @Min(1) int perAccountQueueCapacity,
    @NotNull @DurationMin(seconds = 1) Duration jobTimeout,
    @NotNull @DurationMin(millis = 10) Duration progressivePollInterval,
    @NotNull @DurationMin(seconds = 1) Duration progressiveIdleTimeout,
    @NotNull DataSize initialBuffer,
    @NotNull DataSize maxOutput,
    @NotNull @DurationMin(seconds = 1) Duration failedCleanupDelay,
    @NotNull @DurationMin(seconds = 1) Duration canceledCleanupDelay,
    @NotNull @DurationMin(seconds = 1) Duration insufficientSpaceCleanupDelay,
    @NotNull @DurationMin(seconds = 1) Duration timedOutCleanupDelay,
    @NotNull @DurationMin(seconds = 1) Duration diagnosticRetention) {

  @ConstructorBinding
  public SharedFolderMediaProperties {
    requirePositive(initialBuffer, "initial buffer");
    requirePositive(maxOutput, "maximum output");
  }

  /** Preserves callers created before terminal cleanup retention became configurable. */
  public SharedFolderMediaProperties(
      int queueCapacity,
      int perAccountQueueCapacity,
      Duration jobTimeout,
      Duration progressivePollInterval,
      Duration progressiveIdleTimeout,
      DataSize initialBuffer,
      DataSize maxOutput) {
    this(queueCapacity, perAccountQueueCapacity, jobTimeout, progressivePollInterval,
        progressiveIdleTimeout, initialBuffer, maxOutput, Duration.ofHours(1),
        Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(7));
  }

  /** Returns the state-specific delay before private artifacts become cleanup-eligible. */
  public Duration cleanupDelay(MediaJobStatus status) {
    return switch (status) {
      case FAILED -> failedCleanupDelay;
      case CANCELED -> canceledCleanupDelay;
      case INSUFFICIENT_SPACE -> insufficientSpaceCleanupDelay;
      case TIMED_OUT -> timedOutCleanupDelay;
      default -> throw new IllegalArgumentException("Media status is not cleanup-eligible");
    };
  }

  private static void requirePositive(DataSize value, String label) {
    if (value == null || value.toBytes() < 1) {
      throw new IllegalArgumentException("Shared-folder media " + label + " must be positive");
    }
  }
}
