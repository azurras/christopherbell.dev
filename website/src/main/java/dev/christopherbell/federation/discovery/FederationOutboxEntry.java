package dev.christopherbell.federation.discovery;

import java.time.Instant;

/** Persistence-neutral public post data required to build one federation outbox item. */
public record FederationOutboxEntry(
    String id,
    String text,
    String parentId,
    Instant createdOn,
    Instant lastUpdatedOn) {}
