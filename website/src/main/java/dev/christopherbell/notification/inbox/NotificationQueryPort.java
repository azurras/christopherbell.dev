package dev.christopherbell.notification.inbox;

import dev.christopherbell.libs.pagination.StableCursor;
import java.util.Optional;

/** Persistence-neutral stable notification inbox query and update boundary. */
public interface NotificationQueryPort {
  NotificationPage page(String accountId, Optional<StableCursor> cursor, int requestedSize);

  NotificationReadResult markAllRead(String accountId);
}
