package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Durable claim and health for one roaming spawn, including the cooldown after defeat. */
public record SurviveFieldFight(String routeId, String fightId, String partyId, SurviveEnemy enemy,
                                double x, double z, double heading, int health,
                                List<String> fighters, Instant respawnAt, SkillOpening skillOpening,
                                Map<String, SurviveEnmity> enmity) {
  public SurviveFieldFight {
    UUID.fromString(fightId);
    UUID.fromString(partyId);
    fighters = List.copyOf(fighters);
    enmity = enmity == null ? Map.of() : Map.copyOf(enmity);
    if (routeId.isBlank() || routeId.length() > 48 || enemy == SurviveEnemy.RAIDER
        || !Double.isFinite(heading) || !(Math.hypot(x, z) <= 78)
        || health < 0 || health > enemy.health() || (health == 0) != (respawnAt != null)
        || (health == 0) != fighters.isEmpty() || fighters.size() > 2
        || fighters.stream().distinct().count() != fighters.size()
        || skillOpening != null && !fighters.contains(skillOpening.survivorId())
        || !fighters.containsAll(enmity.keySet())) {
      throw new IllegalArgumentException("Saved field encounter is invalid.");
    }
    fighters.forEach(UUID::fromString);
  }

  public SurviveFieldFight withProgress(int remainingHealth, List<String> remainingFighters, Instant nextSpawn) {
    var remainingOpening = skillOpening != null && remainingFighters.contains(skillOpening.survivorId())
        ? skillOpening : null;
    var remainingEnmity = enmity.entrySet().stream().filter(entry -> remainingFighters.contains(entry.getKey()))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        remainingHealth, remainingFighters, nextSpawn, remainingOpening, remainingEnmity);
  }

  public SurviveFieldFight withSkillOpening(SkillOpening opening) {
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        health, fighters, respawnAt, opening, enmity);
  }

  /** Carry updated attention scores through shared health and skill changes. */
  public SurviveFieldFight withEnmity(Map<String, SurviveEnmity> scores) {
    return new SurviveFieldFight(routeId, fightId, partyId, enemy, x, z, heading,
        health, fighters, respawnAt, skillOpening, scores);
  }

  /** Server timestamp and actor for the weapon skill that can start Impact. */
  public record SkillOpening(String survivorId, Instant openedAt) {
    public SkillOpening {
      UUID.fromString(survivorId);
      Objects.requireNonNull(openedAt);
    }
  }
}
