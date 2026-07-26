package dev.christopherbell.message.conversation;

import java.time.Instant;

/** Public confirmation that only the caller's conversation view was archived. */
public record ConversationArchiveResult(String conversationKey, Instant archivedAt) {}
