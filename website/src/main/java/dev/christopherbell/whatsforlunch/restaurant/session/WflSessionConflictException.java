package dev.christopherbell.whatsforlunch.restaurant.session;

/** Signals a bounded WFL session mutation conflict with a stable public code. */
public final class WflSessionConflictException extends RuntimeException {
  private final WflSessionConflict conflict;

  public WflSessionConflictException(WflSessionConflict conflict) {
    super(conflict.code());
    this.conflict = conflict;
  }

  public WflSessionConflict conflict() {
    return conflict;
  }

  public String code() {
    return conflict.code();
  }
}
