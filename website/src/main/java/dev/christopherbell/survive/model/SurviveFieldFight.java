package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable claim and health for one roaming spawn, including the cooldown after defeat. */
public record SurviveFieldFight(String routeId, String fightId, String partyId, SurviveEnemy enemy,
                                double x, double z, double heading, int health,
                                List<String> fighters, Instant respawnAt) {
  public SurviveFieldFight {
    UUID.fromString(fightId);
    UUID.fromString(partyId);
    fighters = List.copyOf(fighters);
    if (routeId.isBlank() || routeId.length() > 48 || enemy == SurviveEnemy.RAIDER
        || !Double.isFinite(heading) || !(Math.hypot(x, z) <= 78)
        || health < 0 || health > enemy.health() || (health == 0) != (respawnAt != null)
        || (health == 0) != fighters.isEmpty() || fighters.size() > 2
        || fighters.stream().distinct().count() != fighters.size()) {
      throw new IllegalArgumentException("Saved field encounter is invalid.");
    }
    fighters.forEach(UUID::fromString);
  }

  public SurviveFieldFight withProgress(int remainingHealth, List<String> remainingFighters, Instant nextSpawn) {
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        remainingHealth, remainingFighters, nextSpawn);
  }
}
