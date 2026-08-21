package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared repository, authentication-join, and atomic session behavior for both engines. */
interface BrowserSessionParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String ACCOUNT_ID = "browser-parity-account-" + RUN;
  String SESSION_ID = "browser-parity-session-" + RUN;
  Instant NOW = Instant.parse("2026-08-13T18:00:00Z");

  BrowserSessionRepository sessions();

  BrowserSessionAuthenticationStore authentication();

  BrowserSessionActivityStore activity();

  void createAccount(Account account);

  void resetFixture();

  @BeforeEach
  default void seedSession() {
    resetFixture();
    createAccount(Account.builder()
        .id(ACCOUNT_ID)
        .createdOn(NOW.minusSeconds(60))
        .email(ACCOUNT_ID + "@example.test")
        .passwordHash("account-hash")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .username(ACCOUNT_ID)
        .build());
    sessions().save(session());
  }

  @Test
  default void joinsTheSessionToCurrentAccountSecurityState() {
    var joined = authentication().findById(SESSION_ID).orElseThrow();

    assertThat(joined.session().getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(joined.account().id()).isEqualTo(ACCOUNT_ID);
    assertThat(joined.account().passwordHash()).isEqualTo("account-hash");
  }

  @Test
  default void touchAndRotationFenceStaleObservers() {
    var touched = activity().touch(
        SESSION_ID, NOW.minusSeconds(10), NOW, NOW.plusSeconds(120)).orElseThrow();
    assertThat(touched.getLastSeenOn()).isEqualTo(NOW);
    assertThat(activity().touch(
        SESSION_ID, NOW.minusSeconds(10), NOW.plusSeconds(1), NOW.plusSeconds(121))).isEmpty();

    var rotated = activity().rotate(
        SESSION_ID,
        "token-a",
        NOW.minusSeconds(10),
        "token-b",
        NOW.plusSeconds(1),
        NOW.plusSeconds(30),
        NOW.plusSeconds(121)).orElseThrow();
    assertThat(rotated.getTokenHash()).isEqualTo("token-b");
    assertThat(activity().rotate(
        SESSION_ID,
        "token-a",
        NOW.minusSeconds(10),
        "token-c",
        NOW.plusSeconds(2),
        NOW.plusSeconds(31),
        NOW.plusSeconds(122))).isEmpty();
  }

  @Test
  default void deletesByAccountIdIdempotently() {
    assertThat(sessions().deleteByAccountId(ACCOUNT_ID)).isOne();
    assertThat(sessions().deleteByAccountId(ACCOUNT_ID)).isZero();
    assertThat(authentication().findById(SESSION_ID)).isEmpty();
  }

  private static BrowserSession session() {
    return BrowserSession.builder()
        .id(SESSION_ID)
        .accountId(ACCOUNT_ID)
        .role(Role.USER)
        .tokenHash("token-a")
        .accountSecurityFingerprint("fingerprint")
        .createdOn(NOW.minusSeconds(10))
        .lastSeenOn(NOW.minusSeconds(10))
        .rotatedOn(NOW.minusSeconds(10))
        .idleExpiresOn(NOW.plusSeconds(60))
        .absoluteExpiresOn(NOW.plusSeconds(600))
        .build();
  }
}
