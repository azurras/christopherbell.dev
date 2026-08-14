package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresqlSchemaContractTest {

  @Test
  void twoLivePrefixesKeepFlywayHistoryAndDomainSchemasIsolated() throws Exception {
    try (var first = PostgresqlSchemaTestSupport.migrate();
         var second = PostgresqlSchemaTestSupport.migrate();
         var connection = first.connect()) {
      assertThat(first.migrationsExecuted()).isEqualTo(10);
      assertThat(second.migrationsExecuted()).isEqualTo(10);
      assertThat(ownedSchemas(connection, first.prefix()))
          .hasSize(PostgresqlSchemaTestSupport.DOMAINS.size());
      assertThat(ownedSchemas(connection, second.prefix()))
          .hasSize(PostgresqlSchemaTestSupport.DOMAINS.size());
      assertThat(task2HistoryTableCount(connection)).isEqualTo(2);
    }
  }

  @Test
  void emptyFlywayMigrationCreatesExactlyTheTenOwnedCatalogSchemasAndTables() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(database.migrationsExecuted()).isEqualTo(10);
      assertThat(ownedSchemas(connection, database.prefix()))
          .containsExactlyInAnyOrderElementsOf(PostgresqlSchemaTestSupport.DOMAINS.stream()
              .map(database.prefix()::concat)
              .toList());
      assertThat(canonicalTables(connection, database.prefix()))
          .containsExactlyInAnyOrderElementsOf(catalogTables());
      assertThat(missingCatalogTargets(connection, database.prefix())).isEmpty();
      assertThat(scalar(connection,
          "select count(*) from information_schema.columns "
              + "where left(table_schema, ?) = ? and data_type in ('json', 'jsonb')",
          database.prefix().length(), database.prefix())).isZero();
      assertThat(scalar(connection,
          "select count(*) from information_schema.columns "
              + "where left(table_schema, ?) = ? and table_name <> 'flyway_schema_history' "
              + "and data_type = 'timestamp without time zone'",
          database.prefix().length(), database.prefix())).isZero();
      assertThat(tablesWithoutPrimaryKeys(connection, database.prefix())).isEmpty();
    }
  }

  @Test
  void schemaEnforcesKeysDeleteRulesPrecisionAndCursorIndexes() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(constraintDeleteRule(connection, database.prefix() + "social",
          "post_author_fk")).isEqualTo("RESTRICT");
      assertThat(constraintDeleteRule(connection, database.prefix() + "social",
          "post_like_post_fk")).isEqualTo("CASCADE");
      assertThat(constraintDeleteRule(connection, database.prefix() + "music",
          "playlist_track_playlist_fk")).isEqualTo("CASCADE");
      assertThat(constraintDeleteRule(connection, database.prefix() + "music",
          "playlist_updated_by_account_fk")).isEqualTo("RESTRICT");
      assertThat(columnPrecision(connection, database.prefix() + "lunch", "restaurant",
          "latitude")).containsExactly(9, 6);
      assertThat(columnPrecision(connection, database.prefix() + "canes", "price_snapshot",
          "average_price")).containsExactly(12, 2);
    }
  }

  @Test
  void everyManifestIndexHasAnEquivalentRelationalConstraintOrIndex() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(manifestIndexViolations(connection, database.prefix())).isEmpty();
    }
  }

  @Test
  void schemaPreservesNullableVinCacheAndLunchPreferenceStates() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      execute(connection, "insert into \"" + database.prefix()
          + "mobility\".vin_decode_cache (vin) values ('JM1BN1L30K1234567')");
      execute(connection, "update \"" + database.prefix()
          + "mobility\".vin_decode_cache set body = 'HATCHBACK' "
          + "where vin = 'JM1BN1L30K1234567'");
      assertThat(columnType(connection, database.prefix() + "mobility", "vin_decode_cache", "body"))
          .isEqualTo("text");
      assertThat(nullableColumns(connection, database.prefix() + "mobility", "vin_decode_cache"))
          .contains("body", "created_on", "expires_on", "last_updated_on", "refreshed_on");

      execute(connection, "insert into \"" + database.prefix()
          + "identity\".account (account_id, email, normalized_email, role, status, username) "
          + "values ('preference-owner', 'preference@example.com', 'preference@example.com', "
          + "'USER', 'ACTIVE', 'preference-owner')");
      execute(connection, "insert into \"" + database.prefix()
          + "lunch\".lunch_preference (account_id) values ('preference-owner')");
      assertThat(nullableColumns(connection, database.prefix() + "lunch", "lunch_preference"))
          .contains("radius_miles");
    }
  }

  @Test
  void catalogLoadOrderAndDependenciesCoverEveryRelationalForeignKey() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var catalog = loadCatalog();
      assertThat(relationalDependencyViolations(connection, database.prefix(), catalog)).isEmpty();
      assertThat(constraintDeleteRule(connection, database.prefix() + "communication",
          "notification_lunch_session_fk")).isEqualTo("SET NULL");
    }
  }

  @Test
  void restaurantSparseNameUniquenessAllowsMissingNamesButRejectsDuplicateNames()
      throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      execute(connection, "insert into \"" + database.prefix()
          + "lunch\".restaurant "
          + "(restaurant_id, display_name, dedupe_key, search_city, search_state) values "
          + "('restaurant-null-1', 'One', 'null-1', 'city', 'state'), "
          + "('restaurant-null-2', 'Two', 'null-2', 'city', 'state')");
      execute(connection, "insert into \"" + database.prefix()
          + "lunch\".restaurant "
          + "(restaurant_id, display_name, normalized_name, dedupe_key, search_city, search_state) "
          + "values ('restaurant-name-1', 'Named', 'named', 'name-1', 'city', 'state')");
      assertThatThrownBy(() -> execute(connection, "insert into \"" + database.prefix()
          + "lunch\".restaurant "
          + "(restaurant_id, display_name, normalized_name, dedupe_key, search_city, search_state) "
          + "values ('restaurant-name-2', 'Named Again', 'named', 'name-2', 'city', 'state')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

    }
  }

  @Test
  void deletingTheRequiredPlaylistUpdaterIsRestricted() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      execute(connection, "insert into \"" + database.prefix()
          + "identity\".account (account_id, email, normalized_email, role, status, username) "
          + "values ('playlist-owner', 'playlist@example.com', 'playlist@example.com', "
          + "'USER', 'ACTIVE', 'playlist-owner')");
      execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist "
          + "(playlist_id, normalized_name, name, updated_by_account_id, updated_at) "
          + "values ('playlist-owned', 'owned', 'Owned', 'playlist-owner', transaction_timestamp())");
      assertThatThrownBy(() -> execute(connection, "delete from \"" + database.prefix()
          + "identity\".account where account_id = 'playlist-owner'"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23001");
      assertThat(longScalar(connection, "select count(*) from \"" + database.prefix()
          + "music\".playlist where playlist_id = 'playlist-owned'"))
          .isOne();
    }
  }

  @Test
  void versionSixPseudonymsUpgradeIntoTheGuardedRegistry() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrateThrough("6");
         var connection = database.connect()) {
      assertThat(database.migrationsExecuted()).isEqualTo(6);
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('upgrade-owner', 'upgrade@example.test', 'upgrade@example.test', "
          + "'USER', 'ACTIVE', 'upgrade-owner')");
      execute(connection, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('upgrade-post', 'upgrade-owner', 'before', 'upgrade-post', transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('upgrade-post', 0, 'deleted:abcdef012345', 'before', 'after', "
          + "transaction_timestamp())");

      assertThat(database.migrateToLatest()).isEqualTo(4);
      assertThat(longScalar(connection, "select count(*) from " + identity
          + ".deleted_account_pseudonym where pseudonym_id = 'deleted:abcdef012345'"))
          .isOne();
      assertForeignKeyViolation(() -> execute(connection, "update " + social
          + ".post_edit_audit set editor_account_id = 'arbitrary-dangling-id' "
          + "where post_id = 'upgrade-post'"));
    }
  }

  @Test
  void versionSevenUpgradesParentDeletionGuards() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrateThrough("7");
         var connection = database.connect()) {
      assertThat(database.migrationsExecuted()).isEqualTo(7);
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('v7-owner', 'v7@example.test', 'v7@example.test', 'USER', 'ACTIVE', 'v7-owner')");
      execute(connection, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('v7-post', 'v7-owner', 'before', 'v7-post', transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('v7-post', 0, 'v7-owner', 'before', 'after', "
          + "transaction_timestamp())");

      assertThat(database.migrateToLatest()).isEqualTo(3);
      assertRestrictViolation(() -> execute(connection,
          "delete from " + identity + ".account where account_id = 'v7-owner'"));
    }
  }

  @Test
  void versionEightRowsUpgradeWithoutInventingPresenceAndAcceptNullableSourceState()
      throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrateThrough("8");
         var connection = database.connect()) {
      var identity = quoted(database.prefix() + "identity");
      var lunch = quoted(database.prefix() + "lunch");
      var mobility = quoted(database.prefix() + "mobility");
      var platform = quoted(database.prefix() + "platform");
      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('v8-owner', 'v8@example.test', 'v8@example.test', 'USER', 'ACTIVE', 'v8-owner')");
      execute(connection, "insert into " + lunch
          + ".restaurant (restaurant_id, display_name, dedupe_key, search_city, search_state) "
          + "values ('v8-restaurant', 'V8', 'v8', 'austin', 'tx')");
      execute(connection, "insert into " + lunch
          + ".restaurant_vote (restaurant_vote_id, account_id, restaurant_id, vote_value, "
          + "created_on, last_updated_on) values "
          + "('v8-vote', 'v8-owner', 'v8-restaurant', -1, transaction_timestamp(), "
          + "transaction_timestamp())");
      execute(connection, "insert into " + mobility
          + ".vin_decode_cache (vin) values ('V8EMPTYRESPONSE1')");
      execute(connection, "insert into " + mobility
          + ".vin_decode_cache (vin, make) values ('V8FILLEDRESP0001', 'Mazda')");
      execute(connection, "insert into " + mobility
          + ".nhtsa_import_state (import_state_id) values ('v8-nhtsa')");
      execute(connection, "insert into " + mobility
          + ".random_vin_import_state (import_state_id) values ('v8-random')");
      execute(connection, "insert into " + mobility
          + ".random_vin_import_state (import_state_id, robots_reason) "
          + "values ('v8-random-present', 'policy')");
      execute(connection, "insert into " + platform
          + ".admin_activity (admin_activity_id, actor_username, action, target_type, target_id, "
          + "target_label, reason, message, created_on) values "
          + "('v8-admin', 'v8-owner', 'V8', 'TEST', 'v8-target', '', '', '', "
          + "transaction_timestamp())");
      execute(connection, "insert into " + platform
          + ".admin_activity_value (admin_activity_id, partition_name, value_key, value_text) "
          + "values ('v8-admin', 'before', 'state', 'old')");

      assertThat(database.migrateToLatest()).isEqualTo(2);
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".vin_decode_cache where vin = 'V8EMPTYRESPONSE1' and not response_present"))
          .isOne();
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".vin_decode_cache where vin = 'V8FILLEDRESP0001' and response_present"))
          .isOne();
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".random_vin_import_state where import_state_id = 'v8-random' "
          + "and not robots_policy_present"))
          .isOne();
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".random_vin_import_state where import_state_id = 'v8-random-present' "
          + "and robots_policy_present"))
          .isOne();
      assertThat(longScalar(connection, "select count(*) from " + platform
          + ".admin_activity where admin_activity_id = 'v8-admin' "
          + "and before_values_present and not after_values_present "
          + "and not metadata_present"))
          .isOne();
      execute(connection, "insert into " + lunch
          + ".restaurant (restaurant_id, display_name, normalized_name, dedupe_key, "
          + "search_city, search_state) values "
          + "('v8-restaurant-peer', 'V8 Peer', 'v8 peer', 'v8', 'austin', 'tx')");
      assertThatThrownBy(() -> execute(connection, "insert into " + lunch
          + ".restaurant (restaurant_id, display_name, normalized_name, dedupe_key, "
          + "search_city, search_state) values "
          + "('v8-restaurant-conflict', 'V8 Conflict', 'v8 peer', 'v8-conflict', "
          + "'austin', 'tx')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

      execute(connection, "update " + mobility + ".nhtsa_import_state set calls_today = null, "
          + "lifetime_calls = null, lifetime_vins_processed = null, "
          + "permanently_disabled = null, vins_processed_today = null "
          + "where import_state_id = 'v8-nhtsa'");
      execute(connection, "update " + mobility + ".random_vin_import_state set "
          + "calls_today = null, lifetime_calls = null, lifetime_vins_processed = null, "
          + "permanently_disabled = null, robots_allowed = null, robots_fail_closed = null, "
          + "vins_processed_today = null where import_state_id = 'v8-random'");
      execute(connection, "update " + lunch
          + ".restaurant_vote set vote_value = null where restaurant_vote_id = 'v8-vote'");
      execute(connection, "update " + platform
          + ".admin_activity set target_label = null, reason = null, message = null "
          + "where admin_activity_id = 'v8-admin'");
      assertThat(longScalar(connection, "select count(*) from " + lunch
          + ".restaurant_vote where restaurant_vote_id = 'v8-vote' and vote_value is null"))
          .isOne();
    }
  }

  @Test
  void versionNineRowsBackfillOnlyProvableRawValuePresence() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrateThrough("9");
         var connection = database.connect()) {
      var mobility = quoted(database.prefix() + "mobility");
      execute(connection, "insert into " + mobility
          + ".vin_decode_cache (vin, response_present) values "
          + "('V9AMBIGUOUS000001', true), ('V9WITHRAWVALUE001', true)");
      execute(connection, "insert into " + mobility
          + ".vin_decode_raw_value (vin, field_name, field_value) values "
          + "('V9WITHRAWVALUE001', 'Make', 'Mazda')");

      assertThat(database.migrateToLatest()).isOne();
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".vin_decode_cache where vin = 'V9AMBIGUOUS000001' "
          + "and not raw_decoded_values_present"))
          .isOne();
      assertThat(longScalar(connection, "select count(*) from " + mobility
          + ".vin_decode_cache where vin = 'V9WITHRAWVALUE001' "
          + "and raw_decoded_values_present"))
          .isOne();
      assertThatThrownBy(() -> execute(connection, "update " + mobility
          + ".vin_decode_cache set response_present = false, "
          + "raw_decoded_values_present = true where vin = 'V9AMBIGUOUS000001'"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23514");
    }
  }

  @Test
  void versionSixDanglingIdentifierPreventsUpgrade() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrateThrough("6");
         var connection = database.connect()) {
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('v6-owner', 'v6@example.test', 'v6@example.test', 'USER', 'ACTIVE', 'v6-owner')");
      execute(connection, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('v6-post', 'v6-owner', 'before', 'v6-post', transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('v6-post', 0, 'unregistered-v6-id', 'before', 'after', "
          + "transaction_timestamp())");

      assertThatThrownBy(database::migrateToLatest)
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23503");
    }
  }

  @Test
  void retainedIdentifierConstraintsRejectUnknownAccountsAcrossEveryUnlinkedColumn()
      throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      var platform = quoted(database.prefix() + "platform");
      var sharedFolder = quoted(database.prefix() + "shared_folder");
      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('guard-owner', 'guard@example.test', 'guard@example.test', "
          + "'USER', 'ACTIVE', 'guard-owner')");
      execute(connection, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('guard-post', 'guard-owner', 'before', 'guard-post', transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('guard-post', 0, 'guard-owner', 'before', 'after', "
          + "transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_report (post_report_id, reported_account_id, reporter_account_id, report_type, "
          + "target_type, reason, status, created_on) values "
          + "('guard-report', 'guard-owner', 'guard-owner', 'SPAM', 'POST', 'guard', 'OPEN', "
          + "transaction_timestamp())");
      execute(connection, "insert into " + platform
          + ".admin_activity (admin_activity_id, actor_account_id, actor_username, action, "
          + "target_type, target_id, target_label, reason, message, created_on) values "
          + "('guard-activity', 'guard-owner', 'guard-owner', 'GUARD', 'ACCOUNT', "
          + "'guard-owner', 'guard-owner', 'guard', 'guard', transaction_timestamp())");
      execute(connection, "insert into " + sharedFolder
          + ".audit_event (audit_event_id, account_id, action, outcome, client_ip, occurred_at, "
          + "expires_at) values ('guard-audit', 'guard-owner', 'GUARD', 'SUCCESS', "
          + "inet '127.0.0.1', transaction_timestamp(), transaction_timestamp() + interval '1 day')");
      execute(connection, "insert into " + sharedFolder
          + ".recycle_item (recycle_item_id, original_path, deleted_by_account_id, deleted_at, "
          + "expires_at, payload_key, size_bytes, source_fingerprint, state, source_identity, "
          + "retry_after) values ('guard-recycle', '/guard', 'guard-owner', transaction_timestamp(), "
          + "transaction_timestamp() + interval '1 day', 'payload', 1, 'fingerprint', 'READY', "
          + "'source', transaction_timestamp())");

      assertForeignKeyViolation(() -> execute(connection, "update " + social
          + ".post_edit_audit set editor_account_id = 'dangling-editor' where post_id = 'guard-post'"));
      assertForeignKeyViolation(() -> execute(connection, "update " + social
          + ".post_report set reported_account_id = 'dangling-reported' "
          + "where post_report_id = 'guard-report'"));
      assertForeignKeyViolation(() -> execute(connection, "update " + social
          + ".post_report set reporter_account_id = 'dangling-reporter' "
          + "where post_report_id = 'guard-report'"));
      assertForeignKeyViolation(() -> execute(connection, "update " + platform
          + ".admin_activity set actor_account_id = 'dangling-actor' "
          + "where admin_activity_id = 'guard-activity'"));
      assertForeignKeyViolation(() -> execute(connection, "update " + sharedFolder
          + ".audit_event set account_id = 'dangling-auditor' where audit_event_id = 'guard-audit'"));
      assertForeignKeyViolation(() -> execute(connection, "update " + sharedFolder
          + ".recycle_item set deleted_by_account_id = 'dangling-deleter' "
          + "where recycle_item_id = 'guard-recycle'"));
    }
  }

  @Test
  void retainedIdentifiersCannotBeOrphanedByDeletingEitherParent() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      var platform = quoted(database.prefix() + "platform");
      var sharedFolder = quoted(database.prefix() + "shared_folder");
      var references = List.of(
          new RetainedReference(social, "post_edit_audit", "editor_account_id",
              "live-editor", "deleted:000000000001"),
          new RetainedReference(social, "post_report", "reported_account_id",
              "live-reported", "deleted:000000000002"),
          new RetainedReference(social, "post_report", "reporter_account_id",
              "live-reporter", "deleted:000000000003"),
          new RetainedReference(platform, "admin_activity", "actor_account_id",
              "live-admin", "deleted:000000000004"),
          new RetainedReference(sharedFolder, "audit_event", "account_id",
              "live-audit", "deleted:000000000005"),
          new RetainedReference(sharedFolder, "recycle_item", "deleted_by_account_id",
              "live-recycle", "deleted:000000000006"));

      execute(connection, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('content-owner', 'content@example.test', 'content@example.test', "
          + "'USER', 'ACTIVE', 'content-owner'), "
          + "('live-editor', 'editor@example.test', 'editor@example.test', "
          + "'USER', 'ACTIVE', 'live-editor'), "
          + "('live-reported', 'reported@example.test', 'reported@example.test', "
          + "'USER', 'ACTIVE', 'live-reported'), "
          + "('live-reporter', 'reporter@example.test', 'reporter@example.test', "
          + "'USER', 'ACTIVE', 'live-reporter'), "
          + "('live-admin', 'admin@example.test', 'admin@example.test', "
          + "'USER', 'ACTIVE', 'live-admin'), "
          + "('live-audit', 'audit@example.test', 'audit@example.test', "
          + "'USER', 'ACTIVE', 'live-audit'), "
          + "('live-recycle', 'recycle@example.test', 'recycle@example.test', "
          + "'USER', 'ACTIVE', 'live-recycle')");
      execute(connection, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('parent-guard-post', 'content-owner', 'before', 'parent-guard-post', "
          + "transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('parent-guard-post', 0, 'live-editor', 'before', 'after', "
          + "transaction_timestamp())");
      execute(connection, "insert into " + social
          + ".post_report (post_report_id, reported_account_id, reporter_account_id, report_type, "
          + "target_type, reason, status, created_on) values "
          + "('parent-guard-report', 'live-reported', 'live-reporter', 'SPAM', 'POST', "
          + "'guard', 'OPEN', transaction_timestamp())");
      execute(connection, "insert into " + platform
          + ".admin_activity (admin_activity_id, actor_account_id, actor_username, action, "
          + "target_type, target_id, target_label, reason, message, created_on) values "
          + "('parent-guard-activity', 'live-admin', 'live-admin', 'GUARD', 'ACCOUNT', "
          + "'target', 'target', 'guard', 'guard', transaction_timestamp())");
      execute(connection, "insert into " + sharedFolder
          + ".audit_event (audit_event_id, account_id, action, outcome, occurred_at, expires_at) "
          + "values ('parent-guard-audit', 'live-audit', 'GUARD', 'SUCCESS', "
          + "transaction_timestamp(), transaction_timestamp() + interval '1 day')");
      execute(connection, "insert into " + sharedFolder
          + ".recycle_item (recycle_item_id, original_path, deleted_by_account_id, deleted_at, "
          + "expires_at, payload_key, size_bytes, source_fingerprint, state, source_identity, "
          + "retry_after) values ('parent-guard-recycle', '/guard', 'live-recycle', "
          + "transaction_timestamp(), transaction_timestamp() + interval '1 day', 'payload', 1, "
          + "'fingerprint', 'READY', 'source', transaction_timestamp())");

      for (var reference : references) {
        assertRestrictViolation(() -> execute(connection, "delete from " + identity
            + ".account where account_id = '" + reference.liveId() + "'"));
        execute(connection, "insert into " + identity
            + ".deleted_account_pseudonym (pseudonym_id) values ('"
            + reference.pseudonym() + "')");
        execute(connection, "update " + reference.schema() + "." + reference.table()
            + " set " + reference.column() + " = '" + reference.pseudonym()
            + "' where " + reference.column() + " = '" + reference.liveId() + "'");
        assertThat(executeUpdate(connection, "delete from " + identity
            + ".account where account_id = '" + reference.liveId() + "'"))
            .as(reference.column())
            .isOne();
        assertRestrictViolation(() -> execute(connection, "delete from " + identity
            + ".deleted_account_pseudonym where pseudonym_id = '"
            + reference.pseudonym() + "'"));
      }
    }
  }

  @Test
  void committedChildInsertMakesConcurrentLiveAccountDeleteBlockThenFail() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var setup = database.connect();
         var child = database.connect();
         var parent = database.connect();
         var observer = database.connect();
         var executor = Executors.newSingleThreadExecutor()) {
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      execute(setup, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('race-content', 'race-content@example.test', 'race-content@example.test', "
          + "'USER', 'ACTIVE', 'race-content'), "
          + "('race-live', 'race-live@example.test', 'race-live@example.test', "
          + "'USER', 'ACTIVE', 'race-live')");
      execute(setup, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('race-post', 'race-content', 'before', 'race-post', transaction_timestamp())");
      execute(parent, "set statement_timeout = '10s'");

      child.setAutoCommit(false);
      execute(child, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('race-post', 0, 'race-live', 'before', 'after', "
          + "transaction_timestamp())");
      var childPid = Math.toIntExact(longScalar(child, "select pg_backend_pid()"));
      var parentPid = Math.toIntExact(longScalar(parent, "select pg_backend_pid()"));
      var deletion = executor.submit(() -> sqlState(() -> execute(parent,
          "delete from " + identity + ".account where account_id = 'race-live'")));

      awaitBlockedBy(observer, parentPid, childPid);
      child.commit();

      assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo("23001");
      assertThat(longScalar(setup, "select count(*) from " + identity
          + ".account where account_id = 'race-live'"))
          .isOne();
      assertThat(longScalar(setup, "select count(*) from " + social
          + ".post_edit_audit where post_id = 'race-post' and editor_account_id = 'race-live'"))
          .isOne();
      assertThat(danglingPostEditAuditCount(setup, identity, social)).isZero();
    }
  }

  @Test
  void uncommittedPseudonymDeleteMakesConcurrentChildUpdateBlockThenFail() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var setup = database.connect();
         var child = database.connect();
         var parent = database.connect();
         var observer = database.connect();
         var executor = Executors.newSingleThreadExecutor()) {
      var identity = quoted(database.prefix() + "identity");
      var social = quoted(database.prefix() + "social");
      execute(setup, "insert into " + identity
          + ".account (account_id, email, normalized_email, role, status, username) values "
          + "('race-update-owner', 'race-update@example.test', 'race-update@example.test', "
          + "'USER', 'ACTIVE', 'race-update-owner')");
      execute(setup, "insert into " + identity
          + ".deleted_account_pseudonym (pseudonym_id) values ('deleted:face00000001')");
      execute(setup, "insert into " + social
          + ".post (post_id, account_id, post_text, root_post_id, created_on) values "
          + "('race-update-post', 'race-update-owner', 'before', 'race-update-post', "
          + "transaction_timestamp())");
      execute(setup, "insert into " + social
          + ".post_edit_audit (post_id, ordinal, editor_account_id, before_text, after_text, "
          + "edited_on) values ('race-update-post', 0, 'race-update-owner', 'before', 'after', "
          + "transaction_timestamp())");
      execute(child, "set statement_timeout = '10s'");

      parent.setAutoCommit(false);
      assertThat(executeUpdate(parent, "delete from " + identity
          + ".deleted_account_pseudonym where pseudonym_id = 'deleted:face00000001'"))
          .isOne();
      var childPid = Math.toIntExact(longScalar(child, "select pg_backend_pid()"));
      var parentPid = Math.toIntExact(longScalar(parent, "select pg_backend_pid()"));
      var update = executor.submit(() -> sqlState(() -> execute(child, "update " + social
          + ".post_edit_audit set editor_account_id = 'deleted:face00000001' "
          + "where post_id = 'race-update-post'")));

      awaitBlockedBy(observer, childPid, parentPid);
      parent.commit();

      assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo("23503");
      assertThat(longScalar(setup, "select count(*) from " + identity
          + ".deleted_account_pseudonym where pseudonym_id = 'deleted:face00000001'"))
          .isZero();
      assertThat(longScalar(setup, "select count(*) from " + social
          + ".post_edit_audit where post_id = 'race-update-post' "
          + "and editor_account_id = 'race-update-owner'"))
          .isOne();
      assertThat(danglingPostEditAuditCount(setup, identity, social)).isZero();
    }
  }

  @Test
  void coordinatePlaylistAndLeaseContractsFailOrTransitionAtTheDatabaseBoundary() throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThatThrownBy(() -> execute(connection,
          "insert into \"" + database.prefix() + "lunch\".restaurant "
              + "(restaurant_id, display_name, dedupe_key, search_city, search_state, latitude) "
              + "values ('invalid-coordinate', 'Invalid', 'invalid', 'city', 'state', 32.1)"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23514");

      execute(connection, "insert into \"" + database.prefix()
          + "identity\".account (account_id, email, normalized_email, role, status, username) "
          + "values ('account-1', 'account@example.com', 'account@example.com', "
          + "'USER', 'ACTIVE', 'account')");

      execute(connection, "insert into \"" + database.prefix()
          + "mobility\".vehicle (vehicle_id, vin) "
          + "values ('vehicle-1', 'JM1BN1L30K1234567')");
      assertThatThrownBy(() -> execute(connection, "insert into \"" + database.prefix()
          + "mobility\".vehicle (vehicle_id, vin) "
          + "values ('vehicle-2', 'JM1BN1L30K1234567')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

      execute(connection, "insert into \"" + database.prefix()
          + "music\".access_attempt "
          + "(access_attempt_id, principal_type, principal, reason, first_attempt_at, "
          + "last_attempt_at, attempt_count, expires_at) values "
          + "('large-count', 'ACCOUNT', 'account-1', 'RATE_LIMIT', "
          + "transaction_timestamp(), transaction_timestamp(), 2147483648, "
          + "transaction_timestamp() + interval '1 hour')");
      execute(connection, "insert into \"" + database.prefix()
          + "lunch\".lunch_session "
          + "(lunch_session_id, active_until, created_by_account_id, created_by_username, "
          + "created_on, delete_on, last_updated_on, restaurant_reset_count, revision) values "
          + "('large-reset-count', transaction_timestamp() + interval '1 hour', 'account-1', "
          + "'account', transaction_timestamp(), transaction_timestamp() + interval '2 hours', "
          + "transaction_timestamp(), 2147483648, 0)");
      assertThat(longScalar(connection, "select attempt_count from \"" + database.prefix()
          + "music\".access_attempt where access_attempt_id = 'large-count'"))
          .isEqualTo(2_147_483_648L);
      assertThat(longScalar(connection, "select restaurant_reset_count from \""
          + database.prefix() + "lunch\".lunch_session "
          + "where lunch_session_id = 'large-reset-count'"))
          .isEqualTo(2_147_483_648L);

      execute(connection, "insert into \"" + database.prefix()
          + "music\".track (track_id, relative_path, title, duration_seconds, index_status) "
          + "values ('track-1', 'track-1.mp3', 'Track 1', 120, 'READY')");
      execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist "
          + "(playlist_id, normalized_name, name, updated_by_account_id, updated_at) "
          + "values ('playlist-1', 'playlist', 'Playlist', 'account-1', transaction_timestamp())");
      execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist_track (playlist_id, ordinal, track_id) "
          + "values ('playlist-1', 0, 'track-1')");
      assertThatThrownBy(() -> execute(connection, "insert into \"" + database.prefix()
          + "music\".playlist_track (playlist_id, ordinal, track_id) "
          + "values ('playlist-1', 1, 'track-1')"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("23505");

      var leaseSchema = database.prefix() + "shared_folder";
      execute(connection, "insert into \"" + leaseSchema
          + "\".maintenance_lease (lease_name, owner_token, fence_token, acquired_at, expires_at) "
          + "values ('maintenance', 'old-owner', 1, transaction_timestamp() - interval '2 minutes', "
          + "transaction_timestamp() - interval '1 minute')");
      assertThat(executeUpdate(connection, "update \"" + leaseSchema
          + "\".maintenance_lease set owner_token = 'new-owner', fence_token = fence_token + 1, "
          + "acquired_at = transaction_timestamp(), expires_at = transaction_timestamp() + interval '1 minute' "
          + "where lease_name = 'maintenance' and expires_at <= transaction_timestamp()"))
          .isEqualTo(1);
      assertThat(executeUpdate(connection, "update \"" + leaseSchema
          + "\".maintenance_lease set expires_at = transaction_timestamp() + interval '2 minutes' "
          + "where lease_name = 'maintenance' and owner_token = 'new-owner' and fence_token = 2"))
          .isEqualTo(1);
      assertThat(executeUpdate(connection, "delete from \"" + leaseSchema
          + "\".maintenance_lease where lease_name = 'maintenance' "
          + "and owner_token = 'new-owner' and fence_token = 2"))
          .isEqualTo(1);
    }
  }

  private static Set<String> catalogTables() {
    var catalog = loadCatalog();
    var tables = catalog.kinds().stream()
        .flatMap(kind -> kind.targetTables().stream()
            .map(table -> kind.targetSchema() + '.' + table))
        .collect(Collectors.toCollection(HashSet::new));
    tables.add("platform.persistence_migration_run");
    tables.add("platform.persistence_migration_source");
    tables.add("identity.deleted_account_pseudonym");
    return Set.copyOf(tables);
  }

  private static Set<String> missingCatalogTargets(Connection connection, String prefix)
      throws SQLException {
    var catalogTargets = new HashSet<String>();
    var catalog = loadCatalog();
    catalog.kinds().forEach(kind -> {
      catalogTargets.add(kind.targetSchema() + '.' + kind.keyMapping().targetColumn());
      kind.fieldMappings().values().forEach(mapping -> mapping.targets().forEach(
          target -> catalogTargets.add(kind.targetSchema() + '.' + target)));
    });

    try (var statement = connection.prepareStatement("""
        select substring(table_schema from ?), table_name, column_name
        from information_schema.columns
        where left(table_schema, ?) = ?
        """)) {
      statement.setInt(1, prefix.length() + 1);
      statement.setInt(2, prefix.length());
      statement.setString(3, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          catalogTargets.remove(rows.getString(1) + '.' + rows.getString(2) + '.'
              + rows.getString(3));
        }
      }
    }
    return Set.copyOf(catalogTargets);
  }

  private static PostgresqlMigrationCatalog loadCatalog() {
    try (InputStream input = PostgresqlSchemaContractTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("PostgreSQL migration catalog could not be closed.", failure);
    }
  }

  private static List<String> relationalDependencyViolations(
      Connection connection, String prefix, PostgresqlMigrationCatalog catalog) throws SQLException {
    var ownerByTable = catalog.kinds().stream().flatMap(kind -> kind.targetTables().stream()
            .map(table -> Map.entry(kind.targetSchema() + '.' + table, kind)))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    var violations = new ArrayList<String>();
    try (var statement = connection.prepareStatement("""
        select source_ns.nspname, source_table.relname, target_ns.nspname, target_table.relname,
               constraint_row.conname
        from pg_constraint constraint_row
        join pg_class source_table on source_table.oid = constraint_row.conrelid
        join pg_namespace source_ns on source_ns.oid = source_table.relnamespace
        join pg_class target_table on target_table.oid = constraint_row.confrelid
        join pg_namespace target_ns on target_ns.oid = target_table.relnamespace
        where constraint_row.contype = 'f' and left(source_ns.nspname, ?) = ?
        order by source_ns.nspname, source_table.relname, constraint_row.conname
        """)) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var sourceTable = rows.getString(1).substring(prefix.length()) + '.' + rows.getString(2);
          var targetTable = rows.getString(3).substring(prefix.length()) + '.' + rows.getString(4);
          var source = ownerByTable.get(sourceTable);
          var target = ownerByTable.get(targetTable);
          if (source == null || target == null || source.sourceKind().equals(target.sourceKind())) {
            continue;
          }
          if (!source.dependsOnKinds().contains(target.sourceKind())
              || target.loadOrder() >= source.loadOrder()) {
            violations.add(rows.getString(5) + ": " + source.sourceKind() + '@'
                + source.loadOrder() + " must follow " + target.sourceKind() + '@'
                + target.loadOrder());
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<String> manifestIndexViolations(Connection connection, String prefix)
      throws SQLException {
    assertThat(DomainCollectionManifest.ALL_INDEXES).hasSize(126);
    var catalog = loadCatalog();
    var catalogByKind = catalog.kinds().stream().collect(Collectors.toUnmodifiableMap(
        PostgresqlMigrationCatalog.Kind::sourceKind, Function.identity()));
    var actualIndexes = relationalIndexes(connection, prefix);
    var violations = new ArrayList<String>();
    for (var manifestIndex : DomainCollectionManifest.ALL_INDEXES) {
      if (manifestIndex.kind().isEmpty()) {
        catalog.kinds().stream()
            .filter(kind -> kind.sourceCollection().equals(manifestIndex.collection()))
            .forEach(kind -> requireRelationalIndex(
                manifestIndex.name() + " -> " + kind.sourceKind(), kind,
                List.of(new IndexRequirement(
                    keyTarget(kind).table(),
                    List.of(new IndexColumn(keyTarget(kind).column(), 1)))),
                true, false, actualIndexes, violations));
        continue;
      }
      var kind = catalogByKind.get(manifestIndex.kind().orElseThrow());
      var groups = new LinkedHashMap<String, List<IndexColumn>>();
      for (var sourceKey : manifestIndex.keys()) {
        var target = indexTarget(kind, sourceKey.path());
        groups.computeIfAbsent(target.table(), ignored -> new ArrayList<>())
            .add(new IndexColumn(target.column(), sourceKey.direction()));
      }
      var sparse = manifestIndex.partialFilterExpression().containsKey("$and");
      var requirements = groups.entrySet().stream()
          .map(entry -> new IndexRequirement(entry.getKey(), List.copyOf(entry.getValue())))
          .toList();
      requireRelationalIndex(
          manifestIndex.name(), kind, requirements, manifestIndex.unique(), sparse,
          actualIndexes, violations);
    }
    return List.copyOf(violations);
  }

  private static void requireRelationalIndex(
      String sourceName,
      PostgresqlMigrationCatalog.Kind kind,
      List<IndexRequirement> requiredGroups,
      boolean unique,
      boolean sparse,
      List<RelationalIndex> actualIndexes,
      List<String> violations) {
    for (var requirement : requiredGroups) {
      var keys = requirement.columns();
      var table = requirement.table();
      var matches = actualIndexes.stream()
          .filter(index -> index.schema().equals(kind.targetSchema()))
          .filter(index -> index.table().equals(table))
          .filter(index -> !unique || index.unique())
          .filter(index -> index.columns().size() >= keys.size())
          .filter(index -> index.columns().subList(0, keys.size()).equals(keys))
          .filter(index -> !sparse || index.predicate() != null
              && index.predicate().toLowerCase().contains(keys.getFirst().name().toLowerCase())
              && index.predicate().toLowerCase().contains("is not null"))
          .toList();
      if (matches.isEmpty()) {
        violations.add(sourceName + " -> " + kind.targetSchema() + '.' + table + keys
            + (unique ? " unique" : "") + (sparse ? " partial" : ""));
      }
    }
  }

  private static TargetColumn indexTarget(
      PostgresqlMigrationCatalog.Kind kind, String manifestPath) {
    if (manifestPath.equals("_id.legacyId")) {
      return keyTarget(kind);
    }
    var path = manifestPath.substring("payload.".length());
    var separator = path.indexOf('.');
    var field = separator < 0 ? path : path.substring(0, separator);
    var targetField = separator < 0 ? field : path.substring(path.lastIndexOf('.') + 1);
    var desiredColumn = camelToSnake(targetField);
    var targets = kind.fieldMappings().get(field).targets().stream()
        .map(PostgresqlSchemaContractTest::targetColumn)
        .toList();
    var exact = targets.stream().filter(target -> target.column().equals(desiredColumn)).toList();
    if (exact.size() == 1) {
      return exact.getFirst();
    }
    var nonOrdinal = targets.stream()
        .filter(target -> !target.column().equals("ordinal"))
        .filter(target -> !target.column().endsWith("_ordinal"))
        .toList();
    assertThat(nonOrdinal).as(kind.sourceKind() + '.' + manifestPath).hasSize(1);
    return nonOrdinal.getFirst();
  }

  private static TargetColumn keyTarget(PostgresqlMigrationCatalog.Kind kind) {
    return targetColumn(kind.keyMapping().targetColumn());
  }

  private static TargetColumn targetColumn(String target) {
    return new TargetColumn(tableName(target), columnName(target));
  }

  private static String tableName(String target) {
    return target.substring(0, target.indexOf('.'));
  }

  private static String columnName(String target) {
    return target.substring(target.indexOf('.') + 1);
  }

  private static String camelToSnake(String value) {
    return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }

  private static List<RelationalIndex> relationalIndexes(Connection connection, String prefix)
      throws SQLException {
    var builders = new LinkedHashMap<String, RelationalIndexBuilder>();
    try (var statement = connection.prepareStatement("""
        select namespace.nspname, table_row.relname, index_row.relname, definition.indisunique,
               pg_get_expr(definition.indpred, definition.indrelid), attribute.attname,
               key_row.ordinality,
               case when (definition.indoption[key_row.ordinality - 1] & 1) = 1 then -1 else 1 end
        from pg_index definition
        join pg_class table_row on table_row.oid = definition.indrelid
        join pg_namespace namespace on namespace.oid = table_row.relnamespace
        join pg_class index_row on index_row.oid = definition.indexrelid
        join lateral unnest(definition.indkey) with ordinality key_row(attnum, ordinality)
          on key_row.ordinality <= definition.indnkeyatts
        join pg_attribute attribute
          on attribute.attrelid = table_row.oid and attribute.attnum = key_row.attnum
        where left(namespace.nspname, ?) = ?
        order by namespace.nspname, table_row.relname, index_row.relname, key_row.ordinality
        """)) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var key = rows.getString(1) + '|' + rows.getString(2) + '|' + rows.getString(3);
          var builder = builders.get(key);
          if (builder == null) {
            builder = new RelationalIndexBuilder(
                rows.getString(1).substring(prefix.length()), rows.getString(2),
                rows.getString(3), rows.getBoolean(4), rows.getString(5));
            builders.put(key, builder);
          }
          builder.columns().add(new IndexColumn(rows.getString(6), rows.getInt(8)));
        }
      }
    }
    return builders.values().stream().map(RelationalIndexBuilder::build)
        .sorted(Comparator.comparing(RelationalIndex::name)).toList();
  }
  private static Set<String> ownedSchemas(Connection connection, String prefix) throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement(
        "select schema_name from information_schema.schemata "
            + "where left(schema_name, ?) = ? order by schema_name")) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) result.add(rows.getString(1));
      }
    }
    return result;
  }

  private static int task2HistoryTableCount(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select count(*) from information_schema.tables
        where table_schema = 'public'
          and table_name ~ '^flyway_cbtest_[0-9a-f]{24}_history$'
        """);
         var rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static Set<String> canonicalTables(Connection connection, String prefix)
      throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement(
        "select table_schema, table_name from information_schema.tables "
            + "where left(table_schema, ?) = ? and table_type = 'BASE TABLE'")) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (!rows.getString(2).equals("flyway_schema_history")) {
            result.add(rows.getString(1).substring(prefix.length()) + '.' + rows.getString(2));
          }
        }
      }
    }
    return result;
  }

  private static Set<String> tablesWithoutPrimaryKeys(Connection connection, String prefix)
      throws SQLException {
    var result = new HashSet<String>();
    try (var statement = connection.prepareStatement("""
        select t.table_schema, t.table_name
        from information_schema.tables t
        where left(t.table_schema, ?) = ? and t.table_type = 'BASE TABLE'
          and t.table_name <> 'flyway_schema_history'
          and not exists (
            select 1 from information_schema.table_constraints c
            where c.table_schema = t.table_schema and c.table_name = t.table_name
              and c.constraint_type = 'PRIMARY KEY')
        """)) {
      statement.setInt(1, prefix.length());
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) result.add(rows.getString(1) + '.' + rows.getString(2));
      }
    }
    return result;
  }

  private static String constraintDeleteRule(
      Connection connection, String schema, String constraint) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select delete_rule from information_schema.referential_constraints
        where constraint_schema = ? and constraint_name = ?
        """)) {
      statement.setString(1, schema);
      statement.setString(2, constraint);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static int[] columnPrecision(
      Connection connection, String schema, String table, String column) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select numeric_precision, numeric_scale from information_schema.columns
        where table_schema = ? and table_name = ? and column_name = ?
        """)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return new int[] {rows.getInt(1), rows.getInt(2)};
      }
    }
  }

  private static String columnType(
      Connection connection, String schema, String table, String column) throws SQLException {
    try (var statement = connection.prepareStatement("""
        select data_type from information_schema.columns
        where table_schema = ? and table_name = ? and column_name = ?
        """)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static Set<String> nullableColumns(
      Connection connection, String schema, String table) throws SQLException {
    var columns = new HashSet<String>();
    try (var statement = connection.prepareStatement("""
        select column_name from information_schema.columns
        where table_schema = ? and table_name = ? and is_nullable = 'YES'
        """)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          columns.add(rows.getString(1));
        }
      }
    }
    return Set.copyOf(columns);
  }

  private static int scalar(Connection connection, String sql, int length, String prefix)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setInt(1, length);
      statement.setString(2, prefix);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void assertForeignKeyViolation(SqlAction action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(SQLException.class)
        .extracting(failure -> ((SQLException) failure).getSQLState())
        .isEqualTo("23503");
  }

  private static void assertRestrictViolation(SqlAction action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(SQLException.class)
        .extracting(failure -> ((SQLException) failure).getSQLState())
        .isEqualTo("23001");
  }

  private static void awaitBlockedBy(
      Connection observer, int waitingPid, int blockingPid) throws SQLException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var statement = observer.prepareStatement(
        "select ? = any(pg_blocking_pids(?))")) {
      statement.setInt(1, blockingPid);
      statement.setInt(2, waitingPid);
      while (System.nanoTime() < deadline) {
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          if (rows.getBoolean(1)) {
            return;
          }
        }
        Thread.onSpinWait();
      }
    }
    throw new AssertionError(
        "PostgreSQL backend " + waitingPid + " did not block on backend " + blockingPid
            + "; " + backendDiagnostic(observer, waitingPid));
  }

  private static String backendDiagnostic(Connection observer, int pid) throws SQLException {
    try (var statement = observer.prepareStatement("""
        select state, wait_event_type, wait_event, pg_blocking_pids(pid), query
        from pg_stat_activity where pid = ?
        """)) {
      statement.setInt(1, pid);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return "backend is absent";
        }
        return "state=" + rows.getString(1)
            + ", wait=" + rows.getString(2) + '/' + rows.getString(3)
            + ", blockers=" + rows.getString(4)
            + ", query=" + rows.getString(5);
      }
    }
  }

  private static String sqlState(SqlAction action) {
    try {
      action.run();
      return null;
    } catch (SQLException failure) {
      return failure.getSQLState();
    }
  }

  private static long danglingPostEditAuditCount(
      Connection connection, String identity, String social) throws SQLException {
    return longScalar(connection, "select count(*) from " + social + ".post_edit_audit audit "
        + "left join " + identity + ".account account "
        + "on account.account_id = audit.editor_account_id "
        + "left join " + identity + ".deleted_account_pseudonym pseudonym "
        + "on pseudonym.pseudonym_id = audit.editor_account_id "
        + "where audit.editor_account_id is not null "
        + "and account.account_id is null and pseudonym.pseudonym_id is null");
  }

  private static String quoted(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private static int executeUpdate(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      return statement.executeUpdate(sql);
    }
  }

  private static long longScalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private record TargetColumn(String table, String column) {}

  private record RetainedReference(
      String schema, String table, String column, String liveId, String pseudonym) {}

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private record IndexColumn(String name, int direction) {}

  private record IndexRequirement(String table, List<IndexColumn> columns) {}

  private record RelationalIndex(
      String schema,
      String table,
      String name,
      boolean unique,
      String predicate,
      List<IndexColumn> columns) {}

  private record RelationalIndexBuilder(
      String schema,
      String table,
      String name,
      boolean unique,
      String predicate,
      List<IndexColumn> columns) {
    RelationalIndexBuilder(
        String schema, String table, String name, boolean unique, String predicate) {
      this(schema, table, name, unique, predicate, new ArrayList<>());
    }

    RelationalIndex build() {
      return new RelationalIndex(schema, table, name, unique, predicate, List.copyOf(columns));
    }
  }
}
