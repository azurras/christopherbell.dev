package dev.christopherbell.account.auth;

import dev.christopherbell.account.model.Account;
import java.time.Instant;
import java.util.Optional;

/** Commits login metadata without overwriting concurrently changed account security state. */
public interface AccountLoginStore {

  /**
   * Updates login-only fields when the observed active credential is still current.
   *
   * @return the authoritative post-update account, or empty after a concurrent credential or
   *     lifecycle change
   */
  Optional<Account> completeLogin(Account observed, String passwordHash, Instant loginOn);
}
