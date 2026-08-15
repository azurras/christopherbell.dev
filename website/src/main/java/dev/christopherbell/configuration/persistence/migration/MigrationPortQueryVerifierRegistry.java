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
import dev.christopherbell.post.api.PostMigrationFeedVerifier;

/** Closed executable semantics for each catalog-declared persistence-port query. */
final class MigrationPortQueryVerifierRegistry {
  private static final Pattern SQL_TYPE = Pattern.compile("[a-z][a-z0-9_ ]*(?:\\[\\])?");
  private static final List<AdapterQueryStrategy> SPECS = List.of(
      spec("account", "find-by-id", "account", List.of("account_id"), List.of(asc("account_id")), null, 2),
      spec("account", "find-by-email", "account", List.of("email"), List.of(asc("email")), null, 2),
      spec("account", "find-by-username", "account", List.of("username"), List.of(asc("username")), null, 2),
      spec("account", "federation-actor-page", "account_federation_identity", List.of("actor_id"), List.of(asc("actor_id")), null, 100),
      spec("account_follow", "find-by-id", "account_follow", List.of("account_follow_id"), List.of(asc("account_follow_id")), null, 2),
      spec("account_follow", "follower-page", "account_follow", List.of("followed_account_id"), List.of(ascNullsFirst("created_on"), asc("account_follow_id")), null, 100),
      spec("account_follow", "followed-page", "account_follow", List.of("follower_account_id"), List.of(ascNullsFirst("created_on"), asc("account_follow_id")), null, 100),
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
      spec("federation_delivery_job", "due-job-page", "federation_delivery_job", List.of("state"), List.of(ascNullsFirst("next_attempt_on"), asc("created_on"), asc("delivery_job_id")), "next_attempt_on", 100),
      spec("federation_delivery_job", "expired-claim-page", "federation_delivery_job", List.of("state"), List.of(asc("claim_until"), asc("delivery_job_id")), "claim_until", 100),
      spec("federation_delivery_job", "find-by-post-and-peer", "federation_delivery_job", List.of("post_id", "peer_inbox"), List.of(asc("post_id"), asc("peer_inbox")), null, 2),
      spec("music_track", "find-by-id", "track", List.of("track_id"), List.of(asc("track_id")), null, 2),
      spec("music_track", "find-by-path", "track", List.of("relative_path"), List.of(asc("relative_path")), null, 2),
      spec("music_track", "artist-page", "track", List.of(), List.of(asc("artist")), null, 100),
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
      spec("media_job", "least-recently-used-page", "media_job", List.of(), List.of(ascNullsFirst("last_accessed_at"), asc("media_job_id")), null, 100),
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
      spec("upload_session", "maintenance-due-page", "upload_session", List.of(), List.of(ascNullsFirst("maintenance_retry_at"), asc("expires_at"), asc("upload_session_id")), "expires_at", 100),
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
  private static final Map<Declaration, AdapterQueryStrategy> RULES = rules();
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

  Set<String> explicitFamilyDeclarations() {
    return RULES.values().stream()
        .filter(strategy -> Set.of(
            SemanticFamily.JOINED_CHILD_PAGE, SemanticFamily.GROUPED_PROJECTION)
            .contains(strategy.semantics().family()))
        .map(strategy -> strategy.declaration().sourceKind() + "/"
            + strategy.declaration().queryName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  String semanticFamily(String sourceKind, String queryName) {
    var strategy = RULES.get(new Declaration(sourceKind, queryName));
    if (strategy == null) {
      throw new IllegalArgumentException("PostgreSQL migration port-query declaration is invalid.");
    }
    return strategy.semantics().family().name();
  }

  String nullPlacement(String sourceKind, String queryName, String column) {
    var strategy = RULES.get(new Declaration(sourceKind, queryName));
    if (strategy == null) {
      throw new IllegalArgumentException("PostgreSQL migration port-query declaration is invalid.");
    }
    return strategy.order().stream().filter(order -> order.column().equals(column))
        .findFirst().orElseThrow().nullPlacement().name();
  }

  boolean verifyConditionalClaimForTest(Connection connection, String schema, String table)
      throws SQLException {
    return verifyConditionalClaimUnderRollback(connection, schema, table);
  }

  boolean verifyExplicitFamilyForTest(
      Connection connection,
      String schema,
      String sourceKind,
      String queryName,
      Map<String, List<Map<String, Object>>> sourceRows) throws SQLException {
    var strategy = RULES.get(new Declaration(sourceKind, queryName));
    if (strategy == null || !Set.of(
        SemanticFamily.JOINED_CHILD_PAGE, SemanticFamily.GROUPED_PROJECTION)
        .contains(strategy.semantics().family())
        || !sourceRows.keySet().equals(requiredTables(strategy))) {
      throw new IllegalArgumentException("PostgreSQL migration explicit query fixture is invalid.");
    }
    var snapshots = new LinkedHashMap<String, TableSnapshot>();
    for (var table : requiredTables(strategy)) {
      snapshots.put(table, new TableSnapshot(
          table,
          metadata(connection, schema, table),
          sourceRows.get(table).stream()
              .map(values -> new ExpectedRow(java.util.Collections.unmodifiableMap(
                  new LinkedHashMap<>(values))))
              .toList()));
    }
    return executeRule(connection, schema, Map.copyOf(snapshots), strategy);
  }

  List<String> schemaViolations(
      Connection connection, String schemaPrefix, PostgresqlMigrationCatalog catalog)
      throws SQLException {
    var result = new ArrayList<String>();
    for (var kind : catalog.kinds()) {
      for (var queryName : kind.portQueries()) {
        var spec = RULES.get(new Declaration(kind.sourceKind(), queryName));
        if (spec == null || !kind.targetTables().containsAll(requiredTables(spec))
            || !validRequiredMetadata(
                connection, schemaPrefix + kind.targetSchema(), requiredTables(spec))
            || !spec.validFor(metadata(
                connection, schemaPrefix + kind.targetSchema(), spec.table()))) {
          result.add(kind.sourceKind() + "/" + queryName);
        }
      }
    }
    return List.copyOf(result);
  }

  private static boolean validRequiredMetadata(
      Connection connection, String schema, Set<String> tables) throws SQLException {
    for (var table : tables) {
      if (metadata(connection, schema, table).primaryKeys().isEmpty()) {
        return false;
      }
    }
    return true;
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
      if (spec == null || !kind.targetTables().containsAll(requiredTables(spec))) {
        return false;
      }
      var snapshots = new LinkedHashMap<String, TableSnapshot>();
      for (var table : requiredTables(spec)) {
        var tableMetadata = metadata(
            connection, schemaPrefix + kind.targetSchema(), table);
        if (tableMetadata.primaryKeys().isEmpty()) {
          return false;
        }
        var expected = expectedRows(
            connection, schemaPrefix + kind.targetSchema(), platformSchema, runId, kind,
            table, tableMetadata.primaryKeys(), codec, includePriorShadowRows);
        snapshots.put(table, new TableSnapshot(table, tableMetadata, expected));
      }
      if (!spec.validFor(snapshots.get(spec.table()).metadata())) {
        return false;
      }
      if (!executeRule(
          connection, schemaPrefix + kind.targetSchema(),
          Map.copyOf(snapshots), spec)) {
        return false;
      }
    }
    return true;
  }

  private static boolean executeRule(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      AdapterQueryStrategy spec) throws SQLException {
    if (spec.semantics().family() == SemanticFamily.JOINED_CHILD_PAGE) {
      return verifyJoinedChildPage(connection, schema, snapshots, spec);
    }
    if (spec.semantics().family() == SemanticFamily.GROUPED_PROJECTION) {
      return verifyGroupedProjection(connection, schema, snapshots, spec);
    }
    var snapshot = snapshots.get(spec.table());
    if (spec.semantics().family() == SemanticFamily.CONDITIONAL_CLAIM
        && !verifyConditionalClaimUnderRollback(connection, schema, snapshot.table())) {
      return false;
    }
    if (spec.semantics().family() == SemanticFamily.KEYSET_PAGE
        && !verifyPostFeedThroughAdapter(connection, schema, snapshot.rows(), spec)) {
      return false;
    }
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
      sql.append(quoted(order.column())).append(order.descending() ? " desc" : " asc");
      if (order.nullPlacement() != NullPlacement.NATIVE) {
        sql.append(order.nullPlacement() == NullPlacement.FIRST
            ? " nulls first" : " nulls last");
      }
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

  private static Set<String> requiredTables(AdapterQueryStrategy strategy) {
    return switch (strategy.declaration()) {
      case Declaration value when value.equals(
          new Declaration("message", "participant-page")) -> Set.of("message", "message_participant");
      case Declaration value when value.equals(
          new Declaration("session", "participant-session-page")) ->
          Set.of("lunch_session", "lunch_session_participant");
      case Declaration value when value.equals(
          new Declaration("music_playlist", "playlist-track-order")) ->
          Set.of("playlist", "playlist_track");
      case Declaration value when value.equals(
          new Declaration("post_report", "moderation-page")) ->
          Set.of("post_report", "post_report_moderation_audit");
      default -> Set.of(strategy.table());
    };
  }

  private static boolean verifyJoinedChildPage(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      AdapterQueryStrategy strategy) throws SQLException {
    return switch (strategy.declaration()) {
      case Declaration value when value.equals(
          new Declaration("message", "participant-page")) ->
          verifyMessageParticipantPage(connection, schema, snapshots, strategy.limit());
      case Declaration value when value.equals(
          new Declaration("session", "participant-session-page")) ->
          verifyLunchParticipantPage(connection, schema, snapshots, strategy.limit());
      case Declaration value when value.equals(
          new Declaration("music_playlist", "playlist-track-order")) ->
          verifyPlaylistTrackOrder(connection, schema, snapshots);
      case Declaration value when value.equals(
          new Declaration("post_report", "moderation-page")) ->
          verifyModerationPage(connection, schema, snapshots, strategy.limit());
      default -> false;
    };
  }

  private static boolean verifyGroupedProjection(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      AdapterQueryStrategy strategy) throws SQLException {
    var field = switch (strategy.declaration().queryName()) {
      case "artist-page" -> "artist";
      case "album-page" -> "album";
      case "genre-page" -> "genre";
      default -> null;
    };
    if (!strategy.declaration().sourceKind().equals("music_track") || field == null) {
      return false;
    }
    var sourceRows = snapshots.get("track").rows();
    var expected = normalizedMusicStrings(sourceRows.stream()
        .filter(row -> row.values().get("missing_since") == null)
        .filter(row -> "READY".equals(row.values().get("index_status")))
        .map(row -> parameterText(row.values().get(field)))
        .toList());
    var sql = "select distinct " + quoted(field) + " from " + quoted(schema)
        + ".\"track\" where missing_since is null and index_status='READY' and "
        + quoted(field) + " is not null";
    var actualValues = new ArrayList<String>();
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        actualValues.add(rows.getString(1));
      }
    }
    return normalizedMusicStrings(actualValues).equals(expected);
  }

  private static List<String> normalizedMusicStrings(List<String> values) {
    var byNormalizedValue = new java.util.TreeMap<String, String>();
    for (var value : values) {
      if (value != null && !value.isBlank()) {
        byNormalizedValue.merge(value.toLowerCase(Locale.ROOT), value,
            (left, right) -> left.compareTo(right) <= 0 ? left : right);
      }
    }
    return List.copyOf(byNormalizedValue.values());
  }

  private static boolean verifyMessageParticipantPage(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      int maximumLimit) throws SQLException {
    var participants = snapshots.get("message_participant").rows();
    if (participants.isEmpty()) {
      return true;
    }
    var roots = byStringIdentity(snapshots.get("message").rows(), "message_id");
    var sql = "select message.message_id from " + quoted(schema) + ".\"message\" message "
        + "join " + quoted(schema) + ".message_participant participant "
        + "on participant.message_id=message.message_id where participant.account_id=? "
        + "order by message.created_on desc, message.message_id desc limit ? offset ?";
    for (var accountId : distinctValues(participants, "account_id")) {
      var expected = participants.stream()
          .filter(row -> java.util.Objects.equals(
              accountId, parameterText(row.values().get("account_id"))))
          .map(row -> roots.get(parameterText(row.values().get("message_id"))))
          .filter(java.util.Objects::nonNull)
          .sorted((left, right) -> compareRows(left, right,
              List.of(desc("created_on"), desc("message_id"))))
          .map(row -> parameterText(row.values().get("message_id")))
          .toList();
      if (!verifyStringPages(connection, sql, accountId, expected, maximumLimit)) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyLunchParticipantPage(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      int maximumLimit) throws SQLException {
    var participants = snapshots.get("lunch_session_participant").rows();
    if (participants.isEmpty()) {
      return true;
    }
    var now = databaseNow(connection);
    var roots = byStringIdentity(snapshots.get("lunch_session").rows(), "lunch_session_id");
    var sql = "select session.lunch_session_id from " + quoted(schema)
        + ".lunch_session session join " + quoted(schema)
        + ".lunch_session_participant participant using (lunch_session_id) "
        + "where participant.account_id=? and session.delete_on>current_timestamp "
        + "order by session.created_on desc, session.lunch_session_id asc limit ? offset ?";
    for (var accountId : distinctValues(participants, "account_id")) {
      var expected = participants.stream()
          .filter(row -> java.util.Objects.equals(
              accountId, parameterText(row.values().get("account_id"))))
          .map(row -> roots.get(parameterText(row.values().get("lunch_session_id"))))
          .filter(java.util.Objects::nonNull)
          .filter(row -> instant(row.values().get("delete_on")).isAfter(now))
          .sorted((left, right) -> compareRows(left, right,
              List.of(desc("created_on"), asc("lunch_session_id"))))
          .map(row -> parameterText(row.values().get("lunch_session_id")))
          .toList();
      if (!verifyStringPages(connection, sql, accountId, expected, maximumLimit)) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyPlaylistTrackOrder(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots) throws SQLException {
    var roots = snapshots.get("playlist").rows();
    var children = snapshots.get("playlist_track").rows();
    for (var root : roots) {
      var playlistId = parameterText(root.values().get("playlist_id"));
      var expected = children.stream()
          .filter(row -> java.util.Objects.equals(
              playlistId, parameterText(row.values().get("playlist_id"))))
          .sorted((left, right) -> compareRows(left, right, List.of(asc("ordinal"))))
          .map(row -> parameterText(row.values().get("track_id")))
          .toList();
      var sql = "select track_id from " + quoted(schema)
          + ".playlist_track where playlist_id=? order by ordinal asc";
      if (!fetchStrings(connection, sql, List.of(playlistId)).equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyModerationPage(
      Connection connection,
      String schema,
      Map<String, TableSnapshot> snapshots,
      int maximumLimit) throws SQLException {
    var audits = byStringIdentity(
        snapshots.get("post_report_moderation_audit").rows(), "post_report_id");
    var expected = snapshots.get("post_report").rows().stream()
        .filter(row -> audits.containsKey(parameterText(row.values().get("post_report_id"))))
        .sorted((left, right) -> compareRows(left, right,
            List.of(desc("created_on"), desc("post_report_id"))))
        .map(row -> parameterText(row.values().get("post_report_id")))
        .limit(maximumLimit)
        .toList();
    var sql = "select report.post_report_id from " + quoted(schema)
        + ".post_report report join " + quoted(schema)
        + ".post_report_moderation_audit audit using (post_report_id) "
        + "order by report.created_on desc, report.post_report_id desc limit " + maximumLimit;
    return fetchStrings(connection, sql, List.of()).equals(expected);
  }

  private static boolean verifyStringPages(
      Connection connection,
      String sql,
      String filter,
      List<String> expected,
      int maximumLimit) throws SQLException {
    var pageSize = Math.min(2, maximumLimit);
    if (!fetchStrings(connection, sql, List.of(filter, pageSize, 0))
        .equals(expected.stream().limit(pageSize).toList())) {
      return false;
    }
    if (expected.size() < 2) {
      return true;
    }
    return fetchStrings(connection, sql, List.of(filter, pageSize, 1))
        .equals(expected.stream().skip(1).limit(pageSize).toList());
  }

  private static List<String> fetchStrings(
      Connection connection, String sql, List<?> parameters) throws SQLException {
    var values = new ArrayList<String>();
    try (var statement = connection.prepareStatement(sql)) {
      for (var index = 0; index < parameters.size(); index++) {
        statement.setObject(index + 1, parameters.get(index));
      }
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(rows.getString(1));
        }
      }
    }
    return List.copyOf(values);
  }

  private static Map<String, ExpectedRow> byStringIdentity(
      List<ExpectedRow> rows, String column) {
    var result = new LinkedHashMap<String, ExpectedRow>();
    rows.forEach(row -> result.put(parameterText(row.values().get(column)), row));
    return Map.copyOf(result);
  }

  private static List<String> distinctValues(List<ExpectedRow> rows, String column) {
    return rows.stream().map(row -> parameterText(row.values().get(column)))
        .distinct().sorted().toList();
  }

  private static int compareRows(ExpectedRow left, ExpectedRow right, List<Order> order) {
    return expectedComparator(order, List.of()).compare(left, right);
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
         var rows = statement.executeQuery("select current_timestamp")) {
      rows.next();
      return rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
    }
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.time.OffsetDateTime offsetDateTime) {
      return offsetDateTime.toInstant();
    }
    throw new IllegalArgumentException("PostgreSQL migration timestamp is invalid.");
  }

  private static boolean verifyConditionalClaimUnderRollback(
      Connection connection, String schema, String table) throws SQLException {
    if (!Set.of("application_lease", "maintenance_lease").contains(table)) {
      return false;
    }
    var savepoint = connection.setSavepoint("migration_port_claim_probe");
    try {
      var suffix = UUID.randomUUID().toString();
      var expired = "migration-expired-" + suffix;
      var active = "migration-active-" + suffix;
      var insert = "insert into " + quoted(schema) + "." + quoted(table)
          + " (lease_name, owner_token, fence_token, acquired_at, expires_at)"
          + " values (?, 'incumbent', 7, current_timestamp, "
          + "current_timestamp + cast(? as interval))";
      try (var statement = connection.prepareStatement(insert)) {
        statement.setString(1, expired);
        statement.setString(2, "-1 minute");
        statement.executeUpdate();
        statement.setString(1, active);
        statement.setString(2, "1 minute");
        statement.executeUpdate();
      }
      var claim = "update " + quoted(schema) + "." + quoted(table)
          + " set owner_token=?, fence_token=fence_token+1, acquired_at=current_timestamp,"
          + " expires_at=current_timestamp + interval '1 minute' where lease_name=?"
          + " and (owner_token=? or expires_at<=current_timestamp)";
      try (var statement = connection.prepareStatement(claim)) {
        if (claim(statement, "contender", active, "contender") != 0
            || claim(statement, "contender", expired, "contender") != 1
            || claim(statement, "incumbent", active, "incumbent") != 1) {
          return false;
        }
      }
      return true;
    } finally {
      connection.rollback(savepoint);
      connection.releaseSavepoint(savepoint);
    }
  }

  private static boolean verifyPostFeedThroughAdapter(
      Connection connection,
      String socialSchema,
      List<ExpectedRow> sourceRows,
      AdapterQueryStrategy strategy) {
    return strategy.table().equals("post")
        && PostMigrationFeedVerifier.verify(
            connection,
            socialSchema,
            strategy.declaration().queryName(),
            sourceRows.stream().map(ExpectedRow::values).toList());
  }

  private static int claim(
      java.sql.PreparedStatement statement, String newOwner, String lease, String ownerMatch)
      throws SQLException {
    statement.setString(1, newOwner);
    statement.setString(2, lease);
    statement.setString(3, ownerMatch);
    return statement.executeUpdate();
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
            item);
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

  private static int compareOrdered(Object left, Object right, Order order) {
    if (left == right) {
      return 0;
    }
    var nullsFirst = order.nullPlacement() == NullPlacement.FIRST
        || order.nullPlacement() == NullPlacement.NATIVE && order.descending();
    if (left == null) {
      return nullsFirst ? -1 : 1;
    }
    if (right == null) {
      return nullsFirst ? 1 : -1;
    }
    var compared = compareValues(left, right);
    return order.descending() ? -compared : compared;
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

  private static Map<Declaration, AdapterQueryStrategy> rules() {
    var result = new LinkedHashMap<Declaration, AdapterQueryStrategy>();
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

  private static AdapterQueryStrategy spec(
      String kind,
      String query,
      String table,
      List<String> filters,
      List<Order> order,
      String deadlineColumn,
      int limit) {
    return new AdapterQueryStrategy(
        new Declaration(kind, query), table, List.copyOf(filters), List.copyOf(order),
        deadlineColumn, limit, semantics(kind, query, filters, deadlineColumn));
  }

  private static AdapterSemantics semantics(
      String kind, String query, List<String> filters, String deadlineColumn) {
    var declaration = new Declaration(kind, query);
    var family = switch (declaration) {
      case Declaration value when value.equals(new Declaration(
          "application_lease", "claim-expired-lease")) -> SemanticFamily.CONDITIONAL_CLAIM;
      case Declaration value when value.equals(new Declaration(
          "maintenance_lease", "claim-expired-lease")) -> SemanticFamily.CONDITIONAL_CLAIM;
      case Declaration value when value.sourceKind().equals("post")
          && Set.of("author-feed-page", "public-feed-page")
          .contains(value.queryName()) -> SemanticFamily.KEYSET_PAGE;
      case Declaration value when Set.of(
          new Declaration("message", "participant-page"),
          new Declaration("session", "participant-session-page"),
          new Declaration("music_playlist", "playlist-track-order"),
          new Declaration("post_report", "moderation-page"))
          .contains(value) -> SemanticFamily.JOINED_CHILD_PAGE;
      case Declaration value when value.sourceKind().equals("music_track")
          && Set.of("artist-page", "album-page", "genre-page").contains(value.queryName()) ->
          SemanticFamily.GROUPED_PROJECTION;
      default -> deadlineColumn != null ? SemanticFamily.DEADLINE_PAGE
          : filters.isEmpty() ? SemanticFamily.ORDERED_PAGE
          : query.startsWith("find-") ? SemanticFamily.LOOKUP
          : SemanticFamily.FILTERED_PAGE;
    };
    return new AdapterSemantics(family, family == SemanticFamily.KEYSET_PAGE, 1,
        family == SemanticFamily.CONDITIONAL_CLAIM);
  }

  private static Order asc(String column) {
    return new Order(column, false, NullPlacement.NATIVE);
  }

  private static Order ascNullsFirst(String column) {
    return new Order(column, false, NullPlacement.FIRST);
  }

  private static Order desc(String column) {
    return new Order(column, true, NullPlacement.NATIVE);
  }

  private record Declaration(String sourceKind, String queryName) {}

  private record AdapterQueryStrategy(
      Declaration declaration,
      String table,
      List<String> filters,
      List<Order> order,
      String deadlineColumn,
      int limit,
      AdapterSemantics semantics) {
    private AdapterQueryStrategy {
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

  private enum SemanticFamily {
    LOOKUP,
    FILTERED_PAGE,
    ORDERED_PAGE,
    DEADLINE_PAGE,
    KEYSET_PAGE,
    JOINED_CHILD_PAGE,
    GROUPED_PROJECTION,
    CONDITIONAL_CLAIM
  }

  private enum NullPlacement { NATIVE, FIRST, LAST }

  private record AdapterSemantics(
      SemanticFamily family, boolean keysetCursor, int lookaheadRows, boolean rollbackMutation) {}

  private record Order(String column, boolean descending, NullPlacement nullPlacement) {}

  private record ExpectedRow(Map<String, Object> values) {}

  private record TableMetadata(Map<String, String> types, List<String> primaryKeys) {}

  private record TableSnapshot(String table, TableMetadata metadata, List<ExpectedRow> rows) {}

  private record Parameter(Object value) {}
}
