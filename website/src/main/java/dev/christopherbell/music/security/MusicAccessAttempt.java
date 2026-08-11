package dev.christopherbell.music.security;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

/** Aggregated denied Music entry attempts retained for 30 days. */
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
