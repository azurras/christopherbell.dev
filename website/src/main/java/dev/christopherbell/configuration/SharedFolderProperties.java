package dev.christopherbell.configuration;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Typed limits and storage roots for the shared-folder feature. */
@Validated
@ConfigurationProperties("app.shared-folder")
public record SharedFolderProperties(
    @NotNull Path root,
    @NotNull Path systemRoot,
    @NotNull DataSize maxUpload,
    @NotNull DataSize uploadChunk,
    @NotNull DataSize minimumFreeSpace,
    @NotNull DataSize transcodeCacheLimit,
    @NotNull @DurationMin(seconds = 1) Duration recycleRetention,
    @NotNull @DurationMin(seconds = 1) Duration auditRetention,
    boolean enabled) {

  /** Rejects zero-sized resource limits during binding instead of accepting an unsafe default. */
  public SharedFolderProperties {
    requirePositive(maxUpload, "max upload");
    requirePositive(uploadChunk, "upload chunk");
    requirePositive(minimumFreeSpace, "minimum free space");
    requirePositive(transcodeCacheLimit, "transcode cache limit");
  }

  private static void requirePositive(DataSize value, String label) {
    if (value == null || value.toBytes() < 1) {
      throw new IllegalArgumentException("Shared-folder " + label + " must be at least one byte");
    }
  }
}
