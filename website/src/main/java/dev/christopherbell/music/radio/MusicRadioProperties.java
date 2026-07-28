package dev.christopherbell.music.radio;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded smart-radio selection, transition, and lease controls. */
@Validated
@ConfigurationProperties("app.music.radio")
public record MusicRadioProperties(
    @Min(1) @Max(500) int trackCooldown,
    @Min(0) @Max(100) int artistCooldown,
    @DecimalMin("0.0") @DecimalMax("1.0") double explorationProbability,
    @Min(1) @Max(1000) int maximumCatchUpTransitions,
    @NotNull @DurationMin(seconds = 2) Duration leaseDuration) {
}
