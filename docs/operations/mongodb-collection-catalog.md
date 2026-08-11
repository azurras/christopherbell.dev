# MongoDB Collection Catalog

This catalog is the source of truth for physical collection ownership in the
`christopherbell` database. `legacy-named` remains active under a historical
name. `rollback-retained` is source-backed data intentionally preserved during
an approved migration observation window; it never authorizes cleanup.
`orphan-candidate` and `system-managed` classify reviewed non-source rows and
also never authorize cleanup.

The catalog describes source expectations. Use `prod.cmd mongo-inventory` for
metadata-only live comparison. A live-only name is an unreviewed extra, not
permission to drop it. Never infer disposability from an empty count.

| Physical name | Logical name | Owner and mapping | Role | Cardinality and retention | Index contract | Sensitivity | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `application_leases` | Application leases | platform and `MongoLeaseDocument` | lease | Bounded by active lease keys; expired leases are reclaimable | `_id` lease key and V001 expiration index | internal | active |
| `scheduled_collector_runs` | Scheduled collector runs | platform and `ScheduledCollectorRun` | event-history | One redacted record per collector attempt; durable | V003 collector and start-time query indexes | internal | active |
| `account_deletion_jobs` | Account deletion jobs | account and `AccountDeletionJob` | job | One pseudonymous retry checkpoint per deletion | `_id` pseudonym | security | active |
| `account_follows` | Account follows | account and `AccountFollow`; manual social queries | edge | Account-owned social edges; deleted with either account | Unique follower/followed pair plus directional lookups | user | active |
| `accounts` | Accounts | account and `Account`; manual session/deletion/federation access | entity | One durable document per account | Unique username and email identity indexes plus lookup indexes | security | active |
| `account_trust_relationships` | Account trust relationships | account and `AccountTrustRelationship`; manual deletion/query access | edge | Account-owned trust edges | Unique owner/target pair plus owner and target indexes | user | active |
| `command_center_pending_actions` | Pending command-center actions | admin and `PendingActionDocument` | singleton-state | Fixed machine-power action key; cleared after execution/cancel | `_id` fixed key | security | active |
| `admin_activity` | Administrative activity | admin and `AdminActivity` | audit | Append-only bounded/redacted audit history | Created, action, target, and actor descending indexes | audit | active |
| `canes_box_price_snapshots` | Canes price snapshots | canesboxtracker and `CanesBoxPriceSnapshot` | event-history | One durable weekly market snapshot | `_id` weekly identity | public-reference | active |
| `application_migrations` | Application migrations | platform and `MigrationRecord` | audit | One durable status/checksum record per migration ID | `_id` migration ID and V001 status protection | internal | active |
| `browser_sessions` | Browser sessions | account security and `BrowserSession`; manual auth/deletion access | entity | Per-browser session; absolute-expiry TTL | Account lookup and absolute-expiry TTL indexes | security | active |
| `federation_delivery_jobs` | Federation delivery jobs | federation and `FederationDeliveryJob`; manual outbox access | job | Bounded retry metadata; no payloads or keys | V007 state, retry, claim, post, and peer indexes | internal | active |
| `federation_scan_state` | Federation scan checkpoint | federation and `FederationScanState` | singleton-state | One durable outbound reconciliation cursor | `_id` fixed cursor key | internal | active |
| `location_zip_coordinates` | ZIP coordinates | location and `ZipCoordinate` | entity | One reference row per supported ZIP | ZIP identity and geographic lookup indexes | public-reference | active |
| `zip_coordinate_import_state` | ZIP import state | location and `ZipCoordinateImportState` | singleton-state | One durable dataset checksum/outcome | `_id` importer key | internal | active |
| `conversation_archive_states` | Conversation archive states | message and `ConversationArchiveState`; manual deletion/query access | preference | Per-account conversation archive edge | Unique account/conversation pair and account query index | user | active |
| `messages` | Messages | message and `Message`; manual conversation/deletion aggregation | entity | Durable direct-message history | Conversation, participant, and unread indexes | confidential | active |
| `music_tracks` | Music tracks | music and `MusicTrack` | entity | One catalog row per observed track revision | Unique path plus artist, album, and genre indexes | user | active |
| `music_playlists` | Music playlists | music and `MusicPlaylist` | entity | One durable document per playlist | Unique normalized name | user | active |
| `music_metadata_edits` | Music metadata edits | music and `MusicMetadataEdit` | audit | Per-track edit history with application-managed expiry | Track and expiry indexes | audit | active |
| `music_queue_state` | Legacy Music queue state | music migration and `MusicQueueState` | singleton-state | One immutable rollback copy retained for seven days after cutover | `_id` fixed key and optimistic version | user | rollback-retained |
| `music_radio_history` | Music radio history | music and `MusicRadioHistoryEvent` | event-history | Append-only playback history | Station sequence and occurrence-time indexes | user | active |
| `music_radio_state` | Legacy Music radio state | music migration and `MusicRadioState` | singleton-state | One immutable rollback copy retained for seven days after cutover | `_id` fixed key and optimistic version | user | rollback-retained |
| `music_runtime_state` | Music runtime state | music and `MusicRuntimeStateDocument`; `MusicRuntimeStateStore` | singleton-state | Exactly queue and radio documents with independent optimistic versions | Collision-proof `_id` values `queue` and `radio` | user | active |
| `music_access_attempts` | Music access attempts | music and `MusicAccessAttempt` | audit | Short-lived bounded security attempts | Absolute-expiry TTL | security | active |
| `notification_delivery_guards` | Notification delivery guards | notification and `NotificationDeliveryGuard`; manual deletion access | lease | Short-lived unique fanout claims | `_id` dedupe key and absolute-expiry TTL | internal | active |
| `notification_rate_limits` | Notification rate limits | notification and `NotificationRateLimit`; manual deletion access | singleton-state | Short-lived fixed-window counters | `_id` scope key and absolute-expiry TTL | internal | active |
| `notifications` | Notifications | notification and `Notification`; manual inbox/deletion access | entity | Per-account notification history | Account/time and account/read indexes | user | active |
| `notification_preferences` | Notification preferences | notification and `NotificationPreference`; manual deletion access | preference | One document per account | Unique account ID | user | active |
| `hidden_post_threads` | Hidden post threads | post and `HiddenPostThread`; manual deletion access | edge | Per-account hidden-thread edges | Unique account/root pair plus directional indexes | user | active |
| `post_likes` | Post likes | post and `PostLike`; manual engagement/deletion access | edge | Per-account post-like edges | Unique post/account pair and query indexes | user | active |
| `posts` | Posts | post and `Post`; manual feed/discovery/federation access | entity | Durable social content with application-managed expiration | Account, creation, thread, parent, and expiration indexes | user | active |
| `post_link_preview_cache` | Post link-preview cache | post and `PostLinkPreviewCacheEntry` | cache | One expiring result per normalized URL; application cleanup | URL `_id` and V003 expiry index | internal | active |
| `post_reports` | Post reports | report and `PostReport` | entity | Durable moderation queue records | Queue/time indexes and sparse unique open-dedupe key | confidential | active |
| `shared_folder_audit` | Shared-folder audit | sharedfolder and `SharedFolderAuditEvent` | audit | Redacted audit events with absolute-expiry TTL | Account, action, outcome, path, occurrence, and TTL indexes | audit | active |
| `shared_folder_maintenance_leases` | Shared-folder maintenance lease | sharedfolder and `SharedFolderMaintenanceLeaseDocument` | lease | One fixed-key process coordination lease | `_id` fixed key and expiry field | internal | active |
| `shared_folder_media_jobs` | Shared-folder media jobs | sharedfolder and `MediaJob`; manual V012 access | job | Bounded job/cache lifecycle with terminal TTL | Owner, cache, status, LRU, claim, and terminal TTL indexes | confidential | active |
| `shared_folder_radio` | Shared-folder radio state | sharedfolder and `SharedFolderRadioDocument` | singleton-state | One optimistic station document with bounded duration knowledge | `_id` fixed key and optimistic version | confidential | active |
| `shared_folder_recycle_items` | Shared-folder recycle items | sharedfolder and `SharedFolderRecycleItem` | entity | Private recycle metadata through restore/expiry workflow | State, deletion, recovery, expiry, and retry indexes | confidential | active |
| `shared_folder_mutation_recoveries` | Shared-folder mutation recoveries | sharedfolder and `SharedFolderMutationRecovery` | job | Retryable mutation recovery journal | Owner and update-time indexes | confidential | active |
| `shared_folder_upload_sessions` | Shared-folder upload sessions | sharedfolder and `SharedFolderUploadSession`; manual V012 access | job | Bounded resumable uploads with terminal TTL | Owner/state, expiry, and terminal TTL indexes | confidential | active |
| `vehicles` | Vehicles | vehicle and `Vehicle` | entity | One durable vehicle document per VIN | Unique VIN | user | active |
| `vehicle_vin_decode_cache` | VIN decode cache | vehicle and `VehicleVinDecodeCache` | cache | One expiring provider response per VIN | VIN `_id` and V003 expiry index | public-reference | active |
| `vehicle_import_state` | Vehicle import state | vehicle and `NhtsaVinImportState`, `RandomVinImportState` | singleton-state | One startup-validated key per import provider: `nhtsa` and `randomvin` by default | Provider-specific `_id` keys; equal configured keys fail startup | internal | active |
| `restaurant_import_previews` | Restaurant import previews | whatsforlunch and `RestaurantImportPreviewDocument` | lease | Short-lived reviewed-import authorization | V002 actor, expiry, and consumed-state indexes | security | active |
| `whatsforlunch_daily_picks` | Daily lunch picks | whatsforlunch and `DailyLunchPicks` | cache | One durable generated result per lunch date | Date-derived `_id` | user | active |
| `whatsforlunch` | Restaurants | whatsforlunch and `Restaurant`; manual inventory/dedupe access | entity | Durable restaurant catalog | Normalized name, source, location, and search indexes | public-reference | legacy-named |
| `whatsforlunch_favorites` | Restaurant favorites | whatsforlunch and `RestaurantFavorite`; manual deletion access | edge | Per-account favorite edges | Unique restaurant/account pair plus directional indexes | user | active |
| `restaurant_import_state` | Restaurant import state | whatsforlunch and `RestaurantImportState` | singleton-state | One durable scheduler state per source | Source `_id` | internal | active |
| `whatsforlunch_ratings` | Restaurant votes | whatsforlunch and `RestaurantVote`; manual query/deletion access | edge | Per-account thumbs vote per restaurant | Unique restaurant/account pair plus account index | user | legacy-named |
| `whatsforlunch_preferences` | Lunch preferences | whatsforlunch and `WhatsForLunchPreference`; manual deletion access | preference | One document per account | Account `_id` | user | active |
| `whatsforlunch_sessions` | Lunch sessions | whatsforlunch and `WhatsForLunchSession`; manual mutation/deletion access | entity | Collaborative sessions with terminal absolute-expiry TTL | Short code, creator, state, archive, and TTL indexes | user | active |

