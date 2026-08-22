package dev.christopherbell.post.feed;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL keyset queries for deterministic global, author, and following feeds. */
@PostgresPersistence
public class PostgresPostFeedQueryRepository implements PostFeedQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcClient database;
  private final StableCursorCodec cursors;
  private final PostgresPostMapper mapper;
  private final String postTable;
  private final String followTable;

  public PostgresPostFeedQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
    mapper = new PostgresPostMapper(database, schemas);
    postTable = schemas.qualifiedTable("social", "post");
    followTable = schemas.qualifiedTable("identity", "account_follow");
  }

  @Override public PostFeedSlice global(Optional<StableCursor> cursor, int size) {
    return global(cursor, size, PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice global(
      Optional<StableCursor> cursor, int size, PostFeedVisibility visibility) {
    return page("true", new HashMap<>(), cursor, size, visibility);
  }

  @Override public PostFeedSlice account(String id, Optional<StableCursor> cursor, int size) {
    return account(id, cursor, size, PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice account(
      String id, Optional<StableCursor> cursor, int size, PostFeedVisibility visibility) {
    var parameters = new HashMap<String, Object>();
    parameters.put("accountId", id);
    return page("account_id = :accountId", parameters, cursor, size, visibility);
  }

  @Override
  public PostFeedSlice accounts(
      Collection<String> ids, Optional<StableCursor> cursor, int size) {
    if (ids.isEmpty()) return new PostFeedSlice(List.of(), null);
    var parameters = new HashMap<String, Object>();
    parameters.put("accountIds", ids);
    return page("account_id in (:accountIds)", parameters, cursor, size,
        PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice following(
      String followerId, Optional<StableCursor> cursor, int size, PostFeedVisibility visibility) {
    var parameters = new HashMap<String, Object>();
    parameters.put("followerId", followerId);
    return page("account_id in (select followed_account_id from %s where follower_account_id = :followerId)"
            .formatted(followTable), parameters, cursor, size, visibility);
  }

  private PostFeedSlice page(
      String scope, HashMap<String, Object> parameters, Optional<StableCursor> cursor,
      int requestedSize, PostFeedVisibility visibility) {
    var clauses = new ArrayList<String>();
    clauses.add(scope);
    cursor.ifPresent(boundary -> {
      clauses.add("(created_on < :cursorTime or (created_on = :cursorTime and post_id < :cursorId))");
      parameters.put("cursorTime", boundary.timestamp().atOffset(ZoneOffset.UTC));
      parameters.put("cursorId", boundary.id());
    });
    visibility.expiresAfter().ifPresent(cutoff -> {
      clauses.add("expires_on > :expiresAfter");
      parameters.put("expiresAfter", cutoff.atOffset(ZoneOffset.UTC));
    });
    if (!visibility.excludedAccountIds().isEmpty()) {
      clauses.add("account_id not in (:excludedAccounts)");
      parameters.put("excludedAccounts", visibility.excludedAccountIds());
    }
    if (!visibility.excludedRootIds().isEmpty()) {
      clauses.add("root_post_id not in (:excludedRoots)");
      parameters.put("excludedRoots", visibility.excludedRootIds());
    }
    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var statement = database.sql("""
            select * from %s where %s
            order by created_on desc, post_id desc limit :limit
            """.formatted(postTable, String.join(" and ", clauses)));
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    var rows = statement.param("limit", size + 1).query(mapper::row).list();
    return slice(mapper.mapAll(rows), size);
  }

  private PostFeedSlice slice(List<Post> loaded, int size) {
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String next = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.getLast();
      next = cursors.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new PostFeedSlice(items, next);
  }
}
