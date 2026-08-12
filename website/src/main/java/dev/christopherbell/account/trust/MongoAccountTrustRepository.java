package dev.christopherbell.account.trust;

import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
final class MongoAccountTrustRepository
    extends KindScopedRepositorySupport<AccountTrustRelationship>
    implements AccountTrustRepository {
  MongoAccountTrustRepository(DomainMongoOperationsFactory factory) {
    super(factory, AccountTrustRelationship.class);
  }

  @Override public AccountTrustRelationship save(AccountTrustRelationship value) {
    return saveValue(value);
  }
  @Override
  public Optional<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdAndType(
      String owner, String target, AccountTrustType type) {
    return findOne(Query.query(exact(owner, target, type)));
  }
  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTypeIn(
      String owner, Collection<AccountTrustType> types) {
    return find(Query.query(Criteria.where("ownerAccountId").is(owner).and("type").in(types)));
  }
  @Override
  public List<AccountTrustRelationship> findByTargetAccountIdAndOwnerAccountIdInAndType(
      String target, Collection<String> owners, AccountTrustType type) {
    return find(Query.query(Criteria.where("targetAccountId").is(target)
        .and("ownerAccountId").in(owners).and("type").is(type)));
  }
  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
      String owner, Collection<String> targets, Collection<AccountTrustType> types) {
    return find(Query.query(Criteria.where("ownerAccountId").is(owner)
        .and("targetAccountId").in(targets).and("type").in(types)));
  }
  @Override
  public boolean existsByOwnerAccountIdAndTargetAccountIdAndType(
      String owner, String target, AccountTrustType type) {
    return mongo.exists(Query.query(exact(owner, target, type)));
  }
  @Override
  public void deleteByOwnerAccountIdAndTargetAccountIdAndType(
      String owner, String target, AccountTrustType type) {
    mongo.remove(Query.query(exact(owner, target, type)));
  }

  private static Criteria exact(String owner, String target, AccountTrustType type) {
    return Criteria.where("ownerAccountId").is(owner)
        .and("targetAccountId").is(target).and("type").is(type);
  }
}
