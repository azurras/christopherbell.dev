package dev.christopherbell.post.expiration;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.post.like.PostLike;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Mongo atomic storage effects for post expiration. */
@MongoPersistence
public final class MongoPostExpirationStore implements PostExpirationStore {
  private final KindScopedMongoOperations<Post> posts;
  private final KindScopedMongoOperations<PostLike> likes;

  public MongoPostExpirationStore(DomainMongoOperationsFactory factory) {
    this.posts = factory.forType(Post.class);
    this.likes = factory.forType(PostLike.class);
  }

  @Override
  public void synchronizeReplies(String rootId, String rootPostId, Instant expiresOn) {
    posts.updateMulti(
        new Query(new Criteria().andOperator(
            Criteria.where("rootId").is(rootId),
            Criteria.where("id").ne(rootPostId),
            Criteria.where("parentId").ne(null))),
        new Update().set("expiresOn", expiresOn));
  }

  @Override
  public Optional<Post> incrementCounter(
      String postId, String field, int delta, Instant changedOn, boolean extended) {
    var criteria = Criteria.where("id").is(postId);
    if (delta < 0) {
      criteria = new Criteria().andOperator(criteria, Criteria.where(field).gt(0));
    }
    var update = new Update().inc(field, delta).set("lastUpdatedOn", changedOn);
    if (extended) update.set("lastExtendedOn", changedOn);
    return posts.findAndUpdate(new Query(criteria), update)
        .or(() -> posts.findById(postId));
  }

  @Override
  public long deletePosts(List<String> postIds) {
    likes.remove(new Query(Criteria.where("postId").in(postIds)));
    return posts.remove(new Query(Criteria.where("id").in(postIds))).getDeletedCount();
  }

  @Override
  public Optional<Post> decrementFloorZero(
      String postId, String field, int delta, Instant changedOn) {
    return posts.decrementFloorZeroById(postId, field, delta, "lastUpdatedOn", changedOn)
        .or(() -> posts.findById(postId));
  }

  @Override
  public void updateExpiration(String postId, Instant expiresOn) {
    posts.updateFirst(
        new Query(Criteria.where("id").is(postId)),
        new Update().set("expiresOn", expiresOn));
  }
}
