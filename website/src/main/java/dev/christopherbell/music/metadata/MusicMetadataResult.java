package dev.christopherbell.music.metadata;

import dev.christopherbell.music.web.MusicTrackView;
import java.time.Instant;

public record MusicMetadataResult(
    String editId,
    String observedToken,
    Instant backupExpiresAt,
    MusicTrackView track) {
}
