package dev.christopherbell.configuration.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** Converts PostgreSQL integrity categories without retaining sensitive SQL details. */
public final class PostgresqlIntegrityViolationTranslator {
  private static final String UNIQUE_VIOLATION = "23505";

  private PostgresqlIntegrityViolationTranslator() {}

  /** Returns a duplicate exception only for SQLSTATE's unique-constraint category. */
  public static DataIntegrityViolationException translate(
      String sqlState, String duplicateMessage, String integrityMessage) {
    var safeCause = new PostgresqlConstraintViolationCause(sqlState);
    return UNIQUE_VIOLATION.equals(sqlState)
        ? new DuplicateKeyException(duplicateMessage, safeCause)
        : new DataIntegrityViolationException(integrityMessage, safeCause);
  }
}
