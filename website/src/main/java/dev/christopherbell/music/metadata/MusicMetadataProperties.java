package dev.christopherbell.music.metadata;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Private storage and workload limits for revision-checked Music tag edits. */
@Validated
@ConfigurationProperties("app.music.metadata")
public record MusicMetadataProperties(
    @NotNull Path privateRoot,
    @NotNull @DurationMin(seconds = 5) Duration processTimeout,
    @Min(1024) @Max(16_777_216) int processMaxOutputBytes,
    @Min(1_048_576) long maxSourceBytes,
    @Min(1024) @Max(20_971_520) int artworkMaxBytes,
    @Min(1_048_576) long maxExpansionBytes,
    @NotNull @DurationMin(days = 1) Duration backupRetention,
    @NotNull @DurationMin(minutes = 5) Duration leaseDuration) {
}
