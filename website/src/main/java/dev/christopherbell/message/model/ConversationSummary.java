package dev.christopherbell.message.model;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ConversationSummary(
    String accountId,
    String username,
    String displayName,
    String latestText,
    Instant lastMessageOn,
    long unreadCount
) {}
