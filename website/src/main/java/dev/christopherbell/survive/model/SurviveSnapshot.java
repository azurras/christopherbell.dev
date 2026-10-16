package dev.christopherbell.survive.model;

import java.util.List;

/** Immutable view of one survivor and the shared camp; credentials never enter this response. */
public record SurviveSnapshot(
    String name, int health, int strength, int stamina, int experience,
    int experienceToNextLevel, int wood, int food, int inventoryCapacity,
    int enemyHealth, Status status, long revision, long worldRevision,
    int shelters, int boats, List<String> survivors, List<String> events,
    List<SurviveAction> actions, String message, String survivorId, List<Recipient> recipients, boolean saved,
    Phase phase, long day, long secondsToNextPhase, int hunger, int stone, SurviveWeapon weapon,
    int weaponDurability, SurviveEnemy enemy, int bedsInUse, int beds, boolean guarding) {

  /** Public targeting identity only; it never authorizes control of the survivor. */
  public record Recipient(String survivorId, String name) {}

  /** Terminal states keep a survivor visible but prevent further gameplay mutations. */
  public enum Status { EXPLORING, COMBAT, SLEEPING, DEAD, ESCAPED }

  /** The shared world's time of day; every survivor sees the same phase. */
  public enum Phase { DAY, DUSK, NIGHT }

  public SurviveSnapshot {
    survivors = List.copyOf(survivors);
    events = List.copyOf(events);
    actions = List.copyOf(actions);
    recipients = List.copyOf(recipients);
  }
}
