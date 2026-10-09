package dev.christopherbell.account.api;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.deletion.AccountDeletionService;
import dev.christopherbell.account.model.AccountStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Publishes only current account identity and active-state checks for private monitoring. */
@Component
public class MonitorAccountAccess {
  private final AccountRepository accounts;
  private final AccountDeletionService deletions;
  public MonitorAccountAccess(AccountRepository accounts, AccountDeletionService deletions) {
    this.accounts = accounts;
    this.deletions = deletions;
  }

  public String requireCurrentActiveAccount() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || !isActive(authentication.getName())) {
      throw new AccessDeniedException("Active account required.");
    }
    return authentication.getName();
  }

  public boolean isActive(String accountId) {
    return !deletions.hasStarted(accountId) && accounts.findById(accountId)
        .map(account -> account.getStatus() == AccountStatus.ACTIVE).orElse(false);
  }
}
