package dev.christopherbell.message.conversation;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.message.model.Message;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence-neutral conversation summary, unread-count, and history query boundary. */
public interface ConversationQueryPort {
  List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit);

  Map<String, Long> unreadCounts(
      String recipientAccountId, Collection<String> senderAccountIds);

  ConversationMessageSlice page(
      String conversationKey, Optional<StableCursor> cursor, int requestedSize);
}
