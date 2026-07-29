package dev.christopherbell.federation.outbound;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Bounded durable metadata for one post/peer Create delivery; never stores payloads or keys. */
@Document("federation_delivery_jobs")
record FederationDeliveryJob(
    @Id String id,
    String postId,
    String accountId,
    String peerName,
    String peerInbox,
    FederationDeliveryState state,
    int attempts,
    Instant nextAttemptOn,
    String claimOwner,
    Instant claimUntil,
    Integer lastStatus,
    String lastOutcome,
    Instant createdOn,
    Instant updatedOn
) {}
