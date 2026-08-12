package dev.christopherbell.notification.preference;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoNotificationPreferenceRepository
    extends KindScopedRepositorySupport<NotificationPreference>
    implements NotificationPreferenceRepository {
  MongoNotificationPreferenceRepository(DomainMongoOperationsFactory factory) {
    super(factory, NotificationPreference.class);
  }

  @Override public NotificationPreference save(NotificationPreference value) {
    return saveValue(value);
  }
  @Override public Optional<NotificationPreference> findByAccountId(String accountId) {
    return findOne(Query.query(Criteria.where("accountId").is(accountId)));
  }
}
