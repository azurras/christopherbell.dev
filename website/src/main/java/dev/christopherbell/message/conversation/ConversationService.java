package dev.christopherbell.message.conversation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.pagination.StableCursorCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Handles conversation reads, summaries, and read-state updates. */
@RequiredArgsConstructor
@Service
public class ConversationService {
  private final MessageRepository messageRepository;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;
  private final ConversationQueryRepository conversationQueries;
  private final ConversationArchiveService conversationArchives;
  private final StableCursorCodec cursorCodec;

  /**
   * Loads a conversation with another user and marks incoming unread messages as read.
   */
  public List<MessageDetail> getConversation(String username, int limit)
      throws ResourceNotFoundException {
    try {
      return getConversationPage(username, null, limit).items();
    } catch (InvalidRequestException impossibleBlankCursor) {
      throw new IllegalStateException("Blank conversation cursor was rejected", impossibleBlankCursor);
    }
  }

  /** Loads one stable page of the newest remaining messages and marks page-contained reads. */
  public ConversationPage getConversationPage(String username, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    var participants = resolveParticipants(username);
    var slice = conversationQueries.page(
        participants.conversationKey(), cursorCodec.decode(cursor), size);
    var messages = slice.items();

    var changed = messages.stream()
        .filter(message -> participants.self().getId().equals(message.getRecipientAccountId()))
        .filter(message -> !Boolean.TRUE.equals(message.getRead()))
        .peek(message -> message.setRead(true))
        .toList();
    if (!changed.isEmpty()) {
      messageRepository.saveAll(changed);
    }

    var details = new ArrayList<>(messages.stream()
        .map(message -> toDetail(
            message,
            participants.self().getId(),
            Map.of(
                participants.self().getId(), participants.self(),
                participants.other().getId(), participants.other())))
        .toList());
    Collections.reverse(details);
    return new ConversationPage(details, slice.nextCursor());
  }

  /**
   * Lists the current user's latest conversations with unread counts.
   */
  public List<ConversationSummary> getConversations(int limit) throws ResourceNotFoundException {
    var self = getSelfAccount();
    var latestByOtherId = new java.util.LinkedHashMap<String, Message>();
    for (var message : conversationQueries.latestDistinctVisible(self.getId(), limit)) {
      var otherId = self.getId().equals(message.getSenderAccountId())
          ? message.getRecipientAccountId()
          : message.getSenderAccountId();
      latestByOtherId.put(otherId, message);
    }

    var accounts = accountRepository.findAllById(latestByOtherId.keySet());
    var accountById = new HashMap<String, Account>();
    accounts.forEach(account -> accountById.put(account.getId(), account));
    return latestByOtherId.entrySet().stream()
        .map(entry -> summary(entry.getKey(), entry.getValue(), self, accountById.get(entry.getKey())))
        .toList();
  }

  /** Archives only the current account's view of a resolved conversation. */
  public ConversationArchiveResult archive(String username) throws ResourceNotFoundException {
    var participants = resolveParticipants(username);
    return conversationArchives.archive(
        participants.self().getId(),
        participants.conversationKey(),
        java.util.Set.of(participants.self().getId(), participants.other().getId()));
  }

  private ConversationSummary summary(String otherId, Message message, Account self, Account other) {
    return ConversationSummary.builder()
        .accountId(otherId)
        .username(other == null ? null : other.getUsername())
        .displayName(displayName(other))
        .latestText(message.getText())
        .lastMessageOn(message.getCreatedOn())
        .unreadCount(messageRepository.countByRecipientAccountIdAndSenderAccountIdAndReadFalse(
            self.getId(),
            otherId))
        .build();
  }

  private Account getSelfAccount() throws ResourceNotFoundException {
    var selfId = permissionService.getSelfId();
    return accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));
  }

  private ConversationParticipants resolveParticipants(String username)
      throws ResourceNotFoundException {
    var self = getSelfAccount();
    var other = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(username))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", username)));
    return new ConversationParticipants(
        self, other, conversationKey(self.getId(), other.getId()));
  }

  private static String conversationKey(String firstAccountId, String secondAccountId) {
    return firstAccountId.compareTo(secondAccountId) < 0
        ? firstAccountId + ":" + secondAccountId
        : secondAccountId + ":" + firstAccountId;
  }

  private static MessageDetail toDetail(Message message, String selfId, Map<String, Account> accounts) {
    var sender = accounts.get(message.getSenderAccountId());
    var recipient = accounts.get(message.getRecipientAccountId());
    return MessageDetail.builder()
        .id(message.getId())
        .senderAccountId(message.getSenderAccountId())
        .senderUsername(sender == null ? null : sender.getUsername())
        .recipientAccountId(message.getRecipientAccountId())
        .recipientUsername(recipient == null ? null : recipient.getUsername())
        .text(message.getText())
        .read(Boolean.TRUE.equals(message.getRead()))
        .mine(selfId.equals(message.getSenderAccountId()))
        .createdOn(message.getCreatedOn())
        .build();
  }

  private static String displayName(Account account) {
    if (account == null) {
      return null;
    }
    return java.util.stream.Stream.of(account.getFirstName(), account.getLastName())
        .filter(part -> part != null && !part.isBlank())
        .reduce((first, second) -> first + " " + second)
        .orElse(account.getUsername());
  }

  private record ConversationParticipants(
      Account self,
      Account other,
      String conversationKey
  ) {}
}
