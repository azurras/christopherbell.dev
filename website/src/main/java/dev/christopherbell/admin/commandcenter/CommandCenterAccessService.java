package dev.christopherbell.admin.commandcenter;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Revalidates the persisted account state required by every command-center API. */
@RequiredArgsConstructor
@Service("commandCenterAccessService")
public class CommandCenterAccessService {
  private final PermissionService permissionService;
  private final AccountRepository accountRepository;

  /**
   * Returns whether the current identity still belongs to an active approved administrator.
   *
   * <p>Identity and repository failures deny access so stale JWT authority cannot fail open.
   *
   * @return {@code true} only for a currently persisted active approved admin
   */
  public boolean hasFreshAdminAccess() {
    try {
      var accountId = permissionService.getSelfId();
      if (accountId == null || accountId.isBlank()) {
        return false;
      }
      return accountRepository.findById(accountId)
          .filter(account -> account.getRole() == Role.ADMIN)
          .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
          .filter(account -> Boolean.TRUE.equals(account.getIsApproved()))
          .isPresent();
    } catch (RuntimeException exception) {
      return false;
    }
  }
}
