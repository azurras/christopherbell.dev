package dev.christopherbell.libs.pagination;

import java.time.Instant;

/** Stable secondary-key cursor used by newest-first MongoDB queries. */
public record StableCursor(Instant timestamp, String id) {
  static final int MAX_ID_LENGTH = 128;

  public StableCursor {
    if (timestamp == null) {
      throw new IllegalArgumentException("Cursor timestamp is required.");
    }
    if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH || containsControl(id)) {
      throw new IllegalArgumentException("Cursor id is invalid.");
    }
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }
}
