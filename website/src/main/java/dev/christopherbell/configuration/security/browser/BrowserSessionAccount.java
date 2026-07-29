package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.util.Set;

/** Minimal current account state needed to validate and authorize a browser session. */
public record BrowserSessionAccount(
    String id,
    String passwordHash,
    Role role,
    Set<AccountPermission> permissions,
    AccountStatus status) {

  public BrowserSessionAccount {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  /** Returns whether this current account may continue the snapshotted browser session. */
  public boolean validates(String expectedFingerprint) {
    if (!AccountStatus.ACTIVE.equals(status)) return false;
    var account = Account.builder()
        .id(id)
        .passwordHash(passwordHash)
        .role(role)
        .permissions(permissions)
        .status(status)
        .build();
    return AccountSecurityFingerprint.matches(expectedFingerprint, account);
  }
}
