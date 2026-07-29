package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A stub class for testing Account related functionality.
 */
public class AccountStub {
  public static final String EMAIL = "test@example.com";
  public static final String FIRST_NAME = "Jacob";
  public static final String INVITE_CODE = "392af584-8ca5-4531-ad09-9767168e1d91";
  public static final String INVITE_CODE_OWNER = "afbcf77d-d5d8-4d89-b89f-c24d82b61b0f";
  public static final String LAST_NAME = "Jones";
  public static final String PASSWORD = "NotAUsedPasswordInAnyEnvironmentIncludingProduction";
  public static final String ROLE = Role.ADMIN.name();
  public static final String USERNAME = "jacobfreakingjones";

  /**
   * Creates a stub AccountDetail object for testing purposes.
   *
   * @return a stub AccountDetail object
   */
  public static AccountDetail getAccountDetailStub() {
    return AccountDetail.builder()
        .createdOn(Instant.now())
        .email(EMAIL)
        .firstName(FIRST_NAME)
        .lastLoginOn(Instant.now())
        .lastName(LAST_NAME)
        .role(Role.valueOf(ROLE))
        .username(USERNAME)
        .build();
  }

  /**
   * Creates a stub Account object for testing purposes.
   *
   * @return a stub Account object
   */
  public static Account getAccountStub() {
    return Account.builder()
        .createdOn(Instant.now())
        .email(EMAIL)
        .inviteCode(UUID.fromString(INVITE_CODE))
        .inviteCodeOwner(UUID.fromString(INVITE_CODE_OWNER))
        .firstName(FIRST_NAME)
        .lastLoginOn(Instant.now())
        .lastName(LAST_NAME)
        .role(Role.valueOf(ROLE))
        .username(USERNAME)
        .build();
  }
}
