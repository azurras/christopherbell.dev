package dev.christopherbell.federation.outbound;

import java.time.Instant;
import java.util.Objects;

/** Stable ascending post boundary committed only after job upserts complete. */
record FederationScanCursor(Instant createdOn, String postId) {
  FederationScanCursor {
    Objects.requireNonNull(createdOn, "createdOn");
    if (postId == null || postId.isBlank()) {
      throw new IllegalArgumentException("Federation scan post ID must not be blank");
    }
  }
}
