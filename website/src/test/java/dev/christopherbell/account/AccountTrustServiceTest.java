package dev.christopherbell.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.trust.AccountTrustRelationship;
import dev.christopherbell.account.trust.AccountTrustRepository;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.permission.PermissionService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AccountTrustServiceTest {
  @Mock private AccountTrustRepository accountTrustRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private PermissionService permissionService;

  @Test
  public void setTrust_createsMuteRelationshipForCurrentUser() throws Exception {
    var target = Account.builder().id("target").username("alex").build();
    var saved = AccountTrustRelationship.builder()
        .ownerAccountId("self")
        .targetAccountId("target")
        .type(AccountTrustType.MUTE)
        .build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn("self");
    when(accountRepository.findByUsername("alex")).thenReturn(Optional.of(target));
    when(accountTrustRepository.findByOwnerAccountIdAndTargetAccountIdAndType(
        "self",
        "target",
        AccountTrustType.MUTE))
        .thenReturn(Optional.empty());
    when(accountTrustRepository.save(any(AccountTrustRelationship.class))).thenReturn(saved);

    var result = service.setTrust("alex", AccountTrustType.MUTE);

    assertEquals("self", result.ownerAccountId());
    assertEquals("target", result.targetAccountId());
    assertEquals("alex", result.targetUsername());
    assertEquals(AccountTrustType.MUTE, result.type());
    verify(accountTrustRepository).save(any(AccountTrustRelationship.class));
  }

  @Test
  public void setTrust_rejectsSelfTrustRelationship() throws Exception {
    var self = Account.builder().id("self").username("chris").build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn("self");
    when(accountRepository.findByUsername("chris")).thenReturn(Optional.of(self));

    assertThrows(InvalidRequestException.class, () -> service.setTrust("chris", AccountTrustType.BLOCK));
    verify(accountTrustRepository, never()).save(any(AccountTrustRelationship.class));
  }

  @Test
  public void hiddenAccountIdsForSelf_returnsMutedAndBlockedTargets() {
    var service = service();
    when(permissionService.getSelfId()).thenReturn("self");
    when(accountTrustRepository.findByOwnerAccountIdAndTypeIn(
        "self",
        Set.of(AccountTrustType.MUTE, AccountTrustType.BLOCK)))
        .thenReturn(List.of(
            AccountTrustRelationship.builder().targetAccountId("muted").build(),
            AccountTrustRelationship.builder().targetAccountId("blocked").build()));

    assertEquals(Set.of("muted", "blocked"), service.hiddenAccountIdsForSelf());
  }

  @Test
  public void isBlockedEitherDirection_checksBothDirections() {
    var service = service();
    when(accountTrustRepository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        "first",
        "second",
        AccountTrustType.BLOCK))
        .thenReturn(false);
    when(accountTrustRepository.existsByOwnerAccountIdAndTargetAccountIdAndType(
        "second",
        "first",
        AccountTrustType.BLOCK))
        .thenReturn(true);

    assertEquals(true, service.isBlockedEitherDirection("first", "second"));
  }

  private AccountTrustService service() {
    return new AccountTrustService(accountTrustRepository, accountRepository, permissionService);
  }
}