Manual Owner Provenance
-----------------------

This owner registry is intentionally explicit because Spring mapping metadata
cannot discover every `MongoTemplate` store, aggregation, migration, or
cross-collection lookup. The architecture gate scans current `MongoTemplate`
consumers and requires every collection-owning class below (or a narrow,
non-collection infrastructure classification). Feature packages continue to
own their collection names locally.

| Manual owner type | Physical names |
| --- | --- |
| `dev.christopherbell.configuration.mongo.migration.V001EnsureMigrationInfrastructure` | `application_leases`, `application_migrations` |
| `dev.christopherbell.configuration.mongo.migration.V002EnsureRestaurantImportPreviewIndexes` | `restaurant_import_previews` |
| `dev.christopherbell.configuration.mongo.migration.V003EnsureVinPreviewCollectorIndexes` | `post_link_preview_cache`, `scheduled_collector_runs`, `vehicle_vin_decode_cache` |
| `dev.christopherbell.configuration.mongo.migration.V004EnsureVoidDiscoveryIndexes` | `posts` |
| `dev.christopherbell.configuration.mongo.migration.V005EnsureVoidPeopleDiscoveryIndexes` | `account_trust_relationships`, `posts` |
| `dev.christopherbell.configuration.mongo.migration.V006EnsureFederationActorIndex` | `accounts` |
| `dev.christopherbell.configuration.mongo.migration.V007EnsureFederationOutboundIndexes` | `federation_delivery_jobs`, `posts` |
| `dev.christopherbell.configuration.mongo.migration.V008RemoveAccountApprovalFields` | `accounts` |
| `dev.christopherbell.configuration.mongo.migration.V009MoveSocialRelationshipsToEdges` | `account_follows`, `accounts`, `post_likes`, `posts` |
| `dev.christopherbell.configuration.mongo.migration.V010BackfillPostExpirationMetrics` | `posts` |
| `dev.christopherbell.configuration.mongo.migration.V011HardenWhatsForLunchData` | `whatsforlunch`, `whatsforlunch_sessions` |
| `dev.christopherbell.configuration.mongo.migration.V012RetainSharedFolderWork` | `shared_folder_media_jobs`, `shared_folder_radio`, `shared_folder_upload_sessions` |
| `dev.christopherbell.configuration.mongo.migration.V013ConvertRestaurantRatingsToVotes` | `whatsforlunch_ratings` |
| `dev.christopherbell.configuration.mongo.migration.V014ConsolidateMusicRuntimeState` | `music_queue_state`, `music_radio_state`, `music_runtime_state` |

Naming Rules
------------

- Use lowercase `snake_case`.
- Prefix ambiguous names with their owning domain.
- Use plural nouns for entities, edges, jobs, events, histories, and audits.
- Use `_state` for singleton/checkpoint documents and lifecycle suffixes such as
  `_jobs`, `_history`, `_audit`, `_cache`, `_guards`, and `_leases` when applicable.
- Keep active legacy physical names until a separate migration is approved.
- Document every intentionally shared mapping and its collision-proof ID scheme.

Live Comparison Rules
---------------------

`prod.cmd mongo-inventory` returns metadata only. Compare its physical names with
this table. Source-only names may be features that have never persisted data.
Live-only names remain unreviewed extras until current source, migrations,
operations scripts, history, count, size, options, and indexes establish their
ownership. No result from this command authorizes deletion.
