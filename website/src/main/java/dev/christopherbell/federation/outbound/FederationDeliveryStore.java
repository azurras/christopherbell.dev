package dev.christopherbell.federation.outbound;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import java.time.Instant;
import java.util.Optional;

/** Owns durable scan, idempotent enqueue, claim, and exact-owner delivery transitions. */
interface FederationDeliveryStore {
  FederationScanCursor loadCursor();

  void enqueueIfAbsent(String postId, String accountId, ControlledPeer peer, Instant now);

  void saveCursor(FederationScanCursor cursor, Instant now);

  Optional<FederationDeliveryJob> claimDue(String owner, Instant now, Instant leaseUntil);

  boolean succeed(String jobId, String owner, int status, Instant now);

  boolean retry(
      String jobId, String owner, Integer status, Instant nextAttempt, Instant now);

  boolean dead(String jobId, String owner, Integer status, String reason, Instant now);

  boolean cancel(String jobId, String owner, String reason, Instant now);
}
