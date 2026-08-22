package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

@PostgresPersistenceSupport
final class PostgresBrowserSessionMapper {
  private PostgresBrowserSessionMapper() {}

  static BrowserSession map(ResultSet record, int rowNumber) throws SQLException {
    return BrowserSession.builder()
        .id(record.getString("browser_session_id"))
        .accountId(record.getString("account_id"))
        .role(role(record.getString("role")))
        .tokenHash(record.getString("token_hash"))
        .previousTokenHash(record.getString("previous_token_hash"))
        .previousTokenExpiresOn(instant(record, "previous_token_expires_on"))
        .accountSecurityFingerprint(record.getString("account_security_fingerprint"))
        .createdOn(instant(record, "created_on"))
        .lastSeenOn(instant(record, "last_seen_on"))
        .rotatedOn(instant(record, "rotated_on"))
        .idleExpiresOn(instant(record, "idle_expires_on"))
        .absoluteExpiresOn(instant(record, "absolute_expires_on"))
        .build();
  }

  private static Role role(String value) {
    return value == null ? null : Role.valueOf(value);
  }

  private static java.time.Instant instant(ResultSet record, String column) throws SQLException {
    return instant(record.getObject(column, OffsetDateTime.class));
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
