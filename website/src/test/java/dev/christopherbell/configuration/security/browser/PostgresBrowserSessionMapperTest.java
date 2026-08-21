package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.persistence.jooq.identity.tables.records.BrowserSessionRecord;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PostgresBrowserSessionMapperTest {
  @Test
  void preservesTheMissingRoleOnLegacySessions() {
    var now = OffsetDateTime.parse("2026-08-20T12:00:00Z");
    var record = new BrowserSessionRecord();
    record.setBrowserSessionId("legacy-session");
    record.setAccountId("legacy-owner");
    record.setRole(null);
    record.setTokenHash("token");
    record.setAccountSecurityFingerprint("fingerprint");
    record.setCreatedOn(now);
    record.setLastSeenOn(now);
    record.setIdleExpiresOn(now);
    record.setAbsoluteExpiresOn(now);

    assertThat(PostgresBrowserSessionMapper.map(record).getRole()).isNull();
  }
}
