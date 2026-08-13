package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;

/** Identical account assertions run against both disposable MongoDB and PostgreSQL. */
interface AccountRepositoryParityContract {
  String FIXTURE_ID = "account-parity-contract";

  AccountRepository parityRepository();

  @BeforeEach
  default void removeParityFixture() {
    parityRepository().deleteById(FIXTURE_ID);
    parityRepository().deleteById(FIXTURE_ID + "-duplicate");
  }

  @Test
  default void parityPreservesIdentityQueriesAndVersionedRoundTrip() {
    var saved = parityRepository().save(parityFixture());

    assertThat(saved.getVersion()).isZero();
    assertThat(parityRepository().findByEmailIgnoreCase("PARITY@EXAMPLE.TEST")).contains(saved);
    assertThat(parityRepository().findByUsernameIgnoreCase("PARITY-OWNER")).contains(saved);
    assertThat(parityRepository().findById(FIXTURE_ID).orElseThrow().getPermissions())
        .containsExactly(AccountPermission.MUSIC_READ);
  }

  @Test
  default void parityRejectsAStaleSecondWriterWithoutReplacingTheWinner() {
    parityRepository().save(parityFixture());
    var winner = parityRepository().findById(FIXTURE_ID).orElseThrow();
    var stale = parityRepository().findById(FIXTURE_ID).orElseThrow();
    winner.setFirstName("winner");
    stale.setFirstName("stale");

    parityRepository().save(winner);

    assertThatThrownBy(() -> parityRepository().save(stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(parityRepository().findById(FIXTURE_ID).orElseThrow().getFirstName())
        .isEqualTo("winner");
  }

  @Test
  default void parityDuplicateIdentityUsesSpringExceptionAndRetainsNativeCause() {
    parityRepository().save(parityFixture());
    var duplicate = parityFixture();
    duplicate.setId(FIXTURE_ID + "-duplicate");
    duplicate.setUsername("parity-owner-duplicate");

    assertThatThrownBy(() -> parityRepository().save(duplicate))
        .isInstanceOf(DuplicateKeyException.class)
        .hasCauseInstanceOf(RuntimeException.class);
  }

  private static Account parityFixture() {
    return Account.builder()
        .id(FIXTURE_ID)
        .createdOn(Instant.parse("2026-08-13T12:00:00Z"))
        .email("parity@example.test")
        .firstName("Parity")
        .passwordHash("hash")
        .role(Role.USER)
        .permissions(new HashSet<>(java.util.Set.of(AccountPermission.MUSIC_READ)))
        .status(AccountStatus.ACTIVE)
        .username("parity-owner")
        .build();
  }
}
