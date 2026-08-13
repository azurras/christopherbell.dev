package dev.christopherbell.federation.discovery;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Bounded active-post queries for a local actor's public outbox. */
@MongoPersistence
@Repository
public class FederationOutboxQueryRepository implements FederationOutboxQueryPort {
  private static final int MAX_PAGE_SIZE = 20;

  private final KindScopedMongoOperations<Post> mongo;
  private final StableCursorCodec cursors;

  public FederationOutboxQueryRepository(
      DomainMongoOperationsFactory factory, StableCursorCodec cursors) {
    this.mongo = factory.forType(Post.class);
    this.cursors = cursors;
  }

  public FederationPage<Post> page(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize,
      Instant now
  ) {
    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Criteria criteria = activeOwned(accountId, now);
    if (cursor.isPresent()) {
      criteria = new Criteria().andOperator(criteria, descendingBoundary(cursor.orElseThrow()));
    }
    var query = new Query(criteria)
        .with(Sort.by(
            new Sort.Order(Sort.Direction.DESC, "createdOn"),
            new Sort.Order(Sort.Direction.DESC, "id")))
        .limit(size + 1);
    var loaded = mongo.find(query, org.springframework.data.domain.Pageable.unpaged());
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      Post boundary = items.get(items.size() - 1);
      nextCursor = cursors.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new FederationPage<>(items, nextCursor);
  }

  public long count(String accountId, Instant now) {
    return mongo.count(new Query(activeOwned(accountId, now)));
  }

  private static Criteria activeOwned(String accountId, Instant now) {
    return Criteria.where("accountId").is(accountId)
        .and("federationOutboundEligible").is(true)
        .and("expiresOn").gt(now)
        .and("createdOn").ne(null);
  }

  private static Criteria descendingBoundary(StableCursor cursor) {
    return new Criteria().orOperator(
        Criteria.where("createdOn").lt(cursor.timestamp()),
        new Criteria().andOperator(
            Criteria.where("createdOn").is(cursor.timestamp()),
            Criteria.where("id").lt(cursor.id())));
  }
}
