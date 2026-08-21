package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/** Shared admin account query behavior executed against real MongoDB and PostgreSQL. */
interface AdminAccountQueryParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String FIRST = "admin-query-a-" + RUN;
  String SECOND = "admin-query-b-" + RUN;

  AccountRepository accounts();

  AdminAccountQueryPort adminQueries();

  @BeforeEach
  default void seedAdminAccounts() {
    accounts().save(account(FIRST, "SharedQuery" + RUN, Instant.parse("2026-08-13T12:00:00Z")));
    accounts().save(account(SECOND, "sharedquery" + RUN, Instant.parse("2026-08-13T12:00:01Z")));
  }

  @Test
  default void filtersLiterallyCaseInsensitivelyWithStableOrderingAndTotals() {
    var page = adminQueries().getAccounts(new AdminAccountQuery(
        0,
        10,
        "username",
        Sort.Direction.ASC,
        AccountStatus.ACTIVE,
        Role.USER,
        ("SHAREDQUERY" + RUN).toUpperCase(java.util.Locale.ROOT)));

    assertThat(page.items()).extracting("id").containsExactly(FIRST, SECOND);
    assertThat(page.totalElements()).isEqualTo(2);
  }

  private static Account account(String id, String firstName, Instant createdOn) {
    return Account.builder().id(id).createdOn(createdOn).email(id + "@example.test")
        .firstName(firstName).passwordHash("hash").role(Role.USER).status(AccountStatus.ACTIVE)
        .username(id).build();
  }
}
