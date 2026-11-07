package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveStrike;
import dev.christopherbell.survive.model.SurviveWeapon;
import dev.christopherbell.survive.model.SurvivePackedWeapon;
import dev.christopherbell.survive.model.SurviveWaystation.Assignment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  /** Fullness from 0 (starving) to 10. Eating restores it. */
  int hunger = MAX_HUNGER;
  int activeFullnessMilliseconds;
  /** The animal in the current fight, present exactly while in combat. */
  SurviveEnemy enemy;
  int enemyHealth;
  /** Whether this fight's first attack has been made, which ends the spear's reach. */
  boolean struckFirst;
  /** The next server-clock exchange, or null until the player explicitly engages. */
  Instant nextStrikeAt;
  SurviveStrike lastStrike;
  int tacticalPoints;
  boolean defending;
  String combatOutcome;
  Assignment waystationAssignment = Assignment.AVAILABLE;
  SurviveWeapon weapon;
  int weaponDurability;
  final List<SurvivePackedWeapon> packedWeapons = new ArrayList<>();
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
    return wood + food + stone + packedWeapons.size();
  }

  boolean hasInventorySpace() {
    return inventoryCount() < SurviveWorld.INVENTORY_CAPACITY;
  }

  /** Damage of one attack, which is also this survivor's share of a raid defense. */
  int attackDamage() {
    return 1 + strength + (weapon == null ? 0 : weapon.damageBonus());
  }

  void gainExperience() {
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
    disengage();
    combatOutcome = null;
    guarding = false;
    status = Status.COMBAT;
  }

  void stopFighting() {
    enemy = null;
    enemyHealth = 0;
    struckFirst = false;
    disengage();
    status = Status.EXPLORING;
  }

  void disengage() {
    nextStrikeAt = null;
    defending = false;
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
      disengage();
      tacticalPoints = 0;
      guarding = false;
      sleptAt = null;
    }
  }
}
