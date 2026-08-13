package dev.christopherbell.account.follow;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

/** Atomic persistence and bounded queries for account-follow edges. */
@MongoPersistence
@Repository
public class AccountFollowStore {
  private final KindScopedMongoOperations<AccountFollow> mongo;

  public AccountFollowStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(AccountFollow.class);
  }

  public FollowTransition follow(String followerId, String followedId, Instant createdOn) {
    try {
      mongo.insert(AccountFollow.builder()
          .id(edgeId(followerId, followedId))
          .followerAccountId(followerId)
          .followedAccountId(followedId)
          .createdOn(createdOn)
          .build());
      return new FollowTransition(true, false);
    } catch (DuplicateKeyException duplicate) {
      return new FollowTransition(false, false);
    }
  }

  public FollowTransition unfollow(String followerId, String followedId) {
    var result = mongo.remove(exact(followerId, followedId));
    return new FollowTransition(false, result.getDeletedCount() > 0);
  }

  public boolean exists(String followerId, String followedId) {
    return mongo.exists(exact(followerId, followedId));
  }

  public long countFollowing(String accountId) {
    return mongo.count(new Query(Criteria.where("followerAccountId").is(accountId)));
  }

  public long countFollowers(String accountId) {
    return mongo.count(new Query(Criteria.where("followedAccountId").is(accountId)));
  }

  public List<String> followedAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followerAccountId").is(accountId)).with(page);
    return mongo.find(query, Pageable.unpaged()).stream()
        .map(AccountFollow::getFollowedAccountId)
        .toList();
  }

  public List<String> followerAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followedAccountId").is(accountId)).with(page);
    return mongo.find(query, Pageable.unpaged()).stream()
        .map(AccountFollow::getFollowerAccountId)
        .toList();
  }

  public void deleteForAccount(String accountId) {
    mongo.remove(new Query(new Criteria().orOperator(
        Criteria.where("followerAccountId").is(accountId),
        Criteria.where("followedAccountId").is(accountId))));
  }

  public static String edgeId(String followerId, String followedId) {
    return followerId + ":" + followedId;
  }

  private static Query exact(String followerId, String followedId) {
    return new Query(Criteria.where("id").is(edgeId(followerId, followedId)));
  }

  public record FollowTransition(boolean created, boolean removed) {}
}
