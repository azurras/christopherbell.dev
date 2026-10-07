package dev.christopherbell.message.conversation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.ConversationKeys;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.permission.PermissionService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Handles conversation reads, summaries, read-state updates and per-account archiving. */
@RequiredArgsConstructor
@Service
public class ConversationService {
  private final MessageRepository messageRepository;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;
  private final ConversationQueryPort conversationQueries;
  private final ConversationArchivePort conversationArchives;
  private final StableCursorCodec cursorCodec;

  /**
   * Opens the newest messages of the conversation with another user, oldest first, and marks the
   * incoming ones read.
   */
  public List<MessageDetail> openConversation(String otherUsername, int limit)
      throws ResourceNotFoundException {
    try {
      return openConversationPage(otherUsername, null, limit).items();
    } catch (InvalidRequestException impossibleBlankCursor) {
      throw new IllegalStateException("Blank conversation cursor was rejected", impossibleBlankCursor);
    }
  }

  /**
   * Opens one stable page of the conversation with another user, oldest first, and marks the
   * incoming unread messages on that page as read.
   *
   * @param cursor the page boundary from a previous page, or blank for the newest messages
   * @throws InvalidRequestException if the cursor is malformed
   * @throws ResourceNotFoundException if either account does not exist
   */
  public ConversationPage openConversationPage(String otherUsername, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    ConversationParticipants participants = resolveParticipants(otherUsername);
    Optional<StableCursor> olderThan = cursorCodec.decode(cursor);
    ConversationMessageSlice newestFirstSlice =
        conversationQueries.page(participants.conversationKey(), olderThan, size);
    markIncomingMessagesRead(newestFirstSlice.items(), participants.self().getId());

    List<MessageDetail> oldestFirstDetails = new ArrayList<>();
    for (Message message : newestFirstSlice.items()) {
      oldestFirstDetails.addFirst(detailFor(message, participants));
    }
    return new ConversationPage(oldestFirstDetails, newestFirstSlice.nextCursor());
  }

  /** Lists the signed-in account's latest visible conversations with unread counts. */
  public List<ConversationSummary> listConversations(int limit) throws ResourceNotFoundException {
    Account self = signedInAccount();
    Map<String, Message> latestMessageByOtherAccountId = new LinkedHashMap<>();
    for (Message latestMessage : conversationQueries.latestDistinctVisible(self.getId(), limit)) {
      String otherAccountId = self.getId().equals(latestMessage.getSenderAccountId())
          ? latestMessage.getRecipientAccountId()
          : latestMessage.getSenderAccountId();
      latestMessageByOtherAccountId.put(otherAccountId, latestMessage);
    }
    Map<String, Account> otherAccountsById = new HashMap<>();
    for (Account otherAccount : accountRepository.findAllById(latestMessageByOtherAccountId.keySet())) {
      otherAccountsById.put(otherAccount.getId(), otherAccount);
    }
    Map<String, Long> unreadCountBySenderId =
        conversationQueries.unreadCounts(self.getId(), latestMessageByOtherAccountId.keySet());
    return latestMessageByOtherAccountId.entrySet().stream()
        .map(latestByOther -> summaryOf(
            latestByOther.getKey(),
            latestByOther.getValue(),
            otherAccountsById.get(latestByOther.getKey()),
            unreadCountBySenderId.getOrDefault(latestByOther.getKey(), 0L)))
        .toList();
  }

  /**
   * Archives the conversation with another user for the signed-in account only; the other
   * participant and the messages are unchanged.
   */
  public ConversationArchiveResult archiveConversationWith(String otherUsername)
      throws ResourceNotFoundException {
    ConversationParticipants participants = resolveParticipants(otherUsername);
    return conversationArchives.archive(
        participants.self().getId(),
        participants.conversationKey(),
        Set.of(participants.self().getId(), participants.other().getId()));
  }

  /** Sets {@code read} on the viewer's unread incoming messages and saves those that changed. */
  private void markIncomingMessagesRead(List<Message> messages, String viewerAccountId) {
    List<Message> newlyReadMessages = new ArrayList<>();
    for (Message message : messages) {
      boolean isUnreadIncoming = viewerAccountId.equals(message.getRecipientAccountId())
          && !Boolean.TRUE.equals(message.getRead());
      if (isUnreadIncoming) {
        message.setRead(true);
        newlyReadMessages.add(message);
      }
    }
    if (!newlyReadMessages.isEmpty()) {
      messageRepository.saveAll(newlyReadMessages);
    }
  }

  private static MessageDetail detailFor(Message message, ConversationParticipants participants) {
    return MessageDetail.from(
        message,
        participants.self().getId(),
        participants.usernameOf(message.getSenderAccountId()),
        participants.usernameOf(message.getRecipientAccountId()));
  }

  private static ConversationSummary summaryOf(
      String otherAccountId, Message latestMessage, Account otherAccount, long unreadCount) {
    return ConversationSummary.builder()
        .accountId(otherAccountId)
        .username(otherAccount == null ? null : otherAccount.getUsername())
        .displayName(displayNameOf(otherAccount))
        .latestText(latestMessage.getText())
        .lastMessageOn(latestMessage.getCreatedOn())
        .unreadCount(unreadCount)
        .build();
  }

  private Account signedInAccount() throws ResourceNotFoundException {
    String signedInAccountId = permissionService.getSelfId();
    return accountRepository
        .findById(signedInAccountId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", signedInAccountId)));
  }

  private ConversationParticipants resolveParticipants(String otherUsername)
      throws ResourceNotFoundException {
    Account self = signedInAccount();
    Account other = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(otherUsername))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", otherUsername)));
    return new ConversationParticipants(self, other, ConversationKeys.between(self.getId(), other.getId()));
  }

  /** First and last name when either is present, otherwise the username. */
  private static String displayNameOf(Account account) {
    if (account == null) {
      return null;
    }
    return Stream.of(account.getFirstName(), account.getLastName())
        .filter(namePart -> namePart != null && !namePart.isBlank())
        .reduce((firstPart, secondPart) -> firstPart + " " + secondPart)
        .orElse(account.getUsername());
  }

  private record ConversationParticipants(Account self, Account other, String conversationKey) {

    /** The username of whichever participant has the id, or {@code null} for anyone else. */
    String usernameOf(String accountId) {
      if (self.getId().equals(accountId)) {
        return self.getUsername();
      }
      if (other.getId().equals(accountId)) {
        return other.getUsername();
      }
      return null;
    }
  }
}
