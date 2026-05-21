package dev.christopherbell.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
  @Mock private NotificationRepository notificationRepository;
  @Mock private AccountRepository accountRepository;

  @Test
  @DisplayName("Mention notifications are created for existing mentioned users")
  void testCreateMentionNotifications_whenMentionedUserExists_savesNotification() {
    var service = new NotificationService(notificationRepository, accountRepository);
    var actor = Account.builder().id("actor").username("writer").build();
    var mentioned = Account.builder().id("mentioned").username("reader").build();
    var post = Post.builder().id("post-1").text("hello @Reader").build();

    when(accountRepository.findByUsernameIgnoreCase("Reader")).thenReturn(Optional.of(mentioned));

    service.createMentionNotifications(post, actor);

    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    var notification = captor.getValue();
    assertEquals("mentioned", notification.getAccountId());
    assertEquals("actor", notification.getActorAccountId());
    assertEquals("writer", notification.getActorUsername());
    assertEquals("post-1", notification.getPostId());
    assertEquals("hello @Reader", notification.getPostText());
    assertEquals(NotificationType.MENTION, notification.getNotificationType());
    assertEquals(false, notification.getRead());
  }

  @Test
  @DisplayName("Mention notifications skip duplicate and self mentions")
  void testCreateMentionNotifications_whenDuplicateAndSelfMention_skipsExtraNotifications() {
    var service = new NotificationService(notificationRepository, accountRepository);
    var actor = Account.builder().id("actor").username("writer").build();
    var post = Post.builder().id("post-1").text("@writer @writer").build();

    when(accountRepository.findByUsernameIgnoreCase("writer")).thenReturn(Optional.of(actor));

    service.createMentionNotifications(post, actor);

    verify(accountRepository).findByUsernameIgnoreCase("writer");
    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  @DisplayName("WFL session invite notifications link to the shared session")
  void testCreateWhatsForLunchSessionInvite_savesSessionNotification() {
    var service = new NotificationService(notificationRepository, accountRepository);
    var actor = Account.builder().id("actor").username("owner").build();
    var recipient = Account.builder().id("recipient").username("friend").build();
    var session = WhatsForLunchSession.builder().id("session-1").build();

    service.createWhatsForLunchSessionInvite(session, actor, recipient);

    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    var notification = captor.getValue();
    assertEquals("recipient", notification.getAccountId());
    assertEquals("actor", notification.getActorAccountId());
    assertEquals("owner", notification.getActorUsername());
    assertEquals("session-1", notification.getWhatsForLunchSessionId());
    assertEquals(NotificationType.WFL_SESSION, notification.getNotificationType());
    assertEquals(false, notification.getRead());
  }
}
