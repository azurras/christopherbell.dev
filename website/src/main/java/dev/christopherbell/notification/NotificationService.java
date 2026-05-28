package dev.christopherbell.notification;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.notification.inbox.NotificationInboxService;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade kept for existing notification callers while subfeatures own behavior. */
@RequiredArgsConstructor
@Service
public class NotificationService {
  private final NotificationDeliveryService notificationDeliveryService;
  private final NotificationInboxService notificationInboxService;

  /** Delegates mention notification creation to the delivery subfeature. */
  public void createMentionNotifications(Post post, Account actor) {
    notificationDeliveryService.createMentionNotifications(post, actor);
  }

  /** Delegates message notification creation to the delivery subfeature. */
  public void createMessageNotification(Message message, Account actor, Account recipient) {
    notificationDeliveryService.createMessageNotification(message, actor, recipient);
  }

  /** Delegates post-like notification creation to the delivery subfeature. */
  public void createPostLikeNotification(Post post, Account actor, Account recipient) {
    notificationDeliveryService.createPostLikeNotification(post, actor, recipient);
  }

  /** Delegates post-comment notification creation to the delivery subfeature. */
  public void createPostCommentNotification(Post reply, Account actor, Account recipient) {
    notificationDeliveryService.createPostCommentNotification(reply, actor, recipient);
  }

  /** Delegates WFL session invite notification creation to the delivery subfeature. */
  public void createWhatsForLunchSessionInvite(
      WhatsForLunchSession session,
      Account actor,
      Account recipient
  ) {
    notificationDeliveryService.createWhatsForLunchSessionInvite(session, actor, recipient);
  }

  /** Delegates inbox notification reads to the inbox subfeature. */
  public List<NotificationDetail> getMyNotifications(int limit) {
    return notificationInboxService.getMyNotifications(limit);
  }

  /** Delegates unread-count reads to the inbox subfeature. */
  public long countMyUnreadNotifications() {
    return notificationInboxService.countMyUnreadNotifications();
  }

  /** Delegates mark-read updates to the inbox subfeature. */
  public NotificationDetail markRead(String notificationId)
      throws InvalidRequestException, ResourceNotFoundException {
    return notificationInboxService.markRead(notificationId);
  }
}
