package dev.christopherbell.notification.inbox;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.permission.PermissionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Reads and updates the current user's notification inbox. */
@RequiredArgsConstructor
@Service
public class NotificationInboxService {
  private final NotificationRepository notificationRepository;
  private final PermissionService permissionService;

  /** Lists the current user's notifications newest first. */
  public List<NotificationDetail> getMyNotifications(int limit) {
    String selfId = permissionService.getSelfId();
    int pageSize = Math.max(1, Math.min(limit, 50));
    var page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
    return notificationRepository.findByAccountIdOrderByCreatedOnDesc(selfId, page).stream()
        .map(this::toDetail)
        .toList();
  }

  /** Counts unread notifications for the current user. */
  public long countMyUnreadNotifications() {
    return notificationRepository.countByAccountIdAndReadFalse(permissionService.getSelfId());
  }

  /** Marks one notification read when it belongs to the current user. */
  public NotificationDetail markRead(String notificationId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (notificationId == null || notificationId.isBlank()) {
      throw new InvalidRequestException("Notification id cannot be null or blank.");
    }

    String selfId = permissionService.getSelfId();
    var notification = notificationRepository.findById(notificationId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Notification with id %s not found.", notificationId)));
    if (!selfId.equals(notification.getAccountId())) {
      throw new ResourceNotFoundException(
          String.format("Notification with id %s not found.", notificationId));
    }

    notification.setRead(true);
    return toDetail(notificationRepository.save(notification));
  }

  private NotificationDetail toDetail(Notification notification) {
    return NotificationDetail.builder()
        .id(notification.getId())
        .accountId(notification.getAccountId())
        .actorAccountId(notification.getActorAccountId())
        .actorUsername(notification.getActorUsername())
        .postId(notification.getPostId())
        .postText(notification.getPostText())
        .messageId(notification.getMessageId())
        .messageText(notification.getMessageText())
        .whatsForLunchSessionId(notification.getWhatsForLunchSessionId())
        .whatsForLunchSessionText(notification.getWhatsForLunchSessionText())
        .notificationType(notification.getNotificationType())
        .read(Boolean.TRUE.equals(notification.getRead()))
        .createdOn(notification.getCreatedOn())
        .build();
  }
}
