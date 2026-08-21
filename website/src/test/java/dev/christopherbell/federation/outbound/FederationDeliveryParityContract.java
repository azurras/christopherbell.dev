package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.post.model.Post;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Shared federation cursor, enqueue, claim, and owner-fencing behavior for both engines. */
interface FederationDeliveryParityContract {
  String RUN = java.util.UUID.randomUUID().toString();

  FederationDeliveryStore deliveries();

  Post deliveryPost();

  @Test
  default void cursorEnqueueClaimAndOwnerFencingAreIdempotent() {
    var now = Instant.now();
    var cursor = new FederationScanCursor(deliveryPost().getCreatedOn(), deliveryPost().getId());
    deliveries().saveCursor(cursor, now);
    assertThat(deliveries().loadCursor()).isEqualTo(cursor);

    var peer = new ControlledPeer("parity-" + RUN, URI.create("https://peer.example/inbox"));
    deliveries().enqueueIfAbsent(
        deliveryPost().getId(), deliveryPost().getAccountId(), peer, now.minusSeconds(1));
    deliveries().enqueueIfAbsent(
        deliveryPost().getId(), deliveryPost().getAccountId(), peer, now.minusSeconds(1));
    var claimed = deliveries().claimDue("worker-a", now, now.plusSeconds(30)).orElseThrow();
    assertThat(claimed.attempts()).isOne();
    assertThat(deliveries().succeed(claimed.id(), "worker-b", 202, now.plusSeconds(1)))
        .isFalse();
    assertThat(deliveries().succeed(claimed.id(), "worker-a", 202, now.plusSeconds(1)))
        .isTrue();
    assertThat(deliveries().claimDue("worker-c", now, now.plusSeconds(30))).isEmpty();
  }
}
