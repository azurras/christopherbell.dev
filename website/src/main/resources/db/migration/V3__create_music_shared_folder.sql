CREATE TABLE ${schema_prefix}music.track (
  track_id varchar(128) PRIMARY KEY,
  relative_path text NOT NULL,
  observed_token text,
  pending_observed_token text,
  title text NOT NULL,
  artist text,
  album_artist text,
  album text,
  track_number integer,
  disc_number integer,
  genre text,
  release_year integer,
  duration_seconds numeric(20, 9) NOT NULL DEFAULT 0,
  audio_codec text,
  container text,
  artwork_revision text,
  favorite boolean NOT NULL DEFAULT false,
  excluded_from_radio boolean NOT NULL DEFAULT false,
  index_status varchar(64) NOT NULL,
  index_failure text,
  last_probe_attempt_at timestamptz,
  indexed_at timestamptz,
  missing_since timestamptz,
  CONSTRAINT track_relative_path_uk UNIQUE (relative_path),
  CONSTRAINT track_duration_nonnegative_ck CHECK (duration_seconds >= 0)
);

CREATE INDEX track__track_radio_candidate
  ON ${schema_prefix}music.track
    (excluded_from_radio, favorite DESC, artist, album, track_id)
  WHERE index_status = 'READY' AND missing_since IS NULL;

