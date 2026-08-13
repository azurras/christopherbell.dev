package dev.christopherbell.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.notification.model.NotificationType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Identical notification-cleanup assertions run against both persistence engines. */
interface NotificationCleanupParityContract {
  Instant CUTOFF = Instant.parse("2026-08-13T15:00:00Z");

  NotificationFanoutPort parityFanout();

  @BeforeEach
  default void removeParityFixtures() {
    parityFanout().deleteExpired(Instant.parse("9999-12-31T23:59:59Z"), 100);
  }

  @Test
  default void parityCleanupIsObservableBatchLimitedAndIdempotent() {
    var expired = CUTOFF.minusSeconds(86_400);
    assertThat(parityFanout().tryAcquire(identity("a"), expired)).isPresent();
    assertThat(parityFanout().tryAcquire(identity("b"), expired)).isPresent();

    assertThat(parityFanout().deleteExpired(CUTOFF, 1))
        .extracting("guardsDeleted", "ratesDeleted").containsExactly(1, 1);
    assertThat(parityFanout().deleteExpired(CUTOFF, 1))
        .extracting("guardsDeleted", "ratesDeleted").containsExactly(1, 1);
    assertThat(parityFanout().deleteExpired(CUTOFF, 1).totalDeleted()).isZero();
  }

  @Test
  default void parityCleanupRejectsAnUnboundedBatch() {
    assertThatThrownBy(() -> parityFanout().deleteExpired(CUTOFF, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  private static NotificationEventIdentity identity(String suffix) {
    return new NotificationEventIdentity(
        "cleanup-recipient-" + suffix, "cleanup-actor", NotificationType.LIKE,
        "cleanup-target-" + suffix);
  }
}
