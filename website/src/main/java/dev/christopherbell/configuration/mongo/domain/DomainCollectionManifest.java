package dev.christopherbell.configuration.mongo.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Immutable application authority for the consolidated Mongo domain schema. */
public final class DomainCollectionManifest {
  private static final int MAX_INDEX_NAME_LENGTH = 120;
  private static final Pattern CANONICAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final String TYPE_NOT_APPROVED = "Mongo domain type is not approved.";

  /** The only physical domain collection names approved after cutover. */
  public static final Set<String> ALL_COLLECTIONS = Set.of(
      "accounts", "sessions", "communications", "content", "federation", "music",
      "whatsforlunch", "shared_folder", "vehicles", "location", "canes_box_tracker",
      "application_runtime", "application_migrations", "admin_activity");

  private static final List<RawKindDefinition> RAW_KINDS = List.of(
      kind("accounts", "account", "accounts", "account.model.Account"),
      kind("accounts", "account_follow", "account_follows", "account.follow.AccountFollow"),
      kind("accounts", "account_trust_relationship", "account_trust_relationships",
          "account.trust.AccountTrustRelationship"),
      kind("accounts", "account_deletion_job", "account_deletion_jobs",
          "account.deletion.AccountDeletionJob"),
      kind("sessions", "browser_session", "browser_sessions",
          "configuration.security.browser.BrowserSession"),
      kind("sessions", "conversation_archive_state", "conversation_archive_states",
          "message.conversation.ConversationArchiveState"),
      kind("communications", "message", "messages", "message.model.Message"),
      kind("communications", "notification", "notifications", "notification.model.Notification"),
      kind("communications", "notification_preference", "notification_preferences",
          "notification.preference.NotificationPreference"),
      kind("communications", "notification_delivery_guard", "notification_delivery_guards",
          "notification.delivery.NotificationDeliveryGuard"),
      kind("communications", "notification_rate_limit", "notification_rate_limits",
          "notification.delivery.NotificationRateLimit"),
      kind("content", "post", "posts", "post.model.Post"),
      kind("content", "post_like", "post_likes", "post.like.PostLike"),
      kind("content", "post_report", "post_reports", "report.model.PostReport"),
      kind("content", "hidden_post_thread", "hidden_post_threads", "post.hide.HiddenPostThread"),
      kind("content", "post_link_preview_cache", "post_link_preview_cache",
          "post.preview.PostLinkPreviewCacheEntry"),
      kind("federation", "federation_scan_state", "federation_scan_state",
          "federation.outbound.FederationScanState"),
      kind("federation", "federation_delivery_job", "federation_delivery_jobs",
          "federation.outbound.FederationDeliveryJob"),
      kind("music", "music_track", "music_tracks", "music.catalog.MusicTrack"),
      kind("music", "music_playlist", "music_playlists", "music.library.MusicPlaylist"),
      kind("music", "music_metadata_edit", "music_metadata_edits",
          "music.metadata.MusicMetadataEdit"),
      kind("music", "music_runtime_state", "music_runtime_state",
          "music.radio.MusicRuntimeStateDocument"),
      kind("music", "music_radio_history", "music_radio_history",
          "music.radio.MusicRadioHistoryEvent"),
      kind("music", "music_access_attempt", "music_access_attempts",
          "music.security.MusicAccessAttempt"),
      kind("whatsforlunch", "restaurant", "whatsforlunch",
          "whatsforlunch.restaurant.model.Restaurant"),
      kind("whatsforlunch", "vote", "whatsforlunch_ratings",
          "whatsforlunch.restaurant.model.RestaurantVote"),
      kind("whatsforlunch", "favorite", "whatsforlunch_favorites",
          "whatsforlunch.restaurant.model.RestaurantFavorite"),
      kind("whatsforlunch", "preference", "whatsforlunch_preferences",
          "whatsforlunch.restaurant.model.WhatsForLunchPreference"),
      kind("whatsforlunch", "session", "whatsforlunch_sessions",
          "whatsforlunch.restaurant.model.WhatsForLunchSession"),
      kind("whatsforlunch", "daily_picks", "whatsforlunch_daily_picks",
          "whatsforlunch.restaurant.model.DailyLunchPicks"),
      kind("whatsforlunch", "import_state", "restaurant_import_state",
          "whatsforlunch.restaurant.model.RestaurantImportState"),
      kind("whatsforlunch", "import_preview", "restaurant_import_previews",
          "whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument"),
      kind("shared_folder", "audit_event", "shared_folder_audit",
          "sharedfolder.audit.SharedFolderAuditEvent"),
      kind("shared_folder", "maintenance_lease", "shared_folder_maintenance_leases",
          "sharedfolder.maintenance.SharedFolderMaintenanceLeaseDocument"),
      kind("shared_folder", "media_job", "shared_folder_media_jobs",
          "sharedfolder.media.MediaJob"),
      kind("shared_folder", "mutation_recovery", "shared_folder_mutation_recoveries",
          "sharedfolder.service.SharedFolderMutationRecovery"),
      kind("shared_folder", "radio_state", "shared_folder_radio",
          "sharedfolder.radio.SharedFolderRadioDocument"),
      kind("shared_folder", "recycle_item", "shared_folder_recycle_items",
          "sharedfolder.recycle.SharedFolderRecycleItem"),
      kind("shared_folder", "upload_session", "shared_folder_upload_sessions",
          "sharedfolder.upload.SharedFolderUploadSession"),
      kind("vehicles", "vehicle", "vehicles", "vehicle.model.Vehicle"),
      kind("vehicles", "vin_decode_cache", "vehicle_vin_decode_cache",
          "vehicle.model.VehicleVinDecodeCache"),
      kind("vehicles", "nhtsa_import_state", "vehicle_import_state",
          "vehicle.nhtsa.model.NhtsaVinImportState"),
      kind("vehicles", "random_vin_import_state", "vehicle_import_state",
          "vehicle.randomvin.model.RandomVinImportState"),
      kind("location", "zip_coordinate", "location_zip_coordinates",
          "location.model.ZipCoordinate"),
      kind("location", "zip_import_state", "zip_coordinate_import_state",
          "location.model.ZipCoordinateImportState"),
      kind("canes_box_tracker", "price_snapshot", "canes_box_price_snapshots",
          "canesboxtracker.model.CanesBoxPriceSnapshot"),
      kind("application_runtime", "application_lease", "application_leases",
          "libs.mongo.lease.MongoLeaseDocument"),
      kind("application_runtime", "scheduled_collector_run", "scheduled_collector_runs",
          "libs.mongo.lease.ScheduledCollectorRun"),
      kind("application_migrations", "migration_record", "application_migrations",
          "configuration.mongo.migration.MigrationRecord"),
      kindWithoutSource("application_migrations", "domain_collection_cutover",
          "configuration.mongo.migration.DomainCollectionCutoverLedger"),
      kind("admin_activity", "admin_activity", "admin_activity", "admin.model.AdminActivity"),
      kind("admin_activity", "pending_action", "command_center_pending_actions",
          "admin.commandcenter.action.PendingActionDocument"));

