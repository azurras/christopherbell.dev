package dev.christopherbell.message.model;

import lombok.Builder;

@Builder
public record MessageCreateRequest(
    String recipientUsername,
    String text
) {}
