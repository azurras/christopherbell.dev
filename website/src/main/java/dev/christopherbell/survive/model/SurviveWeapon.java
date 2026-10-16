package dev.christopherbell.survive.model;

/** Craftable weapons; each attack wears one use until the weapon breaks. */
public enum SurviveWeapon {
  CLUB("club", 2, 0, 1, 8),
  STONE_AXE("stone axe", 2, 2, 2, 12),
  SPEAR("spear", 3, 3, 3, 12);

  private final String displayName;
  private final int woodCost;
  private final int stoneCost;
  private final int damageBonus;
  private final int durability;

  SurviveWeapon(String displayName, int woodCost, int stoneCost, int damageBonus, int durability) {
    this.displayName = displayName;
    this.woodCost = woodCost;
    this.stoneCost = stoneCost;
    this.damageBonus = damageBonus;
    this.durability = durability;
  }

  public String displayName() {
    return displayName;
  }

  public int woodCost() {
    return woodCost;
  }

  public int stoneCost() {
    return stoneCost;
  }

  public int damageBonus() {
    return damageBonus;
  }

  /** Attacks a freshly crafted weapon survives before it breaks. */
  public int durability() {
    return durability;
  }

  /** The stone axe raises wood gathering from 50 to 70 percent. */
  public boolean improvesWoodGathering() {
    return this == STONE_AXE;
  }

  /** The spear's reach spares its holder the enemy's bite on the first attack of each fight. */
  public boolean keepsFirstBiteAway() {
    return this == SPEAR;
  }
}
