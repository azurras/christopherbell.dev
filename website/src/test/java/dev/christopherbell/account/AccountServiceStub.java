package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import java.time.Instant;

/**
 * Stub factory for AccountService tests.
 */
public final class AccountServiceStub {
  public static final String ID = "acc-123";

  private AccountServiceStub() {}

  public static Account getAccountWhenExistsStub() {
    return Account.builder()
        .id(ID)
        .email("old@example.com")
        .firstName("Old")
        .lastName("Name")
        .username("old_user")
        .role(Role.USER)
        .status(AccountStatus.INACTIVE)
        .isApproved(false)
        .createdOn(Instant.now())
        .lastUpdatedOn(Instant.now().minusSeconds(300))
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenAllFieldsSetStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .firstName("Chris")
        .lastName("Bell")
        .email("Chris@Example.com")
        .username("Chris.Bell  ")
        .role(Role.ADMIN)
        .status(AccountStatus.ACTIVE)
        .isApproved(true)
        .moderationReason("Account review")
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenRoleOnlyStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .role(Role.ADMIN)
        .moderationReason("Role promotion")
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenFlagsOnlyStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .status(AccountStatus.ACTIVE)
        .isApproved(true)
        .moderationReason("Account activation")
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenOnlyIdStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenBlankIdStub() {
    return AccountUpdateRequest.builder().id("   ").build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenInvalidEmailStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .email("bad@@example..com")
        .build();
  }

  public static AccountUpdateRequest getAccountUpdateRequestWhenInvalidUsernameStub() {
    return AccountUpdateRequest.builder()
        .id(ID)
        .username("a")
        .build();
  }
}
