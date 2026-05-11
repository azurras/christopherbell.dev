package dev.christopherbell.message.model;

import java.time.Instant;
import lombok.Builder;

@Builder
public record MessageDetail(
    String id,
    String senderAccountId,
    String senderUsername,
    String recipientAccountId,
    String recipientUsername,
    String text,
    Boolean read,
    Boolean mine,
    Instant createdOn
) {}
