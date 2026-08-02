package dev.christopherbell.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.message.conversation.ConversationService;
import dev.christopherbell.message.conversation.ConversationArchiveService;
import dev.christopherbell.message.conversation.ConversationMessageSlice;
import dev.christopherbell.message.conversation.ConversationQueryRepository;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.message.delivery.MessageDeliveryService;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MessageServiceTest {
  @Mock private MessageRepository messageRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private NotificationDeliveryService notificationDeliveryService;
  @Mock private PermissionService permissionService;
  @Mock private AccountTrustService accountTrustService;
  @Mock private ConversationQueryRepository conversationQueries;
  @Mock private ConversationArchiveService conversationArchives;

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
    when(conversationQueries.page(eq("other:self"), eq(Optional.empty()), eq(50)))
        .thenReturn(new ConversationMessageSlice(List.of(incoming), null));

    var result = service.getConversation("alex", 50);

    assertEquals(1, result.size());
    assertEquals(true, incoming.getRead());
    verify(messageRepository).saveAll(eq(List.of(incoming)));
  }

  @ParameterizedTest(name = "{0} conversation summaries use one unread-count query")
  @ValueSource(ints = {1, 50})
  void getConversations_batchesUnreadCountsForEveryReturnedConversation(int conversationCount)
      throws Exception {
    var self = Account.builder().id("self").username("self").build();
    var messages = IntStream.range(0, conversationCount)
        .mapToObj(this::conversationSummaryMessage)
        .toList();
    var otherIds = messages.stream().map(Message::getSenderAccountId).toList();
    var requestedIds = new LinkedHashSet<>(otherIds);
    var accounts = otherIds.stream()
        .map(id -> Account.builder().id(id).username("user-" + id).build())
        .toList();

    when(permissionService.getSelfId()).thenReturn(self.getId());
    when(accountRepository.findById(self.getId())).thenReturn(Optional.of(self));
    when(conversationQueries.latestDistinctVisible(self.getId(), conversationCount))
        .thenReturn(messages);
    when(accountRepository.findAllById(requestedIds)).thenReturn(accounts);
    when(conversationQueries.unreadCounts(self.getId(), requestedIds))
        .thenReturn(otherIds.stream().collect(Collectors.toUnmodifiableMap(
            id -> id,
            id -> 7L)));

    var summaries = service().getConversations(conversationCount);

    assertThat(summaries)
        .extracting(ConversationSummary::accountId)
        .containsExactlyElementsOf(otherIds);
    assertThat(summaries).extracting(ConversationSummary::unreadCount).containsOnly(7L);
    verify(conversationQueries, times(1)).latestDistinctVisible(self.getId(), conversationCount);
    verify(accountRepository, times(1)).findAllById(requestedIds);
    verify(conversationQueries, times(1)).unreadCounts(self.getId(), requestedIds);
    verifyNoInteractions(messageRepository);
  }

  @Test
  void getConversations_preservesOrderDisplayNamesAndMissingUnreadDefaults() throws Exception {
    var self = Account.builder().id("self").username("self").build();
    var newest = message("m-new", "self", "outgoing", "sent", "2026-07-29T12:00:00Z");
    var middle = message("m-middle", "missing", "self", "unknown", "2026-07-29T11:00:00Z");
    var oldest = message("m-old", "known", "self", "received", "2026-07-29T10:00:00Z");
    var outgoing = Account.builder()
        .id("outgoing")
        .username("ada")
        .firstName("Ada")
        .lastName("Lovelace")
        .build();
    var known = Account.builder().id("known").username("grace").build();
    var requestedIds = new LinkedHashSet<>(List.of("outgoing", "missing", "known"));

    when(permissionService.getSelfId()).thenReturn(self.getId());
    when(accountRepository.findById(self.getId())).thenReturn(Optional.of(self));
    when(conversationQueries.latestDistinctVisible(self.getId(), 3))
        .thenReturn(List.of(newest, middle, oldest));
    when(accountRepository.findAllById(requestedIds)).thenReturn(List.of(outgoing, known));
    when(conversationQueries.unreadCounts(self.getId(), requestedIds))
        .thenReturn(Map.of("outgoing", 2L, "known", 4L));

    assertThat(service().getConversations(3)).containsExactly(
        ConversationSummary.builder()
            .accountId("outgoing")
            .username("ada")
            .displayName("Ada Lovelace")
            .latestText("sent")
            .lastMessageOn(Instant.parse("2026-07-29T12:00:00Z"))
            .unreadCount(2L)
            .build(),
        ConversationSummary.builder()
            .accountId("missing")
            .latestText("unknown")
            .lastMessageOn(Instant.parse("2026-07-29T11:00:00Z"))
            .unreadCount(0L)
            .build(),
        ConversationSummary.builder()
            .accountId("known")
            .username("grace")
            .displayName("grace")
            .latestText("received")
            .lastMessageOn(Instant.parse("2026-07-29T10:00:00Z"))
            .unreadCount(4L)
            .build());
  }

  @Test
  void archiveConversation_resolvesParticipantsAndArchivesOnlySelfView() throws Exception {
    var self = Account.builder().id("self").username("self").build();
    var other = Account.builder().id("other").username("alex").build();
    var archived = new dev.christopherbell.message.conversation.ConversationArchiveResult(
        "other:self", Instant.parse("2026-07-26T12:00:00Z"));
    when(permissionService.getSelfId()).thenReturn("self");
    when(accountRepository.findById("self")).thenReturn(Optional.of(self));
    when(accountRepository.findByUsername("alex")).thenReturn(Optional.of(other));
    when(conversationArchives.archive("self", "other:self", java.util.Set.of("self", "other")))
        .thenReturn(archived);

    assertEquals(archived, service().archiveConversation("alex"));

    verify(conversationArchives).archive(
        "self", "other:self", java.util.Set.of("self", "other"));
  }

  private MessageService service() {
    return new MessageService(
        new MessageDeliveryService(
            messageRepository,
            accountRepository,
            notificationDeliveryService,
            permissionService,
            accountTrustService),
        new ConversationService(
            messageRepository,
            accountRepository,
            permissionService,
            conversationQueries,
            conversationArchives,
            new StableCursorCodec()));
  }

  private Message conversationSummaryMessage(int index) {
    var otherId = "other-" + index;
    return message(
        "message-" + index,
        otherId,
        "self",
        "text-" + index,
        Instant.parse("2026-07-29T12:00:00Z").minusSeconds(index).toString());
  }

  private Message message(
      String id,
      String senderAccountId,
      String recipientAccountId,
      String text,
      String createdOn
  ) {
    return Message.builder()
        .id(id)
        .conversationKey(senderAccountId.compareTo(recipientAccountId) < 0
            ? senderAccountId + ":" + recipientAccountId
            : recipientAccountId + ":" + senderAccountId)
        .participantIds(new HashSet<>(List.of(senderAccountId, recipientAccountId)))
        .senderAccountId(senderAccountId)
        .recipientAccountId(recipientAccountId)
        .text(text)
        .read(false)
        .createdOn(Instant.parse(createdOn))
        .build();
  }
}