  private static final Map<String, String> COLLECTION_BY_KIND = RAW_KINDS.stream()
      .collect(Collectors.toUnmodifiableMap(RawKindDefinition::kind, RawKindDefinition::collection));

  /** Exact target index catalog, including one global Mongo identity index per target. */
  public static final List<IndexDefinition> ALL_INDEXES = buildIndexes();

  /** Exact kind/source/type catalog. Runtime Java classes are deliberately not loaded here. */
  public static final List<KindDefinition> ALL_KINDS = RAW_KINDS.stream()
      .map(raw -> new KindDefinition(
          raw.collection(),
          raw.kind(),
          raw.legacySource(),
          raw.ownerTypeName(),
          raw.schemaVersion(),
          ALL_INDEXES.stream()
              .filter(index -> index.kind().equals(Optional.of(raw.kind())))
              .toList()))
      .toList();

  private static final Map<String, KindDefinition> KIND_BY_OWNER = uniqueMap(
      ALL_KINDS, KindDefinition::ownerTypeName, "owner type");
  private static final Map<String, KindDefinition> KIND_BY_KIND = uniqueMap(
      ALL_KINDS, KindDefinition::kind, "kind");
  private static final Map<String, List<KindDefinition>> KINDS_BY_SOURCE = ALL_KINDS.stream()
      .filter(definition -> definition.legacySource().isPresent())
      .collect(Collectors.collectingAndThen(
          Collectors.groupingBy(
              definition -> definition.legacySource().orElseThrow(),
              LinkedHashMap::new,
              Collectors.toList()),
          sourceMap -> sourceMap.entrySet().stream().collect(Collectors.toUnmodifiableMap(
              Map.Entry::getKey,
              entry -> List.copyOf(entry.getValue())))));
  private static final DomainDocumentKindRegistry REGISTRY = DomainDocumentKindRegistry.of(
      ALL_KINDS.stream().collect(Collectors.toUnmodifiableMap(
          KindDefinition::kind, KindDefinition::collection)));

