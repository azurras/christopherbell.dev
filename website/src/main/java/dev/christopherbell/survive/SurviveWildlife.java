package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveEnemy;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Shared, process-local routes and encounter reservations. Mutated only under the service lock. */
final class SurviveWildlife {
  private static final double HUNT_RANGE_METRES = 5;
  private static final long RESPAWN_SECONDS = 35;
  private static final List<Route> ROUTES = List.of(
      new Route("meadow-hog", SurviveEnemy.HOG, -34, 24, 6, 0.14, 0, 0),
      new Route("shore-hog", SurviveEnemy.HOG, 17, 46, 7, 0.12, 2, 0),
      new Route("pine-wolf", SurviveEnemy.WOLF, -38, -26, 8, 0.20, 0, 7),
      new Route("ridge-wolf", SurviveEnemy.WOLF, 31, 19, 9, 0.17, 4, 7),
      new Route("ridge-bear", SurviveEnemy.BEAR, 43, -27, 8, 0.10, 2, 10));
  private final Map<String, Animal> encounters = new HashMap<>();
  private final Map<String, Instant> respawnAt = new HashMap<>();

  List<Animal> roaming(Instant now) {
    return roaming(now, null);
  }

  List<Animal> roaming(Instant now, Animal raider) {
    var roamingAnimals = ROUTES.stream()
        .filter(route -> !now.isBefore(respawnAt.getOrDefault(route.id(), Instant.MIN)))
        .filter(route -> encounters.values().stream().noneMatch(animal -> animal.id().equals(route.id())))
        .map(route -> route.pose(now));
    return Stream.concat(roamingAnimals, Stream.ofNullable(raider)).toList();
  }

  Animal nearest(SurvivePresence.Pose player, Instant now, boolean hostileOnly) {
    return nearest(player, now, hostileOnly, null);
  }

  Animal nearest(SurvivePresence.Pose player, Instant now, boolean hostileOnly, Animal raider) {
    if (player == null) {
      return null;
    }
    return roaming(now, raider).stream()
        .filter(animal -> animal.distanceFrom(player) <= (hostileOnly ? animal.aggroMetres() : HUNT_RANGE_METRES))
        .filter(animal -> !hostileOnly || animal.aggroMetres() > 0)
        .min(Comparator.comparingDouble(animal -> animal.distanceFrom(player))).orElse(null);
  }

  void claim(String survivorId, Animal animal) {
    encounters.put(survivorId, animal);
  }

  Animal encounter(String survivorId) {
    return encounters.get(survivorId);
  }

  void releaseFinished(Set<String> fightingSurvivors, Instant now) {
    encounters.entrySet().removeIf(entry -> {
      if (fightingSurvivors.contains(entry.getKey())) {
        return false;
      }
      if (entry.getValue().kind() != SurviveEnemy.RAIDER) {
        respawnAt.put(entry.getValue().id(), now.plusSeconds(RESPAWN_SECONDS));
      }
      return true;
    });
  }

  /** Public render data. All positions and aggro checks use the same route calculation. */
  public record Animal(String id, SurviveEnemy kind, double x, double z, double heading, double aggroMetres) {
    double distanceFrom(SurvivePresence.Pose player) {
      return Math.hypot(x - player.x(), z - player.z());
    }
  }

  private record Route(String id, SurviveEnemy kind, double x, double z, double radius, double radiansPerSecond,
                       double offset, double aggroMetres) {
    Animal pose(Instant now) {
      double angle = Math.IEEEremainder(now.toEpochMilli() / 1000.0 * radiansPerSecond + offset, Math.PI * 2);
      return new Animal(id, kind, x + Math.cos(angle) * radius, z + Math.sin(angle) * radius,
          Math.atan2(-Math.sin(angle), Math.cos(angle)), aggroMetres);
    }
  }
}
