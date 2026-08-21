package dev.christopherbell.post;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoPostRepository extends KindScopedRepositorySupport<Post>
    implements PostRepository {
  MongoPostRepository(DomainMongoOperationsFactory factory) { super(factory, Post.class); }

  @Override public Post save(Post post) { return saveValue(post); }
  @Override public Optional<Post> findById(String id) { return findValueById(id); }
  @Override public void delete(Post post) { super.deleteById(post.getId()); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public void deleteAll(Iterable<Post> posts) {
    deleteAllValues(posts, Post::getId);
  }
  @Override public long count() { return mongo.count(new Query()); }
  @Override public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return find(accountQuery(accountId));
  }
  @Override public List<Post> findByAccountIdOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    return find(accountQuery(accountId), pageable);
  }
  @Override public Page<Post> findAll(Pageable pageable) { return page(new Query(), pageable); }
  @Override public List<Post> findByRootIdOrderByCreatedOnAsc(String rootId) {
    return find(Query.query(Criteria.where("rootId").is(rootId))
        .with(Sort.by(Sort.Direction.ASC, "createdOn")));
  }
  @Override public List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable) {
    return find(Query.query(Criteria.where("expiresOn").lte(cutoff)), pageable);
  }
  @Override public long countByExpiresOnAfter(Instant cutoff) {
    return mongo.count(Query.query(Criteria.where("expiresOn").gt(cutoff)));
  }
  @Override public Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable) {
    return page(Query.query(Criteria.where("expiresOn").gt(cutoff)), pageable);
  }
  @Override public List<Post> findByExpiresOnIsNull(Pageable pageable) {
    return find(Query.query(Criteria.where("expiresOn").is(null)), pageable);
  }
  @Override public long countByAccountIdAndParentIdIsNull(String accountId) {
    return mongo.count(Query.query(Criteria.where("accountId").is(accountId)
        .and("parentId").is(null)));
  }
  @Override public long countByAccountIdAndParentIdIsNotNull(String accountId) {
    return mongo.count(Query.query(Criteria.where("accountId").is(accountId)
        .and("parentId").ne(null)));
  }

  @Override
  public List<Post> findFederationEligibleAfter(Instant createdOn, String postId, int limit) {
    var eligible = Criteria.where("federationOutboundEligible").is(true);
    Criteria criteria = eligible;
    if (createdOn != null && postId != null) {
      criteria = new Criteria().andOperator(eligible, new Criteria().orOperator(
          Criteria.where("createdOn").gt(createdOn),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(createdOn), Criteria.where("id").gt(postId))));
    }
    return find(Query.query(criteria)
        .with(Sort.by(Sort.Order.asc("createdOn"), Sort.Order.asc("id")))
        .limit(limit));
  }

  @Override
  public List<Post> findFederationOutboxPage(
      String accountId, Instant createdOn, String postId, int limit, Instant expiresAfter) {
    var active = Criteria.where("accountId").is(accountId)
        .and("federationOutboundEligible").is(true)
        .and("expiresOn").gt(expiresAfter)
        .and("createdOn").ne(null);
    Criteria criteria = active;
    if (createdOn != null && postId != null) {
      criteria = new Criteria().andOperator(active, new Criteria().orOperator(
          Criteria.where("createdOn").lt(createdOn),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(createdOn), Criteria.where("id").lt(postId))));
    }
    return find(Query.query(criteria)
        .with(Sort.by(Sort.Order.desc("createdOn"), Sort.Order.desc("id")))
        .limit(limit));
  }

  private static Query accountQuery(String accountId) {
    return Query.query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn"));
  }
}
