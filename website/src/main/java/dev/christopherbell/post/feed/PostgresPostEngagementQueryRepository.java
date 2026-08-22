package dev.christopherbell.post.feed;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL page-wide post engagement aggregates. */
@PostgresPersistence
public class PostgresPostEngagementQueryRepository implements PostEngagementQueryPort {
  private final JdbcClient database;
  private final String postTable;

  public PostgresPostEngagementQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    postTable = schemas.qualifiedTable("social", "post");
  }

  @Override
  public Map<String, Integer> replyCounts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    var counts = new LinkedHashMap<String, Integer>();
    database.sql("""
            select parent_post_id, count(*) as reply_count
            from %s
            where parent_post_id in (:postIds)
            group by parent_post_id
            """.formatted(postTable))
        .param("postIds", postIds)
        .query((row, rowNumber) -> Map.entry(
            row.getString("parent_post_id"), row.getInt("reply_count")))
        .list()
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(counts);
  }
}
