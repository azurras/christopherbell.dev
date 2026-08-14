package dev.christopherbell.configuration.persistence.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed executable semantics for each catalog-declared persistence-port query. */
final class MigrationPortQueryVerifierRegistry {
  private static final Pattern SQL_TYPE = Pattern.compile("[a-z][a-z0-9_ ]*(?:\\[\\])?");
  private static final List<QuerySpec> SPECS = List.of(
      spec("account", "find-by-id", "account", List.of("account_id"), List.of(asc("account_id")), null, 2),
      spec("account", "find-by-email", "account", List.of("email"), List.of(asc("email")), null, 2),
      spec("account", "find-by-username", "account", List.of("username"), List.of(asc("username")), null, 2),
      spec("account", "federation-actor-page", "account_federation_identity", List.of("actor_id"), List.of(asc("actor_id")), null, 100),
      spec("account_follow", "find-by-id", "account_follow", List.of("account_follow_id"), List.of(asc("account_follow_id")), null, 2),
      spec("account_follow", "follower-page", "account_follow", List.of("followed_account_id"), List.of(asc("created_on"), asc("account_follow_id")), null, 100),
      spec("account_follow", "followed-page", "account_follow", List.of("follower_account_id"), List.of(asc("created_on"), asc("account_follow_id")), null, 100),
      spec("account_trust_relationship", "find-by-id", "account_trust_relationship", List.of("relationship_id"), List.of(asc("relationship_id")), null, 2),
      spec("account_trust_relationship", "owner-page", "account_trust_relationship", List.of("owner_account_id"), List.of(asc("owner_account_id")), null, 100),
      spec("account_trust_relationship", "target-page", "account_trust_relationship", List.of("target_account_id"), List.of(asc("target_account_id")), null, 100),
      spec("account_trust_relationship", "incoming-block-page", "account_trust_relationship", List.of("target_account_id", "trust_type"), List.of(asc("created_on"), asc("relationship_id")), null, 100),
      spec("account_deletion_job", "find-by-id", "account_deletion_job", List.of("account_deletion_job_id"), List.of(asc("account_deletion_job_id")), null, 2),
      spec("account_deletion_job", "active-job-page", "account_deletion_job", List.of("status", "account_deletion_job_id"), List.of(asc("status"), asc("account_deletion_job_id")), null, 100),
      spec("browser_session", "find-by-id", "browser_session", List.of("browser_session_id"), List.of(asc("browser_session_id")), null, 2),
      spec("browser_session", "find-by-account", "browser_session", List.of("account_id"), List.of(asc("account_id")), null, 2),
      spec("browser_session", "expiration-page", "browser_session", List.of(), List.of(asc("absolute_expires_on")), "absolute_expires_on", 100),
      spec("conversation_archive_state", "find-by-id", "conversation_archive_state", List.of("archive_state_id"), List.of(asc("archive_state_id")), null, 2),
      spec("conversation_archive_state", "find-by-owner-and-conversation", "conversation_archive_state", List.of("owner_account_id", "conversation_key"), List.of(asc("owner_account_id"), asc("conversation_key")), null, 2),
      spec("message", "find-by-id", "message", List.of("message_id"), List.of(asc("message_id")), null, 2),
      spec("message", "conversation-page", "message", List.of("conversation_key"), List.of(asc("created_on"), asc("message_id")), null, 100),
      spec("message", "participant-page", "message_participant", List.of("account_id"), List.of(asc("message_id")), null, 100),
      spec("message", "unread-by-sender", "message",
          List.of("recipient_account_id", "sender_account_id", "is_read"),
          List.of(desc("created_on"), desc("message_id")), null, 100),
      spec("notification", "find-by-id", "notification", List.of("notification_id"), List.of(asc("notification_id")), null, 2),
      spec("notification", "account-page", "notification", List.of("account_id"), List.of(desc("created_on"), asc("notification_id")), null, 100),
      spec("notification", "unread-by-account", "notification",
          List.of("account_id", "is_read"),
          List.of(desc("created_on"), desc("notification_id")), null, 100),
      spec("notification_preference", "find-by-id", "notification_preference", List.of("notification_preference_id"), List.of(asc("notification_preference_id")), null, 2),
      spec("notification_preference", "find-by-account", "notification_preference", List.of("account_id"), List.of(asc("account_id")), null, 2),
      spec("notification_delivery_guard", "find-by-id", "notification_delivery_guard", List.of("guard_id"), List.of(asc("guard_id")), null, 2),
      spec("notification_delivery_guard", "expiration-page", "notification_delivery_guard", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("notification_rate_limit", "find-by-id", "notification_rate_limit", List.of("rate_limit_id"), List.of(asc("rate_limit_id")), null, 2),
      spec("notification_rate_limit", "expiration-page", "notification_rate_limit", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("post", "find-by-id", "post", List.of("post_id"), List.of(asc("post_id")), null, 2),
      spec("post", "author-feed-page", "post", List.of("account_id"), List.of(desc("created_on"), desc("post_id")), null, 100),
      spec("post", "public-feed-page", "post", List.of(), List.of(desc("created_on"), desc("post_id")), null, 100),
      spec("post", "thread-page", "post", List.of("root_post_id"), List.of(asc("thread_level"), asc("created_on"), asc("post_id")), null, 100),
      spec("post", "expiration-page", "post", List.of(), List.of(asc("expires_on")), "expires_on", 100),
      spec("post", "discovery-page", "post", List.of(), List.of(desc("created_on"), desc("post_id")), null, 100),
      spec("post_like", "find-by-id", "post_like", List.of("post_like_id"), List.of(asc("post_like_id")), null, 2),
      spec("post_like", "find-by-post-and-account", "post_like", List.of("post_id", "account_id"), List.of(asc("post_id"), asc("account_id")), null, 2),
      spec("post_like", "post-like-page", "post_like", List.of(), List.of(asc("post_id"), asc("post_like_id")), null, 100),
      spec("post_report", "find-by-id", "post_report", List.of("post_report_id"), List.of(asc("post_report_id")), null, 2),
      spec("post_report", "moderation-page", "post_report_moderation_audit", List.of(), List.of(asc("post_report_id")), null, 100),
      spec("post_report", "status-page", "post_report", List.of("status"), List.of(asc("status")), null, 100),
      spec("post_report", "find-open-dedupe", "post_report", List.of("status", "open_dedupe_key"), List.of(asc("status"), asc("open_dedupe_key")), null, 2),
      spec("hidden_post_thread", "find-by-id", "hidden_post_thread", List.of("hidden_post_thread_id"), List.of(asc("hidden_post_thread_id")), null, 2),
      spec("hidden_post_thread", "find-by-account-and-root", "hidden_post_thread", List.of("account_id", "root_post_id"), List.of(asc("account_id"), asc("root_post_id")), null, 2),
      spec("hidden_post_thread", "account-page", "hidden_post_thread", List.of("account_id"), List.of(asc("account_id")), null, 100),
      spec("post_link_preview_cache", "find-by-url", "post_link_preview_cache", List.of("url"), List.of(asc("url")), null, 2),
      spec("post_link_preview_cache", "expiration-page", "post_link_preview_cache", List.of(), List.of(asc("expires_on")), "expires_on", 100),
      spec("federation_scan_state", "find-by-id", "federation_scan_state", List.of("scan_state_id"), List.of(asc("scan_state_id")), null, 2),
      spec("federation_scan_state", "outbound-create-cursor", "federation_scan_state", List.of("scan_state_id"), List.of(asc("created_on"), asc("post_id")), null, 100),
      spec("federation_delivery_job", "find-by-id", "federation_delivery_job", List.of("delivery_job_id"), List.of(asc("delivery_job_id")), null, 2),
      spec("federation_delivery_job", "due-job-page", "federation_delivery_job", List.of("state"), List.of(asc("next_attempt_on"), asc("created_on"), asc("delivery_job_id")), "next_attempt_on", 100),
      spec("federation_delivery_job", "expired-claim-page", "federation_delivery_job", List.of("state"), List.of(asc("claim_until"), asc("delivery_job_id")), "claim_until", 100),
      spec("federation_delivery_job", "find-by-post-and-peer", "federation_delivery_job", List.of("post_id", "peer_inbox"), List.of(asc("post_id"), asc("peer_inbox")), null, 2),
      spec("music_track", "find-by-id", "track", List.of("track_id"), List.of(asc("track_id")), null, 2),
      spec("music_track", "find-by-path", "track", List.of("relative_path"), List.of(asc("relative_path")), null, 2),
      spec("music_track", "artist-page", "track", List.of(), List.of(asc("album_artist")), null, 100),
      spec("music_track", "album-page", "track", List.of(), List.of(asc("album")), null, 100),
      spec("music_track", "genre-page", "track", List.of(), List.of(asc("genre")), null, 100),
      spec("music_track", "radio-candidate-page", "track", List.of(), List.of(asc("excluded_from_radio")), null, 100),
      spec("music_playlist", "find-by-id", "playlist", List.of("playlist_id"), List.of(asc("playlist_id")), null, 2),
      spec("music_playlist", "find-by-normalized-name", "playlist", List.of("normalized_name"), List.of(asc("playlist_id")), null, 2),
      spec("music_playlist", "playlist-track-order", "playlist_track", List.of("playlist_id"), List.of(asc("ordinal"), asc("track_id")), null, 100),
      spec("music_metadata_edit", "find-by-id", "metadata_edit", List.of("metadata_edit_id"), List.of(asc("metadata_edit_id")), null, 2),
      spec("music_metadata_edit", "track-edit-page", "metadata_edit", List.of(), List.of(asc("track_id"), asc("edited_by_account_id")), null, 100),
      spec("music_metadata_edit", "expiration-page", "metadata_edit", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("music_runtime_state", "find-by-id", "runtime_state", List.of("runtime_state_id"), List.of(asc("runtime_state_id")), null, 2),
      spec("music_runtime_state", "global-queue", "queue_entry", List.of(), List.of(asc("ordinal"), asc("queue_entry_id")), null, 100),
      spec("music_runtime_state", "global-radio", "runtime_state", List.of(), List.of(asc("station_sequence"), asc("runtime_state_id")), null, 100),
      spec("music_radio_history", "find-by-id", "radio_history", List.of("radio_history_id"), List.of(asc("radio_history_id")), null, 2),
      spec("music_radio_history", "station-sequence-page", "radio_history", List.of("station_sequence"), List.of(asc("station_sequence")), null, 100),
      spec("music_radio_history", "occurred-at-page", "radio_history", List.of(), List.of(desc("occurred_at")), null, 100),
      spec("music_access_attempt", "find-by-id", "access_attempt", List.of("access_attempt_id"), List.of(asc("access_attempt_id")), null, 2),
      spec("music_access_attempt", "expiration-page", "access_attempt", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("restaurant", "find-by-id", "restaurant", List.of("restaurant_id"), List.of(asc("restaurant_id")), null, 2),
      spec("restaurant", "find-by-normalized-name", "restaurant", List.of("normalized_name"), List.of(asc("restaurant_id")), null, 2),
      spec("restaurant", "location-inventory-page", "restaurant", List.of(), List.of(asc("restaurant_id")), null, 100),
      spec("restaurant", "city-inventory-page", "restaurant", List.of(), List.of(asc("city")), null, 100),
      spec("restaurant", "state-inventory-page", "restaurant", List.of(), List.of(asc("search_state")), null, 100),
      spec("vote", "find-by-id", "restaurant_vote", List.of("restaurant_vote_id"), List.of(asc("restaurant_vote_id")), null, 2),
      spec("vote", "find-by-restaurant-and-account", "restaurant_vote", List.of("restaurant_id", "account_id"), List.of(asc("restaurant_id"), asc("account_id")), null, 2),
      spec("vote", "restaurant-vote-page", "restaurant_vote", List.of(), List.of(asc("restaurant_id"), asc("restaurant_vote_id")), null, 100),
      spec("favorite", "find-by-id", "restaurant_favorite", List.of("restaurant_favorite_id"), List.of(asc("restaurant_favorite_id")), null, 2),
      spec("favorite", "find-by-restaurant-and-account", "restaurant_favorite", List.of("restaurant_id", "account_id"), List.of(asc("restaurant_favorite_id")), null, 2),
      spec("favorite", "account-favorite-page", "restaurant_favorite", List.of("account_id", "restaurant_favorite_id"), List.of(asc("account_id"), asc("restaurant_favorite_id")), null, 100),
      spec("preference", "find-by-account", "lunch_preference", List.of("account_id"), List.of(asc("account_id")), null, 2),
      spec("session", "find-by-id", "lunch_session", List.of("lunch_session_id"), List.of(asc("lunch_session_id")), null, 2),
      spec("session", "participant-session-page", "lunch_session_participant", List.of("account_id"), List.of(asc("lunch_session_id")), null, 100),
      spec("session", "creator-session-page", "lunch_session", List.of("created_by_account_id"), List.of(asc("created_on"), asc("lunch_session_id")), null, 100),
      spec("session", "active-session", "lunch_session", List.of(), List.of(asc("active_until"), asc("lunch_session_id")), "active_until", 100),
      spec("daily_picks", "find-by-id", "daily_lunch_picks", List.of("daily_lunch_picks_id"), List.of(asc("daily_lunch_picks_id")), null, 2),
      spec("daily_picks", "find-by-pick-date", "daily_lunch_picks", List.of("pick_date"), List.of(asc("pick_date")), null, 2),
      spec("import_state", "find-by-id", "restaurant_import_state", List.of("import_state_id"), List.of(asc("import_state_id")), null, 2),
      spec("import_state", "scheduler-state", "restaurant_import_state", List.of(), List.of(asc("import_state_id")), null, 100),
      spec("import_preview", "find-by-id", "restaurant_import_preview", List.of("import_preview_id"), List.of(asc("import_preview_id")), null, 2),
      spec("import_preview", "actor-created-page", "restaurant_import_preview", List.of("actor_account_id"), List.of(desc("created_on"), asc("import_preview_id")), null, 100),
      spec("import_preview", "expiration-page", "restaurant_import_preview", List.of(), List.of(asc("expires_on")), "expires_on", 100),
      spec("audit_event", "find-by-id", "audit_event", List.of("audit_event_id"), List.of(asc("audit_event_id")), null, 2),
      spec("audit_event", "occurred-page", "audit_event", List.of(), List.of(desc("occurred_at")), null, 100),
      spec("audit_event", "account-page", "audit_event", List.of("account_id"), List.of(asc("account_id")), null, 100),
      spec("audit_event", "action-page", "audit_event", List.of(), List.of(asc("action")), null, 100),
      spec("audit_event", "outcome-page", "audit_event", List.of(), List.of(asc("outcome")), null, 100),
      spec("audit_event", "path-page", "audit_event", List.of(), List.of(asc("relative_path")), null, 100),
      spec("maintenance_lease", "find-by-id", "maintenance_lease", List.of("lease_name"), List.of(asc("lease_name")), null, 2),
      spec("maintenance_lease", "claim-expired-lease", "maintenance_lease", List.of(), List.of(asc("expires_at"), asc("lease_name")), "expires_at", 100),
      spec("media_job", "find-by-id", "media_job", List.of("media_job_id"), List.of(asc("media_job_id")), null, 2),
      spec("media_job", "least-recently-used-page", "media_job", List.of(), List.of(asc("updated_at")), null, 100),
      spec("media_job", "cleanup-due-page", "media_job", List.of(), List.of(asc("delete_at")), "delete_at", 100),
      spec("media_job", "owner-page", "media_job", List.of("owner_id"), List.of(asc("owner_id")), null, 100),
      spec("media_job", "status-page", "media_job", List.of("status"), List.of(asc("status")), null, 100),
      spec("mutation_recovery", "find-by-id", "mutation_recovery", List.of("mutation_recovery_id"), List.of(asc("mutation_recovery_id")), null, 2),
      spec("mutation_recovery", "owner-page", "mutation_recovery", List.of("owner_id"), List.of(asc("owner_id")), null, 100),
      spec("mutation_recovery", "updated-page", "mutation_recovery", List.of(), List.of(desc("updated_at")), null, 100),
      spec("mutation_recovery", "lease-recovery-page", "mutation_recovery", List.of(), List.of(asc("operation_lease_expires_at")), "operation_lease_expires_at", 100),
      spec("radio_state", "find-by-id", "radio_state", List.of("radio_state_id"), List.of(asc("radio_state_id")), null, 2),
      spec("radio_state", "station-state", "radio_state", List.of("station_sequence", "radio_state_id"), List.of(asc("station_sequence"), asc("radio_state_id")), null, 100),
      spec("recycle_item", "find-by-id", "recycle_item", List.of("recycle_item_id"), List.of(asc("recycle_item_id")), null, 2),
      spec("recycle_item", "state-deleted-page", "recycle_item", List.of(), List.of(asc("state"), asc("deleted_at")), null, 100),
      spec("recycle_item", "recovery-due-page", "recycle_item", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("recycle_item", "expiry-page", "recycle_item", List.of(), List.of(asc("expires_at")), "expires_at", 100),
      spec("upload_session", "find-by-id", "upload_session", List.of("upload_session_id"), List.of(asc("upload_session_id")), null, 2),
      spec("upload_session", "owner-state-page", "upload_session", List.of("owner_id", "finalization_state"), List.of(asc("owner_id"), asc("finalization_state")), null, 100),
      spec("upload_session", "maintenance-due-page", "upload_session", List.of(), List.of(asc("delete_at"), asc("maintenance_attempts"), asc("append_lease_expires_at")), "delete_at", 100),
      spec("upload_session", "expiration-page", "upload_session", List.of(), List.of(asc("delete_at"), asc("append_lease_expires_at")), "delete_at", 100),
      spec("vehicle", "find-by-id", "vehicle", List.of("vehicle_id"), List.of(asc("vehicle_id")), null, 2),
      spec("vehicle", "find-by-vin", "vehicle", List.of("vin"), List.of(asc("vin")), null, 2),
      spec("vin_decode_cache", "find-by-vin", "vin_decode_cache", List.of("vin"), List.of(asc("vin")), null, 2),
      spec("vin_decode_cache", "expiration-page", "vin_decode_cache", List.of(), List.of(asc("expires_on")), "expires_on", 100),
      spec("nhtsa_import_state", "find-by-id", "nhtsa_import_state", List.of("import_state_id"), List.of(asc("import_state_id")), null, 2),
      spec("nhtsa_import_state", "collector-state", "nhtsa_import_state", List.of(), List.of(asc("import_state_id")), null, 100),
      spec("random_vin_import_state", "find-by-id", "random_vin_import_state", List.of("import_state_id"), List.of(asc("import_state_id")), null, 2),
      spec("random_vin_import_state", "collector-state", "random_vin_import_state", List.of(), List.of(asc("import_state_id")), null, 100),
      spec("zip_coordinate", "find-by-zip-code", "zip_coordinate", List.of("zip_code"), List.of(asc("zip_code")), null, 2),
      spec("zip_import_state", "find-by-id", "zip_import_state", List.of("import_state_id"), List.of(asc("import_state_id")), null, 2),
      spec("zip_import_state", "import-state", "zip_import_state", List.of(), List.of(asc("import_state_id")), null, 100),
      spec("price_snapshot", "find-by-id", "price_snapshot", List.of("price_snapshot_id"), List.of(asc("price_snapshot_id")), null, 2),
      spec("price_snapshot", "weekly-snapshot-page", "price_snapshot", List.of(), List.of(desc("collected_on")), null, 100),
      spec("application_lease", "find-by-id", "application_lease", List.of("lease_name"), List.of(asc("lease_name")), null, 2),
      spec("application_lease", "claim-expired-lease", "application_lease", List.of(), List.of(asc("expires_at"), asc("lease_name")), "expires_at", 100),
      spec("scheduled_collector_run", "find-by-id", "scheduled_collector_run", List.of("collector_run_id"), List.of(asc("collector_run_id")), null, 2),
      spec("scheduled_collector_run", "status-completed-page", "scheduled_collector_run", List.of("status"), List.of(asc("status")), null, 100),
      spec("migration_record", "find-by-id", "application_migration_record", List.of("migration_record_id"), List.of(asc("migration_record_id")), null, 2),
      spec("migration_record", "status-completed-page", "application_migration_record", List.of("status"), List.of(asc("status")), null, 100),
      spec("domain_collection_cutover", "find-by-id", "domain_collection_cutover", List.of("cutover_id"), List.of(asc("cutover_id")), null, 2),
      spec("domain_collection_cutover", "target-active-ledger", "domain_collection_cutover", List.of("state"), List.of(asc("state")), null, 100),
      spec("admin_activity", "find-by-id", "admin_activity", List.of("admin_activity_id"), List.of(asc("admin_activity_id")), null, 2),
      spec("admin_activity", "created-page", "admin_activity", List.of(), List.of(desc("created_on")), null, 100),
      spec("admin_activity", "action-page", "admin_activity", List.of(), List.of(asc("action")), null, 100),
      spec("admin_activity", "target-page", "admin_activity", List.of("target_id"), List.of(asc("target_id")), null, 100),
      spec("admin_activity", "actor-page", "admin_activity", List.of("actor_account_id"), List.of(asc("actor_account_id")), null, 100),
      spec("pending_action", "find-by-id", "pending_action", List.of("pending_action_id"), List.of(asc("pending_action_id")), null, 2),
      spec("pending_action", "pending-machine-power", "pending_action", List.of("action"), List.of(asc("execute_at"), asc("pending_action_id")), null, 100)
  );
  private static final Map<Declaration, QuerySpec> RULES = rules();
  private static final Set<String> NAMES = buildNames();

  private MigrationPortQueryVerifierRegistry() {}

  static MigrationPortQueryVerifierRegistry standard() {
    return new MigrationPortQueryVerifierRegistry();
  }

  static MigrationPortQueryVerifierRegistry from(PostgresqlMigrationCatalog catalog) {
    var declared = new LinkedHashSet<Declaration>();
    catalog.kinds().forEach(kind -> kind.portQueries().forEach(
        query -> declared.add(new Declaration(kind.sourceKind(), query))));
    if (declared.size() != 153 || !declared.equals(RULES.keySet())) {
      throw new IllegalArgumentException("PostgreSQL migration port-query registry is invalid.");
    }
    return standard();
  }

  Set<String> names() {
    return NAMES;
  }

  int declarationCount() {
    return RULES.size();
  }

  List<String> schemaViolations(
      Connection connection, String schemaPrefix, PostgresqlMigrationCatalog catalog)
      throws SQLException {
    var result = new ArrayList<String>();
    for (var kind : catalog.kinds()) {
      for (var queryName : kind.portQueries()) {
        var spec = RULES.get(new Declaration(kind.sourceKind(), queryName));
        if (spec == null || !kind.targetTables().contains(spec.table())
            || !spec.validFor(metadata(
                connection, schemaPrefix + kind.targetSchema(), spec.table()))) {
          result.add(kind.sourceKind() + "/" + queryName);
        }
      }
    }
    return List.copyOf(result);
  }

  boolean verify(
      Connection connection,
      String schemaPrefix,
      String platformSchema,
      UUID runId,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationRowCodec codec,
      boolean includePriorShadowRows) throws SQLException {
    for (var queryName : kind.portQueries()) {
      var spec = RULES.get(new Declaration(kind.sourceKind(), queryName));
      if (spec == null || !kind.targetTables().contains(spec.table())) {
        return false;
      }
      var metadata = metadata(connection, schemaPrefix + kind.targetSchema(), spec.table());
      if (!spec.validFor(metadata)) {
        return false;
      }
      var expected = expectedRows(
          connection, schemaPrefix + kind.targetSchema(), platformSchema, runId, kind,
          spec.table(), metadata.primaryKeys(), codec, includePriorShadowRows);
      if (!executeRule(
          connection, schemaPrefix + kind.targetSchema(),
          new TableSnapshot(spec.table(), metadata, expected), spec)) {
        return false;
      }
    }
    return true;
  }

  private static boolean executeRule(
      Connection connection,
      String schema,
      TableSnapshot snapshot,
      QuerySpec spec) throws SQLException {
    var metadata = snapshot.metadata();
    var sourceRows = snapshot.rows();
    var identity = metadata.primaryKeys();
    if (identity.isEmpty()) {
      return false;
    }
    var representative = sourceRows.isEmpty() ? null : sourceRows.getFirst();
    var boundary = spec.deadlineColumn() == null || representative == null
        ? null : representative.values().get(spec.deadlineColumn());
    var expected = sourceRows.stream()
        .filter(row -> matches(row, spec.filters(), representative,
            spec.deadlineColumn(), boundary))
        .sorted(expectedComparator(spec.order(), identity))
        .limit(spec.limit())
        .map(row -> identity(row, identity))
        .toList();

    var sql = new StringBuilder("select ")
        .append(identity.stream().map(key -> quoted(key) + "::text")
            .collect(java.util.stream.Collectors.joining(", ")))
        .append(" from ").append(quoted(schema)).append('.').append(quoted(snapshot.table()));
    var parameters = new ArrayList<Parameter>();
    var predicates = new ArrayList<String>();
    if (representative != null) {
      for (var filter : spec.filters()) {
        var value = representative.values().get(filter);
        if (value == null) {
          predicates.add(quoted(filter) + " is null");
        } else {
          predicates.add(quoted(filter) + "=cast(? as "
              + sqlType(metadata.types().get(filter)) + ")");
          parameters.add(new Parameter(value));
        }
      }
      if (spec.deadlineColumn() != null) {
        predicates.add(quoted(spec.deadlineColumn()) + " is not null");
        if (boundary != null) {
          predicates.add(quoted(spec.deadlineColumn()) + "<=cast(? as "
              + sqlType(metadata.types().get(spec.deadlineColumn())) + ")");
          parameters.add(new Parameter(boundary));
        }
      }
    }
    if (!predicates.isEmpty()) {
      sql.append(" where ").append(String.join(" and ", predicates));
    }
    sql.append(" order by ");
    var orderedColumns = new LinkedHashSet<String>();
    for (var index = 0; index < spec.order().size(); index++) {
      if (index > 0) {
        sql.append(", ");
      }
      var order = spec.order().get(index);
      orderedColumns.add(order.column());
      sql.append(quoted(order.column())).append(order.descending() ? " desc" : " asc")
          .append(" nulls last");
    }
    for (var key : identity) {
      if (!orderedColumns.contains(key)) {
        sql.append(", ").append(quoted(key)).append(" asc");
      }
    }
    sql.append(" limit ").append(spec.limit());
    try (var statement = connection.prepareStatement(sql.toString())) {
      for (var index = 0; index < parameters.size(); index++) {
        statement.setString(index + 1, parameterText(parameters.get(index).value()));
      }
      try (var rows = statement.executeQuery()) {
        var actual = new ArrayList<List<String>>();
        while (rows.next()) {
          var rowIdentity = new ArrayList<String>();
          for (var index = 0; index < identity.size(); index++) {
            rowIdentity.add(rows.getString(index + 1));
          }
          actual.add(List.copyOf(rowIdentity));
        }
        return actual.equals(expected);
      }
    }
  }

  private static List<ExpectedRow> expectedRows(
      Connection connection,
      String targetSchema,
      String platformSchema,
      UUID runId,
      PostgresqlMigrationCatalog.Kind kind,
      String table,
      List<String> primaryKeys,
      MigrationRowCodec codec,
      boolean includePriorShadowRows) throws SQLException {
    var overlaid = new LinkedHashMap<List<String>, ExpectedRow>();
    var sql = includePriorShadowRows
        ? "select staged.source_id, staged.target_ordinal, staged.row_payload "
            + "from " + quoted(platformSchema) + ".persistence_migration_staged_row staged "
            + "join " + quoted(platformSchema) + ".persistence_migration_run run "
            + "on run.run_id=staged.run_id "
            + "where staged.source_kind=? and staged.target_table=? "
            + "and (staged.run_id=? or run.source_frozen=false) "
            + "order by run.started_at, run.run_id, staged.source_id, staged.row_ordinal"
        : "select source_id, target_ordinal, row_payload from " + quoted(platformSchema)
            + ".persistence_migration_staged_row where source_kind=? and target_table=? "
            + "and run_id=? order by source_id, row_ordinal";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, kind.sourceKind());
      statement.setString(2, table);
      statement.setObject(3, runId);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var sourceId = rows.getString(1);
          var targetOrdinal = rows.getInt(2);
          var values = new LinkedHashMap<>(codec.decode(rows.getBytes(3)));
          for (var key : primaryKeys) {
            if (!values.containsKey(key)) {
              values.put(key, key.equals("ordinal") ? targetOrdinal : sourceId);
            }
          }
          var row = new ExpectedRow(
              java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values)));
          overlaid.put(identity(row, primaryKeys), row);
        }
      }
    }
    if (!includePriorShadowRows) {
      return List.copyOf(overlaid.values());
    }
    var existing = existingIdentities(connection, targetSchema, table, primaryKeys);
    return overlaid.entrySet().stream()
        .filter(entry -> existing.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .toList();
  }

