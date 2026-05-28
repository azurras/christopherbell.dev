package dev.christopherbell.message;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.message.conversation.ConversationService;
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
  public MessageDetail sendMessage(MessageCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    return messageDeliveryService.sendMessage(request);
  }

  /** Delegates thread reads and read-state updates to the conversation subfeature. */
  public List<MessageDetail> getConversation(String username, int limit)
      throws ResourceNotFoundException {
    return conversationService.getConversation(username, limit);
  }

  /** Delegates conversation summary reads to the conversation subfeature. */
  public List<ConversationSummary> getConversations(int limit) throws ResourceNotFoundException {
    return conversationService.getConversations(limit);
  }
}
