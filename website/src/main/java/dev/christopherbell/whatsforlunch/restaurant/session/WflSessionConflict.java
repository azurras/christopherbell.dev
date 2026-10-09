package dev.christopherbell.whatsforlunch.restaurant.session;

/** Stable public conflict codes for bounded WFL session mutations, with their descriptions. */
public enum WflSessionConflict {
  FULL("WFL_SESSION_FULL", "This lunch session is full."),
  EXPIRED("WFL_SESSION_EXPIRED", "This lunch session is archived and cannot be changed."),
  CHANGED("WFL_SESSION_CHANGED", "This lunch session changed. Refresh and try again.");

  private final String code;
  private final String description;

  WflSessionConflict(String code, String description) {
    this.code = code;
    this.description = description;
  }

  /** The code clients match on; it never changes. */
  public String code() {
    return code;
  }

  /** The human-readable explanation shown with the code. */
  public String description() {
    return description;
  }
}
