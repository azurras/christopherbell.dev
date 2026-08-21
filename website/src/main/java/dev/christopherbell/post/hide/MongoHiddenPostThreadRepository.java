package dev.christopherbell.post.hide;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoHiddenPostThreadRepository extends KindScopedRepositorySupport<HiddenPostThread>
    implements HiddenPostThreadRepository {
  MongoHiddenPostThreadRepository(DomainMongoOperationsFactory factory) {
    super(factory, HiddenPostThread.class);
  }
  @Override public HiddenPostThread save(HiddenPostThread value) { return saveValue(value); }
  @Override public Optional<HiddenPostThread> findByAccountIdAndRootPostId(
      String accountId, String rootId) {
    return findOne(Query.query(exact(accountId, rootId)));
  }
  @Override public List<HiddenPostThread> findByAccountId(String accountId) {
    return find(Query.query(Criteria.where("accountId").is(accountId)));
  }
  @Override public void deleteByAccountIdAndRootPostId(String accountId, String rootId) {
    mongo.remove(Query.query(exact(accountId, rootId)));
  }
  private static Criteria exact(String accountId, String rootId) {
    return Criteria.where("accountId").is(accountId).and("rootPostId").is(rootId);
  }
}
