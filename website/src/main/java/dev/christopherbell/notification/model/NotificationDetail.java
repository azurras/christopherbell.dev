package dev.christopherbell.notification.model;

import java.time.Instant;
import lombok.Builder;

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
    NotificationType notificationType,
    Boolean read,
    Instant createdOn
) {}