  /** SHA-256 of the exact ordered collection, kind, source, type, and index manifest. */
  public static final String DIGEST = sha256(canonicalManifest());

  static {
    validateManifest();
  }

  private DomainCollectionManifest() {}

  /** Binds an approved runtime type to Task 1's unforgeable kind metadata. */
  public static <T> DomainDocumentKind<T> forType(Class<T> javaType) {
    Objects.requireNonNull(javaType, "javaType");
    var definition = KIND_BY_OWNER.get(javaType.getName());
    if (definition == null) {
      throw new IllegalArgumentException(TYPE_NOT_APPROVED);
    }
    return REGISTRY.require(definition.kind(), definition.schemaVersion(), javaType);
  }

  /** Returns every approved kind that originated in the exact legacy source. */
  public static List<KindDefinition> forSource(String legacySource) {
    if (legacySource == null) {
      return List.of();
    }
    return KINDS_BY_SOURCE.getOrDefault(legacySource, List.of());
  }

  /** Returns metadata for one exact approved kind. */
  public static Optional<KindDefinition> forKind(String kind) {
    return Optional.ofNullable(KIND_BY_KIND.get(kind));
  }

  private static List<IndexDefinition> buildIndexes() {
    var indexes = new ArrayList<IndexDefinition>();
    ALL_COLLECTIONS.stream().sorted().map(DomainCollectionManifest::globalIdIndex)
        .forEach(indexes::add);

    indexes.add(unnamed("account", true, false, null, asc("email")));
    indexes.add(unnamed("account", false, false, null, asc("passwordResetTokenHash")));
    indexes.add(unnamed("account", true, false, null, asc("username")));
    indexes.add(named("account", "federation_actor_lookup", false, false, null,
        asc("status"), asc("federationEnabled"), asc("username")));
    indexes.add(named("account_follow", "account_follow_follower_target_unique", true, false,
        null, asc("followerAccountId"), asc("followedAccountId")));
    indexes.add(named("account_follow", "account_follow_target", false, false, null,
        asc("followedAccountId")));
    indexes.add(named("account_trust_relationship", "owner_target_type_unique", true, false,
        null, asc("ownerAccountId"), asc("targetAccountId"), asc("type")));
    indexes.add(unnamed("account_trust_relationship", false, false, null,
        asc("ownerAccountId")));
    indexes.add(unnamed("account_trust_relationship", false, false, null,
        asc("targetAccountId")));
    indexes.add(named("account_trust_relationship", "void_people_incoming_block", false, false,
        null, asc("targetAccountId"), asc("type"), asc("ownerAccountId")));

    indexes.add(unnamed("browser_session", false, false, null, asc("accountId")));
    indexes.add(unnamed("browser_session", false, false, 0L, asc("absoluteExpiresOn")));
    indexes.add(named("conversation_archive_state", "conversation_archive_owner_key_unique",
        true, false, null, asc("ownerAccountId"), asc("conversationKey")));

    indexes.add(named("message", "message_conversation_created_asc", false, false, null,
        asc("conversationKey"), asc("createdOn")));
    indexes.add(named("message", "message_conversation_created_id_desc", false, false, null,
        asc("conversationKey"), desc("createdOn"), desc("_id")));
    indexes.add(named("message", "message_participant_created_desc", false, false, null,
        asc("participantIds"), desc("createdOn")));
    indexes.add(named("message", "message_participant_created_id_desc", false, false, null,
        asc("participantIds"), desc("createdOn"), desc("_id")));
    indexes.add(named("message", "message_recipient_sender_read", false, false, null,
        asc("recipientAccountId"), asc("senderAccountId"), asc("read")));
    indexes.add(named("notification", "notification_account_created_id_desc", false, false,
        null, asc("accountId"), desc("createdOn"), desc("_id")));
    indexes.add(named("notification", "notification_account_read", false, false, null,
        asc("accountId"), asc("read")));
    indexes.add(unnamed("notification_preference", true, false, null, asc("accountId")));
    indexes.add(unnamed("notification_delivery_guard", false, false, 0L, asc("expiresAt")));
    indexes.add(unnamed("notification_rate_limit", false, false, 0L, asc("expiresAt")));

    indexes.add(named("post", "post_account_created_id_desc", false, false, null,
        asc("accountId"), desc("createdOn"), desc("_id")));
    indexes.add(named("post", "post_created_id_desc", false, false, null,
        desc("createdOn"), desc("_id")));
    indexes.add(named("post", "post_root_created_asc", false, false, null,
        asc("rootId"), asc("createdOn")));
    indexes.add(named("post", "post_parent", false, false, null, asc("parentId")));
    indexes.add(named("post", "post_expires", false, false, null, asc("expiresOn")));
    indexes.add(named("post", "post_account_parent", false, false, null,
        asc("accountId"), asc("parentId")));
    indexes.add(named("post", "void_discovery_new", false, false, null,
        asc("parentId"), desc("createdOn"), desc("_id"), asc("expiresOn")));
    indexes.add(named("post", "void_discovery_fading", false, false, null,
        asc("parentId"), asc("expiresOn"), asc("_id")));
    indexes.add(named("post", "void_discovery_revived", false, false, null,
        asc("parentId"), desc("lastExtendedOn"), desc("_id"), asc("expiresOn")));
    indexes.add(named("post", "void_discovery_topic", false, false, null,
        asc("topics.canonical"), asc("expiresOn"), asc("rootId")));
    indexes.add(named("post", "void_people_active_pool", false, false, null,
        asc("expiresOn"), asc("accountId")));
    indexes.add(named("post", "void_people_authored_activity", false, false, null,
        asc("accountId"), asc("expiresOn"), desc("createdOn"), desc("_id")));
    indexes.add(named("post", "federation_outbound_post_scan", false, false, null,
        asc("federationOutboundEligible"), asc("createdOn"), asc("_id")));
    indexes.add(named("post_like", "post_like_post_account_unique", true, false, null,
        asc("postId"), asc("accountId")));
    indexes.add(named("post_report", "report_created_id_desc", false, false, null,
        desc("createdOn"), desc("_id")));
    indexes.add(named("post_report", "report_status_created_id_desc", false, false, null,
        asc("status"), desc("createdOn"), desc("_id")));
    indexes.add(unnamed("post_report", true, true, null, asc("openDedupeKey")));
    indexes.add(unnamed("post_report", false, false, null, asc("reportType")));
    indexes.add(unnamed("post_report", false, false, null, asc("targetType")));
    indexes.add(named("hidden_post_thread", "account_root_unique", true, false, null,
        asc("accountId"), asc("rootPostId")));
    indexes.add(unnamed("hidden_post_thread", false, false, null, asc("accountId")));
    indexes.add(unnamed("hidden_post_thread", false, false, null, asc("rootPostId")));
    indexes.add(named("post_link_preview_cache", "post_link_preview_cache_expiry", false,
        false, 0L, asc("expiresOn")));

    indexes.add(named("federation_delivery_job", "federation_delivery_post_peer_unique", true,
        false, null, asc("postId"), asc("peerName")));
    indexes.add(named("federation_delivery_job", "federation_delivery_due", false, false, null,
        asc("state"), asc("nextAttemptOn"), asc("createdOn")));
    indexes.add(named("federation_delivery_job", "federation_delivery_expired_claim", false,
        false, null, asc("state"), asc("claimUntil")));

    indexes.add(unnamed("music_track", true, false, null, asc("path")));
    indexes.add(unnamed("music_track", false, false, null, asc("artist")));
    indexes.add(unnamed("music_track", false, false, null, asc("album")));
    indexes.add(unnamed("music_track", false, false, null, asc("genre")));
    indexes.add(unnamed("music_playlist", true, false, null, asc("normalizedName")));
    indexes.add(unnamed("music_metadata_edit", false, false, null, asc("trackId")));
    indexes.add(unnamed("music_metadata_edit", false, false, null, asc("expiresAt")));
    indexes.add(unnamed("music_radio_history", false, false, null, asc("stationSequence")));
    indexes.add(unnamed("music_radio_history", false, false, null, asc("occurredAt")));
    indexes.add(unnamed("music_access_attempt", false, false, 0L, asc("expiresAt")));

    indexes.add(named("restaurant", "restaurant_coordinate_bounds", false, false, null,
        asc("address.latitude"), asc("address.longitude")));
    indexes.add(named("restaurant", "restaurant_inventory_location_name", false, false, null,
        asc("searchState"), asc("searchCity"), asc("dedupeKey"), asc("_id")));
    indexes.add(named("restaurant", "restaurant_inventory_city_name", false, false, null,
        asc("searchCity"), asc("dedupeKey"), asc("_id")));
    indexes.add(named("restaurant", "restaurant_inventory_state_name", false, false, null,
        asc("searchState"), asc("dedupeKey"), asc("_id")));
    indexes.add(named("restaurant", "restaurant_dedupe_key_member", false, false, null,
        asc("dedupeKey"), asc("_id")));
    indexes.add(unnamed("restaurant", true, true, null, asc("normalizedName")));
    indexes.add(named("vote", "restaurant_account_unique", true, false, null,
        asc("restaurantId"), asc("accountId")));
    indexes.add(unnamed("vote", false, false, null, asc("restaurantId")));
    indexes.add(named("favorite", "restaurant_account_unique", true, false, null,
        asc("restaurantId"), asc("accountId")));
    indexes.add(unnamed("favorite", false, false, null, asc("restaurantId")));
    indexes.add(unnamed("favorite", false, false, null, asc("accountId")));
    indexes.add(named("session", "wfl_session_participant_created", false, false, null,
        asc("participantAccountIds"), desc("createdOn"), asc("_id")));
    indexes.add(unnamed("session", false, false, null, asc("createdByAccountId")));
    indexes.add(unnamed("session", false, false, null, asc("participantAccountIds")));
    indexes.add(named("session", "wfl_session_delete_ttl", false, false, 0L,
        asc("deleteOn")));
    indexes.add(named("import_preview", "restaurant_import_preview_expiry", false, false, 0L,
        asc("expiresOn")));
    indexes.add(named("import_preview", "restaurant_import_preview_actor_created", false,
        false, null, asc("actorAccountId"), desc("createdOn")));

    indexes.add(named("audit_event", "shared_audit_occurred_desc", false, false, null,
        desc("occurredAt")));
    indexes.add(named("audit_event", "shared_audit_account_occurred_desc", false, false, null,
        asc("accountId"), desc("occurredAt")));
    indexes.add(named("audit_event", "shared_audit_action_occurred_desc", false, false, null,
        asc("action"), desc("occurredAt")));
    indexes.add(named("audit_event", "shared_audit_outcome_occurred_desc", false, false, null,
        asc("outcome"), desc("occurredAt")));
    indexes.add(named("audit_event", "shared_audit_path_occurred_desc", false, false, null,
        asc("relativePath"), desc("occurredAt")));
    indexes.add(unnamed("audit_event", false, false, null, asc("accountId")));
    indexes.add(unnamed("audit_event", false, false, null, asc("action")));
    indexes.add(unnamed("audit_event", false, false, null, asc("occurredAt")));
    indexes.add(unnamed("audit_event", false, false, 0L, asc("expiresAt")));
    indexes.add(named("media_job", "media_lru", false, false, null,
        asc("status"), asc("lastAccessedAt"), asc("_id")));
    indexes.add(named("media_job", "media_cleanup_due", false, false, null,
        asc("artifactsCleaned"), asc("cleanupAfter"), asc("status"), asc("_id")));
    indexes.add(unnamed("media_job", false, false, null, asc("ownerId")));
    indexes.add(unnamed("media_job", false, false, null, asc("cacheKey")));
    indexes.add(unnamed("media_job", true, true, null, asc("activeCacheKey")));
    indexes.add(unnamed("media_job", false, false, null, asc("status")));
    indexes.add(unnamed("media_job", false, false, null, asc("updatedAt")));
    indexes.add(named("media_job", "shared_media_delete_ttl", false, false, 0L,
        asc("deleteAt")));
    indexes.add(unnamed("mutation_recovery", false, false, null, asc("ownerId")));
    indexes.add(unnamed("mutation_recovery", false, false, null, asc("updatedAt")));
    indexes.add(named("recycle_item", "shared_recycle_state_deleted_desc", false, false, null,
        asc("state"), desc("deletedAt"), desc("_id")));
    indexes.add(named("recycle_item", "shared_recycle_state_recovery_due", false, false, null,
        asc("state"), asc("deletedAt"), asc("_id"), asc("retryAfter")));
    indexes.add(named("recycle_item", "shared_recycle_state_expiry", false, false, null,
        asc("state"), asc("expiresAt"), asc("_id"), asc("retryAfter")));
    indexes.add(named("upload_session", "upload_owner_state", false, false, null,
        asc("ownerId"), asc("state")));
    indexes.add(named("upload_session", "upload_maintenance_due", false, false, null,
        asc("state"), asc("maintenanceRetryAt"), asc("expiresAt"), asc("_id")));
    indexes.add(unnamed("upload_session", false, false, null, asc("ownerId")));
    indexes.add(unnamed("upload_session", false, false, null, asc("expiresAt")));
    indexes.add(named("upload_session", "shared_upload_delete_ttl", false, false, 0L,
        asc("deleteAt")));

    indexes.add(unnamed("vehicle", true, false, null, asc("vin")));
    indexes.add(named("vin_decode_cache", "vehicle_vin_cache_expiry", false, false, 0L,
        asc("expiresOn")));

    indexes.add(named("application_lease", "lease_expiry", false, false, null,
        asc("expiresAt")));
    indexes.add(named("scheduled_collector_run", "scheduled_collector_status_completed", false,
        false, null, asc("status"), desc("completedOn")));
    indexes.add(named("migration_record", "migration_status_completed", false, false, null,
        asc("status"), desc("completedAt")));

    indexes.add(named("admin_activity", "admin_activity_created_id_desc", false, false, null,
        desc("createdOn"), desc("_id")));
    indexes.add(named("admin_activity", "admin_activity_action_created_id_desc", false, false,
        null, asc("action"), desc("createdOn"), desc("_id")));
    indexes.add(named("admin_activity", "admin_activity_target_created_id_desc", false, false,
        null, asc("targetType"), desc("createdOn"), desc("_id")));
    indexes.add(named("admin_activity", "admin_activity_actor_created_id_desc", false, false,
        null, asc("actorUsername"), desc("createdOn"), desc("_id")));

    return List.copyOf(indexes);
  }

