package dev.christopherbell.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** Identical notification ordering and unread assertions run against both engines. */
interface NotificationRepositoryParityContract {
  Instant CREATED = Instant.parse("2026-08-13T14:30:00Z");
  NotificationRepository parityNotifications();

  @Test
  default void parityPreservesOwnerOrderingAndUnreadCounts() {
    parityNotifications().save(notification("notification-parity-a", CREATED));
    parityNotifications().save(notification("notification-parity-b", CREATED.plusSeconds(1)));
    assertThat(parityNotifications().findByAccountIdOrderByCreatedOnDesc(
        "notification-parity-owner", PageRequest.of(0, 10)))
        .extracting(Notification::getId)
        .containsExactly("notification-parity-b", "notification-parity-a");
    assertThat(parityNotifications().countByAccountIdAndReadFalse("notification-parity-owner"))
        .isEqualTo(2);
  }

  private static Notification notification(String id, Instant createdOn) {
    return Notification.builder().id(id).accountId("notification-parity-owner")
        .actorAccountId("notification-parity-actor").actorUsername("actor")
        .notificationType(NotificationType.MENTION).read(false).createdOn(createdOn).build();
  }
}
