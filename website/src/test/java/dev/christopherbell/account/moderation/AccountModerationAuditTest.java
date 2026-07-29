package dev.christopherbell.account.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountMapper;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.auth.AccountSessionRevoker;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.permission.PermissionService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountModerationAuditTest {
  @Mock private AccountRepository accounts;
  @Mock private AccountMapper mapper;
  @Mock private AdminActivityService activity;
  @Mock private PermissionService permissions;
  @Mock private AccountSessionRevoker sessionRevoker;
  private AccountModerationService service;

  @BeforeEach
  void setUp() {
    service = new AccountModerationService(accounts, mapper, activity, permissions, sessionRevoker);
    org.mockito.Mockito.lenient().when(permissions.getSelfId()).thenReturn("admin-a");
    org.mockito.Mockito.lenient().when(accounts.findById("admin-a")).thenReturn(Optional.of(
        Account.builder().id("admin-a").username("original-admin").build()));
  }

  @Test
  @DisplayName("Role and status mutation requires a reason before saving")
  void updateAccount_whenModerationReasonMissing_rejectsBeforeMutation() {
    var account = account();
    when(accounts.findById("account-1")).thenReturn(Optional.of(account));

    assertThrows(InvalidRequestException.class, () -> service.updateAccount(
        AccountUpdateRequest.builder().id("account-1").role(Role.MOD).build()));

    assertThat(account.getRole()).isEqualTo(Role.USER);
    verify(accounts, never()).save(any());
  }

  @Test
  @DisplayName("Role and status mutation records allowlisted before and after state")
  void updateAccount_whenModerated_recordsAudit() throws Exception {
    var account = account();
    var request = AccountUpdateRequest.builder()
        .id("account-1")
        .role(Role.MOD)
        .status(AccountStatus.SUSPENDED)
        .moderationReason("Repeated abuse")
        .build();
    when(accounts.findById("account-1")).thenReturn(Optional.of(account));
    when(accounts.save(account)).thenReturn(account);
    when(mapper.toAccount(account)).thenReturn(AccountDetail.builder().id("account-1").build());

    service.updateAccount(request);

    var command = ArgumentCaptor.forClass(ModerationAuditCommand.class);
    verify(activity).recordModeration(command.capture());
    assertThat(command.getValue().beforeValues())
        .containsOnlyKeys("role", "status")
        .containsEntry("role", "USER")
        .containsEntry("status", "ACTIVE");
    assertThat(command.getValue().afterValues())
        .containsEntry("role", "MOD")
        .containsEntry("status", "SUSPENDED");
    assertThat(command.getValue().reason()).isEqualTo("Repeated abuse");
    var order = inOrder(accounts, sessionRevoker);
    order.verify(accounts).save(account);
    order.verify(sessionRevoker).revokeAll("account-1");
    order.verify(accounts).save(account);
  }

  @Test
  @DisplayName("Profile-only update does not revoke browser sessions")
  void updateAccount_whenOnlyProfileChanges_doesNotRevokeSessions() throws Exception {
    var account = account();
    when(accounts.findById("account-1")).thenReturn(Optional.of(account));
    when(accounts.save(account)).thenReturn(account);
    when(mapper.toAccount(account)).thenReturn(AccountDetail.builder().id("account-1").build());

    service.updateAccount(AccountUpdateRequest.builder()
        .id("account-1")
        .firstName("Updated")
        .build());

    verify(sessionRevoker, never()).revokeAll("account-1");
  }

  @Test
  @DisplayName("Audit failure leaves a durable pending event that retry completes exactly once")
  void updateAccount_whenAuditFails_retryCompletesPendingAudit() throws Exception {
    var account = account();
    var request = AccountUpdateRequest.builder()
        .id("account-1")
        .status(AccountStatus.SUSPENDED)
        .moderationReason("Confirmed abuse")
        .build();
    when(accounts.findById("account-1")).thenReturn(Optional.of(account));
    when(accounts.save(account)).thenReturn(account);
    when(mapper.toAccount(account)).thenReturn(AccountDetail.builder().id("account-1").build());
    when(activity.recordModeration(any()))
        .thenThrow(new ServiceUnavailableException(
            "Moderation audit is temporarily unavailable.", new IllegalStateException("down")))
        .thenAnswer(invocation -> null);

    assertThrows(ServiceUnavailableException.class, () -> service.updateAccount(request));
    assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    assertThat(account.getPendingModerationAudit()).isNotNull();

    org.mockito.Mockito.lenient().when(permissions.getSelfId()).thenReturn("admin-b");
    org.mockito.Mockito.lenient().when(accounts.findById("admin-b")).thenReturn(Optional.of(
        Account.builder().id("admin-b").username("retrying-admin").build()));

    service.updateAccount(request);

    assertThat(account.getPendingModerationAudit()).isNull();
    var commands = ArgumentCaptor.forClass(ModerationAuditCommand.class);
    verify(activity, org.mockito.Mockito.times(2)).recordModeration(commands.capture());
    assertThat(commands.getAllValues())
        .allSatisfy(command -> {
          assertThat(command.actorAccountId()).isEqualTo("admin-a");
          assertThat(command.actorUsername()).isEqualTo("original-admin");
        });
  }

  private Account account() {
    return Account.builder()
        .id("account-1")
        .username("reader")
        .email("private@example.com")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
  }
}
