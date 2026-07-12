package dev.christopherbell.admin.commandcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommandCenterAccessServiceTest {
  @Mock private PermissionService permissionService;
  @Mock private AccountRepository accountRepository;

  private CommandCenterAccessService accessService;

  @BeforeEach
  void setUp() {
    accessService = new CommandCenterAccessService(permissionService, accountRepository);
    when(permissionService.getSelfId()).thenReturn("admin-1");
  }

  @Test
  void activeApprovedPersistedAdminHasFreshAccess() {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(account(
        Role.ADMIN, AccountStatus.ACTIVE, true)));

    assertThat(accessService.hasFreshAdminAccess()).isTrue();
  }

  @Test
  void persistedNonAdminIsDenied() {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(account(
        Role.USER, AccountStatus.ACTIVE, true)));

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void suspendedAdminIsDenied() {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(account(
        Role.ADMIN, AccountStatus.SUSPENDED, true)));

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void unapprovedAdminIsDenied() {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.of(account(
        Role.ADMIN, AccountStatus.ACTIVE, false)));

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void missingPersistedAccountIsDenied() {
    when(accountRepository.findById("admin-1")).thenReturn(Optional.empty());

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void blankCurrentAccountIdIsDenied() {
    when(permissionService.getSelfId()).thenReturn(" ");

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void identityResolutionFailureIsDenied() {
    when(permissionService.getSelfId()).thenThrow(new IllegalStateException("invalid token"));

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  @Test
  void repositoryFailureIsDenied() {
    when(accountRepository.findById("admin-1")).thenThrow(new IllegalStateException("database"));

    assertThat(accessService.hasFreshAdminAccess()).isFalse();
  }

  private Account account(Role role, AccountStatus status, boolean approved) {
    return Account.builder()
        .id("admin-1")
        .role(role)
        .status(status)
        .isApproved(approved)
        .build();
  }
}
