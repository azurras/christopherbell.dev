# MongoDB Collection Catalog

The target schema has exactly 14 physical collections and 52 manifest-owned
document kinds. Every stored domain document uses the canonical envelope
`_id: { kind, legacyId }`, `_kind`, `schemaVersion`, and `payload`. The
application accesses each row through its fixed kind-scoped adapter; a kind
cannot select a collection at runtime.

The authoritative manifest digest is
`576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24`.
Every target collection has MongoDB's global `_id_` index. The table's index
count is the number of additional exact-kind indexes; all 112 such indexes are
defined by the same manifest. `runtime inventory` means the document count must
come from `prod.cmd mongo-inventory`, not from a stale documentation snapshot.

| Physical collection | Owning module | Kind | Legacy source | Schema version | Count | Index contract | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `accounts` | `account` | `account` | `accounts` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `accounts` | `account` | `account_follow` | `account_follows` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `accounts` | `account` | `account_trust_relationship` | `account_trust_relationships` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `accounts` | `account` | `account_deletion_job` | `account_deletion_jobs` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `sessions` | `configuration` | `browser_session` | `browser_sessions` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `sessions` | `message` | `conversation_archive_state` | `conversation_archive_states` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `communications` | `message` | `message` | `messages` | 1 | runtime inventory | 5 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `communications` | `notification` | `notification` | `notifications` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `communications` | `notification` | `notification_preference` | `notification_preferences` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `communications` | `notification` | `notification_delivery_guard` | `notification_delivery_guards` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `communications` | `notification` | `notification_rate_limit` | `notification_rate_limits` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `content` | `post` | `post` | `posts` | 1 | runtime inventory | 13 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `content` | `post` | `post_like` | `post_likes` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `content` | `report` | `post_report` | `post_reports` | 1 | runtime inventory | 5 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `content` | `post` | `hidden_post_thread` | `hidden_post_threads` | 1 | runtime inventory | 3 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `content` | `post` | `post_link_preview_cache` | `post_link_preview_cache` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `federation` | `federation` | `federation_scan_state` | `federation_scan_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `federation` | `federation` | `federation_delivery_job` | `federation_delivery_jobs` | 1 | runtime inventory | 3 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_track` | `music_tracks` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_playlist` | `music_playlists` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_metadata_edit` | `music_metadata_edits` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_runtime_state` | `music_runtime_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_radio_history` | `music_radio_history` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `music` | `music` | `music_access_attempt` | `music_access_attempts` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `restaurant` | `whatsforlunch` | 1 | runtime inventory | 6 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `vote` | `whatsforlunch_ratings` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `favorite` | `whatsforlunch_favorites` | 1 | runtime inventory | 3 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `preference` | `whatsforlunch_preferences` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `session` | `whatsforlunch_sessions` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `daily_picks` | `whatsforlunch_daily_picks` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `import_state` | `restaurant_import_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `whatsforlunch` | `whatsforlunch` | `import_preview` | `restaurant_import_previews` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `audit_event` | `shared_folder_audit` | 1 | runtime inventory | 9 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `maintenance_lease` | `shared_folder_maintenance_leases` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `media_job` | `shared_folder_media_jobs` | 1 | runtime inventory | 8 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `mutation_recovery` | `shared_folder_mutation_recoveries` | 1 | runtime inventory | 2 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `radio_state` | `shared_folder_radio` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `recycle_item` | `shared_folder_recycle_items` | 1 | runtime inventory | 3 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `shared_folder` | `sharedfolder` | `upload_session` | `shared_folder_upload_sessions` | 1 | runtime inventory | 5 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `vehicles` | `vehicle` | `vehicle` | `vehicles` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `vehicles` | `vehicle` | `vin_decode_cache` | `vehicle_vin_decode_cache` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `vehicles` | `vehicle` | `nhtsa_import_state` | `vehicle_import_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `vehicles` | `vehicle` | `random_vin_import_state` | `vehicle_import_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `location` | `location` | `zip_coordinate` | `location_zip_coordinates` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `location` | `location` | `zip_import_state` | `zip_coordinate_import_state` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `canes_box_tracker` | `canesboxtracker` | `price_snapshot` | `canes_box_price_snapshots` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `application_runtime` | `cbell-lib` | `application_lease` | `application_leases` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `application_runtime` | `cbell-lib` | `scheduled_collector_run` | `scheduled_collector_runs` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `application_migrations` | `configuration` | `migration_record` | `application_migrations` | 1 | runtime inventory | 1 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `application_migrations` | `configuration` | `domain_collection_cutover` | cutover-created | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `admin_activity` | `admin` | `admin_activity` | `admin_activity` | 1 | runtime inventory | 4 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |
| `admin_activity` | `admin` | `pending_action` | `command_center_pending_actions` | 1 | runtime inventory | 0 kind-scoped; manifest `576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24` | target |

## Operational contract

- Before cutover, `prod.cmd mongo-consolidation-preview` must report the exact
  manifest digest, zero unexpected sources/targets, and zero collisions.
- The guarded `prod.cmd mongo-consolidate -ConfirmDomainCollectionCutover`
  workflow owns the deployment lock, fresh checksummed backup, dry restore,
  isolated candidate database/port, stopped-writer publication, verification,
  and exact allowlisted deletion. No other result authorizes a drop.
- After cutover, `prod.cmd mongo-inventory` must report exactly the 14 physical
  target names, 52 exact kinds, 126 total indexes, matching counts/checksums,
  all compliance flags true, and no superseded or temporary collection.
- `music_queue_state` and `music_radio_state` are V014 rollback artifacts only.
  They are never migration inputs and are deleted only by the verified
  allowlisted cutover.
- Rollback before deletion reverses the ledger. Rollback after deletion keeps
  the writer stopped and restores the exact retained backup before the legacy
  release can start.