  private static IndexDefinition globalIdIndex(String collection) {
    return new IndexDefinition(
        collection,
        Optional.empty(),
        "_id_",
        List.of(new IndexKey("_id", 1)),
        true,
        false,
        Map.of(),
        Optional.empty(),
        Optional.empty());
  }

  private static IndexDefinition named(
      String kind,
      String legacyName,
      boolean unique,
      boolean legacySparse,
      Long expireAfterSeconds,
      SourceIndexKey... sourceKeys) {
    return kindIndex(kind, legacyName, unique, legacySparse, expireAfterSeconds, sourceKeys);
  }

  private static IndexDefinition unnamed(
      String kind,
      boolean unique,
      boolean legacySparse,
      Long expireAfterSeconds,
      SourceIndexKey... sourceKeys) {
    return kindIndex(kind, null, unique, legacySparse, expireAfterSeconds, sourceKeys);
  }

  private static IndexDefinition kindIndex(
      String kind,
      String legacyName,
      boolean unique,
      boolean legacySparse,
      Long expireAfterSeconds,
      SourceIndexKey... sourceKeys) {
    var collection = COLLECTION_BY_KIND.get(kind);
    if (collection == null) {
      throw new IllegalStateException("Mongo index kind is not approved.");
    }
    var keys = java.util.Arrays.stream(sourceKeys)
        .map(sourceKey -> new IndexKey(indexPath(sourceKey.path()), sourceKey.direction()))
        .toList();
    if (legacySparse && keys.size() != 1) {
      throw new IllegalStateException("Compound sparse Mongo index requires explicit review.");
    }
    var partialFilter = legacySparse
        ? Map.<String, Object>of("$and", List.of(
            Map.of("_kind", kind),
            Map.of(keys.getFirst().path(), Map.of("$exists", true))))
        : Map.<String, Object>of("_kind", kind);
    return new IndexDefinition(
        collection,
        Optional.of(kind),
        canonicalIndexName(kind, legacyName, keys),
        keys,
        unique,
        false,
        partialFilter,
        Optional.ofNullable(expireAfterSeconds),
        Optional.empty());
  }

