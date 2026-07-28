package dev.christopherbell.music.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Storage, process, and workload limits for the Music catalog. */
@Validated
@ConfigurationProperties("app.music")
public record MusicProperties(
    @NotNull Path root,
    @NotNull Path artworkCacheRoot,
    @NotBlank String ffprobeCommand,
    @NotBlank String ffmpegCommand,
    @Min(1) @Max(1000) int scanBatchSize,
    @NotNull @DurationMin(seconds = 10) Duration scanInterval,
    @NotNull @DurationMin(seconds = 1) Duration probeTimeout,
    @Min(1024) @Max(16_777_216) int probeMaxOutputBytes,
    @Min(1024) @Max(20_971_520) int artworkMaxBytes,
    @Min(64) @Max(4096) int artworkMaxDimension,
    boolean enabled) {
}
