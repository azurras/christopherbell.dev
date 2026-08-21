package dev.christopherbell.message.conversation;

import java.util.Set;

/** Persistence-neutral owner-scoped conversation archive transition. */
public interface ConversationArchivePort {
  ConversationArchiveResult archive(
      String ownerAccountId, String conversationKey, Set<String> participantIds);
}