CREATE TABLE ${schema_prefix}music.playlist (
  playlist_id varchar(128) PRIMARY KEY,
  normalized_name text NOT NULL,
  name text NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  updated_by_account_id varchar(128) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT playlist_normalized_name_uk UNIQUE (normalized_name),
  CONSTRAINT playlist_version_nonnegative_ck CHECK (version >= 0),
  CONSTRAINT playlist_updated_by_account_fk FOREIGN KEY (updated_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL
);

CREATE TABLE ${schema_prefix}music.playlist_track (
  playlist_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  track_id varchar(128) NOT NULL,
  PRIMARY KEY (playlist_id, ordinal),
  CONSTRAINT playlist_track_track_uk UNIQUE (playlist_id, track_id),
  CONSTRAINT playlist_track_playlist_fk FOREIGN KEY (playlist_id)
    REFERENCES ${schema_prefix}music.playlist (playlist_id) ON DELETE CASCADE,
  CONSTRAINT playlist_track_track_fk FOREIGN KEY (track_id)
    REFERENCES ${schema_prefix}music.track (track_id) ON DELETE RESTRICT,
  CONSTRAINT playlist_track_ordinal_nonnegative_ck CHECK (ordinal >= 0)
);

CREATE INDEX playlist_track__playlist_track_track
  ON ${schema_prefix}music.playlist_track (track_id, playlist_id, ordinal);

CREATE TABLE ${schema_prefix}music.metadata_edit (
  metadata_edit_id varchar(128) PRIMARY KEY,
  track_id varchar(128) NOT NULL,
  source_path text NOT NULL,
  backup_file_name text NOT NULL,
  backup_sha256 varchar(64) NOT NULL,
  original_observed_token text NOT NULL,
  replacement_observed_token text,
  original_audio_codec text,
  original_duration_seconds numeric(20, 9) NOT NULL DEFAULT 0,
  edited_by_account_id varchar(128) NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  status varchar(64) NOT NULL,
  undone_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT metadata_edit_track_fk FOREIGN KEY (track_id)
    REFERENCES ${schema_prefix}music.track (track_id) ON DELETE RESTRICT,
  CONSTRAINT metadata_edit_account_fk FOREIGN KEY (edited_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT metadata_edit_backup_sha256_ck CHECK (backup_sha256 ~ '^[0-9A-Fa-f]{64}$'),
  CONSTRAINT metadata_edit_version_nonnegative_ck CHECK (version >= 0)
);

CREATE INDEX metadata_edit__metadata_edit_expiration
  ON ${schema_prefix}music.metadata_edit (expires_at, metadata_edit_id);
CREATE INDEX metadata_edit__metadata_edit_track_created
  ON ${schema_prefix}music.metadata_edit (track_id, created_at DESC, metadata_edit_id);

CREATE TABLE ${schema_prefix}music.runtime_state (
  runtime_state_id varchar(128) PRIMARY KEY,
  state_kind varchar(64) NOT NULL,
  station_sequence bigint,
  track_id varchar(128),
  observed_token text,
  started_at timestamptz,
  duration_seconds numeric(20, 9),
  radio_source varchar(64),
  queue_entry_id varchar(128),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT runtime_state_track_fk FOREIGN KEY (track_id)
    REFERENCES ${schema_prefix}music.track (track_id) ON DELETE SET NULL,
  CONSTRAINT runtime_state_version_nonnegative_ck CHECK (version >= 0),
  CONSTRAINT runtime_state_duration_nonnegative_ck
    CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);

CREATE TABLE ${schema_prefix}music.queue_entry (
  runtime_state_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  queue_entry_id varchar(128) NOT NULL,
  track_id varchar(128) NOT NULL,
  observed_token text,
  enqueued_by_account_id varchar(128),
  enqueued_at timestamptz NOT NULL,
  PRIMARY KEY (runtime_state_id, ordinal),
  CONSTRAINT queue_entry_id_uk UNIQUE (queue_entry_id),
  CONSTRAINT queue_entry_runtime_state_fk FOREIGN KEY (runtime_state_id)
    REFERENCES ${schema_prefix}music.runtime_state (runtime_state_id) ON DELETE CASCADE,
  CONSTRAINT queue_entry_track_fk FOREIGN KEY (track_id)
    REFERENCES ${schema_prefix}music.track (track_id) ON DELETE RESTRICT,
  CONSTRAINT queue_entry_account_fk FOREIGN KEY (enqueued_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL,
  CONSTRAINT queue_entry_ordinal_nonnegative_ck CHECK (ordinal >= 0)
);

CREATE TABLE ${schema_prefix}music.radio_history (
  radio_history_id varchar(128) PRIMARY KEY,
  station_sequence bigint NOT NULL,
  track_id varchar(128) NOT NULL,
  observed_token text NOT NULL,
  artist text,
  radio_source varchar(64) NOT NULL,
  outcome varchar(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT radio_history_track_fk FOREIGN KEY (track_id)
    REFERENCES ${schema_prefix}music.track (track_id) ON DELETE RESTRICT,
  CONSTRAINT radio_history_station_sequence_uk UNIQUE (station_sequence)
);

CREATE INDEX radio_history__radio_history_occurred
  ON ${schema_prefix}music.radio_history (occurred_at DESC, radio_history_id);

CREATE TABLE ${schema_prefix}music.access_attempt (
  access_attempt_id varchar(128) PRIMARY KEY,
  principal_type varchar(64) NOT NULL,
  principal text NOT NULL,
  reason varchar(128) NOT NULL,
  first_attempt_at timestamptz NOT NULL,
  last_attempt_at timestamptz NOT NULL,
  attempt_count bigint NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT access_attempt_count_positive_ck CHECK (attempt_count > 0),
  CONSTRAINT access_attempt_window_ck CHECK (last_attempt_at >= first_attempt_at)
);

CREATE INDEX access_attempt__access_attempt_expiration
  ON ${schema_prefix}music.access_attempt (expires_at, access_attempt_id);

CREATE TABLE ${schema_prefix}shared_folder.audit_event (
  audit_event_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  action varchar(128) NOT NULL,
  relative_path text,
  size_bytes bigint,
  outcome varchar(64) NOT NULL,
  failure_category varchar(128),
  client_ip inet NOT NULL,
  occurred_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT audit_event_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT audit_event_size_nonnegative_ck CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

CREATE INDEX audit_event__audit_event_occurred
  ON ${schema_prefix}shared_folder.audit_event (occurred_at DESC, audit_event_id);
CREATE INDEX audit_event__audit_event_expiration
  ON ${schema_prefix}shared_folder.audit_event (expires_at, audit_event_id);

CREATE TABLE ${schema_prefix}shared_folder.maintenance_lease (
  lease_name varchar(128) PRIMARY KEY,
  owner_token varchar(128) NOT NULL,
  fence_token bigint NOT NULL DEFAULT 1,
  acquired_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT maintenance_lease_fence_positive_ck CHECK (fence_token > 0)
);

CREATE INDEX maintenance_lease__maintenance_lease_expiration
  ON ${schema_prefix}shared_folder.maintenance_lease (expires_at, lease_name);

CREATE TABLE ${schema_prefix}shared_folder.media_job (
  media_job_id varchar(128) PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  owner_id varchar(128) NOT NULL,
  source_path text NOT NULL,
  source_size bigint NOT NULL,
  source_modified_at timestamptz NOT NULL,
  output_profile varchar(64) NOT NULL,
  profile_version integer NOT NULL,
  cache_key varchar(256) NOT NULL,
  active_cache_key varchar(256),
  status varchar(64) NOT NULL,
  failure_category varchar(128),
  output_bytes bigint NOT NULL DEFAULT 0,
  reserved_bytes bigint NOT NULL DEFAULT 0,
  descriptor_published boolean NOT NULL DEFAULT false,
  deadline timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  last_accessed_at timestamptz,
  cleanup_after timestamptz,
  artifacts_cleaned boolean NOT NULL DEFAULT false,
  delete_at timestamptz,
  CONSTRAINT media_job_owner_fk FOREIGN KEY (owner_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT media_job_active_cache_key_uk UNIQUE (active_cache_key),
  CONSTRAINT media_job_version_nonnegative_ck CHECK (version >= 0),
  CONSTRAINT media_job_sizes_nonnegative_ck
    CHECK (source_size >= 0 AND output_bytes >= 0 AND reserved_bytes >= 0)
);

CREATE INDEX media_job__media_cleanup_due
  ON ${schema_prefix}shared_folder.media_job
    (cleanup_after, last_accessed_at, media_job_id)
  WHERE artifacts_cleaned = false;
CREATE INDEX media_job__media_delete_due
  ON ${schema_prefix}shared_folder.media_job (delete_at, media_job_id)
  WHERE delete_at IS NOT NULL;

CREATE TABLE ${schema_prefix}shared_folder.mutation_recovery (
  mutation_recovery_id varchar(128) PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  owner_id varchar(128) NOT NULL,
  source_path text NOT NULL,
  destination_parent_path text NOT NULL,
  entry_name text NOT NULL,
  source_identity text NOT NULL,
  target_identity text,
  quarantine_key text,
  native_mode boolean NOT NULL DEFAULT false,
  state varchar(64) NOT NULL,
  operation_lease_token varchar(128),
  operation_lease_expires_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT mutation_recovery_owner_fk FOREIGN KEY (owner_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT mutation_recovery_version_nonnegative_ck CHECK (version >= 0),
  CONSTRAINT mutation_recovery_lease_pair_ck CHECK (
    (operation_lease_token IS NULL) = (operation_lease_expires_at IS NULL))
);

CREATE INDEX mutation_recovery__mutation_recovery_lease
  ON ${schema_prefix}shared_folder.mutation_recovery
    (operation_lease_expires_at, updated_at, mutation_recovery_id);

CREATE TABLE ${schema_prefix}shared_folder.radio_state (
  radio_state_id varchar(128) PRIMARY KEY,
  state varchar(64),
  station_sequence bigint NOT NULL,
  relative_path text,
  started_at timestamptz,
  duration_seconds numeric(20, 9),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT shared_radio_duration_nonnegative_ck
    CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
  CONSTRAINT shared_radio_version_nonnegative_ck CHECK (version >= 0)
);

CREATE TABLE ${schema_prefix}shared_folder.radio_track_duration (
  radio_state_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  relative_path text NOT NULL,
  observed_token text NOT NULL,
  duration_seconds numeric(20, 9) NOT NULL,
  PRIMARY KEY (radio_state_id, ordinal),
  CONSTRAINT radio_track_duration_state_fk FOREIGN KEY (radio_state_id)
    REFERENCES ${schema_prefix}shared_folder.radio_state (radio_state_id) ON DELETE CASCADE,
  CONSTRAINT radio_track_duration_nonnegative_ck CHECK (duration_seconds >= 0)
);

CREATE TABLE ${schema_prefix}shared_folder.recycle_item (
  recycle_item_id varchar(128) PRIMARY KEY,
  original_path text NOT NULL,
  deleted_by_account_id varchar(128) NOT NULL,
  deleted_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  payload_key text NOT NULL,
  size_bytes bigint NOT NULL,
  is_directory boolean NOT NULL DEFAULT false,
  source_fingerprint text NOT NULL,
  state varchar(64) NOT NULL,
  replacement_key text,
  replacement_fingerprint text,
  source_identity text NOT NULL,
  retry_after timestamptz NOT NULL,
  CONSTRAINT recycle_item_account_fk FOREIGN KEY (deleted_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT recycle_item_size_nonnegative_ck CHECK (size_bytes >= 0),
  CONSTRAINT recycle_item_replacement_pair_ck CHECK (
    (replacement_key IS NULL) = (replacement_fingerprint IS NULL))
);

CREATE INDEX recycle_item__recycle_recovery_due
  ON ${schema_prefix}shared_folder.recycle_item (state, retry_after, recycle_item_id);
CREATE INDEX recycle_item__recycle_expiration
  ON ${schema_prefix}shared_folder.recycle_item (expires_at, recycle_item_id);

CREATE TABLE ${schema_prefix}shared_folder.upload_session (
  upload_session_id varchar(128) PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  owner_id varchar(128) NOT NULL,
  parent_path text NOT NULL,
  entry_name text NOT NULL,
  expected_bytes bigint NOT NULL,
  expected_sha256 varchar(64) NOT NULL,
  target_observed_token text,
  next_offset bigint NOT NULL DEFAULT 0,
  staging_key text NOT NULL,
  append_lease_token varchar(128),
  append_lease_expires_at timestamptz,
  append_offset bigint,
  append_length bigint,
  append_digest varchar(64),
  append_chunk_key text,
  finalizing_identity text,
  finalizing_replace boolean,
  finalizing_target_identity text,
  finalizing_quarantine_key text,
  finalization_state varchar(64),
  finalization_lease_token varchar(128),
  finalization_lease_expires_at timestamptz,
  expires_at timestamptz NOT NULL,
  delete_at timestamptz,
  maintenance_retry_at timestamptz,
  maintenance_attempts integer NOT NULL DEFAULT 0,
  state varchar(64) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT upload_session_owner_fk FOREIGN KEY (owner_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT upload_session_version_nonnegative_ck CHECK (version >= 0),
  CONSTRAINT upload_session_lengths_nonnegative_ck CHECK (
    expected_bytes >= 0 AND next_offset >= 0
    AND (append_offset IS NULL OR append_offset >= 0)
    AND (append_length IS NULL OR append_length >= 0)
    AND maintenance_attempts >= 0),
  CONSTRAINT upload_session_sha256_ck CHECK (expected_sha256 ~ '^[0-9A-Fa-f]{64}$'),
  CONSTRAINT upload_session_append_lease_pair_ck CHECK (
    (append_lease_token IS NULL) = (append_lease_expires_at IS NULL)),
  CONSTRAINT upload_session_finalization_lease_pair_ck CHECK (
    (finalization_lease_token IS NULL) = (finalization_lease_expires_at IS NULL))
);

CREATE INDEX upload_session__upload_maintenance_due
  ON ${schema_prefix}shared_folder.upload_session
    (maintenance_retry_at, expires_at, upload_session_id);
CREATE INDEX upload_session__upload_owner_state
  ON ${schema_prefix}shared_folder.upload_session
    (owner_id, state, updated_at DESC, upload_session_id);

CREATE TABLE ${schema_prefix}shared_folder.upload_chunk (
  upload_session_id varchar(128) NOT NULL,
  chunk_key text NOT NULL,
  digest varchar(64),
  chunk_length bigint,
  PRIMARY KEY (upload_session_id, chunk_key),
  CONSTRAINT upload_chunk_session_fk FOREIGN KEY (upload_session_id)
    REFERENCES ${schema_prefix}shared_folder.upload_session (upload_session_id) ON DELETE CASCADE,
  CONSTRAINT upload_chunk_digest_ck CHECK (
    digest IS NULL OR digest ~ '^[0-9A-Fa-f]{64}$'),
  CONSTRAINT upload_chunk_length_nonnegative_ck CHECK (
    chunk_length IS NULL OR chunk_length >= 0)
);
