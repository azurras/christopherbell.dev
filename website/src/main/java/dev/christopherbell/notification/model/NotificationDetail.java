package dev.christopherbell.notification.model;

import java.time.Instant;
import lombok.Builder;

/** One notification as its recipient sees it. */
@Builder
public record NotificationDetail(
    String id,
    String accountId,
    String actorAccountId,
    String actorUsername,
    String postId,
    String postText,
    String messageId,
    String messageText,
    String whatsForLunchSessionId,
    String whatsForLunchSessionText,
    NotificationType notificationType,
    Boolean read,
    Instant createdOn
) {

  /** Describes a stored notification; a missing read flag reads as unread. */
  public static NotificationDetail from(Notification notification) {
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
