package dev.christopherbell.notification.preference;

import java.util.Optional;

/** Persistence boundary for account notification preferences. */
public interface NotificationPreferenceRepository {
  NotificationPreference save(NotificationPreference preference);
  Optional<NotificationPreference> findByAccountId(String accountId);
}
