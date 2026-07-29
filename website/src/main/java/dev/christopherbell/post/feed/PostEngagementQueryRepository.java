package dev.christopherbell.post.feed;

import dev.christopherbell.post.model.Post;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Page-wide post engagement aggregates whose query count is independent of page size. */
@Repository
@RequiredArgsConstructor
public class PostEngagementQueryRepository {
  private final MongoTemplate mongo;

  public Map<String, Integer> replyCounts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) {
      return Map.of();
    }
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("parentId").in(postIds)),
        Aggregation.group("parentId").count().as("count"));
    var result = new LinkedHashMap<String, Integer>();
    mongo.aggregate(aggregation, Post.class, CountRow.class)
        .getMappedResults()
        .forEach(row -> result.put(row.id(), row.count()));
    return Map.copyOf(result);
  }

  private record CountRow(String id, int count) {}
}
