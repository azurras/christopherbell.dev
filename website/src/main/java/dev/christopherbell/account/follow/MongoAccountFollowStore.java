package dev.christopherbell.account.follow;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Mongo implementation of the account-follow persistence boundary. */
@MongoPersistence
public class MongoAccountFollowStore implements AccountFollowStore {
  private final KindScopedMongoOperations<AccountFollow> mongo;

  public MongoAccountFollowStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(AccountFollow.class);
  }

  @Override
  public FollowTransition follow(String followerId, String followedId, Instant createdOn) {
    try {
      mongo.insert(AccountFollow.builder()
          .id(AccountFollowStore.edgeId(followerId, followedId))
          .followerAccountId(followerId)
          .followedAccountId(followedId)
          .createdOn(createdOn)
          .build());
      return new FollowTransition(true, false);
    } catch (DuplicateKeyException duplicate) {
      return new FollowTransition(false, false);
    }
  }

  @Override
  public FollowTransition unfollow(String followerId, String followedId) {
    var result = mongo.remove(exact(followerId, followedId));
    return new FollowTransition(false, result.getDeletedCount() > 0);
  }

  @Override
  public boolean exists(String followerId, String followedId) {
    return mongo.exists(exact(followerId, followedId));
  }

  @Override
  public long countFollowing(String accountId) {
    return mongo.count(new Query(Criteria.where("followerAccountId").is(accountId)));
  }

  @Override
  public long countFollowers(String accountId) {
    return mongo.count(new Query(Criteria.where("followedAccountId").is(accountId)));
  }

  @Override
  public List<String> followedAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followerAccountId").is(accountId)).with(page);
    return mongo.find(query, Pageable.unpaged()).stream()
        .map(AccountFollow::getFollowedAccountId)
        .toList();
  }

  @Override
  public List<String> followerAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followedAccountId").is(accountId)).with(page);
    return mongo.find(query, Pageable.unpaged()).stream()
        .map(AccountFollow::getFollowerAccountId)
        .toList();
  }

  @Override
  public void deleteForAccount(String accountId) {
    mongo.remove(new Query(new Criteria().orOperator(
        Criteria.where("followerAccountId").is(accountId),
        Criteria.where("followedAccountId").is(accountId))));
  }

  private static Query exact(String followerId, String followedId) {
    return new Query(Criteria.where("id").is(AccountFollowStore.edgeId(followerId, followedId)));
  }
}
