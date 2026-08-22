package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PostgresBrowserSessionMapperTest {
  @Test
  void preservesTheMissingRoleOnLegacySessions() throws Exception {
    var now = OffsetDateTime.parse("2026-08-20T12:00:00Z");
    var record = mock(ResultSet.class);
    when(record.getString("browser_session_id")).thenReturn("legacy-session");
    when(record.getString("account_id")).thenReturn("legacy-owner");
    when(record.getString("role")).thenReturn(null);
    when(record.getString("token_hash")).thenReturn("token");
    when(record.getString("account_security_fingerprint")).thenReturn("fingerprint");
    when(record.getObject(anyString(), eq(OffsetDateTime.class))).thenReturn(now);

    assertThat(PostgresBrowserSessionMapper.map(record, 0).getRole()).isNull();
  }
}
