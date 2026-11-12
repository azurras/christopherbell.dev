package dev.christopherbell.survive.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A fighter's durable and fading contribution to enemy attention. */
public record SurviveEnmity(int durablePoints, int fadingPoints, Instant measuredAt) {
  private static final int DURABLE_POINTS_PER_HP = 1_000;
  private static final int FADING_POINTS_PER_HP = 3_000;
  private static final int MAX_POINTS = 100_000;

  public SurviveEnmity {
    Objects.requireNonNull(measuredAt);
    if (durablePoints < 0 || fadingPoints < 0 || durablePoints > MAX_POINTS || fadingPoints > MAX_POINTS) {
      throw new IllegalArgumentException("Saved enmity is out of range.");
    }
  }

  public static SurviveEnmity emptyAt(Instant at) {
    return new SurviveEnmity(0, 0, at);
  }

  /** Add actual HP dealt and settle fading points at the strike time. */
  public SurviveEnmity afterDamageDealt(int damage, Instant at) {
    return new SurviveEnmity(
        Math.min(MAX_POINTS, durablePoints + damage * DURABLE_POINTS_PER_HP),
        Math.min(MAX_POINTS, fadingPointsAt(at) + damage * FADING_POINTS_PER_HP),
        at.isBefore(measuredAt) ? measuredAt : at);
  }

  /** A bite lowers durable attention without restarting the fading clock. */
  public SurviveEnmity afterDamageTaken(int damage) {
    return new SurviveEnmity(Math.max(0, durablePoints - damage * DURABLE_POINTS_PER_HP), fadingPoints, measuredAt);
  }

  public int scoreAt(Instant at) {
    return durablePoints + fadingPointsAt(at);
  }

  private int fadingPointsAt(Instant at) {
    if (!at.isAfter(measuredAt)) {
      return fadingPoints;
    }
    var elapsed = Duration.between(measuredAt, at);
    return elapsed.compareTo(Duration.ofMillis(fadingPoints)) >= 0 ? 0 : fadingPoints - (int) elapsed.toMillis();
  }
}
