package dev.christopherbell.account.follow;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Atomic persistence and bounded queries for account-follow edges. */
@Repository
@RequiredArgsConstructor
public class AccountFollowStore {
  private final MongoTemplate mongo;

  public FollowTransition follow(String followerId, String followedId, Instant createdOn) {
    var result = mongo.upsert(
        exact(followerId, followedId),
        new Update()
            .setOnInsert("_id", edgeId(followerId, followedId))
            .setOnInsert("followerAccountId", followerId)
            .setOnInsert("followedAccountId", followedId)
            .setOnInsert("createdOn", createdOn),
        AccountFollow.class);
    return new FollowTransition(result.getUpsertedId() != null, false);
  }

  public FollowTransition unfollow(String followerId, String followedId) {
    var result = mongo.remove(exact(followerId, followedId), AccountFollow.class);
    return new FollowTransition(false, result.getDeletedCount() > 0);
  }

  public boolean exists(String followerId, String followedId) {
    return mongo.exists(exact(followerId, followedId), AccountFollow.class);
  }

  public long countFollowing(String accountId) {
    return mongo.count(
        new Query(Criteria.where("followerAccountId").is(accountId)), AccountFollow.class);
  }

  public long countFollowers(String accountId) {
    return mongo.count(
        new Query(Criteria.where("followedAccountId").is(accountId)), AccountFollow.class);
  }

  public List<String> followedAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followerAccountId").is(accountId)).with(page);
    query.fields().include("followedAccountId");
    return mongo.find(query, AccountFollow.class).stream()
        .map(AccountFollow::getFollowedAccountId)
        .toList();
  }

  public List<String> followerAccountIds(String accountId, Pageable page) {
    var query = new Query(Criteria.where("followedAccountId").is(accountId)).with(page);
    query.fields().include("followerAccountId");
    return mongo.find(query, AccountFollow.class).stream()
        .map(AccountFollow::getFollowerAccountId)
        .toList();
  }

  public void deleteForAccount(String accountId) {
    mongo.remove(new Query(new Criteria().orOperator(
        Criteria.where("followerAccountId").is(accountId),
        Criteria.where("followedAccountId").is(accountId))), AccountFollow.class);
  }

  public static String edgeId(String followerId, String followedId) {
    return followerId + ":" + followedId;
  }

  private static Query exact(String followerId, String followedId) {
    return new Query(Criteria.where("_id").is(edgeId(followerId, followedId)));
  }

  public record FollowTransition(boolean created, boolean removed) {}
}
