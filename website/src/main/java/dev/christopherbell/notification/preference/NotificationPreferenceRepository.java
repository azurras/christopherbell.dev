package dev.christopherbell.notification.preference;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence boundary for account notification preferences. */
public interface NotificationPreferenceRepository
    extends MongoRepository<NotificationPreference, String> {
  Optional<NotificationPreference> findByAccountId(String accountId);
}
