package dev.christopherbell.post.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;

/** Root-authoritative expiry stages applied before filtering, ordering, counts, or page limits.
 * Callers authorize the POST foreign kind when wrapping the complete aggregation.
 */
public final class PostExpirationAggregation {
  private PostExpirationAggregation() {}

  /** Resolves each reply deadline from its root before applying the active-content cutoff. */
  public static List<AggregationOperation> activeAt(Instant cutoff) {
    var stages = new ArrayList<AggregationOperation>();
    var lookup = new Document("from", "content")
        .append("let", new Document("rootId", "$rootId"))
        .append("pipeline", List.of(new Document("$match", new Document("$expr",
            new Document("$and", List.of(
                new Document("$eq", List.of("$_kind", "post")),
                new Document("$eq", List.of("$_id.legacyId", "$$rootId")))))),
            new Document("$limit", 1)))
        .append("as", "expirationRoot");
    stages.add(context -> new Document("$lookup", lookup));
    var isRoot = new Document("$eq", List.of(
        new Document("$ifNull", List.of("$parentId", "")), ""));
    var rootExpiration = new Document("$arrayElemAt", List.of("$expirationRoot.payload.expiresOn", 0));
    stages.add(context -> new Document("$set", new Document("expiresOn",
        new Document("$cond", List.of(isRoot, "$expiresOn", rootExpiration)))));
    stages.add(context -> new Document("$match", new Document("expiresOn",
        new Document("$gt", Date.from(cutoff)))));
    stages.add(context -> new Document("$unset", "expirationRoot"));
    return List.copyOf(stages);
  }
}
