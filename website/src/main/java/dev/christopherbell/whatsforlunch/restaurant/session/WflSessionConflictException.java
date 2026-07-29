package dev.christopherbell.whatsforlunch.restaurant.session;

/** Stable public conflict code for a bounded WFL session mutation. */
public final class WflSessionConflictException extends RuntimeException {
  private final String code;

  public WflSessionConflictException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
