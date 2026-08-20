package dev.christopherbell.configuration.persistence.migration;

import dev.christopherbell.account.api.AccountMigrationVerifier;
import dev.christopherbell.admin.api.AdminMigrationVerifier;
import dev.christopherbell.canesboxtracker.api.CanesBoxTrackerMigrationVerifier;
import dev.christopherbell.configuration.persistence.PlatformMigrationVerifier;
import dev.christopherbell.federation.api.FederationMigrationVerifier;
import dev.christopherbell.location.api.LocationMigrationVerifier;
import dev.christopherbell.message.api.MessageMigrationVerifier;
import dev.christopherbell.music.api.MusicMigrationVerifier;
import dev.christopherbell.notification.api.NotificationMigrationVerifier;
import dev.christopherbell.post.api.PostMigrationVerifier;
import dev.christopherbell.report.api.ReportMigrationVerifier;
import dev.christopherbell.sharedfolder.api.SharedFolderMigrationVerifier;
import dev.christopherbell.vehicle.api.VehicleMigrationVerifier;
import dev.christopherbell.whatsforlunch.api.WhatsForLunchMigrationVerifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed executable ownership for every catalog-declared persistence-port operation. */
final class MigrationPortQueryVerifierRegistry {
  private static final List<Operation> OPERATIONS = List.of(
      op("account", "find-by-id", "account", SemanticFamily.LOOKUP,
          "dev.christopherbell.account.PostgresAccountRepository", "findById", Module.ACCOUNT),
      op("account_follow", "follow-exists", "account_follow", SemanticFamily.LOOKUP,
          "dev.christopherbell.account.follow.PostgresAccountFollowStore", "exists", Module.ACCOUNT),
      op("account_trust_relationship", "relationship-exists", "account_trust_relationship",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.account.trust.PostgresAccountTrustRepository",
          "existsByOwnerAccountIdAndTargetAccountIdAndType", Module.ACCOUNT),
      op("account_deletion_job", "find-by-id", "account_deletion_job", SemanticFamily.LOOKUP,
          "dev.christopherbell.account.deletion.PostgresAccountDeletionJobRepository",
          "findById", Module.ACCOUNT),
      op("browser_session", "find-authentication-by-id", "browser_session",
          SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.configuration.security.browser.PostgresBrowserSessionAuthenticationStore",
          "findById", Module.PLATFORM),
      op("conversation_archive_state", "archive-conversation", "conversation_archive_state",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.message.conversation.PostgresConversationArchiveService",
          "archive", Module.MESSAGE, "conversation_archive_participant"),
      op("message", "conversation-page", "message", SemanticFamily.ORDERED_PAGE,
          "dev.christopherbell.message.PostgresMessageRepository",
          "findByConversationKeyOrderByCreatedOnAsc", Module.MESSAGE, "message_participant"),
      op("message", "participant-page", "message", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.message.PostgresMessageRepository",
          "findByParticipantIdsContainingOrderByCreatedOnDesc", Module.MESSAGE,
          "message_participant"),
      op("notification", "find-by-id", "notification", SemanticFamily.LOOKUP,
          "dev.christopherbell.notification.PostgresNotificationRepository", "findById",
          Module.NOTIFICATION),
      op("notification", "account-page", "notification", SemanticFamily.KEYSET_PAGE,
          "dev.christopherbell.notification.inbox.PostgresNotificationQueryRepository", "page",
          Module.NOTIFICATION),
      op("notification", "unread-by-account", "notification", SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.notification.PostgresNotificationRepository",
          "countByAccountIdAndReadFalse", Module.NOTIFICATION),
      op("notification_preference", "find-by-account", "notification_preference",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.notification.preference.PostgresNotificationPreferenceRepository",
          "findByAccountId", Module.NOTIFICATION),
      op("notification_delivery_guard", "try-acquire", "notification_delivery_guard",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard",
          "tryAcquire", Module.NOTIFICATION),
      op("notification_rate_limit", "try-acquire", "notification_rate_limit",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard",
          "tryAcquire", Module.NOTIFICATION),
      op("post", "find-by-id", "post", SemanticFamily.LOOKUP,
          "dev.christopherbell.post.PostgresPostRepository", "findById", Module.POST),
      op("post", "author-feed-page", "post", SemanticFamily.KEYSET_PAGE,
          "dev.christopherbell.post.feed.PostgresPostFeedQueryRepository", "account", Module.POST),
      op("post", "public-feed-page", "post", SemanticFamily.KEYSET_PAGE,
          "dev.christopherbell.post.feed.PostgresPostFeedQueryRepository", "global", Module.POST),
      op("post_like", "like-exists", "post_like", SemanticFamily.LOOKUP,
          "dev.christopherbell.post.like.PostgresPostLikeStore", "exists", Module.POST),
      op("post_report", "find-by-id", "post_report", SemanticFamily.LOOKUP,
          "dev.christopherbell.report.PostgresReportRepository", "findById", Module.REPORT),
      op("post_report", "moderation-page", "post_report", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.report.query.PostgresReportQueryService", "query", Module.REPORT),
      op("post_report", "find-open-dedupe", "post_report", SemanticFamily.LOOKUP,
          "dev.christopherbell.report.PostgresReportRepository", "findByOpenDedupeKey",
          Module.REPORT),
      op("hidden_post_thread", "find-by-account-and-root", "hidden_post_thread",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.post.hide.PostgresHiddenPostThreadRepository",
          "findByAccountIdAndRootPostId", Module.POST),
      op("hidden_post_thread", "account-page", "hidden_post_thread",
          SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.post.hide.PostgresHiddenPostThreadRepository",
          "findByAccountId", Module.POST),
      op("post_link_preview_cache", "find-by-id", "post_link_preview_cache",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository",
          "findById", Module.POST),
      op("post_link_preview_cache", "delete-expired", "post_link_preview_cache",
          SemanticFamily.DEADLINE_PAGE,
          "dev.christopherbell.post.preview.PostgresPostLinkPreviewCacheRepository",
          "deleteExpired", Module.POST),
      op("federation_scan_state", "load-cursor", "federation_scan_state",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.federation.outbound.PostgresFederationDeliveryJobRepository",
          "loadCursor", Module.FEDERATION),
      op("federation_delivery_job", "claim-due", "federation_delivery_job",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.federation.outbound.PostgresFederationDeliveryJobRepository",
          "claimDue", Module.FEDERATION),
      op("federation_delivery_job", "enqueue-if-absent", "federation_delivery_job",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.federation.outbound.PostgresFederationDeliveryJobRepository",
          "enqueueIfAbsent", Module.FEDERATION),
      op("music_track", "find-by-id", "track", SemanticFamily.LOOKUP,
          "dev.christopherbell.music.catalog.PostgresMusicTrackRepository", "findById",
          Module.MUSIC),
      op("music_track", "catalog-search", "track", SemanticFamily.GROUPED_PROJECTION,
          "dev.christopherbell.music.catalog.PostgresMusicCatalogQueryRepository", "search",
          Module.MUSIC),
      op("music_playlist", "find-by-id", "playlist", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.music.library.PostgresMusicPlaylistRepository", "findById",
          Module.MUSIC, "playlist_track"),
      op("music_metadata_edit", "find-by-id", "metadata_edit", SemanticFamily.LOOKUP,
          "dev.christopherbell.music.metadata.PostgresMusicMetadataEditRepository", "findById",
          Module.MUSIC),
      op("music_metadata_edit", "expiration-page", "metadata_edit", SemanticFamily.DEADLINE_PAGE,
          "dev.christopherbell.music.metadata.PostgresMusicMetadataEditRepository",
          "findTop100ByExpiresAtBeforeOrderByExpiresAtAsc", Module.MUSIC),
      op("music_runtime_state", "global-queue", "runtime_state", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.music.radio.PostgresMusicRuntimeStateRepository", "findQueue",
          Module.MUSIC, "queue_entry"),
      op("music_runtime_state", "global-radio", "runtime_state", SemanticFamily.LOOKUP,
          "dev.christopherbell.music.radio.PostgresMusicRuntimeStateRepository", "findRadio",
          Module.MUSIC, "queue_entry"),
      op("music_radio_history", "station-sequence-page", "radio_history",
          SemanticFamily.ORDERED_PAGE,
          "dev.christopherbell.music.radio.PostgresMusicRadioHistoryRepository",
          "findTop100ByOrderByStationSequenceDesc", Module.MUSIC),
      op("music_access_attempt", "recent-page", "access_attempt", SemanticFamily.ORDERED_PAGE,
          "dev.christopherbell.music.security.PostgresMusicAccessAttemptRepository", "recent",
          Module.MUSIC),
      op("music_access_attempt", "delete-expired", "access_attempt", SemanticFamily.DEADLINE_PAGE,
          "dev.christopherbell.music.security.PostgresMusicAccessAttemptRepository",
          "deleteExpired", Module.MUSIC),
      op("restaurant", "find-by-id", "restaurant", SemanticFamily.LOOKUP,
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository", "findById",
          Module.LUNCH),
      op("restaurant", "find-by-normalized-name", "restaurant", SemanticFamily.LOOKUP,
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository",
          "findByNormalizedName", Module.LUNCH),
      op("restaurant", "coordinate-bounds", "restaurant", SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantRepository",
          "findByCoordinateBounds", Module.LUNCH),
      op("vote", "find-by-restaurant-and-account", "restaurant_vote", SemanticFamily.LOOKUP,
          "dev.christopherbell.whatsforlunch.restaurant.vote.PostgresRestaurantVoteRepository",
          "findByRestaurantIdAndAccountId", Module.LUNCH),
      op("favorite", "find-by-restaurant-and-account", "restaurant_favorite",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository",
          "findByRestaurantIdAndAccountId", Module.LUNCH),
      op("favorite", "account-favorite-page", "restaurant_favorite",
          SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.favorite.PostgresRestaurantFavoriteRepository",
          "findByAccountIdOrderByCreatedOnDesc", Module.LUNCH),
      op("preference", "find-by-account", "lunch_preference", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.preference.PostgresWhatsForLunchPreferenceRepository",
          "findById", Module.LUNCH, "lunch_preference_cuisine"),
      op("session", "find-by-id", "lunch_session", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionRepository",
          "findById", Module.LUNCH, "lunch_session_participant", "lunch_session_restaurant",
          "lunch_session_vote", "lunch_session_reset_audit", "lunch_session_reset_restaurant"),
      op("session", "participant-session-page", "lunch_session",
          SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.session.PostgresWhatsForLunchSessionRepository",
          "findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc", Module.LUNCH,
          "lunch_session_participant"),
      op("daily_picks", "find-by-id", "daily_lunch_picks", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.whatsforlunch.restaurant.PostgresDailyLunchPicksRepository",
          "findById", Module.LUNCH, "daily_lunch_pick_restaurant"),
      op("import_state", "find-by-id", "restaurant_import_state", SemanticFamily.LOOKUP,
          "dev.christopherbell.whatsforlunch.restaurant.PostgresRestaurantImportStateRepository",
          "findById", Module.LUNCH),
      op("import_preview", "claim", "restaurant_import_preview", SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.whatsforlunch.restaurant.importing.PostgresRestaurantImportPreviewStore",
          "claim", Module.LUNCH),
      op("audit_event", "search", "audit_event", SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.sharedfolder.audit.PostgresSharedFolderAuditRepository", "search",
          Module.SHARED_FOLDER),
      op("maintenance_lease", "claim-expired-lease", "maintenance_lease",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.sharedfolder.maintenance.PostgresSharedFolderMaintenanceLeaseStore",
          "tryAcquire", Module.SHARED_FOLDER),
      op("media_job", "find-by-id", "media_job", SemanticFamily.LOOKUP,
          "dev.christopherbell.sharedfolder.media.PostgresMediaJobRepository", "findById",
          Module.SHARED_FOLDER),
      op("mutation_recovery", "find-by-id", "mutation_recovery", SemanticFamily.LOOKUP,
          "dev.christopherbell.sharedfolder.service.PostgresSharedFolderMutationRecoveryRepository",
          "findById", Module.SHARED_FOLDER),
      op("radio_state", "find-by-id", "radio_state", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.sharedfolder.radio.PostgresSharedFolderRadioRepository", "findById",
          Module.SHARED_FOLDER, "radio_track_duration"),
      op("recycle_item", "find-by-id", "recycle_item", SemanticFamily.LOOKUP,
          "dev.christopherbell.sharedfolder.recycle.PostgresSharedFolderRecycleRepository",
          "findById", Module.SHARED_FOLDER),
      op("recycle_item", "state-deleted-page", "recycle_item", SemanticFamily.FILTERED_PAGE,
          "dev.christopherbell.sharedfolder.recycle.PostgresSharedFolderRecycleRepository",
          "findByStateOrderByDeletedAtDescIdDesc", Module.SHARED_FOLDER),
      op("upload_session", "find-by-id", "upload_session", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.sharedfolder.upload.PostgresSharedFolderUploadSessionRepository",
          "findById", Module.SHARED_FOLDER, "upload_chunk"),
      op("vehicle", "find-by-id", "vehicle", SemanticFamily.LOOKUP,
          "dev.christopherbell.vehicle.core.PostgresVehicleRepository", "findById", Module.VEHICLE),
      op("vehicle", "find-by-vin", "vehicle", SemanticFamily.LOOKUP,
          "dev.christopherbell.vehicle.core.PostgresVehicleRepository", "existsByVin", Module.VEHICLE),
      op("vin_decode_cache", "find-by-vin", "vin_decode_cache", SemanticFamily.LOOKUP,
          "dev.christopherbell.vehicle.nhtsa.decode.PostgresVehicleVinDecodeCacheRepository",
          "findById", Module.VEHICLE, "vin_decode_raw_value"),
      op("nhtsa_import_state", "find-by-id", "nhtsa_import_state", SemanticFamily.LOOKUP,
          "dev.christopherbell.vehicle.nhtsa.enrichment.PostgresNhtsaVinImportStateRepository",
          "findById", Module.VEHICLE),
      op("random_vin_import_state", "find-by-id", "random_vin_import_state",
          SemanticFamily.LOOKUP,
          "dev.christopherbell.vehicle.randomvin.importing.PostgresRandomVinImportStateRepository",
          "findById", Module.VEHICLE),
      op("zip_coordinate", "find-by-zip-code", "zip_coordinate", SemanticFamily.LOOKUP,
          "dev.christopherbell.location.zip.PostgresZipCoordinateRepository", "findById",
          Module.LOCATION),
      op("zip_import_state", "find-by-id", "zip_import_state", SemanticFamily.LOOKUP,
          "dev.christopherbell.location.zip.PostgresZipCoordinateImportStateRepository", "findById",
          Module.LOCATION),
      op("price_snapshot", "find-by-id", "price_snapshot", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository", "findById",
          Module.CANES, "metro_price"),
      op("price_snapshot", "weekly-snapshot-page", "price_snapshot", SemanticFamily.ORDERED_PAGE,
          "dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository",
          "findTop60ByOrderByWeekStartDateDesc", Module.CANES, "metro_price"),
      op("application_lease", "claim-expired-lease", "application_lease",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.configuration.persistence.PostgresApplicationLeaseStore",
          "tryAcquire", Module.PLATFORM),
      op("scheduled_collector_run", "save", "scheduled_collector_run",
          SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.configuration.persistence.PostgresScheduledCollectorRunStore",
          "save", Module.PLATFORM),
      op("admin_activity", "find-by-id", "admin_activity", SemanticFamily.JOINED_CHILD_PAGE,
          "dev.christopherbell.admin.activity.PostgresAdminActivityRepository", "findById",
          Module.ADMIN, "admin_activity_value"),
      op("admin_activity", "query", "admin_activity", SemanticFamily.GROUPED_PROJECTION,
          "dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository", "query",
          Module.ADMIN, "admin_activity_value"),
      op("pending_action", "active", "pending_action", SemanticFamily.LOOKUP,
          "dev.christopherbell.admin.commandcenter.action.PostgresPendingActionStore", "active",
          Module.ADMIN),
      op("pending_action", "reserve", "pending_action", SemanticFamily.CONDITIONAL_CLAIM,
          "dev.christopherbell.admin.commandcenter.action.PostgresPendingActionStore", "reserve",
          Module.ADMIN));

  private static final Map<Declaration, Operation> RULES = rules();

  private MigrationPortQueryVerifierRegistry() {}

  static MigrationPortQueryVerifierRegistry standard() {
    return new MigrationPortQueryVerifierRegistry();
  }

  static MigrationPortQueryVerifierRegistry from(PostgresqlMigrationCatalog catalog) {
    var declared = new LinkedHashSet<Declaration>();
    catalog.kinds().forEach(kind -> kind.portQueries().forEach(query -> {
      if (!declared.add(new Declaration(kind.sourceKind(), query))) {
        throw invalid();
      }
    }));
    if (!declared.equals(RULES.keySet())) {
      throw invalid();
    }
    return standard();
  }

  Set<String> names() {
    return OPERATIONS.stream().map(operation -> operation.declaration().queryName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  int declarationCount() {
    return RULES.size();
  }

  List<AdapterBinding> actualAdapterBindings() {
    return OPERATIONS.stream().map(operation -> new AdapterBinding(
        operation.declaration().sourceKind(), operation.declaration().queryName(),
        operation.ownerType(), operation.ownerMethod())).toList();
  }

  Set<String> migrationOwnedFallbackDeclarations() {
    return OPERATIONS.stream()
        .filter(operation -> operation.ownerType().contains(".persistence.migration."))
        .map(operation -> operation.declaration().sourceKind() + "/"
            + operation.declaration().queryName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  Set<String> explicitFamilyDeclarations() {
    return OPERATIONS.stream().filter(operation -> Set.of(
        SemanticFamily.JOINED_CHILD_PAGE, SemanticFamily.GROUPED_PROJECTION)
        .contains(operation.family()))
        .filter(operation -> Set.of(
            new Declaration("message", "participant-page"),
            new Declaration("session", "participant-session-page"),
            new Declaration("music_playlist", "find-by-id"),
            new Declaration("post_report", "moderation-page"),
            new Declaration("music_track", "catalog-search")).contains(operation.declaration()))
        .map(operation -> operation.declaration().sourceKind() + "/"
            + operation.declaration().queryName())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  String semanticFamily(String sourceKind, String queryName) {
    return requireOperation(sourceKind, queryName).family().name();
  }

  boolean verifyConditionalClaimForTest(Connection connection, String schema, String table)
      throws SQLException {
    return switch (table) {
      case "application_lease" -> PlatformMigrationVerifier.verify(
          connection, schema, "application_lease", List.of());
      case "maintenance_lease" -> SharedFolderMigrationVerifier.verify(
          connection, schema, "maintenance_lease", "claim-expired-lease", List.of());
      default -> false;
    };
  }

  boolean verifyExplicitFamilyForTest(
      Connection connection, String schema, String sourceKind, String queryName,
      Map<String, List<Map<String, Object>>> sourceRows) throws SQLException {
    var operation = requireOperation(sourceKind, queryName);
    if (!Set.of(SemanticFamily.JOINED_CHILD_PAGE, SemanticFamily.GROUPED_PROJECTION)
        .contains(operation.family()) || !sourceRows.keySet().equals(operation.tables())) {
      throw new IllegalArgumentException("PostgreSQL migration explicit query fixture is invalid.");
    }
    return execute(connection, schema, operation, sourceRows);
  }

  boolean verifyBoundAdapterForTest(
      Connection connection, String schema, String sourceKind, String queryName,
      Map<String, List<Map<String, Object>>> sourceRows) throws SQLException {
    var operation = requireOperation(sourceKind, queryName);
    if (!sourceRows.keySet().equals(operation.tables())) {
      throw new IllegalArgumentException("PostgreSQL migration adapter fixture is invalid.");
    }
    return execute(connection, schema, operation, sourceRows);
  }

  List<String> schemaViolations(
      Connection connection, String schemaPrefix, PostgresqlMigrationCatalog catalog)
      throws SQLException {
    var result = new ArrayList<String>();
    for (var kind : catalog.kinds()) {
      for (var queryName : kind.portQueries()) {
        var operation = RULES.get(new Declaration(kind.sourceKind(), queryName));
        if (operation == null || !kind.targetTables().containsAll(operation.tables())) {
          result.add(kind.sourceKind() + "/" + queryName);
          continue;
        }
        for (var table : operation.tables()) {
          if (primaryKeys(connection, schemaPrefix + kind.targetSchema(), table).isEmpty()) {
            result.add(kind.sourceKind() + "/" + queryName);
            break;
          }
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
      var operation = RULES.get(new Declaration(kind.sourceKind(), queryName));
      if (operation == null || !kind.targetTables().containsAll(operation.tables())) {
        return false;
      }
      var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
      for (var table : operation.tables()) {
        var schema = schemaPrefix + kind.targetSchema();
        var keys = primaryKeys(connection, schema, table);
        if (keys.isEmpty()) {
          return false;
        }
        tables.put(table, expectedRows(
            connection, schema, platformSchema, runId, kind, table, keys, codec,
            includePriorShadowRows));
      }
      if (!execute(
          connection, schemaPrefix + kind.targetSchema(), operation, Map.copyOf(tables))) {
        return false;
      }
    }
    return true;
  }

  private static boolean execute(
      Connection connection, String schema, Operation operation,
      Map<String, List<Map<String, Object>>> tables) throws SQLException {
    var rows = tables.get(operation.mainTable());
    var declaration = operation.declaration();
    return switch (operation.module()) {
      case ACCOUNT -> AccountMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), rows);
      case ADMIN -> AdminMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
      case CANES -> CanesBoxTrackerMigrationVerifier.verify(
          connection, schema, declaration.queryName(), rows);
      case FEDERATION -> FederationMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
      case LOCATION -> LocationMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), rows);
      case LUNCH -> WhatsForLunchMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), tables);
      case MESSAGE -> MessageMigrationVerifier.verify(
          connection, schema, declaration.queryName(), tables);
      case MUSIC -> MusicMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), tables);
      case NOTIFICATION -> NotificationMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
      case PLATFORM -> PlatformMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), rows);
      case POST -> PostMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
      case REPORT -> ReportMigrationVerifier.verify(
          connection, schema, declaration.queryName(), rows);
      case SHARED_FOLDER -> SharedFolderMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
      case VEHICLE -> VehicleMigrationVerifier.verify(
          connection, schema, declaration.sourceKind(), declaration.queryName(), rows);
    };
  }

  private static List<Map<String, Object>> expectedRows(
      Connection connection,
      String targetSchema,
      String platformSchema,
      UUID runId,
      PostgresqlMigrationCatalog.Kind kind,
      String table,
      List<String> primaryKeys,
      MigrationRowCodec codec,
      boolean includePriorShadowRows) throws SQLException {
    var overlaid = new LinkedHashMap<List<String>, Map<String, Object>>();
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
          var copy = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
          overlaid.put(identity(copy, primaryKeys), copy);
        }
      }
    }
    if (!includePriorShadowRows) {
      return List.copyOf(overlaid.values());
    }
    var existing = existingIdentities(connection, targetSchema, table, primaryKeys);
    return overlaid.entrySet().stream().filter(entry -> existing.contains(entry.getKey()))
        .map(Map.Entry::getValue).toList();
  }

  private static Set<List<String>> existingIdentities(
      Connection connection, String schema, String table, List<String> primaryKeys)
      throws SQLException {
    var sql = "select " + primaryKeys.stream().map(key -> quoted(key) + "::text")
        .collect(java.util.stream.Collectors.joining(", "))
        + " from " + quoted(schema) + "." + quoted(table);
    var result = new LinkedHashSet<List<String>>();
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
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

  private static List<String> primaryKeys(Connection connection, String schema, String table)
      throws SQLException {
    var result = new ArrayList<Map.Entry<Short, String>>();
    try (var keys = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
      while (keys.next()) {
        result.add(Map.entry(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME")));
      }
    }
    result.sort(Map.Entry.comparingByKey());
    return result.stream().map(Map.Entry::getValue).toList();
  }

  private static List<String> identity(
      Map<String, Object> row, List<String> primaryKeys) {
    return primaryKeys.stream().map(key -> java.util.Objects.toString(row.get(key), null)).toList();
  }

  private static String quoted(String identifier) {
    if (identifier == null || !identifier.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("PostgreSQL migration identifier is invalid.");
    }
    return '"' + identifier + '"';
  }

  private static Operation requireOperation(String sourceKind, String queryName) {
    var operation = RULES.get(new Declaration(sourceKind, queryName));
    if (operation == null) {
      throw invalid();
    }
    return operation;
  }

  private static Map<Declaration, Operation> rules() {
    var result = new LinkedHashMap<Declaration, Operation>();
    for (var operation : OPERATIONS) {
      if (result.put(operation.declaration(), operation) != null) {
        throw new IllegalStateException("Duplicate PostgreSQL adapter operation declaration.");
      }
    }
    return java.util.Collections.unmodifiableMap(result);
  }

  private static Operation op(
      String sourceKind,
      String queryName,
      String mainTable,
      SemanticFamily family,
      String ownerType,
      String ownerMethod,
      Module module,
      String... additionalTables) {
    var tables = new LinkedHashSet<String>();
    tables.add(mainTable);
    tables.addAll(List.of(additionalTables));
    return new Operation(new Declaration(sourceKind, queryName), mainTable,
        java.util.Collections.unmodifiableSet(tables), family, ownerType, ownerMethod, module);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("PostgreSQL migration port-query registry is invalid.");
  }

  private record Declaration(String sourceKind, String queryName) {}

  record AdapterBinding(
      String sourceKind, String queryName, String ownerType, String operation) {}

  private record Operation(
      Declaration declaration,
      String mainTable,
      Set<String> tables,
      SemanticFamily family,
      String ownerType,
      String ownerMethod,
      Module module) {}

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

  private enum Module {
    ACCOUNT,
    ADMIN,
    CANES,
    FEDERATION,
    LOCATION,
    LUNCH,
    MESSAGE,
    MUSIC,
    NOTIFICATION,
    PLATFORM,
    POST,
    REPORT,
    SHARED_FOLDER,
    VEHICLE
  }
}
