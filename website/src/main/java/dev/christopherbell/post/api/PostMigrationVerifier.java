package dev.christopherbell.post.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.post.PostgresPostRepository;
import dev.christopherbell.post.hide.PostgresHiddenPostThreadRepository;
import dev.christopherbell.post.like.PostgresPostLikeStore;
import dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Published post-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class PostMigrationVerifier {
  private PostMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) throws SQLException {
    var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
        connection, true);
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    return switch (sourceKind + "/" + queryName) {
      case "post/find-by-id" -> verifyOptionalLookup(
          rows, "post_id", new PostgresPostRepository(
              jdbc, schemas,
              org.springframework.transaction.support.TransactionOperations.withoutTransaction())::findById);
      case "post/author-feed-page", "post/public-feed-page" ->
          PostMigrationFeedVerifier.verify(connection, schema, queryName, rows);
      case "post_like/like-exists" -> verifyLikes(connection, schema, rows);
      case "hidden_post_thread/find-by-account-and-root" ->
          verifyHidden(connection, schema, rows);
      case "hidden_post_thread/account-page" -> verifyHiddenPage(connection, schema, rows);
      case "post_link_preview_cache/find-by-id" -> verifyOptionalLookup(
          rows, "url",
          previewCache(connection, schema)::findById);
      case "post_link_preview_cache/delete-expired" ->
          verifyPreviewCleanup(connection, schema, rows);
      default -> false;
    };
  }

  private static boolean verifyLikes(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var store = new PostgresPostLikeStore(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
    return rows.stream().allMatch(row -> store.exists(
        text(row.get("post_id")), text(row.get("account_id"))))
        && !store.exists("migration-verifier-missing-post", "migration-verifier-missing-account");
  }

  private static boolean verifyHidden(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = hiddenThreads(connection, schema);
    return rows.stream().allMatch(row -> repository.findByAccountIdAndRootPostId(
        text(row.get("account_id")), text(row.get("root_post_id"))).isPresent())
        && repository.findByAccountIdAndRootPostId(
            "migration-verifier-missing-account", "migration-verifier-missing-root").isEmpty();
  }

  private static boolean verifyHiddenPage(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = hiddenThreads(connection, schema);
    for (var account : rows.stream().map(row -> text(row.get("account_id"))).distinct().toList()) {
      var expected = rows.stream().filter(row -> account.equals(text(row.get("account_id"))))
          .map(row -> text(row.get("hidden_post_thread_id"))).sorted().toList();
      var actual = repository.findByAccountId(account).stream()
          .map(value -> value.getId()).sorted().toList();
      if (!actual.equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private static PostgresHiddenPostThreadRepository hiddenThreads(
      Connection connection, String schema) {
    return new PostgresHiddenPostThreadRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
  }

  private static boolean verifyPreviewCleanup(
      Connection connection, String schema, List<Map<String, Object>> rows)
      throws SQLException {
    var cutoff = rows.stream().map(row -> instant(row.get("expires_on")))
        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    var expected = rows.stream().filter(row -> {
      var expiry = instant(row.get("expires_on"));
      return expiry != null && !expiry.isAfter(cutoff);
    }).count();
    return rollback(connection, () ->
        previewCache(connection, schema).deleteExpired(cutoff, 10_000) == expected);
  }

  private static PostgresPostLinkPreviewCacheRepository previewCache(
      Connection connection, String schema) {
    return new PostgresPostLinkPreviewCacheRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
  }
}
