package dev.christopherbell.notification.delivery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.notification.preference.NotificationPreferenceService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Creates user notifications from feature events. */
@RequiredArgsConstructor
@Service
public class NotificationDeliveryService {
  private static final Pattern MENTION_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z0-9._-]{3,32})");

  private final NotificationRepository notificationRepository;
  private final AccountRepository accountRepository;
  private final NotificationPreferenceService notificationPreferenceService;
  private final NotificationFanoutGuard fanoutGuard;
  private final Clock clock;

  /** Creates mention notifications for valid mentioned usernames in a post. */
  public void createMentionNotifications(Post post, Account actor) {
    if (post == null || actor == null || post.getText() == null) {
      return;
    }

    for (var username : extractMentionUsernames(post.getText())) {
      accountRepository.findByUsernameIgnoreCase(username)
          .filter(account -> !account.getId().equals(actor.getId()))
          .ifPresent(account -> deliver(Notification.builder()
              .accountId(account.getId())
              .actorAccountId(actor.getId())
              .actorUsername(actor.getUsername())
              .postId(post.getId())
              .postText(post.getText())
              .notificationType(NotificationType.MENTION)
              .read(false)
              .build(), post.getId()));
    }
  }

  /** Creates a direct-message notification for the message recipient. */
  public void createMessageNotification(Message message, Account actor, Account recipient) {
    if (message == null || actor == null || recipient == null) {
      return;
    }
    deliver(Notification.builder()
        .accountId(recipient.getId())
        .actorAccountId(actor.getId())
        .actorUsername(actor.getUsername())
        .messageId(message.getId())
        .messageText(message.getText())
        .notificationType(NotificationType.MESSAGE)
        .read(false)
        .build(), message.getId());
  }

  /** Creates a notification when another user likes a post. */
  public void createPostLikeNotification(Post post, Account actor, Account recipient) {
    createPostNotification(post, actor, recipient, NotificationType.LIKE);
  }

  /** Creates a notification when another user comments on a post. */
  public void createPostCommentNotification(Post reply, Account actor, Account recipient) {
    createPostNotification(reply, actor, recipient, NotificationType.COMMENT);
  }

  /** Creates a notification that links a recipient into a shared WFL session. */
  public void createWhatsForLunchSessionInvite(
      WhatsForLunchSession session,
      Account actor,
      Account recipient
  ) {
    if (session == null || actor == null || recipient == null) {
      return;
    }
    deliver(Notification.builder()
        .accountId(recipient.getId())
        .actorAccountId(actor.getId())
        .actorUsername(actor.getUsername())
        .whatsForLunchSessionId(session.getId())
        .whatsForLunchSessionText("Vote on today's lunch picks.")
        .notificationType(NotificationType.WFL_SESSION)
        .read(false)
        .build(), session.getId());
  }

  static Set<String> extractMentionUsernames(String text) {
    var usernames = new LinkedHashSet<String>();
    if (text == null || text.isBlank()) {
      return usernames;
    }

    var matcher = MENTION_PATTERN.matcher(text);
    while (matcher.find()) {
      try {
        usernames.add(UsernameSanitizer.sanitize(matcher.group(1)));
      } catch (IllegalArgumentException ignored) {
        // Ignore invalid mention-like tokens.
      }
    }
    return usernames;
  }

  private void createPostNotification(
      Post post,
      Account actor,
      Account recipient,
      NotificationType notificationType
  ) {
    if (post == null || actor == null || recipient == null || notificationType == null) {
      return;
    }
    if (actor.getId() != null && actor.getId().equals(recipient.getId())) {
      return;
    }
    deliver(Notification.builder()
        .accountId(recipient.getId())
        .actorAccountId(actor.getId())
        .actorUsername(actor.getUsername())
        .postId(post.getId())
        .postText(post.getText())
        .notificationType(notificationType)
        .read(false)
        .build(), post.getId());
  }

  private boolean shouldDeliver(String accountId, NotificationType notificationType) {
    return notificationPreferenceService.shouldDeliver(accountId, notificationType);
  }

  private void deliver(Notification notification, String targetId) {
    if (notification.getAccountId() == null
        || notification.getActorAccountId() == null
        || notification.getNotificationType() == null
        || targetId == null) {
      return;
    }
    if (!shouldDeliver(notification.getAccountId(), notification.getNotificationType())) {
      return;
    }
    var identity = new NotificationEventIdentity(
        notification.getAccountId(),
        notification.getActorAccountId(),
        notification.getNotificationType(),
        targetId);
    var now = clock.instant();
    fanoutGuard.tryAcquire(identity, now).ifPresent(permit -> {
      notification.setId(UUID.randomUUID().toString());
      notification.setCreatedOn(now);
      try {
        notificationRepository.save(notification);
      } catch (RuntimeException failure) {
        try {
          fanoutGuard.release(permit);
        } catch (RuntimeException releaseFailure) {
          failure.addSuppressed(releaseFailure);
        }
        throw failure;
      }
    });
  }
}
