package dev.christopherbell.music.catalog;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Protected installation root for the pinned FFmpeg and FFprobe tool set. */
@Validated
@ConfigurationProperties("app.music.media-tools")
public record MusicMediaToolProperties(@NotNull Path root) {
}
