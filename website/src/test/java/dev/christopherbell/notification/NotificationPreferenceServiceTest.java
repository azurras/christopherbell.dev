package dev.christopherbell.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.notification.model.NotificationPreferenceUpdateRequest;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.notification.preference.NotificationPreference;
import dev.christopherbell.notification.preference.NotificationPreferenceRepository;
import dev.christopherbell.notification.preference.NotificationPreferenceService;
import dev.christopherbell.permission.PermissionService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {
  @Mock private NotificationPreferenceRepository repository;
  @Mock private PermissionService permissionService;

  @Test
  @DisplayName("Missing notification preferences return all-enabled defaults")
  void getPreferences_whenMissing_returnsAllEnabledDefaults() {
    var service = service();
    when(permissionService.getSelfId()).thenReturn("acct-1");
    when(repository.findByAccountId("acct-1")).thenReturn(Optional.empty());

    var detail = service.getMyPreferences();

    assertTrue(detail.mentions());
    assertTrue(detail.likes());
    assertTrue(detail.comments());
    assertTrue(detail.messages());
    assertTrue(detail.wflSessions());
  }

  @Test
  @DisplayName("Preference updates save requested settings for the current user")
  void updatePreferences_savesRequestedSettingsForCurrentUser() throws InvalidRequestException {
    var service = service();
    when(permissionService.getSelfId()).thenReturn("acct-1");
    when(repository.findByAccountId("acct-1")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var detail = service.updateMyPreferences(
        new NotificationPreferenceUpdateRequest(false, true, false, true, false));

    assertFalse(detail.mentions());
    assertTrue(detail.likes());
    assertFalse(detail.comments());
    assertTrue(detail.messages());
    assertFalse(detail.wflSessions());
  }

  @Test
  @DisplayName("Delivery checks return false when a notification type is disabled")
  void shouldDeliver_returnsFalseWhenTypeDisabled() {
    var service = service();
    when(repository.findByAccountId("acct-1")).thenReturn(Optional.of(NotificationPreference.builder()
        .accountId("acct-1")
        .mentions(true)
        .likes(false)
        .comments(true)
        .messages(true)
        .wflSessions(true)
        .build()));

    assertFalse(service.shouldDeliver("acct-1", NotificationType.LIKE));
  }

  @Test
  @DisplayName("Preference updates reject missing category fields")
  void updatePreferences_whenFieldMissing_rejectsRequest() {
    var service = service();

    assertThrows(InvalidRequestException.class, () -> service.updateMyPreferences(
        new NotificationPreferenceUpdateRequest(false, true, null, true, false)));

    verify(repository, never()).save(any());
  }

  private NotificationPreferenceService service() {
    return new NotificationPreferenceService(repository, permissionService);
  }
}
