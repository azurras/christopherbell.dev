package dev.christopherbell.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.admin.activity.AdminActivityRepository;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.permission.PermissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminActivityServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-18T15:00:00Z");

  @Mock private AccountRepository accountRepository;
  @Mock private AdminActivityRepository adminActivityRepository;
  @Mock private PermissionService permissionService;

  @Test
  @DisplayName("Recent activity is loaded in repository order")
  void getRecentActivity_returnsRepositoryResults() {
    var activities = List.of(AdminActivity.builder().id("a1").build());
    var service = service();

    when(adminActivityRepository.findTop25ByOrderByCreatedOnDesc()).thenReturn(activities);

    assertSame(activities, service.getRecentActivity());

    verify(adminActivityRepository).findTop25ByOrderByCreatedOnDesc();
    verifyNoMoreInteractions(accountRepository, adminActivityRepository, permissionService);
  }

  @Test
  @DisplayName("Record uses actor username when account exists")
  void record_whenActorAccountExists_savesActivityWithUsername() {
    var service = service();
    var metadata = Map.of("source", "back-office");
    var saved = AdminActivity.builder().id("activity-1").build();

    when(permissionService.getSelfId()).thenReturn("account-1");
    when(accountRepository.findById(eq("account-1")))
        .thenReturn(Optional.of(Account.builder().id("account-1").username("cbell").build()));
    when(adminActivityRepository.save(org.mockito.ArgumentMatchers.any(AdminActivity.class)))
        .thenReturn(saved);

    var result = service.record(
        "IMPORT_RESTAURANTS",
        "restaurant",
        "restaurant-1",
        "Lunch Spot",
        "%s started a restaurant import.",
        metadata);

    assertSame(saved, result);
    var captor = ArgumentCaptor.forClass(AdminActivity.class);
    verify(adminActivityRepository).save(captor.capture());
    var activity = captor.getValue();
    assertEquals("account-1", activity.getActorAccountId());
    assertEquals("cbell", activity.getActorUsername());
    assertEquals("IMPORT_RESTAURANTS", activity.getAction());
    assertEquals("restaurant", activity.getTargetType());
    assertEquals("restaurant-1", activity.getTargetId());
    assertEquals("Lunch Spot", activity.getTargetLabel());
    assertEquals("cbell started a restaurant import.", activity.getMessage());
    assertEquals(metadata, activity.getMetadata());
    assertEquals(NOW, activity.getCreatedOn());
  }

  @Test
  @DisplayName("Record falls back to actor id when account is missing")
  void record_whenActorAccountMissing_savesActivityWithActorId() {
    var service = service();

    when(permissionService.getSelfId()).thenReturn("account-2");
    when(accountRepository.findById(eq("account-2"))).thenReturn(Optional.empty());
    when(adminActivityRepository.save(org.mockito.ArgumentMatchers.any(AdminActivity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.record(
        "DELETE_RESTAURANT",
        "restaurant",
        "restaurant-2",
        "Old Spot",
        "%s deleted a restaurant.",
        Map.of());

    assertEquals("account-2", result.getActorUsername());
    assertEquals("account-2 deleted a restaurant.", result.getMessage());
    assertEquals(NOW, result.getCreatedOn());
  }

  @Test
  @DisplayName("Record falls back to actor id when username is missing")
  void record_whenActorUsernameMissing_savesActivityWithActorId() {
    var service = service();

    when(permissionService.getSelfId()).thenReturn("account-3");
    when(accountRepository.findById(eq("account-3")))
        .thenReturn(Optional.of(Account.builder().id("account-3").build()));
    when(adminActivityRepository.save(org.mockito.ArgumentMatchers.any(AdminActivity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.record(
        "UPDATE_USER",
        "account",
        "account-4",
        "target-user",
        "%s updated a user.",
        Map.of());

    assertEquals("account-3", result.getActorUsername());
    assertEquals("account-3 updated a user.", result.getMessage());
  }

  @Test
  @DisplayName("Explicit actor records do not require request security context")
  void recordForActor_savesWithoutReadingCurrentRequestActor() {
    var service = service();
    when(adminActivityRepository.save(org.mockito.ArgumentMatchers.any(AdminActivity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.recordForActor(
        "account-5",
        "captured-admin",
        "COMMAND_CENTER_ACTION_LAUNCHED",
        "command-center",
        "RESTART_SITE",
        "RESTART_SITE",
        "%s launched a protected action.",
        Map.of("outcome", "launched"));

    assertEquals("account-5", result.getActorAccountId());
    assertEquals("captured-admin", result.getActorUsername());
    assertEquals("captured-admin launched a protected action.", result.getMessage());
    assertEquals(NOW, result.getCreatedOn());
    verify(adminActivityRepository).save(org.mockito.ArgumentMatchers.any(AdminActivity.class));
    verifyNoMoreInteractions(accountRepository, adminActivityRepository, permissionService);
  }

  private AdminActivityService service() {
    return new AdminActivityService(
        accountRepository,
        adminActivityRepository,
        Clock.fixed(NOW, ZoneOffset.UTC),
        permissionService);
  }
}
