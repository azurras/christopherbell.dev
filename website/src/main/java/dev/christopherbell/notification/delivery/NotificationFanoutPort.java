package dev.christopherbell.notification.delivery;

import java.time.Instant;
import java.util.Optional;

/** Persistence-neutral atomic notification dedupe and rate-limit boundary. */
public interface NotificationFanoutPort {
  Optional<NotificationDeliveryPermit> tryAcquire(
      NotificationEventIdentity identity, Instant now);

  void release(NotificationDeliveryPermit permit);
}
