package dev.christopherbell.post.api;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.post.feed.PostFeedSlice;
import dev.christopherbell.post.feed.PostFeedVisibility;
import dev.christopherbell.post.feed.PostgresPostFeedQueryRepository;
import dev.christopherbell.post.model.Post;
import java.sql.Connection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Published post-module verification path used by the Mongo-to-PostgreSQL cutover. */
@PostgresPersistenceSupport
public final class PostMigrationFeedVerifier {
  private static final String AUTHOR_FEED = "author-feed-page";
  private static final String PUBLIC_FEED = "public-feed-page";

  private PostMigrationFeedVerifier() {
  }

  /**
   * Executes the real PostgreSQL feed adapter and compares its keyset, lookahead, and visibility
   * behavior with the independently transformed source rows.
   */
  public static boolean verify(
      Connection connection,
      String socialSchema,
      String queryName,
      List<Map<String, Object>> sourceRows) {
    if (!socialSchema.endsWith("social")
        || !(queryName.equals(AUTHOR_FEED) || queryName.equals(PUBLIC_FEED))) {
      return false;
    }
    var repository = new PostgresPostFeedQueryRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(socialSchema),
        new StableCursorCodec());
    var account = sourceRows.stream().map(row -> text(row.get("account_id")))
        .filter(Objects::nonNull).findFirst().orElse(null);
    var scoped = queryName.equals(AUTHOR_FEED);
    if (scoped && account == null) {
      return sourceRows.isEmpty();
    }
    var expected = sourceRows.stream()
        .filter(row -> !scoped || Objects.equals(text(row.get("account_id")), account))
        .sorted(Comparator
            .comparing(PostMigrationFeedVerifier::createdOn).reversed()
            .thenComparing(row -> text(row.get("post_id")), Comparator.reverseOrder()))
        .toList();
    var first = scoped
        ? repository.account(account, java.util.Optional.empty(), 1)
        : repository.global(java.util.Optional.empty(), 1);
    if (!feedMatches(first, expected, 1)) {
      return false;
    }
    if (expected.isEmpty()) {
      return true;
    }
    var boundary = expected.getFirst();
    var cursor = new StableCursor(createdOn(boundary), text(boundary.get("post_id")));
    var tail = expected.stream().skip(1).toList();
    var after = scoped
        ? repository.account(account, java.util.Optional.of(cursor), 1)
        : repository.global(java.util.Optional.of(cursor), 1);
    if (!feedMatches(after, tail, 1)) {
      return false;
    }
    var cutoff = Instant.EPOCH;
    var excludedAccounts = account == null ? Set.<String>of() : Set.of(account);
    var excludedRoots = expected.stream()
        .map(row -> text(row.get("root_post_id")))
        .filter(Objects::nonNull).findFirst().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    var visibility = new PostFeedVisibility(
        excludedAccounts, excludedRoots, java.util.Optional.of(cutoff));
    var visibleExpected = expected.stream()
        .filter(row -> !excludedAccounts.contains(text(row.get("account_id"))))
        .filter(row -> !excludedRoots.contains(text(row.get("root_post_id"))))
        .filter(row -> row.get("expires_on") instanceof Instant expiry && expiry.isAfter(cutoff))
        .toList();
    var visible = scoped
        ? repository.account(account, java.util.Optional.empty(), 1, visibility)
        : repository.global(java.util.Optional.empty(), 1, visibility);
    return feedMatches(visible, visibleExpected, 1);
  }

  private static Instant createdOn(Map<String, Object> row) {
    return (Instant) row.get("created_on");
  }

  private static boolean feedMatches(
      PostFeedSlice actual, List<Map<String, Object>> expected, int size) {
    var expectedIds = expected.stream().limit(size).map(row -> text(row.get("post_id"))).toList();
    var actualIds = actual.posts().stream().map(Post::getId).toList();
    return actualIds.equals(expectedIds)
        && (actual.nextCursor() != null) == (expected.size() > size);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
