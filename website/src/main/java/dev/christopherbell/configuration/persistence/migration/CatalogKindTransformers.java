package dev.christopherbell.configuration.persistence.migration;

/* Exact class identities are durable catalog provenance; behavior is declarative in the catalog. */
final class AccountTransformer extends CatalogDocumentTransformer {
  AccountTransformer(PostgresqlMigrationCatalog.Kind kind) { super("account", kind); }
}
final class AccountFollowTransformer extends CatalogDocumentTransformer {
  AccountFollowTransformer(PostgresqlMigrationCatalog.Kind kind) { super("account_follow", kind); }
}
final class AccountTrustRelationshipTransformer extends CatalogDocumentTransformer {
  AccountTrustRelationshipTransformer(PostgresqlMigrationCatalog.Kind kind) { super("account_trust_relationship", kind); }
}
final class AccountDeletionJobTransformer extends CatalogDocumentTransformer {
  AccountDeletionJobTransformer(PostgresqlMigrationCatalog.Kind kind) { super("account_deletion_job", kind); }
}
final class BrowserSessionTransformer extends CatalogDocumentTransformer {
  BrowserSessionTransformer(PostgresqlMigrationCatalog.Kind kind) { super("browser_session", kind); }
}
final class ConversationArchiveStateTransformer extends CatalogDocumentTransformer {
  ConversationArchiveStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("conversation_archive_state", kind); }
}
final class MessageTransformer extends CatalogDocumentTransformer {
  MessageTransformer(PostgresqlMigrationCatalog.Kind kind) { super("message", kind); }
}
final class NotificationTransformer extends CatalogDocumentTransformer {
  NotificationTransformer(PostgresqlMigrationCatalog.Kind kind) { super("notification", kind); }
}
final class NotificationPreferenceTransformer extends CatalogDocumentTransformer {
  NotificationPreferenceTransformer(PostgresqlMigrationCatalog.Kind kind) { super("notification_preference", kind); }
}
final class NotificationDeliveryGuardTransformer extends CatalogDocumentTransformer {
  NotificationDeliveryGuardTransformer(PostgresqlMigrationCatalog.Kind kind) { super("notification_delivery_guard", kind); }
}
final class NotificationRateLimitTransformer extends CatalogDocumentTransformer {
  NotificationRateLimitTransformer(PostgresqlMigrationCatalog.Kind kind) { super("notification_rate_limit", kind); }
}
final class PostTransformer extends CatalogDocumentTransformer {
  PostTransformer(PostgresqlMigrationCatalog.Kind kind) { super("post", kind); }
}
final class PostLikeTransformer extends CatalogDocumentTransformer {
  PostLikeTransformer(PostgresqlMigrationCatalog.Kind kind) { super("post_like", kind); }
}
final class PostReportTransformer extends CatalogDocumentTransformer {
  PostReportTransformer(PostgresqlMigrationCatalog.Kind kind) { super("post_report", kind); }
}
final class HiddenPostThreadTransformer extends CatalogDocumentTransformer {
  HiddenPostThreadTransformer(PostgresqlMigrationCatalog.Kind kind) { super("hidden_post_thread", kind); }
}
final class PostLinkPreviewCacheTransformer extends CatalogDocumentTransformer {
  PostLinkPreviewCacheTransformer(PostgresqlMigrationCatalog.Kind kind) { super("post_link_preview_cache", kind); }
}
final class FederationScanStateTransformer extends CatalogDocumentTransformer {
  FederationScanStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("federation_scan_state", kind); }
}
final class FederationDeliveryJobTransformer extends CatalogDocumentTransformer {
  FederationDeliveryJobTransformer(PostgresqlMigrationCatalog.Kind kind) { super("federation_delivery_job", kind); }
}
final class MusicTrackTransformer extends CatalogDocumentTransformer {
  MusicTrackTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_track", kind); }
}
final class MusicPlaylistTransformer extends CatalogDocumentTransformer {
  MusicPlaylistTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_playlist", kind); }
}
final class MusicMetadataEditTransformer extends CatalogDocumentTransformer {
  MusicMetadataEditTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_metadata_edit", kind); }
}
final class MusicRuntimeStateTransformer extends CatalogDocumentTransformer {
  MusicRuntimeStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_runtime_state", kind); }
}
final class MusicRadioHistoryTransformer extends CatalogDocumentTransformer {
  MusicRadioHistoryTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_radio_history", kind); }
}
final class MusicAccessAttemptTransformer extends CatalogDocumentTransformer {
  MusicAccessAttemptTransformer(PostgresqlMigrationCatalog.Kind kind) { super("music_access_attempt", kind); }
}
final class RestaurantTransformer extends CatalogDocumentTransformer {
  RestaurantTransformer(PostgresqlMigrationCatalog.Kind kind) { super("restaurant", kind); }
}
final class RestaurantVoteTransformer extends CatalogDocumentTransformer {
  RestaurantVoteTransformer(PostgresqlMigrationCatalog.Kind kind) { super("vote", kind); }
}
final class RestaurantFavoriteTransformer extends CatalogDocumentTransformer {
  RestaurantFavoriteTransformer(PostgresqlMigrationCatalog.Kind kind) { super("favorite", kind); }
}
final class WhatsForLunchPreferenceTransformer extends CatalogDocumentTransformer {
  WhatsForLunchPreferenceTransformer(PostgresqlMigrationCatalog.Kind kind) { super("preference", kind); }
}
final class WhatsForLunchSessionTransformer extends CatalogDocumentTransformer {
  WhatsForLunchSessionTransformer(PostgresqlMigrationCatalog.Kind kind) { super("session", kind); }
}
final class DailyLunchPicksTransformer extends CatalogDocumentTransformer {
  DailyLunchPicksTransformer(PostgresqlMigrationCatalog.Kind kind) { super("daily_picks", kind); }
}
final class RestaurantImportStateTransformer extends CatalogDocumentTransformer {
  RestaurantImportStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("import_state", kind); }
}
final class RestaurantImportPreviewTransformer extends CatalogDocumentTransformer {
  RestaurantImportPreviewTransformer(PostgresqlMigrationCatalog.Kind kind) { super("import_preview", kind); }
}
final class SharedFolderAuditEventTransformer extends CatalogDocumentTransformer {
  SharedFolderAuditEventTransformer(PostgresqlMigrationCatalog.Kind kind) { super("audit_event", kind); }
}
final class SharedFolderMaintenanceLeaseTransformer extends CatalogDocumentTransformer {
  SharedFolderMaintenanceLeaseTransformer(PostgresqlMigrationCatalog.Kind kind) { super("maintenance_lease", kind); }
}
final class MediaJobTransformer extends CatalogDocumentTransformer {
  MediaJobTransformer(PostgresqlMigrationCatalog.Kind kind) { super("media_job", kind); }
}
final class SharedFolderMutationRecoveryTransformer extends CatalogDocumentTransformer {
  SharedFolderMutationRecoveryTransformer(PostgresqlMigrationCatalog.Kind kind) { super("mutation_recovery", kind); }
}
final class SharedFolderRadioTransformer extends CatalogDocumentTransformer {
  SharedFolderRadioTransformer(PostgresqlMigrationCatalog.Kind kind) { super("radio_state", kind); }
}
final class SharedFolderRecycleItemTransformer extends CatalogDocumentTransformer {
  SharedFolderRecycleItemTransformer(PostgresqlMigrationCatalog.Kind kind) { super("recycle_item", kind); }
}
final class SharedFolderUploadSessionTransformer extends CatalogDocumentTransformer {
  SharedFolderUploadSessionTransformer(PostgresqlMigrationCatalog.Kind kind) { super("upload_session", kind); }
}
final class VehicleTransformer extends CatalogDocumentTransformer {
  VehicleTransformer(PostgresqlMigrationCatalog.Kind kind) { super("vehicle", kind); }
}
final class VehicleVinDecodeCacheTransformer extends CatalogDocumentTransformer {
  VehicleVinDecodeCacheTransformer(PostgresqlMigrationCatalog.Kind kind) { super("vin_decode_cache", kind); }
}
final class NhtsaVinImportStateTransformer extends CatalogDocumentTransformer {
  NhtsaVinImportStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("nhtsa_import_state", kind); }
}
final class RandomVinImportStateTransformer extends CatalogDocumentTransformer {
  RandomVinImportStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("random_vin_import_state", kind); }
}
final class ZipCoordinateTransformer extends CatalogDocumentTransformer {
  ZipCoordinateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("zip_coordinate", kind); }
}
final class ZipCoordinateImportStateTransformer extends CatalogDocumentTransformer {
  ZipCoordinateImportStateTransformer(PostgresqlMigrationCatalog.Kind kind) { super("zip_import_state", kind); }
}
final class CanesBoxPriceSnapshotTransformer extends CatalogDocumentTransformer {
  CanesBoxPriceSnapshotTransformer(PostgresqlMigrationCatalog.Kind kind) { super("price_snapshot", kind); }
}
final class ApplicationLeaseTransformer extends CatalogDocumentTransformer {
  ApplicationLeaseTransformer(PostgresqlMigrationCatalog.Kind kind) { super("application_lease", kind); }
}
final class ScheduledCollectorRunTransformer extends CatalogDocumentTransformer {
  ScheduledCollectorRunTransformer(PostgresqlMigrationCatalog.Kind kind) { super("scheduled_collector_run", kind); }
}
final class ApplicationMigrationRecordTransformer extends CatalogDocumentTransformer {
  ApplicationMigrationRecordTransformer(PostgresqlMigrationCatalog.Kind kind) { super("migration_record", kind); }
}
final class DomainCollectionCutoverTransformer extends CatalogDocumentTransformer {
  DomainCollectionCutoverTransformer(PostgresqlMigrationCatalog.Kind kind) { super("domain_collection_cutover", kind); }
}
final class AdminActivityTransformer extends CatalogDocumentTransformer {
  AdminActivityTransformer(PostgresqlMigrationCatalog.Kind kind) { super("admin_activity", kind); }
}
final class PendingActionTransformer extends CatalogDocumentTransformer {
  PendingActionTransformer(PostgresqlMigrationCatalog.Kind kind) { super("pending_action", kind); }
}
