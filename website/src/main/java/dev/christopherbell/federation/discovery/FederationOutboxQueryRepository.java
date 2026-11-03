package dev.christopherbell.federation.discovery;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.api.PostExpirationAggregation;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
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

  @Override
  public FederationPage<FederationOutboxEntry> page(
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
    var stages = activeStages(criteria, now);
    stages.add(context -> new Document("$sort", new Document("createdOn", -1).append("_id", -1)));
    stages.add(context -> new Document("$limit", size + 1));
    var loaded = mongo.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), Post.class);
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).map(FederationOutboxQueryRepository::entry).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.get(items.size() - 1);
      nextCursor = cursors.encode(new StableCursor(boundary.createdOn(), boundary.id()));
    }
    return new FederationPage<>(items, nextCursor);
  }

  private static FederationOutboxEntry entry(Post post) {
    return new FederationOutboxEntry(
        post.getId(),
        post.getText(),
        post.getParentId(),
        post.getCreatedOn(),
        post.getLastUpdatedOn());
  }

  @Override
  public long count(String accountId, Instant now) {
    var stages = activeStages(activeOwned(accountId, now), now);
    stages.add(context -> new Document("$count", "total"));
    return mongo.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), CountRow.class)
        .stream().findFirst().map(CountRow::total).orElse(0L);
  }

  private static ArrayList<AggregationOperation> activeStages(Criteria criteria, Instant now) {
    var stages = new ArrayList<AggregationOperation>();
    stages.add(context -> new Document("$match", criteria.getCriteriaObject()));
    stages.addAll(PostExpirationAggregation.activeAt(now));
    return stages;
  }

  public record CountRow(long total) {}

  private static Criteria activeOwned(String accountId, Instant now) {
    return Criteria.where("accountId").is(accountId)
        .and("federationOutboundEligible").is(true)
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
