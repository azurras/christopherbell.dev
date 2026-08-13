package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.Role;
import dev.christopherbell.persistence.jooq.identity.tables.records.BrowserSessionRecord;
import java.time.OffsetDateTime;

final class PostgresBrowserSessionMapper {
  private PostgresBrowserSessionMapper() {}

  static BrowserSession map(BrowserSessionRecord record) {
    return BrowserSession.builder()
        .id(record.getBrowserSessionId())
        .accountId(record.getAccountId())
        .role(Role.valueOf(record.getRole()))
        .tokenHash(record.getTokenHash())
        .previousTokenHash(record.getPreviousTokenHash())
        .previousTokenExpiresOn(instant(record.getPreviousTokenExpiresOn()))
        .accountSecurityFingerprint(record.getAccountSecurityFingerprint())
        .createdOn(record.getCreatedOn().toInstant())
        .lastSeenOn(record.getLastSeenOn().toInstant())
        .rotatedOn(instant(record.getRotatedOn()))
        .idleExpiresOn(record.getIdleExpiresOn().toInstant())
        .absoluteExpiresOn(record.getAbsoluteExpiresOn().toInstant())
        .build();
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