  static String canonicalIndexName(String kind, String legacyName, List<IndexKey> keys) {
    requireCanonicalName(kind, "kind");
    var suffix = legacyName == null
        ? keys.stream().map(DomainCollectionManifest::indexNameToken)
            .collect(Collectors.joining("__"))
        : legacyName;
    if (suffix.isBlank()) {
      throw new IllegalArgumentException("Mongo index name is invalid.");
    }
    var canonical = kind + "__" + suffix;
    if (canonical.length() <= MAX_INDEX_NAME_LENGTH) {
      return canonical;
    }
    var hash = sha256(canonical).substring(0, 16);
    var prefixLength = MAX_INDEX_NAME_LENGTH - hash.length() - 2;
    return canonical.substring(0, prefixLength) + "__" + hash;
  }

  private static String indexNameToken(IndexKey key) {
    var path = key.path();
    if (path.startsWith("payload.")) {
      path = path.substring("payload.".length());
    } else if (path.equals("_id.legacyId")) {
      path = "_id";
    }
    var safePath = path.replaceAll("[^A-Za-z0-9_]+", "_");
    return safePath + (key.direction() == 1 ? "_asc" : "_desc");
  }

  private static String indexPath(String sourcePath) {
    if ("_id".equals(sourcePath)) {
      return "_id.legacyId";
    }
    return "payload." + sourcePath;
  }

