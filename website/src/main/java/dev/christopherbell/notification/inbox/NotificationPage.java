package dev.christopherbell.notification.inbox;

import dev.christopherbell.notification.model.NotificationDetail;
import java.util.List;

/** One stable newest-first notification page. */
public record NotificationPage(List<NotificationDetail> items, String nextCursor) {
  public NotificationPage {
    items = List.copyOf(items);
  }
}
