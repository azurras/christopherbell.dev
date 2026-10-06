package dev.christopherbell.survive.model;

import java.util.List;

/** Immutable view of one survivor and the shared camp; credentials never enter this response. */
public record SurviveSnapshot(
    String name, int health, int strength, int stamina, int experience,
    int experienceToNextLevel, int wood, int food, int inventoryCapacity,
    int enemyHealth, Status status, long revision, long worldRevision,
    int shelters, int boats, List<String> survivors, List<String> events,
    List<SurviveAction> actions, String message) {

  /** Terminal states keep a survivor visible but prevent further gameplay mutations. */
  public enum Status { EXPLORING, COMBAT, DEAD, ESCAPED }

  public SurviveSnapshot {
    survivors = List.copyOf(survivors);
    events = List.copyOf(events);
    actions = List.copyOf(actions);
  }
}
