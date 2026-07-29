package dev.christopherbell.federation.outbound;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Singleton durable cursor for ascending eligible-post reconciliation. */
@Document("federation_scan_state")
record FederationScanState(@Id String id, Instant createdOn, String postId, Instant updatedOn) {
  static final String OUTBOUND_CREATE = "outbound-create";

  FederationScanCursor cursor() {
    return createdOn == null || postId == null ? null : new FederationScanCursor(createdOn, postId);
  }
}
