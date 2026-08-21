package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the WFL session persistence port. */
@MongoPersistence
@Repository
public class MongoWhatsForLunchSessionRepository
    extends KindScopedRepositorySupport<WhatsForLunchSession>
    implements WhatsForLunchSessionRepository {
  public MongoWhatsForLunchSessionRepository(DomainMongoOperationsFactory factory) {
    super(factory, WhatsForLunchSession.class);
  }
  @Override public WhatsForLunchSession save(WhatsForLunchSession session) {
    return saveValue(session);
  }
  @Override public Optional<WhatsForLunchSession> findById(String id) {
    return findValueById(id);
  }
  @Override public List<WhatsForLunchSession>
      findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
          String accountId, Instant now, Pageable pageable) {
    return find(Query.query(Criteria.where("participantAccountIds").is(accountId)
        .and("deleteOn").gt(now)).with(Sort.by(Sort.Direction.DESC, "createdOn")), pageable);
  }
}
