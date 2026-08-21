package dev.christopherbell.music.security;

import dev.christopherbell.music.api.MusicAccessRetention;
import java.time.Instant;
import java.util.List;

/** Atomic persistence and bounded query boundary for denied Music access attempts. */
public interface MusicAccessAttemptRepository extends MusicAccessRetention {
  MusicAccessAttempt record(
      String id,
      MusicAccessPrincipalType principalType,
      String principal,
      String reason,
      Instant occurredAt,
      Instant expiresAt);

  List<MusicAccessAttempt> recent(int limit);

  @Override
  int deleteExpired(Instant cutoff, int limit);
}
