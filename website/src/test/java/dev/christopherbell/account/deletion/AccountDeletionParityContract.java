package dev.christopherbell.account.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Shared deletion checkpoint and idempotent account-removal behavior for both engines. */
interface AccountDeletionParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String ACCOUNT_ID = "deletion-parity-account-" + RUN;
  String PSEUDONYM = "deleted-parity-" + RUN;

  AccountDeletionJobRepository jobs();

  AccountDeletionOperations operations();

  void createAccount(Account account);

  @Test
  default void roundTripsAResumableDeletionCheckpoint() {
    var started = jobs().save(AccountDeletionJob.started(PSEUDONYM));
    started.fail("shared-contract");
    var failed = jobs().save(started);

    var reloaded = jobs().findById(PSEUDONYM).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(AccountDeletionStatus.FAILED);
    assertThat(reloaded.getNextStep()).isEqualTo(AccountDeletionStep.ENSURE_TOMBSTONE);
    assertThat(reloaded.getFailureCategory()).isEqualTo("shared-contract");
  }

  @Test
  default void accountRemovalIsSafeToRepeat() {
    createAccount(Account.builder()
        .id(ACCOUNT_ID)
        .createdOn(Instant.parse("2026-08-13T18:00:00Z"))
        .email(ACCOUNT_ID + "@example.test")
        .passwordHash("hash")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .username(ACCOUNT_ID)
        .build());

    operations().ensureTombstone();
    operations().ensureTombstone();
    assertThat(operations().accountExists(ACCOUNT_ID)).isTrue();
    operations().removeReferencesAndAccount(ACCOUNT_ID);
    operations().removeReferencesAndAccount(ACCOUNT_ID);
    assertThat(operations().accountExists(ACCOUNT_ID)).isFalse();
  }
}
