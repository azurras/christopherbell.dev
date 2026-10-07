package dev.christopherbell.message;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.message.conversation.ConversationService;
import dev.christopherbell.message.conversation.ConversationArchiveResult;
import dev.christopherbell.message.conversation.ConversationPage;
import dev.christopherbell.message.delivery.MessageDeliveryService;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade that preserves the message API service surface while subfeatures own behavior. */
@RequiredArgsConstructor
@Service
public class MessageService {
  private final MessageDeliveryService messageDeliveryService;
  private final ConversationService conversationService;

  /** Delegates direct-message sending to the delivery subfeature. */
  public MessageDetail sendMessage(MessageCreateRequest createRequest)
      throws InvalidRequestException, ResourceNotFoundException {
    return messageDeliveryService.sendMessage(createRequest);
  }

  /** Opens a conversation and marks its incoming messages read. */
  public List<MessageDetail> openConversation(String otherUsername, int limit)
      throws ResourceNotFoundException {
    return conversationService.openConversation(otherUsername, limit);
  }

  /** Lists the signed-in account's latest conversations. */
  public List<ConversationSummary> listConversations(int limit) throws ResourceNotFoundException {
    return conversationService.listConversations(limit);
  }

  /** Opens one stable page of a conversation and marks its incoming messages read. */
  public ConversationPage openConversationPage(String otherUsername, String cursor, int size)
      throws InvalidRequestException, ResourceNotFoundException {
    return conversationService.openConversationPage(otherUsername, cursor, size);
  }

  /** Archives only the current account's view of a conversation. */
  public ConversationArchiveResult archiveConversationWith(String otherUsername)
      throws ResourceNotFoundException {
    return conversationService.archiveConversationWith(otherUsername);
  }
}
