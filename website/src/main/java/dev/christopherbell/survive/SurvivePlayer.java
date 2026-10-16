package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Instant;
import java.util.UUID;

/** Private survivor state, owned and mutated only under the world service's monitor. */
final class SurvivePlayer {
  static final int MAX_HEALTH = 10;
  static final int MAX_HUNGER = 10;
  final String survivorId;
  final String accountId;
  final String name;
  Instant lastSeen;
  int health = MAX_HEALTH;
  int strength = 2;
  int stamina = 10;
  int experience;
  int experienceToNextLevel = 1;
  int wood;
  int food;
  int stone;
  /** Fullness from 0 (starving) to 10; effort spends it and eating restores it. */
  int hunger = MAX_HUNGER;
  /** The animal in the current fight, present exactly while in combat. */
  SurviveEnemy enemy;
  int enemyHealth;
  /** Whether this fight's first attack has been made, which ends the spear's reach. */
  boolean struckFirst;
  SurviveWeapon weapon;
  int weaponDurability;
  /** Standing watch at camp for the coming nightfall raid. */
  boolean guarding;
  /** When this survivor lay down in a shelter bed, present exactly while sleeping. */
  Instant sleptAt;
  Status status = Status.EXPLORING;
  long revision;
  String message = "You arrive at the camp. Gather wood, build together, and survive.";

  SurvivePlayer(String name, Instant joinedAt) {
    this(name, joinedAt, null, UUID.randomUUID().toString());
  }

  SurvivePlayer(String name, Instant joinedAt, String accountId, String survivorId) {
    this.accountId = accountId;
    this.survivorId = survivorId;
    this.name = name;
    lastSeen = joinedAt;
  }

  int inventoryCount() {
    return wood + food + stone;
  }

  boolean hasInventorySpace() {
    return inventoryCount() < SurviveWorld.INVENTORY_CAPACITY;
  }

  /** Damage of one attack, which is also this survivor's share of a raid defense. */
  int attackDamage() {
    return 1 + strength + (weapon == null ? 0 : weapon.damageBonus());
  }

  void gainGatheringExperience() {
    // Bound exponential thresholds and attack strength without changing early progression.
    if (strength >= 20) {
      return;
    }
    experience++;
    if (experience >= experienceToNextLevel) {
      strength++;
      stamina++;
      experience = 0;
      experienceToNextLevel *= 2;
    }
  }

  void startFighting(SurviveEnemy foundEnemy) {
    enemy = foundEnemy;
    enemyHealth = foundEnemy.health();
    struckFirst = false;
    guarding = false;
    status = Status.COMBAT;
  }

  void stopFighting() {
    enemy = null;
    enemyHealth = 0;
    struckFirst = false;
    status = Status.EXPLORING;
  }

  /** Spends one use of the held weapon and reports whether that use broke it. */
  boolean wearWeaponAndReportBreak() {
    if (weapon == null) {
      return false;
    }
    weaponDurability--;
    if (weaponDurability > 0) {
      return false;
    }
    weapon = null;
    weaponDurability = 0;
    return true;
  }

  void fallAsleep(Instant bedtime) {
    guarding = false;
    sleptAt = bedtime;
    status = Status.SLEEPING;
  }

  /**
   * Gets up, spending a night's fullness, and returns the health actually recovered. A starving
   * survivor does not heal.
   */
  int wakeAndRecover(int healthToRecover) {
    boolean starving = hunger == 0;
    hunger = Math.max(0, hunger - 2);
    sleptAt = null;
    status = Status.EXPLORING;
    if (starving) {
      return 0;
    }
    int previousHealth = health;
    health = Math.min(MAX_HEALTH, health + healthToRecover);
    return health - previousHealth;
  }

  void takeDamage(int damage) {
    health = Math.max(0, health - damage);
    if (health == 0) {
      status = Status.DEAD;
      enemy = null;
      enemyHealth = 0;
      struckFirst = false;
      guarding = false;
      sleptAt = null;
    }
  }
}
