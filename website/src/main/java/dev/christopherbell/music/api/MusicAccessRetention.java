package dev.christopherbell.music.api;

import java.time.Instant;

/** Published boundary for bounded Music access-attempt retention. */
public interface MusicAccessRetention {
  int deleteExpired(Instant cutoff, int limit);
}
