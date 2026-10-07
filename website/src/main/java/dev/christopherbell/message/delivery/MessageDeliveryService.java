package dev.christopherbell.message.delivery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.ConversationKeys;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
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
  private final Clock clock;

  /**
   * Sends one direct message from the signed-in account and notifies the recipient.
   *
   * @param createRequest the recipient's username and the message text
   * @return the stored message as the sender sees it
   * @throws InvalidRequestException if the recipient or text is missing, the text is longer than
   *     1000 characters, the sender is suspended, the recipient is the sender, or either account
   *     blocks the other
   * @throws ResourceNotFoundException if the sender or recipient account does not exist
   */
  public MessageDetail sendMessage(MessageCreateRequest createRequest)
      throws InvalidRequestException, ResourceNotFoundException {
    validateRequest(createRequest);
    Account sender = signedInAccount();
    rejectSuspendedSender(sender);
    Account recipient = accountRepository
        .findByUsername(UsernameSanitizer.sanitize(createRequest.recipientUsername()))
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with username %s not found.", createRequest.recipientUsername())));
    if (sender.getId().equals(recipient.getId())) {
      throw new InvalidRequestException("You cannot message yourself.");
    }
    if (accountTrustService.isBlockedEitherDirection(sender.getId(), recipient.getId())) {
      throw new InvalidRequestException("Messages are not available between these accounts.");
    }

    Message newMessage = Message.builder()
        .id(UUID.randomUUID().toString())
        .conversationKey(ConversationKeys.between(sender.getId(), recipient.getId()))
        .participantIds(new HashSet<>(List.of(sender.getId(), recipient.getId())))
        .senderAccountId(sender.getId())
        .recipientAccountId(recipient.getId())
        .text(createRequest.text().trim())
        .read(false)
        .createdOn(clock.instant())
        .build();
    Message storedMessage = messageRepository.save(newMessage);
    notificationDeliveryService.createMessageNotification(storedMessage, sender, recipient);
    return MessageDetail.from(
        storedMessage, sender.getId(), sender.getUsername(), recipient.getUsername());
  }

  private static void validateRequest(MessageCreateRequest createRequest)
      throws InvalidRequestException {
    if (createRequest == null || createRequest.recipientUsername() == null
        || createRequest.recipientUsername().isBlank()) {
      throw new InvalidRequestException("Recipient username cannot be null or blank.");
    }
    if (createRequest.text() == null || createRequest.text().isBlank()) {
      throw new InvalidRequestException("Message text cannot be null or blank.");
    }
    if (createRequest.text().trim().length() > MAX_MESSAGE_LENGTH) {
      throw new InvalidRequestException("Message text exceeds 1000 characters.");
    }
  }

  private Account signedInAccount() throws ResourceNotFoundException {
    String signedInAccountId = permissionService.getSelfId();
    return accountRepository
        .findById(signedInAccountId)
        .orElseThrow(() -> new ResourceNotFoundException(
            String.format("Account with id %s not found.", signedInAccountId)));
  }

  private static void rejectSuspendedSender(Account sender) throws InvalidRequestException {
    if (sender.getStatus() == AccountStatus.SUSPENDED) {
      throw new InvalidRequestException("Suspended accounts cannot send messages.");
    }
  }
}
