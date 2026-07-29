package dev.christopherbell.music.security;

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

/** Authorizes Music operations from fresh persisted account state. */
@RequiredArgsConstructor
@Service
public final class MusicAccessService {
  private final PermissionService permissionService;
  private final AccountRepository accountRepository;

  /** Requires a fresh effective Music read capability. */
  public Account requireRead() {
    return require(AccountPermission.MUSIC_READ, "Music read access required");
  }

  /** Requires a fresh effective Music write capability. */
  public Account requireWrite() {
    return require(AccountPermission.MUSIC_WRITE, "Music write access required");
  }

  /** Requires a fresh active administrator account for Music administration. */
  public Account requireAdmin() {
    Account account = currentActiveApprovedAccount();
    if (account.getRole() != Role.ADMIN) {
      throw new AccessDeniedException("Music administrator access required");
    }
    return account;
  }

  /** Returns stored capabilities plus role and implication-derived Music capabilities. */
  public Set<AccountPermission> effectivePermissions(Account account) {
    if (account == null) {
      return Set.of();
    }
    EnumSet<AccountPermission> effective = EnumSet.noneOf(AccountPermission.class);
    if (account.getPermissions() != null) {
      account.getPermissions().stream()
          .filter(java.util.Objects::nonNull)
          .forEach(effective::add);
    }
    if (account.getRole() == Role.ADMIN) {
      effective.add(AccountPermission.MUSIC_READ);
      effective.add(AccountPermission.MUSIC_WRITE);
    }
    if (effective.contains(AccountPermission.MUSIC_WRITE)) {
      effective.add(AccountPermission.MUSIC_READ);
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
          .orElseThrow(this::denied);
    } catch (AccessDeniedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw denied();
    }
  }

  private AccessDeniedException denied() {
    return new AccessDeniedException("Music access denied");
  }
}
