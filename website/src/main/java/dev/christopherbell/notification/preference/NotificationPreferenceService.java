package dev.christopherbell.notification.preference;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.notification.model.NotificationPreferenceDetail;
import dev.christopherbell.notification.model.NotificationPreferenceUpdateRequest;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reads, saves, and evaluates per-user notification category preferences. */
@RequiredArgsConstructor
@Service
public class NotificationPreferenceService {
  private final NotificationPreferenceRepository notificationPreferenceRepository;
  private final PermissionService permissionService;

  /** Returns the current user's preferences, defaulting every category on. */
  public NotificationPreferenceDetail getMyPreferences() {
    return toDetail(preferencesFor(permissionService.getSelfId()));
  }

  /** Saves the current user's requested notification category preferences. */
  public NotificationPreferenceDetail updateMyPreferences(NotificationPreferenceUpdateRequest request)
      throws InvalidRequestException {
    validateCompleteRequest(request);
    var accountId = permissionService.getSelfId();
    var preferences = notificationPreferenceRepository.findByAccountId(accountId)
        .orElseGet(() -> defaultsFor(accountId));

    preferences.setMentions(request.mentions());
    preferences.setLikes(request.likes());
    preferences.setComments(request.comments());
    preferences.setMessages(request.messages());
    preferences.setWflSessions(request.wflSessions());

    return toDetail(notificationPreferenceRepository.save(preferences));
  }

  /** Returns whether the recipient should receive this notification type. */
  public boolean shouldDeliver(String accountId, NotificationType type) {
    if (accountId == null || accountId.isBlank() || type == null) {
      return false;
    }
    return enabled(preferencesFor(accountId), type);
  }

  private NotificationPreference preferencesFor(String accountId) {
    return notificationPreferenceRepository.findByAccountId(accountId)
        .orElseGet(() -> defaultsFor(accountId));
  }

  private void validateCompleteRequest(NotificationPreferenceUpdateRequest request)
      throws InvalidRequestException {
    if (request == null
        || request.mentions() == null
        || request.likes() == null
        || request.comments() == null
        || request.messages() == null
        || request.wflSessions() == null) {
      throw new InvalidRequestException("All notification preference fields are required.");
    }
  }

  private NotificationPreference defaultsFor(String accountId) {
    return NotificationPreference.builder()
        .accountId(accountId)
        .mentions(true)
        .likes(true)
        .comments(true)
        .messages(true)
        .wflSessions(true)
        .build();
  }

  private boolean enabled(NotificationPreference preferences, NotificationType type) {
    return switch (type) {
      case MENTION -> preferences.isMentions();
      case LIKE -> preferences.isLikes();
      case COMMENT -> preferences.isComments();
      case MESSAGE -> preferences.isMessages();
      case WFL_SESSION -> preferences.isWflSessions();
    };
  }

  private NotificationPreferenceDetail toDetail(NotificationPreference preferences) {
    return NotificationPreferenceDetail.builder()
        .mentions(preferences.isMentions())
        .likes(preferences.isLikes())
        .comments(preferences.isComments())
        .messages(preferences.isMessages())
        .wflSessions(preferences.isWflSessions())
        .build();
  }
}
