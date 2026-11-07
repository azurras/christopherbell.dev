package dev.christopherbell.survive.model;

import java.time.Duration;
import java.time.Instant;

/** Server-owned timing for a single cast. No reward is reserved until a successful reel. */
public record SurviveFishing(Instant biteAt) {
  public SurviveFishing {
    if (biteAt == null) {
      throw new IllegalArgumentException("A fishing cast needs a bite time.");
    }
  }

  public static SurviveFishing castAt(Instant now) {
    return new SurviveFishing(now.plusSeconds(2));
  }

  public Instant escapesAt() {
    return biteAt.plusSeconds(5);
  }

  public Timing timingAt(Instant now) {
    return new Timing(Math.max(0, Duration.between(now, biteAt).toMillis()),
        Math.max(0, Duration.between(now, escapesAt()).toMillis()));
  }

  /** Relative deadlines for drawing a cue without relying on the player's wall clock. */
  public record Timing(long millisecondsUntilBite, long millisecondsUntilEscape) {}
}
