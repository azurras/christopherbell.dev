package dev.christopherbell.music.security;

import java.time.Instant;
import java.util.List;

/** Atomic persistence and bounded query boundary for denied Music access attempts. */
public interface MusicAccessAttemptRepository {
  MusicAccessAttempt record(
      String id,
      MusicAccessPrincipalType principalType,
      String principal,
      String reason,
      Instant occurredAt,
      Instant expiresAt);

  List<MusicAccessAttempt> recent(int limit);

  int deleteExpired(Instant cutoff, int limit);
}
