package dev.christopherbell.survive.model;

import java.time.Instant;

/** A committed strike cue. The weapon reflects the hit, including a weapon that broke on impact. */
public record SurviveStrike(long revision, Instant at, SurviveWeapon weapon, boolean weaponSkill) {
  public SurviveStrike {
    if (revision < 1 || at == null) {
      throw new IllegalArgumentException("Saved strike cue is invalid.");
    }
  }

  /** Observers only animate recent strikes, even when old combat rounds settle together. */
  public boolean visibleAt(Instant now) {
    return !at.isAfter(now) && at.isAfter(now.minusMillis(1400));
  }
}
