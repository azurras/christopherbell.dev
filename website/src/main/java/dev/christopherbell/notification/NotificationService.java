package dev.christopherbell.notification;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class NotificationService {
  private static final Pattern MENTION_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z0-9._-]{3,32})");

  private final NotificationRepository notificationRepository;
  private final AccountRepository accountRepository;

  public void createMentionNotifications(Post post, Account actor) {
    if (post == null || actor == null || post.getText() == null) {
      return;
    }

    var now = Instant.now();
    for (var username : extractMentionUsernames(post.getText())) {
      accountRepository.findByUsernameIgnoreCase(username)
          .filter(account -> !account.getId().equals(actor.getId()))
          .ifPresent(account -> notificationRepository.save(Notification.builder()
              .id(UUID.randomUUID().toString())
              .accountId(account.getId())
              .actorAccountId(actor.getId())
              .actorUsername(actor.getUsername())
              .postId(post.getId())
              .postText(post.getText())
              .notificationType(NotificationType.MENTION)
              .read(false)
              .createdOn(now)
              .build()));
    }
  }

  public void createMessageNotification(Message message, Account actor, Account recipient) {
    if (message == null || actor == null || recipient == null) {
      return;
    }
    notificationRepository.save(Notification.builder()
        .id(UUID.randomUUID().toString())
        .accountId(recipient.getId())
        .actorAccountId(actor.getId())
        .actorUsername(actor.getUsername())
        .messageId(message.getId())
        .messageText(message.getText())
        .notificationType(NotificationType.MESSAGE)
        .read(false)
        .createdOn(Instant.now())
        .build());
  }

  public List<NotificationDetail> getMyNotifications(int limit) {
    String selfId = PermissionService.getSelf();
    int pageSize = Math.max(1, Math.min(limit, 50));
    var page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdOn"));
    return notificationRepository.findByAccountIdOrderByCreatedOnDesc(selfId, page).stream()
        .map(this::toDetail)
        .toList();
  }

  public long countMyUnreadNotifications() {
    return notificationRepository.countByAccountIdAndReadFalse(PermissionService.getSelf());
  }

  public NotificationDetail markRead(String notificationId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (notificationId == null || notificationId.isBlank()) {
      throw new InvalidRequestException("Notification id cannot be null or blank.");
    }

    String selfId = PermissionService.getSelf();
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
        .notificationType(notification.getNotificationType())
        .read(Boolean.TRUE.equals(notification.getRead()))
        .createdOn(notification.getCreatedOn())
        .build();
  }
}
