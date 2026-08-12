package dev.christopherbell.federation.outbound;

import java.time.Instant;
import org.springframework.data.annotation.Id;

/** Bounded durable metadata for one post/peer Create delivery; never stores payloads or keys. */
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
