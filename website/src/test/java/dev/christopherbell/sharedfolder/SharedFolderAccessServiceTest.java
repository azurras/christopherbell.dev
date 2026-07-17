package dev.christopherbell.sharedfolder;

import static dev.christopherbell.account.model.AccountPermission.SHARED_FOLDER_READ;
import static dev.christopherbell.account.model.AccountPermission.SHARED_FOLDER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class SharedFolderAccessServiceTest {
  @Mock private PermissionService permissionService;
  @Mock private AccountRepository accountRepository;

  private SharedFolderAccessService sharedFolderAccess;

  @BeforeEach
  void setUp() {
    sharedFolderAccess = new SharedFolderAccessService(permissionService, accountRepository);
  }

  @Test
  void adminAlwaysReceivesEffectiveReadAndWrite() {
    Account account = account(Role.ADMIN, AccountStatus.ACTIVE, true, Set.of());

    assertThat(sharedFolderAccess.effectivePermissions(account))
        .containsExactlyInAnyOrder(SHARED_FOLDER_READ, SHARED_FOLDER_WRITE);
  }

  @Test
  void storedWritePermissionAlwaysImpliesEffectiveRead() {
    Account account = account(
        Role.USER, AccountStatus.ACTIVE, true, Set.of(SHARED_FOLDER_WRITE));

    assertThat(sharedFolderAccess.effectivePermissions(account))
        .containsExactlyInAnyOrder(SHARED_FOLDER_READ, SHARED_FOLDER_WRITE);
  }

  @Test
  void readAndWriteDecisionsUseTheRequiredEffectiveCapability() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(accountRepository.findById("account-1"))
        .thenReturn(Optional.of(account(
            Role.USER, AccountStatus.ACTIVE, true, Set.of(SHARED_FOLDER_READ))));

    assertThatCode(sharedFolderAccess::requireRead).doesNotThrowAnyException();
    assertThatThrownBy(sharedFolderAccess::requireWrite)
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Shared-folder write access required");

    verify(accountRepository, times(2)).findById("account-1");
  }

  @Test
  void unchangedJwtLosesAccessImmediatelyAfterRepositoryRevocation() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(accountRepository.findById("account-1"))
        .thenReturn(Optional.of(account(
            Role.USER, AccountStatus.ACTIVE, true, Set.of(SHARED_FOLDER_READ))))
        .thenReturn(Optional.of(account(Role.USER, AccountStatus.ACTIVE, true, Set.of())));

    assertThatCode(sharedFolderAccess::requireRead).doesNotThrowAnyException();
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Shared-folder read access required");

    verify(accountRepository, times(2)).findById("account-1");
  }

  @Test
  void missingInactiveAndUnapprovedPersistedAccountsAreDenied() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(accountRepository.findById("account-1"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(account(
            Role.USER, AccountStatus.INACTIVE, true, Set.of(SHARED_FOLDER_READ))))
        .thenReturn(Optional.of(account(
            Role.USER, AccountStatus.ACTIVE, false, Set.of(SHARED_FOLDER_READ))));

    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void identityAndRepositoryFailuresDenyAccess() {
    when(permissionService.getSelfId())
        .thenReturn(" ")
        .thenReturn("account-1")
        .thenReturn("account-1")
        .thenThrow(new IllegalStateException("invalid token"));
    when(accountRepository.findById("account-1"))
        .thenReturn(Optional.of(Account.builder()
            .id("different-account")
            .role(Role.ADMIN)
            .status(AccountStatus.ACTIVE)
            .isApproved(true)
            .build()))
        .thenThrow(new IllegalStateException("database"));

    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(sharedFolderAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class);
  }

  private Account account(
      Role role,
      AccountStatus status,
      boolean approved,
      Set<AccountPermission> permissions) {
    return Account.builder()
        .id("account-1")
        .role(role)
        .status(status)
        .isApproved(approved)
        .permissions(permissions)
        .build();
  }
}
