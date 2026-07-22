package dev.christopherbell.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

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
    @NotNull DataSize maxOutput) {

  public SharedFolderMediaProperties {
    requirePositive(initialBuffer, "initial buffer");
    requirePositive(maxOutput, "maximum output");
  }

  private static void requirePositive(DataSize value, String label) {
    if (value == null || value.toBytes() < 1) {
      throw new IllegalArgumentException("Shared-folder media " + label + " must be positive");
    }
  }
}
