package dev.christopherbell.survive.model;

import java.util.UUID;

/** A weapon in the pack. Its ID addresses this copy rather than the weapon type. */
public record SurvivePackedWeapon(String id, SurviveWeapon weapon, int durability) {
  public SurvivePackedWeapon {
    UUID.fromString(id);
    if (weapon == null || durability < 1 || durability > weapon.durability()) {
      throw new IllegalArgumentException("Invalid packed weapon condition.");
    }
  }

  public static SurvivePackedWeapon pack(SurviveWeapon weapon, int durability) {
    return new SurvivePackedWeapon(UUID.randomUUID().toString(), weapon, durability);
  }
}
