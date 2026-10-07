package dev.christopherbell.message.conversation;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.message.model.Message;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence-neutral conversation summary, unread-count, and history query boundary. */
public interface ConversationQueryPort {

  /** The newest message of each conversation the owner can see, newest first. */
  List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit);

  /** Unread incoming message counts for the recipient, keyed by sender account id. */
  Map<String, Long> unreadCounts(String recipientAccountId, Collection<String> senderAccountIds);

  /**
   * One stable newest-first slice of a conversation, starting after the cursor when present.
   * Matches the federation outbox and notification query ports.
   */
  ConversationMessageSlice page(
      String conversationKey, Optional<StableCursor> olderThan, int requestedSize);
}
