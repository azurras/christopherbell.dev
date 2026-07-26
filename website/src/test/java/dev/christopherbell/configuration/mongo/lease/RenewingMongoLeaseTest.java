package dev.christopherbell.configuration.mongo.lease;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RenewingMongoLeaseTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

  @Test
  void renewsAtHalfDurationAndFailsClosedWhenOwnershipIsLost() {
    var clock = Mockito.mock(Clock.class);
    var leases = Mockito.mock(MongoLeaseService.class);
    when(clock.instant()).thenReturn(NOW.plusSeconds(59), NOW.plusSeconds(60));
    when(leases.renew("collector:test", "owner-1", NOW.plusSeconds(60), NOW.plusSeconds(180)))
        .thenReturn(false);
    var lease = new RenewingMongoLease(
        leases, clock, "collector:test", "owner-1", Duration.ofMinutes(2), NOW);

    lease.verifyHeld();
    verify(leases, never()).renew("collector:test", "owner-1", NOW.plusSeconds(59), NOW.plusSeconds(179));

    assertThatThrownBy(lease::verifyHeld)
        .isInstanceOf(LeaseOwnershipLostException.class);
  }
}
