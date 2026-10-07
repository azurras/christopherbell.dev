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
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Creates user notifications from feature events.
 *
 * <p>Every delivery respects the recipient's preferences and passes the fanout guard, which
 * drops duplicates and rate-limited events. Missing inputs make an event a no-op.</p>
 */
@RequiredArgsConstructor
@Service
public class NotificationDeliveryService {
  private static final Pattern MENTION_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z0-9._-]{3,32})");
  private static final String WFL_SESSION_INVITE_TEXT = "Vote on today's lunch picks.";

  private final NotificationRepository notificationRepository;
  private final AccountRepository accountRepository;
  private final NotificationPreferenceService notificationPreferenceService;
  private final NotificationFanoutPort fanoutGuard;
  private final Clock clock;

  /** Notifies each valid, existing account mentioned in a post, except the author. */
  public void createMentionNotifications(Post post, Account actor) {
    if (post == null || actor == null || post.getText() == null) {
      return;
    }
    for (String mentionedUsername : extractMentionUsernames(post.getText())) {
      Optional<Account> mentionedAccount = accountRepository.findByUsernameIgnoreCase(mentionedUsername)
          .filter(account -> !account.getId().equals(actor.getId()));
      if (mentionedAccount.isPresent()) {
        deliver(Notification.builder()
            .accountId(mentionedAccount.get().getId())
            .actorAccountId(actor.getId())
            .actorUsername(actor.getUsername())
            .postId(post.getId())
            .postText(post.getText())
            .notificationType(NotificationType.MENTION)
            .read(false)
            .build(), post.getId());
      }
    }
  }

  /** Notifies the recipient of a direct message. */
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

  /** Notifies a post's author when another user likes it. */
  public void createPostLikeNotification(Post post, Account actor, Account recipient) {
    createPostNotification(post, actor, recipient, NotificationType.LIKE);
  }

  /** Notifies a post's author when another user replies. */
  public void createPostCommentNotification(Post reply, Account actor, Account recipient) {
    createPostNotification(reply, actor, recipient, NotificationType.COMMENT);
  }

  /** Invites a recipient into a shared What's For Lunch session. */
  public void createWhatsForLunchSessionInvite(
      WhatsForLunchSession session, Account actor, Account recipient) {
    if (session == null || actor == null || recipient == null) {
      return;
    }
    deliver(Notification.builder()
        .accountId(recipient.getId())
        .actorAccountId(actor.getId())
        .actorUsername(actor.getUsername())
        .whatsForLunchSessionId(session.getId())
        .whatsForLunchSessionText(WFL_SESSION_INVITE_TEXT)
        .notificationType(NotificationType.WFL_SESSION)
        .read(false)
        .build(), session.getId());
  }

  /** Returns the distinct, sanitized usernames mentioned as {@code @name}, in order. */
  static Set<String> extractMentionUsernames(String text) {
    Set<String> mentionedUsernames = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return mentionedUsernames;
    }
    Matcher mentionMatcher = MENTION_PATTERN.matcher(text);
    while (mentionMatcher.find()) {
      try {
        mentionedUsernames.add(UsernameSanitizer.sanitize(mentionMatcher.group(1)));
      } catch (IllegalArgumentException notAUsername) {
        // A mention-like token that is not a valid username is plain text, not a mention.
      }
    }
    return mentionedUsernames;
  }

  private void createPostNotification(
      Post post, Account actor, Account recipient, NotificationType notificationType) {
    if (post == null || actor == null || recipient == null || notificationType == null) {
      return;
    }
    boolean actorIsRecipient = actor.getId() != null && actor.getId().equals(recipient.getId());
    if (actorIsRecipient) {
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

  /**
   * Saves the notification when the recipient wants this type and the fanout guard grants a
   * permit. If the save fails, the permit is released and the save failure is rethrown with any
   * release failure suppressed.
   */
  private void deliver(Notification notification, String targetId) {
    if (notification.getAccountId() == null
        || notification.getActorAccountId() == null
        || notification.getNotificationType() == null
        || targetId == null) {
      return;
    }
    if (!notificationPreferenceService.shouldDeliver(
        notification.getAccountId(), notification.getNotificationType())) {
      return;
    }
    NotificationEventIdentity eventIdentity = new NotificationEventIdentity(
        notification.getAccountId(),
        notification.getActorAccountId(),
        notification.getNotificationType(),
        targetId);
    Instant now = clock.instant();
    Optional<NotificationDeliveryPermit> deliveryPermit = fanoutGuard.tryAcquire(eventIdentity, now);
    if (deliveryPermit.isEmpty()) {
      return;
    }
    notification.setId(UUID.randomUUID().toString());
    notification.setCreatedOn(now);
    try {
      notificationRepository.save(notification);
    } catch (RuntimeException saveFailure) {
      try {
        fanoutGuard.release(deliveryPermit.get());
      } catch (RuntimeException releaseFailure) {
        saveFailure.addSuppressed(releaseFailure);
      }
      throw saveFailure;
    }
  }
}
