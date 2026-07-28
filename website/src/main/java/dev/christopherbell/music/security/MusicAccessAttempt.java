package dev.christopherbell.music.security;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Aggregated denied Music entry attempts retained for 30 days. */
@Document("music_access_attempts")
public record MusicAccessAttempt(
    @Id String id,
    MusicAccessPrincipalType principalType,
    String principal,
    String reason,
    long count,
    Instant firstAttemptAt,
    Instant lastAttemptAt,
    @Indexed(expireAfter = "0s") Instant expiresAt) {
}