  private static SourceIndexKey asc(String path) {
    return new SourceIndexKey(path, 1);
  }

  private static SourceIndexKey desc(String path) {
    return new SourceIndexKey(path, -1);
  }

  private static RawKindDefinition kind(
      String collection, String kind, String legacySource, String relativeOwnerTypeName) {
    return new RawKindDefinition(
        collection,
        kind,
        Optional.of(legacySource),
        "dev.christopherbell." + relativeOwnerTypeName,
        1);
  }

  private static RawKindDefinition kindWithoutSource(
      String collection, String kind, String relativeOwnerTypeName) {
    return new RawKindDefinition(
        collection,
        kind,
        Optional.empty(),
        "dev.christopherbell." + relativeOwnerTypeName,
        1);
  }

  private static <K> Map<K, KindDefinition> uniqueMap(
      List<KindDefinition> definitions,
      java.util.function.Function<KindDefinition, K> key,
      String label) {
    var result = new LinkedHashMap<K, KindDefinition>();
    for (var definition : definitions) {
      if (result.put(key.apply(definition), definition) != null) {
        throw new IllegalStateException("Duplicate Mongo manifest " + label + '.');
      }
    }
    return Map.copyOf(result);
  }

  private static void validateManifest() {
    if (ALL_COLLECTIONS.size() != 14 || ALL_KINDS.size() != 52) {
      throw new IllegalStateException("Mongo domain manifest cardinality is invalid.");
    }
    ALL_COLLECTIONS.forEach(collection -> requireCanonicalName(collection, "collection"));
    ALL_KINDS.forEach(definition -> {
      if (!ALL_COLLECTIONS.contains(definition.collection())) {
        throw new IllegalStateException("Mongo manifest target is not approved.");
      }
      requireCanonicalName(definition.kind(), "kind");
    });
    if (!forSource("vehicle_import_state").stream()
        .map(KindDefinition::kind)
        .toList()
        .equals(List.of("nhtsa_import_state", "random_vin_import_state"))) {
      throw new IllegalStateException("Mongo shared source mapping is invalid.");
    }
    var duplicateSources = KINDS_BY_SOURCE.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
    if (!duplicateSources.equals(Set.of("vehicle_import_state"))) {
      throw new IllegalStateException("Mongo source mapping cardinality is invalid.");
    }
    var indexNamesByCollection = new LinkedHashMap<String, Set<String>>();
    for (var index : ALL_INDEXES) {
      var names = indexNamesByCollection.computeIfAbsent(index.collection(), ignored -> new TreeSet<>());
      if (!names.add(index.name())) {
        throw new IllegalStateException("Duplicate Mongo target index name.");
      }
    }
  }

