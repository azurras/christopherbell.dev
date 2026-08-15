package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MigrationPortQueryVerifierRegistryTest {
  @Test
  void executableRegistryNamesExactlyCoverEveryDeclaredCatalogPortQuery() throws IOException {
    var catalog = loadCatalog();
    var declared = new LinkedHashSet<String>();
    catalog.kinds().forEach(kind -> declared.addAll(kind.portQueries()));

    var registry = MigrationPortQueryVerifierRegistry.from(catalog);

    assertThat(registry.names()).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(registry.names()).hasSize(82);
    assertThat(registry.declarationCount()).isEqualTo(153);
    assertThat(catalog.kinds().stream().mapToInt(kind -> kind.portQueries().size()).sum())
        .isEqualTo(153);
    assertThat(registry.semanticFamily("post", "author-feed-page"))
        .isEqualTo("KEYSET_PAGE");
    assertThat(registry.semanticFamily("application_lease", "claim-expired-lease"))
        .isEqualTo("CONDITIONAL_CLAIM");
    assertThat(registry.semanticFamily("message", "participant-page"))
        .isEqualTo("JOINED_CHILD_PAGE");
    assertThat(registry.semanticFamily("music_track", "artist-page"))
        .isEqualTo("GROUPED_PROJECTION");
    assertThat(registry.explicitFamilyDeclarations()).containsExactlyInAnyOrderElementsOf(Set.of(
        "message/participant-page",
        "session/participant-session-page",
        "music_playlist/playlist-track-order",
        "post_report/moderation-page",
        "music_track/artist-page",
        "music_track/album-page",
        "music_track/genre-page"));
    assertThat(registry.nullPlacement("account_follow", "follower-page", "created_on"))
        .isEqualTo("FIRST");
    assertThat(registry.nullPlacement(
        "federation_delivery_job", "due-job-page", "next_attempt_on"))
        .isEqualTo("FIRST");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void everyExplicitDeclarationReferencesRealTypedColumns() throws Exception {
    var catalog = loadCatalog();
    var registry = MigrationPortQueryVerifierRegistry.from(catalog);
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(registry.schemaViolations(connection, database.prefix(), catalog)).isEmpty();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void conditionalLeaseStrategiesUseDatabaseTimeAndRollbackEveryProbe() throws Exception {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      connection.setAutoCommit(false);
      assertThat(registry.verifyConditionalClaimForTest(
          connection, database.prefix() + "platform", "application_lease")).isTrue();
      assertThat(registry.verifyConditionalClaimForTest(
          connection, database.prefix() + "shared_folder", "maintenance_lease")).isTrue();
      assertThat(count(connection, database.prefix() + "platform", "application_lease"))
          .isZero();
      assertThat(count(connection, database.prefix() + "shared_folder", "maintenance_lease"))
          .isZero();
      connection.rollback();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void joinedAndGroupedStrategiesMatchProductionMultiRowBehavior() throws Exception {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var prefix = database.prefix();
      insertExplicitFamilyFixtures(connection, prefix);

      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "communication", "message", "participant-page",
          messageRows())).isTrue();
      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "lunch", "session", "participant-session-page",
          lunchRows())).isTrue();
      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "music", "music_playlist", "playlist-track-order",
          playlistRows())).isTrue();
      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "social", "post_report", "moderation-page",
          moderationRows())).isTrue();
      for (var query : List.of("artist-page", "album-page", "genre-page")) {
        assertThat(registry.verifyExplicitFamilyForTest(
            connection, prefix + "music", "music_track", query, musicRows())).isTrue();
      }
    }
  }

  private static void insertExplicitFamilyFixtures(
      java.sql.Connection connection, String prefix) throws java.sql.SQLException {
    execute(connection, "insert into \"" + prefix + "identity\".account "
        + "(account_id,email,normalized_email,role,status,username) values "
        + "('account-a','a@example.test','a@example.test','USER','ACTIVE','a'),"
        + "('account-b','b@example.test','b@example.test','USER','ACTIVE','b')");
    execute(connection, "insert into \"" + prefix + "communication\".message "
        + "(message_id,conversation_key,sender_account_id,recipient_account_id,message_text,created_on) values "
        + "('message-a','c','account-a','account-b','a','2026-08-14T00:00:00Z'),"
        + "('message-b','c','account-b','account-a','b','2026-08-14T00:00:00Z'),"
        + "('message-c','c','account-a','account-b','c','2026-08-15T00:00:00Z')");
    execute(connection, "insert into \"" + prefix + "communication\".message_participant "
        + "(message_id,account_id) values ('message-a','account-a'),"
        + "('message-b','account-a'),('message-c','account-b')");
    execute(connection, "insert into \"" + prefix + "lunch\".lunch_session "
        + "(lunch_session_id,active_until,created_by_account_id,created_by_username,created_on,delete_on,last_updated_on) values "
        + "('session-a','2099-01-01','account-a','a','2026-08-14','2099-01-02','2026-08-14'),"
        + "('session-b','2099-01-01','account-a','a','2026-08-15','2099-01-02','2026-08-15'),"
        + "('session-expired','2000-01-01','account-a','a','1999-01-01','2000-01-02','1999-01-01')");
    execute(connection, "insert into \"" + prefix + "lunch\".lunch_session_participant "
        + "(lunch_session_id,ordinal,account_id,username) values "
        + "('session-a',0,'account-a','a'),('session-b',0,'account-a','a'),"
        + "('session-b',1,'account-b','b'),('session-expired',0,'account-a','a')");
    execute(connection, "insert into \"" + prefix + "music\".track "
        + "(track_id,relative_path,title,artist,album,genre,index_status,missing_since) values "
        + "('track-a','a.mp3','a','Alpha','First','Rock','READY',null),"
        + "('track-b','b.mp3','b','alpha','Second','Jazz','READY',null),"
        + "('track-building','building.mp3','c','Hidden','Hidden','Hidden','BUILDING',null),"
        + "('track-missing','missing.mp3','d','Missing','Missing','Missing','READY','2026-08-14'),"
        + "('track-blank','blank.mp3','e',' ',' ',' ','READY',null)");
    execute(connection, "insert into \"" + prefix + "music\".playlist "
        + "(playlist_id,normalized_name,name,updated_by_account_id,updated_at) values "
        + "('playlist-a','a','A','account-a','2026-08-14')");
    execute(connection, "insert into \"" + prefix + "music\".playlist_track "
        + "(playlist_id,ordinal,track_id) values "
        + "('playlist-a',0,'track-b'),('playlist-a',1,'track-a')");
    execute(connection, "insert into \"" + prefix + "social\".post_report "
        + "(post_report_id,report_type,target_type,reason,status,created_on) values "
        + "('report-a','SPAM','POST','a','OPEN','2026-08-14'),"
        + "('report-b','SPAM','POST','b','OPEN','2026-08-15'),"
        + "('report-c','SPAM','POST','c','OPEN','2026-08-16')");
    execute(connection, "insert into \"" + prefix + "social\".post_report_moderation_audit "
        + "(post_report_id,event_id,actor_account_id,actor_username,action,target_type,target_id,target_label,reason,message) values "
        + "('report-a','event-a','account-a','a','UPDATE','POST','post-a','a','a','a'),"
        + "('report-b','event-b','account-a','a','UPDATE','POST','post-b','b','b','b')");
  }

  private static Map<String, List<Map<String, Object>>> messageRows() {
    return Map.of(
        "message", List.of(
            row("message_id", "message-a", "created_on", instant("2026-08-14T00:00:00Z")),
            row("message_id", "message-b", "created_on", instant("2026-08-14T00:00:00Z")),
            row("message_id", "message-c", "created_on", instant("2026-08-15T00:00:00Z"))),
        "message_participant", List.of(
            row("message_id", "message-a", "account_id", "account-a"),
            row("message_id", "message-b", "account_id", "account-a"),
            row("message_id", "message-c", "account_id", "account-b")));
  }

  private static Map<String, List<Map<String, Object>>> lunchRows() {
    return Map.of(
        "lunch_session", List.of(
            row("lunch_session_id", "session-a", "created_on", instant("2026-08-14T00:00:00Z"),
                "delete_on", instant("2099-01-02T00:00:00Z")),
            row("lunch_session_id", "session-b", "created_on", instant("2026-08-15T00:00:00Z"),
                "delete_on", instant("2099-01-02T00:00:00Z")),
            row("lunch_session_id", "session-expired", "created_on", instant("1999-01-01T00:00:00Z"),
                "delete_on", instant("2000-01-02T00:00:00Z"))),
        "lunch_session_participant", List.of(
            row("lunch_session_id", "session-a", "account_id", "account-a"),
            row("lunch_session_id", "session-b", "account_id", "account-a"),
            row("lunch_session_id", "session-b", "account_id", "account-b"),
            row("lunch_session_id", "session-expired", "account_id", "account-a")));
  }

  private static Map<String, List<Map<String, Object>>> playlistRows() {
    return Map.of(
        "playlist", List.of(row("playlist_id", "playlist-a")),
        "playlist_track", List.of(
            row("playlist_id", "playlist-a", "ordinal", 0, "track_id", "track-b"),
            row("playlist_id", "playlist-a", "ordinal", 1, "track_id", "track-a")));
  }

  private static Map<String, List<Map<String, Object>>> moderationRows() {
    return Map.of(
        "post_report", List.of(
            row("post_report_id", "report-a", "created_on", instant("2026-08-14T00:00:00Z")),
            row("post_report_id", "report-b", "created_on", instant("2026-08-15T00:00:00Z")),
            row("post_report_id", "report-c", "created_on", instant("2026-08-16T00:00:00Z"))),
        "post_report_moderation_audit", List.of(
            row("post_report_id", "report-a"), row("post_report_id", "report-b")));
  }

  private static Map<String, List<Map<String, Object>>> musicRows() {
    return Map.of("track", List.of(
        track("track-a", "Alpha", "First", "Rock", "READY", null),
        track("track-b", "alpha", "Second", "Jazz", "READY", null),
        track("track-building", "Hidden", "Hidden", "Hidden", "BUILDING", null),
        track("track-missing", "Missing", "Missing", "Missing", "READY",
            instant("2026-08-14T00:00:00Z")),
        track("track-blank", " ", " ", " ", "READY", null)));
  }

  private static Map<String, Object> track(
      String id, String artist, String album, String genre, String status, Instant missing) {
    return row("track_id", id, "artist", artist, "album", album, "genre", genre,
        "index_status", status, "missing_since", missing);
  }

  private static Instant instant(String value) {
    return Instant.parse(value);
  }

  private static Map<String, Object> row(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (var index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }

  private static void execute(java.sql.Connection connection, String sql)
      throws java.sql.SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static long count(java.sql.Connection connection, String schema, String table)
      throws java.sql.SQLException {
    try (var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select count(*) from \"" + schema + "\".\"" + table + "\"")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationPortQueryVerifierRegistryTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}
