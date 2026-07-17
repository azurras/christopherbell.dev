package dev.christopherbell.sharedfolder.security;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Authorizes shared-folder reads and writes from fresh persisted account state.
 *
 * <p>JWTs provide only the current identity. Every decision reloads that identity from the
 * account repository so permission revocation, deactivation, and approval changes take effect
 * before the next shared-folder operation.
 */
@RequiredArgsConstructor
@Service("sharedFolderAccessService")
public class SharedFolderAccessService {
  private final PermissionService permissionService;
  private final AccountRepository accountRepository;

  /** Requires a fresh effective shared-folder read capability. */
  public void requireRead() {
    require(AccountPermission.SHARED_FOLDER_READ, "Shared-folder read access required");
  }

  /** Requires a fresh effective shared-folder write capability. */
  public Account requireWrite() {
    return require(AccountPermission.SHARED_FOLDER_WRITE, "Shared-folder write access required");
  }

  /**
   * Returns the role-adjusted effective capabilities for a persisted account.
   *
   * <p>Administrators always receive both capabilities. A stored write grant also provides read
   * access, preserving the invariant that write is never less capable than read.
   *
   * @param account a persisted account
   * @return immutable effective capabilities, or an empty set for a missing account
   */
  public Set<AccountPermission> effectivePermissions(Account account) {
    if (account == null) {
      return Set.of();
    }
    EnumSet<AccountPermission> effective = EnumSet.noneOf(AccountPermission.class);
    if (account.getPermissions() != null) {
      for (AccountPermission permission : account.getPermissions()) {
        if (permission != null) {
          effective.add(permission);
        }
      }
    }
    if (account.getRole() == Role.ADMIN) {
      effective.add(AccountPermission.SHARED_FOLDER_READ);
      effective.add(AccountPermission.SHARED_FOLDER_WRITE);
    }
    if (effective.contains(AccountPermission.SHARED_FOLDER_WRITE)) {
      effective.add(AccountPermission.SHARED_FOLDER_READ);
    }
    return Set.copyOf(effective);
  }

  private Account require(AccountPermission required, String denialMessage) {
    Account account = currentActiveApprovedAccount();
    if (!effectivePermissions(account).contains(required)) {
      throw new AccessDeniedException(denialMessage);
    }
    return account;
  }

  private Account currentActiveApprovedAccount() {
    try {
      String accountId = permissionService.getSelfId();
      if (accountId == null || accountId.isBlank()) {
        throw denied();
      }
      return accountRepository.findById(accountId)
          .filter(account -> accountId.equals(account.getId()))
          .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
          .filter(account -> Boolean.TRUE.equals(account.getIsApproved()))
          .orElseThrow(this::denied);
    } catch (AccessDeniedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw denied();
    }
  }

  private AccessDeniedException denied() {
    return new AccessDeniedException("Shared-folder access denied");
  }
}