  private static void requireCanonicalName(String value, String label) {
    if (value == null || !CANONICAL_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Mongo manifest " + label + " is invalid.");
    }
  }

  private static String canonicalManifest() {
    var canonical = new StringBuilder();
    ALL_COLLECTIONS.stream().sorted().forEach(collection -> canonical
        .append("collection|").append(collection).append('\n'));
    ALL_KINDS.forEach(definition -> canonical
        .append("kind|").append(definition.collection()).append('|')
        .append(definition.kind()).append('|')
        .append(definition.legacySource().orElse("<none>")).append('|')
        .append(definition.ownerTypeName()).append('|')
        .append(definition.schemaVersion()).append('\n'));
    ALL_INDEXES.stream()
        .sorted(Comparator.comparing(IndexDefinition::collection)
            .thenComparing(index -> index.kind().orElse(""))
            .thenComparing(IndexDefinition::name))
        .forEach(index -> {
          canonical.append("index|").append(index.collection()).append('|')
              .append(index.kind().orElse("<global>")).append('|')
              .append(index.name()).append('|');
          index.keys().forEach(key -> canonical.append(key.path()).append(':')
              .append(key.direction()).append(','));
          canonical.append('|').append(index.unique())
              .append('|').append(index.sparse())
              .append('|').append(index.partialFilterExpression())
              .append('|').append(index.expireAfterSeconds().map(Object::toString).orElse("<none>"))
              .append('|').append(index.collation().orElse("<none>"))
              .append('\n');
        });
    return canonical.toString();
  }

