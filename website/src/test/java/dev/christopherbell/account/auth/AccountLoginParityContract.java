package dev.christopherbell.account.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared conditional-login behavior executed against real MongoDB and PostgreSQL. */
interface AccountLoginParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String ACCOUNT_ID = "login-parity-" + RUN;
  Instant LOGIN_ON = Instant.parse("2026-08-13T18:00:00Z");

  AccountRepository accounts();

  AccountLoginStore loginStore();

  @BeforeEach
  default void seedAccount() {
    accounts().save(Account.builder()
        .id(ACCOUNT_ID)
        .createdOn(LOGIN_ON.minusSeconds(60))
        .email(ACCOUNT_ID + "@example.test")
        .passwordHash("observed-hash")
        .passwordSalt("legacy-salt")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .username(ACCOUNT_ID)
        .build());
  }

  @Test
  default void completesOnlyTheObservedActiveCredential() {
    var observed = accounts().findById(ACCOUNT_ID).orElseThrow();
    var updated = loginStore().completeLogin(observed, "replacement-hash", LOGIN_ON).orElseThrow();

    assertThat(updated.getPasswordHash()).isEqualTo("replacement-hash");
    assertThat(updated.getPasswordSalt()).isNull();
    assertThat(updated.getLastLoginOn()).isEqualTo(LOGIN_ON);
    assertThat(loginStore().completeLogin(observed, "stale-hash", LOGIN_ON.plusSeconds(1)))
        .isEmpty();
  }
}
