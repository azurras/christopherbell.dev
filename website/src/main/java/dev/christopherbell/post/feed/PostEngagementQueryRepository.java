package dev.christopherbell.post.feed;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.post.model.Post;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Page-wide post engagement aggregates whose query count is independent of page size. */
@MongoPersistence
@Repository
public class PostEngagementQueryRepository implements PostEngagementQueryPort {
  private final KindScopedMongoOperations<Post> posts;

  public PostEngagementQueryRepository(DomainMongoOperationsFactory factory) {
    this.posts = factory.forType(Post.class);
  }

  public Map<String, Integer> replyCounts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) {
      return Map.of();
    }
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("parentId").in(postIds)),
        Aggregation.group("parentId").count().as("count"));
    var result = new LinkedHashMap<String, Integer>();
    posts.aggregate(KindScopedAggregation.local(aggregation), CountRow.class)
        .forEach(row -> result.put(row.id(), row.count()));
    return Map.copyOf(result);
  }

  private record CountRow(String id, int count) {}
}
