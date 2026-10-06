package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Instant;

/** Private survivor state, owned and mutated only under the world service's monitor. */
final class SurvivePlayer {
  final String name;
  Instant lastSeen;
  int health = 10;
  int strength = 2;
  int stamina = 10;
  int experience;
  int experienceToNextLevel = 1;
  int wood;
  int food;
  int enemyHealth;
  Status status = Status.EXPLORING;
  long revision;
  String message = "You arrive at the camp. Gather wood, build together, and survive.";

  SurvivePlayer(String name, Instant joinedAt) {
    this.name = name;
    lastSeen = joinedAt;
  }

  boolean hasInventorySpace() {
    return wood + food < SurviveWorld.INVENTORY_CAPACITY;
  }

  void gainGatheringExperience() {
    // Bound exponential thresholds and attack strength without changing early progression.
    if (strength >= 20) return;
    experience++;
    if (experience >= experienceToNextLevel) {
      strength++;
      stamina++;
      experience = 0;
      experienceToNextLevel *= 2;
    }
  }

  void takeDamage(int damage) {
    health = Math.max(0, health - damage);
    if (health == 0) {
      status = Status.DEAD;
      enemyHealth = 0;
    }
  }
}
