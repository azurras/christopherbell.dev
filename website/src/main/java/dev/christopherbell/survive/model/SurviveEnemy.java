package dev.christopherbell.survive.model;

/** Wild animals a survivor can fight; the stronger ones only roam after dark. */
public enum SurviveEnemy {
  HOG("hog", 5, 1, 1),
  WOLF("wolf", 8, 2, 1),
  BEAR("bear", 14, 3, 3);

  private final String displayName;
  private final int health;
  private final int bite;
  private final int foodReward;

  SurviveEnemy(String displayName, int health, int bite, int foodReward) {
    this.displayName = displayName;
    this.health = health;
    this.bite = bite;
    this.foodReward = foodReward;
  }

  public String displayName() {
    return displayName;
  }

  public int health() {
    return health;
  }

  /** Damage dealt to a survivor on each exchange the survivor does not block. */
  public int bite() {
    return bite;
  }

  public int foodReward() {
    return foodReward;
  }
}
