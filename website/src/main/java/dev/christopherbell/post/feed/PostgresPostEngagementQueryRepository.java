package dev.christopherbell.post.feed;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL page-wide post engagement aggregates. */
@PostgresPersistence
public final class PostgresPostEngagementQueryRepository implements PostEngagementQueryPort {
  private final DSLContext database;

  public PostgresPostEngagementQueryRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Map<String, Integer> replyCounts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    var counts = new LinkedHashMap<String, Integer>();
    database.select(POST.PARENT_POST_ID, DSL.count())
        .from(POST)
        .where(POST.PARENT_POST_ID.in(postIds))
        .groupBy(POST.PARENT_POST_ID)
        .fetch()
        .forEach(row -> counts.put(row.value1(), row.value2()));
    return Map.copyOf(counts);
  }
}