  private static String sha256(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  /** Immutable metadata for one exact discriminator. */
  public record KindDefinition(
      String collection,
      String kind,
      Optional<String> legacySource,
      String ownerTypeName,
      int schemaVersion,
      List<IndexDefinition> indexes) {
    public KindDefinition {
      Objects.requireNonNull(collection, "collection");
      Objects.requireNonNull(kind, "kind");
      legacySource = Objects.requireNonNull(legacySource, "legacySource");
      Objects.requireNonNull(ownerTypeName, "ownerTypeName");
      if (schemaVersion < 1) {
        throw new IllegalArgumentException("Mongo schema version must be positive.");
      }
      indexes = List.copyOf(indexes);
    }
  }

  /** One exact target index, including global identity or exact-kind partial scope. */
  public record IndexDefinition(
      String collection,
      Optional<String> kind,
      String name,
      List<IndexKey> keys,
      boolean unique,
      boolean sparse,
      Map<String, Object> partialFilterExpression,
      Optional<Long> expireAfterSeconds,
      Optional<String> collation) {
    public IndexDefinition {
      Objects.requireNonNull(collection, "collection");
      kind = Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(name, "name");
      keys = List.copyOf(keys);
      partialFilterExpression = Map.copyOf(partialFilterExpression);
      expireAfterSeconds = Objects.requireNonNull(expireAfterSeconds, "expireAfterSeconds");
      collation = Objects.requireNonNull(collation, "collation");
      if (keys.isEmpty() || name.isBlank() || name.length() > MAX_INDEX_NAME_LENGTH
          || expireAfterSeconds.filter(seconds -> seconds < 0).isPresent()) {
        throw new IllegalArgumentException("Mongo index definition is invalid.");
      }
    }
  }

  /** One ordered target-index key. */
  public record IndexKey(String path, int direction) {
    public IndexKey {
      Objects.requireNonNull(path, "path");
      if (path.isBlank() || direction != 1 && direction != -1) {
        throw new IllegalArgumentException("Mongo index key is invalid.");
      }
    }
  }

  private record RawKindDefinition(
      String collection,
      String kind,
      Optional<String> legacySource,
      String ownerTypeName,
      int schemaVersion) {}

  private record SourceIndexKey(String path, int direction) {}
}
