package dev.christopherbell.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.message.conversation.ConversationService;
import dev.christopherbell.message.delivery.MessageDeliveryService;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
public class MessageServiceTest {
  @Mock private MessageRepository messageRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private NotificationDeliveryService notificationDeliveryService;
  @Mock private PermissionService permissionService;
  @Mock private AccountTrustService accountTrustService;

  @Test
  public void sendMessage_savesMessageAndNotifiesRecipient() throws Exception {
    var sender = Account.builder().id("sender").username("chris").build();
    var recipient = Account.builder().id("recipient").username("alex").build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn(sender.getId());
    when(accountRepository.findById(eq(sender.getId()))).thenReturn(Optional.of(sender));
    when(accountRepository.findByUsername(eq("alex"))).thenReturn(Optional.of(recipient));
    when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.sendMessage(MessageCreateRequest.builder()
        .recipientUsername("alex")
        .text("hello")
        .build());

    assertEquals("hello", result.text());
    assertEquals("chris", result.senderUsername());
    assertEquals("alex", result.recipientUsername());
    assertEquals(true, result.mine());
    verify(messageRepository).save(any(Message.class));
    verify(notificationDeliveryService).createMessageNotification(any(Message.class), eq(sender), eq(recipient));
  }

  @Test
  public void sendMessage_rejectsSelfMessages() throws Exception {
    var sender = Account.builder().id("sender").username("chris").build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn(sender.getId());
    when(accountRepository.findById(eq(sender.getId()))).thenReturn(Optional.of(sender));
    when(accountRepository.findByUsername(eq("chris"))).thenReturn(Optional.of(sender));

    assertThrows(InvalidRequestException.class, () -> service.sendMessage(MessageCreateRequest.builder()
        .recipientUsername("chris")
        .text("hello")
        .build()));
  }

  @Test
  public void sendMessage_rejectsSuspendedSender() throws Exception {
    var sender = Account.builder().id("sender").username("chris").status(AccountStatus.SUSPENDED).build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn(sender.getId());
    when(accountRepository.findById(eq(sender.getId()))).thenReturn(Optional.of(sender));

    assertThrows(InvalidRequestException.class, () -> service.sendMessage(MessageCreateRequest.builder()
        .recipientUsername("alex")
        .text("hello")
        .build()));

    verify(messageRepository, never()).save(any(Message.class));
    verify(notificationDeliveryService, never()).createMessageNotification(any(Message.class), any(Account.class), any(Account.class));
  }

  @Test
  public void sendMessage_rejectsBlockedRelationship() throws Exception {
    var sender = Account.builder().id("sender").username("chris").build();
    var recipient = Account.builder().id("recipient").username("alex").build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn(sender.getId());
    when(accountRepository.findById(eq(sender.getId()))).thenReturn(Optional.of(sender));
    when(accountRepository.findByUsername(eq("alex"))).thenReturn(Optional.of(recipient));
    when(accountTrustService.isBlockedEitherDirection("sender", "recipient")).thenReturn(true);

    assertThrows(InvalidRequestException.class, () -> service.sendMessage(MessageCreateRequest.builder()
        .recipientUsername("alex")
        .text("hello")
        .build()));

    verify(messageRepository, never()).save(any(Message.class));
    verify(notificationDeliveryService, never()).createMessageNotification(any(Message.class), any(Account.class), any(Account.class));
  }

  @Test
  public void getConversation_marksIncomingMessagesRead() throws Exception {
    var self = Account.builder().id("self").username("self").build();
    var other = Account.builder().id("other").username("alex").build();
    var incoming = Message.builder()
        .id("m1")
        .conversationKey("other:self")
        .participantIds(new HashSet<>(List.of("self", "other")))
        .senderAccountId("other")
        .recipientAccountId("self")
        .text("hi")
        .read(false)
        .createdOn(Instant.now())
        .build();
    var service = service();

    when(permissionService.getSelfId()).thenReturn(self.getId());
    when(accountRepository.findById(eq(self.getId()))).thenReturn(Optional.of(self));
    when(accountRepository.findByUsername(eq("alex"))).thenReturn(Optional.of(other));
    when(messageRepository.findByConversationKeyOrderByCreatedOnAsc(
        eq("other:self"),
        any(PageRequest.class)))
        .thenReturn(List.of(incoming));

    var result = service.getConversation("alex", 50);

    assertEquals(1, result.size());
    assertEquals(true, incoming.getRead());
    verify(messageRepository).saveAll(eq(List.of(incoming)));
  }

  private MessageService service() {
    return new MessageService(
        new MessageDeliveryService(
            messageRepository,
            accountRepository,
            notificationDeliveryService,
            permissionService,
            accountTrustService),
        new ConversationService(messageRepository, accountRepository, permissionService));
  }
}
