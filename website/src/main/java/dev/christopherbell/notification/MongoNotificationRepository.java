package dev.christopherbell.notification;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.notification.model.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoNotificationRepository extends KindScopedRepositorySupport<Notification>
    implements NotificationRepository {
  MongoNotificationRepository(DomainMongoOperationsFactory factory) {
    super(factory, Notification.class);
  }

  @Override public Notification save(Notification value) { return saveValue(value); }
  @Override public Optional<Notification> findById(String id) { return findValueById(id); }
  @Override
  public List<Notification> findByAccountIdOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    return find(Query.query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn")), pageable);
  }
  @Override public long countByAccountIdAndReadFalse(String accountId) {
    return mongo.count(Query.query(Criteria.where("accountId").is(accountId)
        .and("read").is(false)));
  }
}
