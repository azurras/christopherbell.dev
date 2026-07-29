package dev.christopherbell.music.security;

import static dev.christopherbell.account.model.AccountPermission.MUSIC_READ;
import static dev.christopherbell.account.model.AccountPermission.MUSIC_WRITE;
import static dev.christopherbell.account.model.AccountPermission.SHARED_FOLDER_READ;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class MusicAccessServiceTest {
  @Mock private PermissionService permissionService;
  @Mock private AccountRepository accountRepository;

  private MusicAccessService musicAccess;

  @BeforeEach
  void setUp() {
    musicAccess = new MusicAccessService(permissionService, accountRepository);
  }

  @Test
  void adminReceivesMusicCapabilitiesWithoutLosingStoredUnrelatedCapabilities() {
    Account account = account(Role.ADMIN, Set.of(SHARED_FOLDER_READ));

    assertThat(musicAccess.effectivePermissions(account))
        .containsExactlyInAnyOrder(SHARED_FOLDER_READ, MUSIC_READ, MUSIC_WRITE);
  }

  @Test
  void storedMusicWriteAlwaysImpliesEffectiveMusicRead() {
    Account account = account(Role.USER, Set.of(MUSIC_WRITE));

    assertThat(musicAccess.effectivePermissions(account))
        .containsExactlyInAnyOrder(MUSIC_READ, MUSIC_WRITE);
  }

  @Test
  void unchangedCredentialLosesMusicAccessImmediatelyAfterRepositoryRevocation() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(accountRepository.findById("account-1"))
        .thenReturn(Optional.of(account(Role.USER, Set.of(MUSIC_READ))))
        .thenReturn(Optional.of(account(Role.USER, Set.of())));

    assertThatCode(musicAccess::requireRead).doesNotThrowAnyException();
    assertThatThrownBy(musicAccess::requireRead)
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Music read access required");

    verify(accountRepository, times(2)).findById("account-1");
  }

  private Account account(Role role, Set<AccountPermission> permissions) {
    return Account.builder()
        .id("account-1")
        .role(role)
        .status(AccountStatus.ACTIVE)
        .permissions(permissions)
        .build();
  }
}
