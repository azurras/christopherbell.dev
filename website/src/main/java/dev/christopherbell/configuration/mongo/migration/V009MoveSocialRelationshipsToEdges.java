package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.account.follow.AccountFollow;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.post.like.PostLike;
import dev.christopherbell.post.like.PostLikeStore;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Copies embedded social relationships into unique edge collections. */
@MongoPersistence
@Component
public final class V009MoveSocialRelationshipsToEdges implements ApplicationMigration {
  private static final int BATCH_SIZE = 250;
  private static final String CHECKSUM =
      "cc4b05a835d9ae8ed63ac4c1b52b28cf745b17d45e1a6530bb8cd1f3e9015c62";

  @Override
  public String id() {
    return "009-move-social-relationships-to-edges";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Move post likes and account follows to unique edge collections";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    ensureIndexes(mongo);
    migrateLikes(mongo);
    migrateFollows(mongo);
    mongo.updateMulti(new Query(), new Update().unset("likedBy"), "posts");
    mongo.updateMulti(new Query(), new Update().unset("followingIds"), "accounts");
  }

  private static void ensureIndexes(MongoTemplate mongo) {
    var postIndexes = mongo.indexOps("posts");
    if (postIndexes.getIndexInfo().stream()
        .anyMatch(index -> "void_people_kept_alive_activity".equals(index.getName()))) {
      postIndexes.dropIndex("void_people_kept_alive_activity");
    }
    mongo.indexOps(PostLike.COLLECTION).createIndex(new Index()
        .on("postId", Sort.Direction.ASC)
        .on("accountId", Sort.Direction.ASC)
        .unique()
        .named("post_like_post_account_unique"));
    mongo.indexOps(AccountFollow.COLLECTION).createIndex(new Index()
        .on("followerAccountId", Sort.Direction.ASC)
        .on("followedAccountId", Sort.Direction.ASC)
        .unique()
        .named("account_follow_follower_target_unique"));
    mongo.indexOps(AccountFollow.COLLECTION).createIndex(new Index()
        .on("followedAccountId", Sort.Direction.ASC)
        .named("account_follow_target"));
  }

  private static void migrateLikes(MongoTemplate mongo) {
    forEachBatch(mongo, "posts", batch -> {
      for (var post : batch) {
        var postId = post.getString("_id");
        var accountIds = distinctStrings(post.get("likedBy"));
        for (var accountId : accountIds) {
          mongo.upsert(
              new Query(Criteria.where("_id").is(PostLikeStore.edgeId(postId, accountId))),
              new Update()
                  .setOnInsert("postId", postId)
                  .setOnInsert("accountId", accountId)
                  .setOnInsert("createdOn", Instant.EPOCH),
              PostLike.class);
        }
        mongo.updateFirst(
            new Query(Criteria.where("_id").is(postId)),
            new Update().set("likesCount", accountIds.size()),
            "posts");
      }
    });
  }

  private static void migrateFollows(MongoTemplate mongo) {
    forEachBatch(mongo, "accounts", batch -> {
      for (var account : batch) {
        var followerId = account.getString("_id");
        for (var followedId : distinctStrings(account.get("followingIds"))) {
          mongo.upsert(
              new Query(Criteria.where("_id").is(AccountFollowStore.edgeId(followerId, followedId))),
              new Update()
                  .setOnInsert("followerAccountId", followerId)
                  .setOnInsert("followedAccountId", followedId)
                  .setOnInsert("createdOn", Instant.EPOCH),
              AccountFollow.class);
        }
      }
    });
  }

  private static void forEachBatch(
      MongoTemplate mongo,
      String collection,
      java.util.function.Consumer<List<Document>> consumer
  ) {
    String lastId = null;
    while (true) {
      var criteria = lastId == null ? new Criteria() : Criteria.where("_id").gt(lastId);
      var query = new Query(criteria)
          .with(Sort.by(Sort.Direction.ASC, "_id"))
          .limit(BATCH_SIZE);
      var batch = mongo.find(query, Document.class, collection);
      if (batch.isEmpty()) {
        return;
      }
      consumer.accept(batch);
      lastId = batch.get(batch.size() - 1).getString("_id");
    }
  }

  private static LinkedHashSet<String> distinctStrings(Object raw) {
    var result = new LinkedHashSet<String>();
    if (raw instanceof Iterable<?> values) {
      values.forEach(value -> {
        if (value instanceof String text && !text.isBlank()) {
          result.add(text);
        }
      });
    }
    return result;
  }
}
