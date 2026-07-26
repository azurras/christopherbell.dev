package dev.christopherbell.admin.activity;

import java.time.Instant;

/** Bounded filters for append-only moderation audit pages. */
public record AdminActivityQuery(
    String action,
    String targetType,
    String actor,
    Instant from,
    Instant to,
    int page,
    int size) {}
