package dev.christopherbell.message.delivery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Handles direct-message creation and notification handoff. */
@RequiredArgsConstructor
@Service
public class MessageDeliveryService {
  private static final int MAX_MESSAGE_LENGTH = 1000;

  private final MessageRepository messageRepository;
  private final AccountRepository accountRepository;
  private final NotificationDeliveryService notificationDeliveryService;
  private final PermissionService permissionService;
  private final AccountTrustService accountTrustService;

  /**
   * Sends one direct message from the current account to another account.
   */
  public MessageDetail sendMessage(MessageCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    validateRequest(request);

    var sender = getSelfAccount();
    ensureActiveSender(sender);
    var recipient = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(request.recipientUsername()))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", request.recipientUsername())));
    if (sender.getId().equals(recipient.getId())) {
      throw new InvalidRequestException("You cannot message yourself.");
    }
    if (accountTrustService.isBlockedEitherDirection(sender.getId(), recipient.getId())) {
      throw new InvalidRequestException("Messages are not available between these accounts.");
    }

    var message = Message.builder()
        .id(UUID.randomUUID().toString())
        .conversationKey(conversationKey(sender.getId(), recipient.getId()))
        .participantIds(new HashSet<>(List.of(sender.getId(), recipient.getId())))
        .senderAccountId(sender.getId())
        .recipientAccountId(recipient.getId())
        .text(request.text().trim())
        .read(false)
        .createdOn(Instant.now())
        .build();
    var saved = messageRepository.save(message);
    notificationDeliveryService.createMessageNotification(saved, sender, recipient);
    return toDetail(saved, sender.getId(), Map.of(sender.getId(), sender, recipient.getId(), recipient));
  }

  private static void validateRequest(MessageCreateRequest request) throws InvalidRequestException {
    if (request == null || request.recipientUsername() == null || request.recipientUsername().isBlank()) {
      throw new InvalidRequestException("Recipient username cannot be null or blank.");
    }
    if (request.text() == null || request.text().isBlank()) {
      throw new InvalidRequestException("Message text cannot be null or blank.");
    }
    if (request.text().trim().length() > MAX_MESSAGE_LENGTH) {
      throw new InvalidRequestException("Message text exceeds 1000 characters.");
    }
  }

  private Account getSelfAccount() throws ResourceNotFoundException {
    var selfId = permissionService.getSelfId();
    return accountRepository
        .findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException(String.format("Account with id %s not found.", selfId)));
  }

  private static void ensureActiveSender(Account sender) throws InvalidRequestException {
    if (sender.getStatus() == AccountStatus.SUSPENDED) {
      throw new InvalidRequestException("Suspended accounts cannot send messages.");
    }
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
}
