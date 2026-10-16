package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveSnapshot.Phase;
import java.time.Duration;
import java.time.Instant;

/**
 * Shared world time computed from the wall clock alone, so every survivor and every application
 * process agrees on the phase without a background ticker. A cycle is 14 minutes of day, 4 of dusk
 * and 6 of night. Boundaries count nightfalls and dawns in order from the world epoch: boundary
 * {@code 2n} is the nightfall of night {@code n} and {@code 2n + 1} is the dawn that ends it.
 */
final class SurviveClock {
  static final Instant WORLD_EPOCH = Instant.parse("2026-10-16T05:00:00Z");
  private static final long CYCLE_SECONDS = Duration.ofMinutes(24).toSeconds();
  private static final long DUSK_STARTS_AFTER_SECONDS = Duration.ofMinutes(14).toSeconds();
  private static final long NIGHT_STARTS_AFTER_SECONDS = Duration.ofMinutes(18).toSeconds();

  private SurviveClock() {}

  static Phase phaseAt(Instant now) {
    long secondsIntoCycle = secondsIntoCycleAt(now);
    if (secondsIntoCycle < DUSK_STARTS_AFTER_SECONDS) {
      return Phase.DAY;
    }
    if (secondsIntoCycle < NIGHT_STARTS_AFTER_SECONDS) {
      return Phase.DUSK;
    }
    return Phase.NIGHT;
  }

  /** The one-based day number; instants before the epoch count down through zero. */
  static long dayAt(Instant now) {
    return Math.floorDiv(secondsSinceEpoch(now), CYCLE_SECONDS) + 1;
  }

  static long secondsToNextPhaseAt(Instant now) {
    long secondsIntoCycle = secondsIntoCycleAt(now);
    return switch (phaseAt(now)) {
      case DAY -> DUSK_STARTS_AFTER_SECONDS - secondsIntoCycle;
      case DUSK -> NIGHT_STARTS_AFTER_SECONDS - secondsIntoCycle;
      case NIGHT -> CYCLE_SECONDS - secondsIntoCycle;
    };
  }

  /** How many nightfalls and dawns have happened since the epoch, as of {@code now}. */
  static long boundariesPassedBy(Instant now) {
    long secondsSinceEpoch = secondsSinceEpoch(now);
    long nightfallsPassed = Math.floorDiv(secondsSinceEpoch - NIGHT_STARTS_AFTER_SECONDS, CYCLE_SECONDS) + 1;
    long dawnsPassed = Math.floorDiv(secondsSinceEpoch, CYCLE_SECONDS);
    return nightfallsPassed + dawnsPassed;
  }

  static Instant boundaryInstant(long boundary) {
    long cycleStartSeconds = nightOf(boundary) * CYCLE_SECONDS;
    long offsetSeconds = isNightfall(boundary) ? NIGHT_STARTS_AFTER_SECONDS : CYCLE_SECONDS;
    return WORLD_EPOCH.plusSeconds(cycleStartSeconds + offsetSeconds);
  }

  static boolean isNightfall(long boundary) {
    return Math.floorMod(boundary, 2) == 0;
  }

  static long nightOf(long boundary) {
    return Math.floorDiv(boundary, 2);
  }

  private static long secondsIntoCycleAt(Instant now) {
    return Math.floorMod(secondsSinceEpoch(now), CYCLE_SECONDS);
  }

  private static long secondsSinceEpoch(Instant now) {
    return Duration.between(WORLD_EPOCH, now).toSeconds();
  }
}
