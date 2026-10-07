package dev.christopherbell.notification.inbox;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.permission.PermissionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Reads and updates the signed-in account's notification inbox. */
@RequiredArgsConstructor
@Service
public class NotificationInboxService {
  private static final int MAX_LIST_SIZE = 50;

  private final NotificationRepository notificationRepository;
  private final PermissionService permissionService;
  private final NotificationQueryPort notificationQueries;
  private final StableCursorCodec cursorCodec;

  /** Lists up to 50 of the signed-in account's notifications, newest first. */
  public List<NotificationDetail> listMyNotifications(int limit) {
    String signedInAccountId = permissionService.getSelfId();
    int pageSize = Math.max(1, Math.min(limit, MAX_LIST_SIZE));
    Pageable newestFirst = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
    return notificationRepository.findByAccountIdOrderByCreatedOnDesc(signedInAccountId, newestFirst)
        .stream()
        .map(NotificationDetail::from)
        .toList();
  }

  /**
   * Returns one stable newest-first page of the signed-in account's notifications.
   *
   * @param cursor the boundary from a previous page, or blank for the newest
   * @throws InvalidRequestException if the cursor is malformed
   */
  public NotificationPage myNotificationPage(String cursor, int size) throws InvalidRequestException {
    return notificationQueries.page(permissionService.getSelfId(), cursorCodec.decode(cursor), size);
  }

  /** Atomically marks all of the signed-in account's notifications read. */
  public NotificationReadResult markAllRead() {
    return notificationQueries.markAllRead(permissionService.getSelfId());
  }

  /** Counts the signed-in account's unread notifications. */
  public long countMyUnreadNotifications() {
    return notificationRepository.countByAccountIdAndReadFalse(permissionService.getSelfId());
  }

  /**
   * Marks one of the signed-in account's notifications read.
   *
   * @throws InvalidRequestException if the id is blank
   * @throws ResourceNotFoundException if no notification has the id or it belongs to someone else
   */
  public NotificationDetail markRead(String notificationId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (notificationId == null || notificationId.isBlank()) {
      throw new InvalidRequestException("Notification id cannot be null or blank.");
    }
    String signedInAccountId = permissionService.getSelfId();
    Notification notification = notificationRepository.findById(notificationId)
        .filter(candidate -> signedInAccountId.equals(candidate.getAccountId()))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Notification with id %s not found.", notificationId)));
    notification.setRead(true);
    return NotificationDetail.from(notificationRepository.save(notification));
  }
}
