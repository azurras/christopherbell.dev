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
    assertThat(registry.declarationCount())
        .isEqualTo(catalog.kinds().stream().mapToInt(kind -> kind.portQueries().size()).sum());
    assertThat(catalog.kinds().stream().mapToInt(kind -> kind.portQueries().size()).sum())
        .isEqualTo(registry.actualAdapterBindings().size());
    assertThat(registry.semanticFamily("post", "author-feed-page"))
        .isEqualTo("KEYSET_PAGE");
    assertThat(registry.semanticFamily("application_lease", "claim-expired-lease"))
        .isEqualTo("CONDITIONAL_CLAIM");
    assertThat(registry.semanticFamily("message", "participant-page"))
        .isEqualTo("JOINED_CHILD_PAGE");
    assertThat(registry.semanticFamily("music_track", "catalog-search"))
        .isEqualTo("GROUPED_PROJECTION");
    assertThat(registry.explicitFamilyDeclarations()).containsExactlyInAnyOrderElementsOf(Set.of(
        "message/participant-page",
        "session/participant-session-page",
        "music_playlist/find-by-id",
        "post_report/moderation-page",
        "music_track/catalog-search"));
  }

  @Test
  void everyDeclarationBindsAnActualAdapterOperationWithoutMigrationGenericFallback()
      throws IOException {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    var declared = loadCatalog().kinds().stream()
        .flatMap(kind -> kind.portQueries().stream()
            .map(query -> kind.sourceKind() + "/" + query))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    assertThat(registry.actualAdapterBindings())
        .extracting(binding -> binding.sourceKind() + "/" + binding.queryName())
        .containsExactlyInAnyOrderElementsOf(declared);
    assertThat(registry.actualAdapterBindings())
        .allSatisfy(binding -> {
          assertThat(binding.ownerType()).startsWith("dev.christopherbell.");
          assertThat(binding.ownerType()).doesNotContain(".persistence.migration.");
          assertThat(binding.operation()).isNotBlank();
          assertThat(java.util.Arrays.stream(loadClass(binding.ownerType()).getMethods())
              .map(java.lang.reflect.Method::getName))
              .contains(binding.operation());
        });
    assertThat(registry.migrationOwnedFallbackDeclarations()).isEmpty();
  }

  private static Class<?> loadClass(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException missingBinding) {
      throw new AssertionError("Missing adapter binding " + name, missingBinding);
    }
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
          connection, prefix + "music", "music_playlist", "find-by-id",
          playlistRows())).isTrue();
      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "social", "post_report", "moderation-page",
          moderationRows())).isTrue();
      assertThat(registry.verifyExplicitFamilyForTest(
          connection, prefix + "music", "music_track", "catalog-search", musicRows())).isTrue();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void highlightedDeclarationsExecuteRealAdapterSemantics() throws Exception {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var prefix = database.prefix();
      insertHighRiskAdapterFixtures(connection, prefix);

      assertThat(registry.verifyBoundAdapterForTest(
          connection, prefix + "communication", "notification", "account-page",
          Map.of("notification", notificationRows()))).isTrue();
      assertThat(registry.verifyBoundAdapterForTest(
          connection, prefix + "shared_folder", "recycle_item", "state-deleted-page",
          Map.of("recycle_item", recycleRows()))).isTrue();
      assertThat(registry.verifyBoundAdapterForTest(
          connection, prefix + "social", "post_report", "moderation-page",
          Map.of("post_report", reportRows()))).isTrue();

      connection.setAutoCommit(false);
      assertThat(registry.verifyConditionalClaimForTest(
          connection, prefix + "platform", "application_lease")).isTrue();
      assertThat(registry.verifyConditionalClaimForTest(
          connection, prefix + "shared_folder", "maintenance_lease")).isTrue();
      connection.rollback();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void lookupAndDeadlineStrategiesMatchAbsenceOrderingAndTies() throws Exception {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var prefix = database.prefix();
      insertLookupAndDeadlineFixtures(connection, prefix);

      assertThat(registry.verifyBoundAdapterForTest(
          connection, prefix + "identity", "account", "find-by-id",
          Map.of("account", List.of(
              row("account_id", "account-a"), row("account_id", "account-b")))))
          .isTrue();
      assertThat(registry.verifyBoundAdapterForTest(
          connection, prefix + "music", "music_metadata_edit", "expiration-page",
          Map.of("metadata_edit", metadataDeadlineRows())))
          .isTrue();
    }
  }

  private static void insertLookupAndDeadlineFixtures(
      java.sql.Connection connection, String prefix) throws java.sql.SQLException {
    execute(connection, "insert into \"" + prefix + "identity\".account "
        + "(account_id,email,normalized_email,role,status,username) values "
        + "('account-a','a@example.test','a@example.test','USER','ACTIVE','a'),"
        + "('account-b','b@example.test','b@example.test','USER','ACTIVE','b')");
    execute(connection, "insert into \"" + prefix + "music\".track "
        + "(track_id,relative_path,title,index_status) values "
        + "('track-a','a.mp3','a','READY')");
    execute(connection, "insert into \"" + prefix + "music\".metadata_edit "
        + "(metadata_edit_id,track_id,source_path,backup_file_name,backup_sha256,"
        + "original_observed_token,edited_by_account_id,created_at,expires_at,status) values "
        + "('deadline-z','track-a','z.mp3','z.bak',repeat('a',64),'z','account-a',"
        + "'2026-08-10','2026-08-11','PREPARED'),"
        + "('deadline-a','track-a','a.mp3','a.bak',repeat('b',64),'a','account-a',"
        + "'2026-08-10','2026-08-11','PREPARED'),"
        + "('deadline-middle','track-a','m.mp3','m.bak',repeat('c',64),'m','account-a',"
        + "'2026-08-10','2026-08-12','PREPARED'),"
        + "('deadline-cutoff','track-a','c.mp3','c.bak',repeat('d',64),'c','account-a',"
        + "'2026-08-10','2026-08-13','PREPARED')");
  }

  private static List<Map<String, Object>> metadataDeadlineRows() {
    return List.of(
        row("metadata_edit_id", "deadline-middle",
            "expires_at", instant("2026-08-12T00:00:00Z")),
        row("metadata_edit_id", "deadline-z",
            "expires_at", instant("2026-08-11T00:00:00Z")),
        row("metadata_edit_id", "deadline-cutoff",
            "expires_at", instant("2026-08-13T00:00:00Z")),
        row("metadata_edit_id", "deadline-a",
            "expires_at", instant("2026-08-11T00:00:00Z")));
  }

  private static void insertHighRiskAdapterFixtures(
      java.sql.Connection connection, String prefix) throws java.sql.SQLException {
    execute(connection, "insert into \"" + prefix + "identity\".account "
        + "(account_id,email,normalized_email,role,status,username) values "
        + "('account-a','a@example.test','a@example.test','USER','ACTIVE','a'),"
        + "('account-b','b@example.test','b@example.test','USER','ACTIVE','b')");
    execute(connection, "insert into \"" + prefix + "communication\".notification "
        + "(notification_id,account_id,notification_type,is_read,created_on) values "
        + "('notification-a','account-a','MESSAGE',false,'2026-08-14T00:00:00Z'),"
        + "('notification-b','account-a','MESSAGE',false,'2026-08-14T00:00:00Z'),"
        + "('notification-c','account-a','MESSAGE',true,'2026-08-15T00:00:00Z'),"
        + "('notification-other','account-b','MESSAGE',false,'2026-08-16T00:00:00Z')");
    execute(connection, "insert into \"" + prefix + "shared_folder\".recycle_item "
        + "(recycle_item_id,original_path,deleted_by_account_id,deleted_at,expires_at,"
        + "payload_key,size_bytes,is_directory,source_fingerprint,state,source_identity,retry_after) values "
        + "('recycle-a','a','account-a','2026-08-14','2099-01-01','a',1,false,'a','RECYCLED','a','1970-01-01'),"
        + "('recycle-b','b','account-a','2026-08-14','2099-01-01','b',1,false,'b','RECYCLED','b','1970-01-01'),"
        + "('recycle-c','c','account-a','2026-08-15','2099-01-01','c',1,false,'c','RECYCLED','c','1970-01-01'),"
        + "('recycle-other','d','account-a','2026-08-16','2099-01-01','d',1,false,'d','PURGING','d','1970-01-01')");
    execute(connection, "insert into \"" + prefix + "social\".post_report "
        + "(post_report_id,report_type,target_type,reason,status,reporter_username,created_on) values "
        + "('report-unmoderated','SPAM','POST','a','OPEN','Alpha','2026-08-16T00:00:00Z'),"
        + "('report-filtered','HARASSMENT','POST','b','RESOLVED','Beta','2026-08-15T00:00:00Z'),"
        + "('report-tie-b','SPAM','POST','c','OPEN','Gamma','2026-08-14T00:00:00Z'),"
        + "('report-tie-a','SPAM','POST','d','OPEN','Gamma','2026-08-14T00:00:00Z'),"
        + "('report-anonymous','SPAM','POST','e','OPEN',null,'2026-08-13T00:00:00Z')");
  }

  private static List<Map<String, Object>> notificationRows() {
    return List.of(
        row("notification_id", "notification-a", "account_id", "account-a",
            "created_on", instant("2026-08-14T00:00:00Z"), "is_read", false),
        row("notification_id", "notification-b", "account_id", "account-a",
            "created_on", instant("2026-08-14T00:00:00Z"), "is_read", false),
        row("notification_id", "notification-c", "account_id", "account-a",
            "created_on", instant("2026-08-15T00:00:00Z"), "is_read", true),
        row("notification_id", "notification-other", "account_id", "account-b",
            "created_on", instant("2026-08-16T00:00:00Z"), "is_read", false));
  }

  private static List<Map<String, Object>> recycleRows() {
    return List.of(
        row("recycle_item_id", "recycle-a", "state", "RECYCLED",
            "deleted_at", instant("2026-08-14T00:00:00Z")),
        row("recycle_item_id", "recycle-b", "state", "RECYCLED",
            "deleted_at", instant("2026-08-14T00:00:00Z")),
        row("recycle_item_id", "recycle-c", "state", "RECYCLED",
            "deleted_at", instant("2026-08-15T00:00:00Z")),
        row("recycle_item_id", "recycle-other", "state", "PURGING",
            "deleted_at", instant("2026-08-16T00:00:00Z")));
  }

  private static List<Map<String, Object>> reportRows() {
    return List.of(
        report("report-unmoderated", "SPAM", "OPEN", "Alpha", "2026-08-16T00:00:00Z"),
        report("report-filtered", "HARASSMENT", "RESOLVED", "Beta",
            "2026-08-15T00:00:00Z"),
        report("report-tie-b", "SPAM", "OPEN", "Gamma", "2026-08-14T00:00:00Z"),
        report("report-tie-a", "SPAM", "OPEN", "Gamma", "2026-08-14T00:00:00Z"),
        report("report-anonymous", "SPAM", "OPEN", null, "2026-08-13T00:00:00Z"));
  }

  private static Map<String, Object> report(
      String id, String type, String status, String reporter, String createdOn) {
    return row("post_report_id", id, "report_type", type, "target_type", "POST",
        "status", status, "reporter_username", reporter, "created_on", instant(createdOn));
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
        + "(post_report_id,report_type,target_type,reason,status,reporter_username,created_on) values "
        + "('report-a','SPAM','POST','a','OPEN','Alpha','2026-08-14T00:00:00Z'),"
        + "('report-b','SPAM','POST','b','OPEN','Beta','2026-08-15T00:00:00Z'),"
        + "('report-c','SPAM','POST','c','OPEN','Gamma','2026-08-16T00:00:00Z')");
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
    return Map.of("post_report", List.of(
        report("report-a", "SPAM", "OPEN", "Alpha", "2026-08-14T00:00:00Z"),
        report("report-b", "SPAM", "OPEN", "Beta", "2026-08-15T00:00:00Z"),
        report("report-c", "SPAM", "OPEN", "Gamma", "2026-08-16T00:00:00Z")));
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
