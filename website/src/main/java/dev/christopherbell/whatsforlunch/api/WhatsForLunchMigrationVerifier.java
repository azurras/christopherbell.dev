package dev.christopherbell.whatsforlunch.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.whatsforlunch.restaurant.PostgresDailyLunchPicksRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.PostgresRestaurantImportPreviewStore;
import dev.christopherbell.whatsforlunch.restaurant.preference.PostgresWhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;

/** Published What's For Lunch adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class WhatsForLunchMigrationVerifier {
  private WhatsForLunchMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      Map<String, List<Map<String, Object>>> tables) throws SQLException {
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var transactions = org.springframework.transaction.support.TransactionOperations
        .withoutTransaction();
    return switch (sourceKind + "/" + queryName) {
      case "restaurant/find-by-id" -> verifyOptionalLookup(
          tables.get("restaurant"), "restaurant_id",
          restaurants(connection, schema)::findById);
      case "restaurant/find-by-normalized-name" ->
          verifyRestaurantNames(connection, schema, tables.get("restaurant"));
      case "restaurant/coordinate-bounds" ->
          verifyCoordinates(connection, schema, tables.get("restaurant"));
      case "vote/find-by-restaurant-and-account" ->
          verifyVotes(connection, schema, tables.get("restaurant_vote"));
      case "favorite/find-by-restaurant-and-account" ->
          verifyFavorites(connection, schema, tables.get("restaurant_favorite"));
      case "favorite/account-favorite-page" ->
          verifyFavoritePage(connection, schema, tables.get("restaurant_favorite"));
      case "preference/find-by-account" -> verifyOptionalLookup(
          tables.get("lunch_preference"), "account_id",
          new PostgresWhatsForLunchPreferenceRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema),
              org.springframework.transaction.support.TransactionOperations.withoutTransaction())
              ::findById);
      case "session/find-by-id" -> verifyOptionalLookup(
          tables.get("lunch_session"), "lunch_session_id",
          new PostgresWhatsForLunchSessionRepository(jdbc, schemas, transactions)::findById);
      case "session/participant-session-page" ->
          verifyParticipantSessions(jdbc, schemas, transactions, tables);
      case "daily_picks/find-by-id" -> verifyOptionalLookup(
          tables.get("daily_lunch_picks"), "daily_lunch_picks_id",
          new PostgresDailyLunchPicksRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema),
              org.springframework.transaction.support.TransactionOperations.withoutTransaction())
              ::findById);
      case "import_state/find-by-id" -> verifyOptionalLookup(
          tables.get("restaurant_import_state"), "import_state_id",
          new PostgresRestaurantImportStateRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      case "import_preview/claim" ->
          verifyPreviewClaim(connection, schema, tables.get("restaurant_import_preview"));
      default -> false;
    };
  }

  private static boolean verifyRestaurantNames(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = restaurants(connection, schema);
    return rows.stream().allMatch(row -> repository.findByNormalizedName(
        text(row.get("normalized_name"))).isPresent())
        && repository.findByNormalizedName("migration-verifier-missing-name").isEmpty();
  }

  private static boolean verifyCoordinates(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var sample = rows.stream()
        .filter(row -> row.get("latitude") instanceof Number
            && row.get("longitude") instanceof Number)
        .findFirst();
    if (sample.isEmpty()) {
      return true;
    }
    var latitude = ((Number) sample.orElseThrow().get("latitude")).doubleValue();
    var longitude = ((Number) sample.orElseThrow().get("longitude")).doubleValue();
    var expected = rows.stream()
        .filter(row -> row.get("latitude") instanceof Number
            && row.get("longitude") instanceof Number)
        .filter(row ->
        Math.abs(((Number) row.get("latitude")).doubleValue() - latitude) <= 0.000001
            && Math.abs(((Number) row.get("longitude")).doubleValue() - longitude) <= 0.000001)
        .map(row -> text(row.get("restaurant_id"))).sorted().toList();
    var actual = restaurants(connection, schema)
        .findByCoordinateBounds(latitude - 0.000001, latitude + 0.000001,
            longitude - 0.000001, longitude + 0.000001).stream()
        .map(value -> value.getId()).sorted().toList();
    return actual.equals(expected);
  }

  private static boolean verifyVotes(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = new PostgresRestaurantVoteRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
    return rows.stream().allMatch(row -> repository.findByRestaurantIdAndAccountId(
        text(row.get("restaurant_id")), text(row.get("account_id"))).isPresent())
        && repository.findByRestaurantIdAndAccountId(
            "migration-verifier-missing-restaurant", "migration-verifier-missing-account").isEmpty();
  }

  private static boolean verifyFavorites(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = favorites(connection, schema);
    return rows.stream().allMatch(row -> repository.findByRestaurantIdAndAccountId(
        text(row.get("restaurant_id")), text(row.get("account_id"))).isPresent())
        && repository.findByRestaurantIdAndAccountId(
            "migration-verifier-missing-restaurant", "migration-verifier-missing-account").isEmpty();
  }

  private static boolean verifyFavoritePage(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = favorites(connection, schema);
    for (var account : rows.stream().map(row -> text(row.get("account_id"))).distinct().toList()) {
      var expected = rows.stream().filter(row -> account.equals(text(row.get("account_id"))))
          .sorted(Comparator.comparing(
              (Map<String, Object> row) -> instant(row.get("created_on"))).reversed()
              .thenComparing(
                  row -> text(row.get("restaurant_favorite_id")), Comparator.reverseOrder()))
          .map(row -> text(row.get("restaurant_favorite_id"))).toList();
      var actual = repository.findByAccountIdOrderByCreatedOnDesc(account).stream()
          .map(value -> value.getId()).toList();
      if (!actual.equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private static PostgresRestaurantFavoriteRepository favorites(
      Connection connection, String schema) {
    return new PostgresRestaurantFavoriteRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
  }

  private static PostgresRestaurantRepository restaurants(
      Connection connection, String schema) {
    return new PostgresRestaurantRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema),
        org.springframework.transaction.support.TransactionOperations.withoutTransaction());
  }

  private static boolean verifyParticipantSessions(
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      org.springframework.transaction.support.TransactionOperations transactions,
      Map<String, List<Map<String, Object>>> tables) {
    var sessions = tables.get("lunch_session");
    var participants = tables.get("lunch_session_participant");
    var repository = new PostgresWhatsForLunchSessionRepository(database, schemas, transactions);
    for (var account : participants.stream().map(row -> text(row.get("account_id"))).distinct().toList()) {
      var membership = participants.stream()
          .filter(row -> account.equals(text(row.get("account_id"))))
          .map(row -> text(row.get("lunch_session_id")))
          .collect(java.util.stream.Collectors.toSet());
      var now = Instant.EPOCH;
      var expected = sessions.stream()
          .filter(row -> membership.contains(text(row.get("lunch_session_id"))))
          .filter(row -> instant(row.get("delete_on")).isAfter(now))
          .sorted(Comparator.comparing(
              (Map<String, Object> row) -> instant(row.get("created_on"))).reversed()
              .thenComparing(row -> text(row.get("lunch_session_id"))))
          .map(row -> text(row.get("lunch_session_id"))).toList();
      var first = repository
          .findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
              account, now, PageRequest.of(0, 2)).stream()
          .map(value -> value.getId()).toList();
      var offset = repository
          .findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
              account, now, PageRequest.of(1, 1)).stream()
          .map(value -> value.getId()).toList();
      if (!first.equals(expected.stream().limit(2).toList())
          || !offset.equals(expected.stream().skip(1).limit(1).toList())) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyPreviewClaim(
      Connection connection, String schema, List<Map<String, Object>> rows)
      throws SQLException {
    if (rows.isEmpty()) {
      return true;
    }
    var row = rows.getFirst();
    var expiry = instant(row.get("expires_on"));
    var now = expiry.minusSeconds(1);
    return rollback(connection, () -> {
      var store = new PostgresRestaurantImportPreviewStore(
          org.springframework.jdbc.core.simple.JdbcClient.create(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
          dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
              .fromPhysicalSchema(schema));
      var first = store.claim(
          text(row.get("import_preview_id")), text(row.get("actor_account_id")), now);
      return first.isPresent()
          && store.claim(text(row.get("import_preview_id")),
              text(row.get("actor_account_id")), now).isEmpty();
    });
  }
}
