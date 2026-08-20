package dev.christopherbell.music.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.catalog.PostgresMusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.PostgresMusicTrackRepository;
import dev.christopherbell.music.library.PostgresMusicPlaylistRepository;
import dev.christopherbell.music.metadata.PostgresMusicMetadataEditRepository;
import dev.christopherbell.music.radio.PostgresMusicRadioHistoryRepository;
import dev.christopherbell.music.radio.PostgresMusicRuntimeStateRepository;
import dev.christopherbell.music.security.PostgresMusicAccessAttemptRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Published Music-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class MusicMigrationVerifier {
  private MusicMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      Map<String, List<Map<String, Object>>> tables) throws SQLException {
    var context = database(connection, schema);
    var rows = tables.values().stream().findFirst().orElse(List.of());
    return switch (sourceKind + "/" + queryName) {
      case "music_track/find-by-id" -> verifyOptionalLookup(
          tables.get("track"), "track_id", new PostgresMusicTrackRepository(context)::findById);
      case "music_track/catalog-search" -> verifyCatalog(context, tables.get("track"));
      case "music_playlist/find-by-id" -> verifyPlaylists(context, tables);
      case "music_metadata_edit/find-by-id" -> verifyOptionalLookup(
          tables.get("metadata_edit"), "metadata_edit_id",
          new PostgresMusicMetadataEditRepository(context)::findById);
      case "music_metadata_edit/expiration-page" -> verifyMetadataExpiry(context, rows);
      case "music_runtime_state/global-queue" -> verifyQueue(context, tables);
      case "music_runtime_state/global-radio" -> verifyRadio(context, tables.get("runtime_state"));
      case "music_radio_history/station-sequence-page" ->
          verifyHistory(context, tables.get("radio_history"));
      case "music_access_attempt/recent-page" ->
          verifyAccessRecent(context, tables.get("access_attempt"));
      case "music_access_attempt/delete-expired" ->
          verifyAccessCleanup(connection, context, tables.get("access_attempt"));
      default -> false;
    };
  }

  private static boolean verifyCatalog(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var expected = rows.stream().filter(row -> row.get("missing_since") == null)
        .filter(row -> "READY".equals(text(row.get("index_status")))).toList();
    var actual = new PostgresMusicCatalogQueryRepository(context)
        .search(new MusicQuery(null, null, null, null, null, null, 0, 100));
    return actual.totalTracks() == expected.size()
        && actual.facets().artists().equals(facets(expected, "artist"))
        && actual.facets().albums().equals(facets(expected, "album"))
        && actual.facets().genres().equals(facets(expected, "genre"));
  }

  private static List<String> facets(List<Map<String, Object>> rows, String key) {
    var values = new TreeMap<String, String>();
    rows.stream().map(row -> text(row.get(key)))
        .filter(value -> value != null && !value.isBlank())
        .forEach(value -> values.merge(value.toLowerCase(Locale.ROOT), value,
            (left, right) -> left.compareTo(right) <= 0 ? left : right));
    return List.copyOf(values.values());
  }

  private static boolean verifyPlaylists(
      org.jooq.DSLContext context, Map<String, List<Map<String, Object>>> tables) {
    var repository = new PostgresMusicPlaylistRepository(context);
    for (var row : tables.get("playlist")) {
      var id = text(row.get("playlist_id"));
      var expected = tables.get("playlist_track").stream()
          .filter(child -> id.equals(text(child.get("playlist_id"))))
          .sorted(Comparator.comparingInt(child -> ((Number) child.get("ordinal")).intValue()))
          .map(child -> text(child.get("track_id"))).toList();
      var actual = repository.findById(id);
      if (actual.isEmpty() || !actual.orElseThrow().trackIds().equals(expected)) {
        return false;
      }
    }
    return repository.findById("migration-verifier-missing-playlist").isEmpty();
  }

  private static boolean verifyMetadataExpiry(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var cutoff = rows.stream().map(row -> instant(row.get("expires_at")))
        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    var expected = rows.stream().filter(row -> {
      var expiry = instant(row.get("expires_at"));
      return expiry != null && expiry.isBefore(cutoff);
    }).sorted(Comparator.comparing(
        (Map<String, Object> row) -> instant(row.get("expires_at")))
        .thenComparing(row -> text(row.get("metadata_edit_id"))))
        .map(row -> text(row.get("metadata_edit_id"))).toList();
    var actual = new PostgresMusicMetadataEditRepository(context)
        .findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff).stream()
        .map(value -> value.id()).toList();
    return actual.equals(expected.stream().limit(100).toList());
  }

  private static boolean verifyQueue(
      org.jooq.DSLContext context, Map<String, List<Map<String, Object>>> tables) {
    var expected = tables.get("runtime_state").stream()
        .filter(row -> "QUEUE".equals(text(row.get("state_kind")))).findFirst();
    var actual = new PostgresMusicRuntimeStateRepository(context).findQueue();
    if (expected.isEmpty()) {
      return actual.isEmpty();
    }
    var expectedEntries = tables.get("queue_entry").stream()
        .sorted(Comparator.comparingInt(row -> ((Number) row.get("ordinal")).intValue()))
        .map(row -> text(row.get("queue_entry_id"))).toList();
    return actual.isPresent()
        && actual.orElseThrow().entries().stream().map(entry -> entry.id()).toList()
            .equals(expectedEntries);
  }

  private static boolean verifyRadio(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var expected = rows.stream().filter(row -> "RADIO".equals(text(row.get("state_kind"))))
        .findFirst();
    var actual = new PostgresMusicRuntimeStateRepository(context).findRadio();
    return expected.isEmpty() ? actual.isEmpty()
        : actual.isPresent()
            && text(expected.orElseThrow().get("runtime_state_id"))
                .equals(actual.orElseThrow().id());
  }

  private static boolean verifyHistory(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var expected = rows.stream()
        .sorted(Comparator.comparingLong(
            (Map<String, Object> row) ->
                ((Number) row.get("station_sequence")).longValue()).reversed())
        .limit(100).map(row -> text(row.get("radio_history_id"))).toList();
    var actual = new PostgresMusicRadioHistoryRepository(context)
        .findTop100ByOrderByStationSequenceDesc().stream().map(value -> value.id()).toList();
    return actual.equals(expected);
  }

  private static boolean verifyAccessRecent(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var expected = rows.stream()
        .sorted(Comparator.comparing(
            (Map<String, Object> row) -> instant(row.get("last_attempt_at"))).reversed()
            .thenComparing(row -> text(row.get("access_attempt_id"))))
        .map(row -> text(row.get("access_attempt_id"))).toList();
    var actual = new PostgresMusicAccessAttemptRepository(context).recent(100).stream()
        .map(value -> value.id()).toList();
    return actual.equals(expected.stream().limit(100).toList());
  }

  private static boolean verifyAccessCleanup(
      Connection connection, org.jooq.DSLContext context, List<Map<String, Object>> rows)
      throws SQLException {
    var cutoff = rows.stream().map(row -> instant(row.get("expires_at")))
        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    var expected = rows.stream().filter(row -> {
      var expiry = instant(row.get("expires_at"));
      return expiry != null && !expiry.isAfter(cutoff);
    }).count();
    return rollback(connection, () ->
        new PostgresMusicAccessAttemptRepository(context).deleteExpired(cutoff, 10_000)
            == expected);
  }
}
