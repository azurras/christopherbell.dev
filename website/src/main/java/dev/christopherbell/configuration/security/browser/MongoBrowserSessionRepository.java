package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoBrowserSessionRepository extends KindScopedRepositorySupport<BrowserSession>
    implements BrowserSessionRepository {
  MongoBrowserSessionRepository(DomainMongoOperationsFactory factory) {
    super(factory, BrowserSession.class);
  }

  @Override public BrowserSession save(BrowserSession session) { return saveValue(session); }
  @Override public void delete(BrowserSession session) { super.deleteById(session.getId()); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public long deleteByAccountId(String accountId) {
    return mongo.remove(Query.query(Criteria.where("accountId").is(accountId))).getDeletedCount();
  }
}
