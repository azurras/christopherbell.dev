package dev.christopherbell.message;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.notification.NotificationService;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class MessageService {
  private static final int MAX_MESSAGE_LENGTH = 1000;

  private final MessageRepository messageRepository;
  private final AccountRepository accountRepository;
  private final NotificationService notificationService;

  public MessageDetail sendMessage(MessageCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    if (request == null || request.recipientUsername() == null || request.recipientUsername().isBlank()) {
      throw new InvalidRequestException("Recipient username cannot be null or blank.");
    }
    if (request.text() == null || request.text().isBlank()) {
      throw new InvalidRequestException("Message text cannot be null or blank.");
    }
    var text = request.text().trim();
    if (text.length() > MAX_MESSAGE_LENGTH) {
      throw new InvalidRequestException("Message text exceeds 1000 characters.");
    }

    var sender = getSelfAccount();
    var recipient = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(request.recipientUsername()))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", request.recipientUsername())));
    if (sender.getId().equals(recipient.getId())) {
      throw new InvalidRequestException("You cannot message yourself.");
    }

    var now = Instant.now();
    var message = Message.builder()
        .id(UUID.randomUUID().toString())
        .conversationKey(conversationKey(sender.getId(), recipient.getId()))
        .participantIds(new HashSet<>(List.of(sender.getId(), recipient.getId())))
        .senderAccountId(sender.getId())
        .recipientAccountId(recipient.getId())
        .text(text)
        .read(false)
        .createdOn(now)
        .build();
    var saved = messageRepository.save(message);
    notificationService.createMessageNotification(saved, sender, recipient);
    return toDetail(saved, sender.getId(), Map.of(sender.getId(), sender, recipient.getId(), recipient));
  }

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
        .map(entry -> {
          var other = accountById.get(entry.getKey());
          var message = entry.getValue();
          return ConversationSummary.builder()
              .accountId(entry.getKey())
              .username(other == null ? null : other.getUsername())
              .displayName(displayName(other))
              .latestText(message.getText())
              .lastMessageOn(message.getCreatedOn())
              .unreadCount(messageRepository.countByRecipientAccountIdAndSenderAccountIdAndReadFalse(
                  self.getId(),
                  entry.getKey()))
              .build();
        })
        .toList();
  }

  private Account getSelfAccount() throws ResourceNotFoundException {
    var selfId = getSelfId();
    return accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));
  }

  String getSelfId() {
    return PermissionService.getSelf();
  }

  private static String conversationKey(String firstAccountId, String secondAccountId) {
    return firstAccountId.compareTo(secondAccountId) < 0
        ? firstAccountId + ":" + secondAccountId
        : secondAccountId + ":" + firstAccountId;
  }

  private MessageDetail toDetail(Message message, String selfId, Map<String, Account> accounts) {
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
