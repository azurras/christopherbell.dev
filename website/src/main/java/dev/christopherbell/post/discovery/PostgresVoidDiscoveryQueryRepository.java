package dev.christopherbell.post.discovery;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL anonymous Void discovery with stable keyset cursors. */
@PostgresPersistence
public class PostgresVoidDiscoveryQueryRepository implements VoidDiscoveryQueryPort {
  private static final int MAX_PAGE_SIZE = 24;
  private final JdbcClient database;
  private final StableCursorCodec cursors;
  private final PostgresPostMapper mapper;
  private final String postTable;
  private final String topicTable;

  public PostgresVoidDiscoveryQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
    mapper = new PostgresPostMapper(database, schemas);
    postTable = schemas.qualifiedTable("social", "post");
    topicTable = schemas.qualifiedTable("social", "post_topic");
  }

  @Override
  public VoidDiscoveryPage<Post> newArrivals(Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage("created_on", false, cursor, size, now, false, Post::getCreatedOn);
  }

  @Override
  public VoidDiscoveryPage<Post> fadingSoon(Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage("expires_on", true, cursor, size, now, false, Post::getExpiresOn);
  }

  @Override
  public VoidDiscoveryPage<Post> recentlyRevived(
      Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage("last_extended_on", false, cursor, size, now, true, Post::getLastExtendedOn);
  }

  @Override
  public VoidDiscoveryPage<Post> topic(
      String canonical, Optional<StableCursor> cursor, int requestedSize, Instant now) {
    int size = pageSize(requestedSize);
    var clauses = new StringBuilder("""
        parent_id_guard and expires_on > :now and post_id in (
          select distinct matched.root_post_id from %s matched
          join %s topic on topic.post_id = matched.post_id
          where matched.expires_on > :now and topic.canonical = :canonical)
        """.formatted(postTable, topicTable).replace("parent_id_guard", "parent_post_id is null"));
    var parameters = new HashMap<String, Object>();
    parameters.put("now", timestamp(now));
    parameters.put("canonical", canonical);
    addBoundary(clauses, parameters, "created_on", false, cursor);
    return postPage(loadPosts(clauses.toString(), parameters,
        "created_on desc, post_id desc", size + 1), size, Post::getCreatedOn);
  }

  @Override
  public VoidDiscoveryPage<VoidTopicSummary> topics(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    int size = pageSize(requestedSize);
    var having = new StringBuilder();
    var parameters = new HashMap<String, Object>();
    parameters.put("now", timestamp(now));
    cursor.ifPresent(value -> {
      having.append(" having max(coalesce(post.last_extended_on, post.created_on)) < :cursorTime")
          .append(" or (max(coalesce(post.last_extended_on, post.created_on)) = :cursorTime")
          .append(" and topic.canonical > :cursorId)");
      parameters.put("cursorTime", timestamp(value.timestamp()));
      parameters.put("cursorId", value.id());
    });
    var statement = database.sql("""
            select topic.canonical, min(topic.display) as display,
              max(coalesce(post.last_extended_on, post.created_on)) as activity_on
            from %s topic join %s post on post.post_id = topic.post_id
            where post.expires_on > :now group by topic.canonical%s
            order by activity_on desc, topic.canonical asc limit :limit
            """.formatted(topicTable, postTable, having));
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    var loaded = statement.param("limit", size + 1).query((row, ignored) ->
        new VoidTopicSummary(row.getString("canonical"), row.getString("display"),
            row.getObject("activity_on", OffsetDateTime.class).toInstant())).list();
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String next = null;
    if (hasNext && !items.isEmpty()) {
      var last = items.getLast();
      next = cursors.encode(new StableCursor(last.activityOn(), last.canonical()));
    }
    return new VoidDiscoveryPage<>(items, next);
  }

  private VoidDiscoveryPage<Post> rootPage(
      String column, boolean ascending, Optional<StableCursor> cursor, int requestedSize,
      Instant now, boolean requireRevival, Function<Post, Instant> timestamp) {
    int size = pageSize(requestedSize);
    var clauses = new StringBuilder("parent_post_id is null and expires_on > :now");
    if (requireRevival) clauses.append(" and last_extended_on is not null");
    var parameters = new HashMap<String, Object>();
    parameters.put("now", timestamp(now));
    addBoundary(clauses, parameters, column, ascending, cursor);
    String direction = ascending ? "asc" : "desc";
    return postPage(loadPosts(clauses.toString(), parameters,
        column + " " + direction + ", post_id " + direction, size + 1), size, timestamp);
  }

  private List<Post> loadPosts(
      String where, HashMap<String, Object> parameters, String order, int limit) {
    var statement = database.sql("select * from %s where %s order by %s limit :limit"
        .formatted(postTable, where, order));
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    return mapper.mapAll(statement.param("limit", limit).query(mapper::row).list());
  }

  private VoidDiscoveryPage<Post> postPage(
      List<Post> loaded, int size, Function<Post, Instant> timestamp) {
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String next = null;
    if (hasNext && !items.isEmpty()) {
      var last = items.getLast();
      next = cursors.encode(new StableCursor(timestamp.apply(last), last.getId()));
    }
    return new VoidDiscoveryPage<>(items, next);
  }

  private static void addBoundary(
      StringBuilder clauses, HashMap<String, Object> parameters, String column,
      boolean ascending, Optional<StableCursor> cursor) {
    cursor.ifPresent(value -> {
      String comparison = ascending ? ">" : "<";
      clauses.append(" and (").append(column).append(' ').append(comparison)
          .append(" :cursorTime or (").append(column).append(" = :cursorTime and post_id ")
          .append(comparison).append(" :cursorId))");
      parameters.put("cursorTime", timestamp(value.timestamp()));
      parameters.put("cursorId", value.id());
    });
  }

  private static int pageSize(int requested) {
    return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
