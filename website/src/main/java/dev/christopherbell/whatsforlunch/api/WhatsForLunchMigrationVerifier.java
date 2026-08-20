package dev.christopherbell.whatsforlunch.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
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
    var context = database(connection, schema);
    return switch (sourceKind + "/" + queryName) {
      case "restaurant/find-by-id" -> verifyOptionalLookup(
          tables.get("restaurant"), "restaurant_id",
          new PostgresRestaurantRepository(context)::findById);
      case "restaurant/find-by-normalized-name" -> verifyRestaurantNames(context, tables.get("restaurant"));
      case "restaurant/coordinate-bounds" -> verifyCoordinates(context, tables.get("restaurant"));
      case "vote/find-by-restaurant-and-account" -> verifyVotes(context, tables.get("restaurant_vote"));
      case "favorite/find-by-restaurant-and-account" ->
          verifyFavorites(context, tables.get("restaurant_favorite"));
      case "favorite/account-favorite-page" ->
          verifyFavoritePage(context, tables.get("restaurant_favorite"));
      case "preference/find-by-account" -> verifyOptionalLookup(
          tables.get("lunch_preference"), "account_id",
          new PostgresWhatsForLunchPreferenceRepository(context)::findById);
      case "session/find-by-id" -> verifyOptionalLookup(
          tables.get("lunch_session"), "lunch_session_id",
          new PostgresWhatsForLunchSessionRepository(context)::findById);
      case "session/participant-session-page" -> verifyParticipantSessions(context, tables);
      case "daily_picks/find-by-id" -> verifyOptionalLookup(
          tables.get("daily_lunch_picks"), "daily_lunch_picks_id",
          new PostgresDailyLunchPicksRepository(context)::findById);
      case "import_state/find-by-id" -> verifyOptionalLookup(
          tables.get("restaurant_import_state"), "import_state_id",
          new PostgresRestaurantImportStateRepository(context)::findById);
      case "import_preview/claim" -> verifyPreviewClaim(connection, context, tables.get("restaurant_import_preview"));
      default -> false;
    };
  }

  private static boolean verifyRestaurantNames(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresRestaurantRepository(context);
    return rows.stream().allMatch(row -> repository.findByNormalizedName(
        text(row.get("normalized_name"))).isPresent())
        && repository.findByNormalizedName("migration-verifier-missing-name").isEmpty();
  }

  private static boolean verifyCoordinates(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return true;
    }
    var latitude = ((Number) rows.getFirst().get("latitude")).doubleValue();
    var longitude = ((Number) rows.getFirst().get("longitude")).doubleValue();
    var expected = rows.stream().filter(row ->
        Math.abs(((Number) row.get("latitude")).doubleValue() - latitude) <= 0.000001
            && Math.abs(((Number) row.get("longitude")).doubleValue() - longitude) <= 0.000001)
        .map(row -> text(row.get("restaurant_id"))).sorted().toList();
    var actual = new PostgresRestaurantRepository(context)
        .findByCoordinateBounds(latitude - 0.000001, latitude + 0.000001,
            longitude - 0.000001, longitude + 0.000001).stream()
        .map(value -> value.getId()).sorted().toList();
    return actual.equals(expected);
  }

  private static boolean verifyVotes(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresRestaurantVoteRepository(context);
    return rows.stream().allMatch(row -> repository.findByRestaurantIdAndAccountId(
        text(row.get("restaurant_id")), text(row.get("account_id"))).isPresent())
        && repository.findByRestaurantIdAndAccountId(
            "migration-verifier-missing-restaurant", "migration-verifier-missing-account").isEmpty();
  }

  private static boolean verifyFavorites(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresRestaurantFavoriteRepository(context);
    return rows.stream().allMatch(row -> repository.findByRestaurantIdAndAccountId(
        text(row.get("restaurant_id")), text(row.get("account_id"))).isPresent())
        && repository.findByRestaurantIdAndAccountId(
            "migration-verifier-missing-restaurant", "migration-verifier-missing-account").isEmpty();
  }

  private static boolean verifyFavoritePage(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var repository = new PostgresRestaurantFavoriteRepository(context);
    for (var account : rows.stream().map(row -> text(row.get("account_id"))).distinct().toList()) {
      var expected = rows.stream().filter(row -> account.equals(text(row.get("account_id"))))
          .sorted(Comparator.comparing(
              (Map<String, Object> row) -> instant(row.get("created_on"))).reversed())
          .map(row -> text(row.get("restaurant_favorite_id"))).toList();
      var actual = repository.findByAccountIdOrderByCreatedOnDesc(account).stream()
          .map(value -> value.getId()).toList();
      if (!actual.equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyParticipantSessions(
      org.jooq.DSLContext context, Map<String, List<Map<String, Object>>> tables) {
    var sessions = tables.get("lunch_session");
    var participants = tables.get("lunch_session_participant");
    var repository = new PostgresWhatsForLunchSessionRepository(context);
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
      Connection connection, org.jooq.DSLContext context, List<Map<String, Object>> rows)
      throws SQLException {
    if (rows.isEmpty()) {
      return true;
    }
    var row = rows.getFirst();
    var expiry = instant(row.get("expires_on"));
    var now = expiry.minusSeconds(1);
    return rollback(connection, () -> {
      var store = new PostgresRestaurantImportPreviewStore(context);
      var first = store.claim(
          text(row.get("import_preview_id")), text(row.get("actor_account_id")), now);
      return first.isPresent()
          && store.claim(text(row.get("import_preview_id")),
              text(row.get("actor_account_id")), now).isEmpty();
    });
  }
}