  private static Set<List<String>> existingIdentities(
      Connection connection, String schema, String table, List<String> primaryKeys)
      throws SQLException {
    var sql = "select " + primaryKeys.stream().map(key -> quoted(key) + "::text")
        .collect(java.util.stream.Collectors.joining(", "))
        + " from " + quoted(schema) + "." + quoted(table);
    var result = new LinkedHashSet<List<String>>();
    try (var statement = connection.createStatement();
         var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        var identity = new ArrayList<String>();
        for (var index = 0; index < primaryKeys.size(); index++) {
          identity.add(rows.getString(index + 1));
        }
        result.add(List.copyOf(identity));
      }
    }
    return Set.copyOf(result);
  }

  private static TableMetadata metadata(
      Connection connection, String schema, String table) throws SQLException {
    var types = new LinkedHashMap<String, String>();
    try (var columns = connection.getMetaData().getColumns(null, schema, table, null)) {
      while (columns.next()) {
        types.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
      }
    }
    var primaryKeys = new ArrayList<Map.Entry<Short, String>>();
    try (var keys = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
      while (keys.next()) {
        primaryKeys.add(Map.entry(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME")));
      }
    }
    primaryKeys.sort(Map.Entry.comparingByKey());
    return new TableMetadata(
        Map.copyOf(types), primaryKeys.stream().map(Map.Entry::getValue).toList());
  }

  private static boolean matches(
      ExpectedRow row,
      List<String> filters,
      ExpectedRow representative,
      String deadlineColumn,
      Object boundary) {
    if (representative == null) {
      return true;
    }
    if (!filters.stream().allMatch(column -> java.util.Objects.deepEquals(
        row.values().get(column), representative.values().get(column)))) {
      return false;
    }
    if (deadlineColumn == null) {
      return true;
    }
    var value = row.values().get(deadlineColumn);
    return boundary != null && value != null && compareValues(value, boundary) <= 0;
  }

  private static Comparator<ExpectedRow> expectedComparator(
      List<Order> order, List<String> identity) {
    return (left, right) -> {
      for (var item : order) {
        var compared = compareOrdered(
            left.values().get(item.column()), right.values().get(item.column()),
            item.descending());
        if (compared != 0) {
          return compared;
        }
      }
      for (var key : identity) {
        var compared = compareNullable(left.values().get(key), right.values().get(key));
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    };
  }

  private static int compareOrdered(Object left, Object right, boolean descending) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    var compared = compareValues(left, right);
    return descending ? -compared : compared;
  }

  private static List<String> identity(ExpectedRow row, List<String> primaryKeys) {
    return primaryKeys.stream().map(key -> parameterText(row.values().get(key))).toList();
  }

  private static int compareNullable(Object left, Object right) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    return compareValues(left, right);
  }

  private static int compareValues(Object left, Object right) {
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()));
    }
    if (left instanceof Instant leftInstant && right instanceof Instant rightInstant) {
      return leftInstant.compareTo(rightInstant);
    }
    if (left instanceof LocalDate leftDate && right instanceof LocalDate rightDate) {
      return leftDate.compareTo(rightDate);
    }
    if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
      return leftBoolean.compareTo(rightBoolean);
    }
    return parameterText(left).compareTo(parameterText(right));
  }

  private static String parameterText(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return "\\x" + HexFormat.of().formatHex(bytes);
    }
    if (value instanceof UUID uuid) {
      return uuid.toString();
    }
    return value.toString();
  }

  private static String sqlType(String type) {
    var normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
    if (!SQL_TYPE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("PostgreSQL migration port-query type is invalid.");
    }
    return normalized;
  }

  private static String quoted(String identifier) {
    if (!identifier.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("PostgreSQL migration port-query identifier is invalid.");
    }
    return '"' + identifier + '"';
  }

  private static Map<Declaration, QuerySpec> rules() {
    var result = new LinkedHashMap<Declaration, QuerySpec>();
    for (var spec : SPECS) {
      if (result.put(spec.declaration(), spec) != null) {
        throw new IllegalStateException("Duplicate PostgreSQL port-query declaration.");
      }
    }
    return java.util.Collections.unmodifiableMap(result);
  }

  private static Set<String> buildNames() {
    var result = new LinkedHashSet<String>();
    SPECS.forEach(spec -> result.add(spec.declaration().queryName()));
    return java.util.Collections.unmodifiableSet(result);
  }

  private static QuerySpec spec(
      String kind,
      String query,
      String table,
      List<String> filters,
      List<Order> order,
      String deadlineColumn,
      int limit) {
    return new QuerySpec(
        new Declaration(kind, query), table, List.copyOf(filters), List.copyOf(order),
        deadlineColumn, limit);
  }

  private static Order asc(String column) {
    return new Order(column, false);
  }

  private static Order desc(String column) {
    return new Order(column, true);
  }

  private record Declaration(String sourceKind, String queryName) {}

  private record QuerySpec(
      Declaration declaration,
      String table,
      List<String> filters,
      List<Order> order,
      String deadlineColumn,
      int limit) {
    private QuerySpec {
      if (filters.isEmpty() && order.isEmpty() || limit < 1) {
        throw new IllegalArgumentException("PostgreSQL migration query semantics are invalid.");
      }
    }

    private boolean validFor(TableMetadata metadata) {
      var columns = metadata.types().keySet();
      return !metadata.primaryKeys().isEmpty()
          && columns.containsAll(filters)
          && order.stream().map(Order::column).allMatch(columns::contains)
          && (deadlineColumn == null || columns.contains(deadlineColumn));
    }
  }

  private record Order(String column, boolean descending) {}

  private record ExpectedRow(Map<String, Object> values) {}

  private record TableMetadata(Map<String, String> types, List<String> primaryKeys) {}

  private record TableSnapshot(String table, TableMetadata metadata, List<ExpectedRow> rows) {}

  private record Parameter(Object value) {}
}
