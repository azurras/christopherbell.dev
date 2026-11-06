package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable claim and health for one roaming spawn, including the cooldown after defeat. */
public record SurviveFieldFight(String routeId, String fightId, String partyId, SurviveEnemy enemy,
                                double x, double z, double heading, int health,
                                List<String> fighters, Instant respawnAt, SkillOpening skillOpening) {
  public SurviveFieldFight {
    UUID.fromString(fightId);
    UUID.fromString(partyId);
    fighters = List.copyOf(fighters);
    if (routeId.isBlank() || routeId.length() > 48 || enemy == SurviveEnemy.RAIDER
        || !Double.isFinite(heading) || !(Math.hypot(x, z) <= 78)
        || health < 0 || health > enemy.health() || (health == 0) != (respawnAt != null)
        || (health == 0) != fighters.isEmpty() || fighters.size() > 2
        || fighters.stream().distinct().count() != fighters.size()
        || skillOpening != null && !fighters.contains(skillOpening.survivorId())) {
      throw new IllegalArgumentException("Saved field encounter is invalid.");
    }
    fighters.forEach(UUID::fromString);
  }

  public SurviveFieldFight withProgress(int remainingHealth, List<String> remainingFighters, Instant nextSpawn) {
    var remainingOpening = skillOpening != null && remainingFighters.contains(skillOpening.survivorId())
        ? skillOpening : null;
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        remainingHealth, remainingFighters, nextSpawn, remainingOpening);
  }

  public SurviveFieldFight withSkillOpening(SkillOpening opening) {
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        health, fighters, respawnAt, opening);
  }

  /** Server timestamp and actor for the weapon skill that can start Impact. */
  public record SkillOpening(String survivorId, Instant openedAt) {
    public SkillOpening {
      UUID.fromString(survivorId);
      Objects.requireNonNull(openedAt);
    }
  }
}
