package dev.christopherbell.message.conversation;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.permission.PermissionService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Handles conversation reads, summaries, and read-state updates. */
@RequiredArgsConstructor
@Service
public class ConversationService {
  private final MessageRepository messageRepository;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;

  /**
   * Loads a conversation with another user and marks incoming unread messages as read.
   */
  public List<MessageDetail> getConversation(String username, int limit)
      throws ResourceNotFoundException {
    var self = getSelfAccount();
    var other = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(username))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", username)));
    var pageSize = Math.max(1, Math.min(limit, 100));
    var page = PageRequest.of(0, pageSize);
    var messages = messageRepository
        .findByConversationKeyOrderByCreatedOnAsc(conversationKey(self.getId(), other.getId()), page)
        .stream()
        .sorted(Comparator.comparing(Message::getCreatedOn))
        .toList();

    var changed = messages.stream()
        .filter(message -> self.getId().equals(message.getRecipientAccountId()))
        .filter(message -> !Boolean.TRUE.equals(message.getRead()))
        .peek(message -> message.setRead(true))
        .toList();
    if (!changed.isEmpty()) {
      messageRepository.saveAll(changed);
    }

    return messages.stream()
        .map(message -> toDetail(message, self.getId(), Map.of(self.getId(), self, other.getId(), other)))
        .toList();
  }

  /**
   * Lists the current user's latest conversations with unread counts.
   */
  public List<ConversationSummary> getConversations(int limit) throws ResourceNotFoundException {
    var self = getSelfAccount();
    var pageSize = Math.max(1, Math.min(limit, 50));
    var page = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdOn"));
    var latestByOtherId = new LinkedHashMap<String, Message>();
    for (var message : messageRepository.findByParticipantIdsContainingOrderByCreatedOnDesc(self.getId(), page)) {
      var otherId = self.getId().equals(message.getSenderAccountId())
          ? message.getRecipientAccountId()
          : message.getSenderAccountId();
      latestByOtherId.putIfAbsent(otherId, message);
      if (latestByOtherId.size() >= pageSize) {
        break;
      }
    }

    var accounts = accountRepository.findAllById(latestByOtherId.keySet());
    var accountById = new HashMap<String, Account>();
    accounts.forEach(account -> accountById.put(account.getId(), account));
    return latestByOtherId.entrySet().stream()
        .map(entry -> summary(entry.getKey(), entry.getValue(), self, accountById.get(entry.getKey())))
        .toList();
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
}
