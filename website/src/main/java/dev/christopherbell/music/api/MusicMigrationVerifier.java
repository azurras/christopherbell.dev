package dev.christopherbell.music.api;

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
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var transactions = new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)));
    var rows = tables.values().stream().findFirst().orElse(List.of());
    return switch (sourceKind + "/" + queryName) {
      case "music_track/find-by-id" -> verifyOptionalLookup(
          tables.get("track"), "track_id", new PostgresMusicTrackRepository(jdbc, schemas)::findById);
      case "music_track/catalog-search" -> verifyCatalog(jdbc, schemas, tables.get("track"));
      case "music_playlist/find-by-id" -> verifyPlaylists(jdbc, schemas, transactions, tables);
      case "music_metadata_edit/find-by-id" -> verifyOptionalLookup(
          tables.get("metadata_edit"), "metadata_edit_id",
          new PostgresMusicMetadataEditRepository(jdbc, schemas)::findById);
      case "music_metadata_edit/expiration-page" -> verifyMetadataExpiry(jdbc, schemas, rows);
      case "music_runtime_state/global-queue" ->
          verifyQueue(jdbc, schemas, transactions, tables);
      case "music_runtime_state/global-radio" ->
          verifyRadio(jdbc, schemas, transactions, tables.get("runtime_state"));
      case "music_radio_history/station-sequence-page" ->
          verifyHistory(connection, schema, tables.get("radio_history"));
      case "music_access_attempt/recent-page" ->
          verifyAccessRecent(connection, schema, tables.get("access_attempt"));
      case "music_access_attempt/delete-expired" ->
          verifyAccessCleanup(connection, schema, tables.get("access_attempt"));
      default -> false;
    };
  }

  private static boolean verifyCatalog(
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var expected = rows.stream().filter(row -> row.get("missing_since") == null)
        .filter(row -> "READY".equals(text(row.get("index_status")))).toList();
    var actual = new PostgresMusicCatalogQueryRepository(database, schemas)
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
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      org.springframework.transaction.support.TransactionOperations transactions,
      Map<String, List<Map<String, Object>>> tables) {
    var repository = new PostgresMusicPlaylistRepository(database, schemas, transactions);
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
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var cutoff = rows.stream().map(row -> instant(row.get("expires_at")))
        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    var expected = rows.stream().filter(row -> {
      var expiry = instant(row.get("expires_at"));
      return expiry != null && expiry.isBefore(cutoff);
    }).sorted(Comparator.comparing(
        (Map<String, Object> row) -> instant(row.get("expires_at")))
        .thenComparing(row -> text(row.get("metadata_edit_id"))))
        .map(row -> text(row.get("metadata_edit_id"))).toList();
    var actual = new PostgresMusicMetadataEditRepository(database, schemas)
        .findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff).stream()
        .map(value -> value.id()).toList();
    return actual.equals(expected.stream().limit(100).toList());
  }

  private static boolean verifyQueue(
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      org.springframework.transaction.support.TransactionOperations transactions,
      Map<String, List<Map<String, Object>>> tables) {
    var expected = tables.get("runtime_state").stream()
        .filter(row -> "QUEUE".equals(text(row.get("state_kind")))).findFirst();
    var actual = new PostgresMusicRuntimeStateRepository(database, schemas, transactions).findQueue();
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
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      org.springframework.transaction.support.TransactionOperations transactions,
      List<Map<String, Object>> rows) {
    var expected = rows.stream().filter(row -> "RADIO".equals(text(row.get("state_kind"))))
        .findFirst();
    var actual = new PostgresMusicRuntimeStateRepository(database, schemas, transactions).findRadio();
    if (expected.isEmpty()) {
      return actual.isEmpty();
    }
    if (actual.isEmpty()) {
      return false;
    }
    var row = expected.orElseThrow();
    var state = actual.orElseThrow();
    return ((Number) row.get("station_sequence")).longValue() == state.stationSequence()
        && text(row.get("track_id")).equals(state.trackId())
        && text(row.get("observed_token")).equals(state.observedToken())
        && instant(row.get("started_at")).equals(state.startedAt())
        && Double.compare(((Number) row.get("duration_seconds")).doubleValue(),
            state.durationSeconds()) == 0
        && text(row.get("radio_source")).equals(state.source().name())
        && java.util.Objects.equals(row.get("queue_entry_id"), state.queueEntryId())
        && java.util.Objects.equals(((Number) row.get("version")).longValue(), state.version());
  }

  private static boolean verifyHistory(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var expected = rows.stream()
        .sorted(Comparator.comparingLong(
            (Map<String, Object> row) ->
                ((Number) row.get("station_sequence")).longValue()).reversed())
        .limit(100).map(row -> text(row.get("radio_history_id"))).toList();
    var actual = new PostgresMusicRadioHistoryRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema))
        .findTop100ByOrderByStationSequenceDesc().stream().map(value -> value.id()).toList();
    return actual.equals(expected);
  }

  private static boolean verifyAccessRecent(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var expected = rows.stream()
        .sorted(Comparator.comparing(
            (Map<String, Object> row) -> instant(row.get("last_attempt_at"))).reversed()
            .thenComparing(row -> text(row.get("access_attempt_id"))))
        .map(row -> text(row.get("access_attempt_id"))).toList();
    var actual = accessAttempts(connection, schema).recent(100).stream()
        .map(value -> value.id()).toList();
    return actual.equals(expected.stream().limit(100).toList());
  }

  private static boolean verifyAccessCleanup(
      Connection connection, String schema, List<Map<String, Object>> rows)
      throws SQLException {
    var cutoff = rows.stream().map(row -> instant(row.get("expires_at")))
        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    var expected = rows.stream().filter(row -> {
      var expiry = instant(row.get("expires_at"));
      return expiry != null && !expiry.isAfter(cutoff);
    }).count();
    return rollback(connection, () ->
        accessAttempts(connection, schema).deleteExpired(cutoff, 10_000) == expected);
  }

  private static PostgresMusicAccessAttemptRepository accessAttempts(
      Connection connection, String schema) {
    return new PostgresMusicAccessAttemptRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema));
  }
}
