package dev.christopherbell.post;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.post.api.PostExpirationAggregation;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoPostRepository extends KindScopedRepositorySupport<Post>
    implements PostRepository {
  MongoPostRepository(DomainMongoOperationsFactory factory) {
    super(factory, Post.class);
  }

  @Override
  public Post save(Post post) {
    return saveValue(post);
  }
  @Override
  public Optional<Post> editIfUnchanged(Post observed, Post replacement, Instant editingOn,
      Instant oldestEditableCreation, int auditLimit, boolean requireActiveExpiration) {
    var query = Query.query(Criteria.where("id").is(observed.getId())
        .and("accountId").is(observed.getAccountId())
        .and("text").is(observed.getText())
        .and("editedOn").is(observed.getEditedOn())
        .and("createdOn").gt(oldestEditableCreation));
    if (requireActiveExpiration
        && (replacement.getParentId() == null || replacement.getParentId().isBlank())) {
      query.addCriteria(new Criteria().orOperator(
          Criteria.where("expiresOn").is(null), Criteria.where("expiresOn").gt(editingOn)));
    }
    var update = new Update().set("text", replacement.getText())
        .set("editedOn", editingOn).set("lastUpdatedOn", editingOn)
        .set("linkPreviews", replacement.getLinkPreviews());
    var audit = replacement.getEditAudit().getLast();
    update.push("editAudit").slice(-auditLimit).each(audit);
    return mongo.findAndUpdate(query, update);
  }

  @Override
  public Optional<Post> findById(String id) {
    return findValueById(id);
  }
  @Override
  public void delete(Post post) {
    super.deleteById(post.getId());
  }
  @Override
  public void deleteById(String id) {
    super.deleteById(id);
  }
  @Override
  public void deleteAll(Iterable<Post> posts) {
    deleteAllValues(posts, Post::getId);
  }
  @Override
  public long count() {
    return mongo.count(new Query());
  }
  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(String accountId) {
    return find(accountQuery(accountId));
  }
  @Override
  public List<Post> findByAccountIdOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    return find(accountQuery(accountId), pageable);
  }
  @Override
  public Page<Post> findAll(Pageable pageable) {
    return page(new Query(), pageable);
  }
  @Override
  public List<Post> findByRootIdOrderByCreatedOnAsc(String rootId) {
    return find(Query.query(Criteria.where("rootId").is(rootId))
        .with(Sort.by(Sort.Direction.ASC, "createdOn")));
  }
  @Override
  public List<Post> findByExpiresOnLessThanEqual(Instant cutoff, Pageable pageable) {
    return find(Query.query(new Criteria().andOperator(
        Criteria.where("expiresOn").lte(cutoff), rootCriteria())), pageable);
  }
  @Override
  public long countByExpiresOnAfter(Instant cutoff) {
    var stages = activeStages(new Criteria(), cutoff);
    stages.add(context -> new Document("$count", "count"));
    return mongo.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), CountRow.class)
        .stream().findFirst().map(CountRow::count).orElse(0L);
  }
  @Override
  public Page<Post> findByExpiresOnAfter(Instant cutoff, Pageable pageable) {
    var stages = activeStages(new Criteria(), cutoff);
    var sort = new Document();
    pageable.getSort().forEach(order -> {
      var property = order.getProperty();
      if (Arrays.stream(Post.class.getDeclaredFields())
          .noneMatch(field -> field.getName().equals(property))) {
        throw new IllegalArgumentException("Unsupported post sort field.");
      }
      sort.append("id".equals(property) ? "_id" : property, order.isAscending() ? 1 : -1);
    });
    if (!sort.isEmpty()) {
      stages.add(context -> new Document("$sort", sort));
    }
    if (pageable.isPaged()) {
      stages.add(context -> new Document("$skip", pageable.getOffset()));
      stages.add(context -> new Document("$limit", pageable.getPageSize()));
    }
    var current = mongo.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), Post.class);
    return new PageImpl<>(current, pageable, countByExpiresOnAfter(cutoff));
  }
  @Override
  public List<Post> findByExpiresOnIsNull(Pageable pageable) {
    return find(Query.query(new Criteria().andOperator(
        Criteria.where("expiresOn").is(null), rootCriteria())), pageable);
  }
  @Override
  public long countByAccountIdAndParentIdIsNull(String accountId) {
    return mongo.count(Query.query(Criteria.where("accountId").is(accountId)
        .and("parentId").is(null)));
  }
  @Override
  public long countByAccountIdAndParentIdIsNotNull(String accountId) {
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
        .and("createdOn").ne(null);
    Criteria criteria = active;
    if (createdOn != null && postId != null) {
      criteria = new Criteria().andOperator(active, new Criteria().orOperator(
          Criteria.where("createdOn").lt(createdOn),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(createdOn), Criteria.where("id").lt(postId))));
    }
    var stages = activeStages(criteria, expiresAfter);
    stages.add(context -> new Document("$sort", new Document("createdOn", -1).append("_id", -1)));
    stages.add(context -> new Document("$limit", limit));
    return mongo.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), Post.class);
  }

  private static Criteria rootCriteria() {
    return new Criteria().orOperator(Criteria.where("parentId").is(null), Criteria.where("parentId").is(""));
  }

  private static ArrayList<AggregationOperation> activeStages(Criteria selector, Instant cutoff) {
    var stages = new ArrayList<AggregationOperation>();
    stages.add(context -> new Document("$match", selector.getCriteriaObject()));
    stages.addAll(PostExpirationAggregation.activeAt(cutoff));
    return stages;
  }

  private record CountRow(long count) {}

  private static Query accountQuery(String accountId) {
    return Query.query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn"));
  }
}
