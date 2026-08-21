ALTER TABLE ${schema_prefix}platform.persistence_migration_run
  ADD COLUMN release_commit varchar(128),
  ADD COLUMN source_uri_digest char(64),
  ADD COLUMN target_jdbc_url_digest char(64),
  ADD COLUMN target_role varchar(128),
  ADD COLUMN source_snapshot_digest char(64),
  ADD COLUMN backup_digest char(64),
  ADD COLUMN writer_lock_digest char(64),
  ADD COLUMN finalize_evidence_digest char(64),
  ADD COLUMN finalize_reauthorization_required boolean NOT NULL DEFAULT false;

UPDATE ${schema_prefix}platform.persistence_migration_run
  SET finalize_reauthorization_required = true
  WHERE source_frozen;

ALTER TABLE ${schema_prefix}platform.persistence_migration_run
  ADD CONSTRAINT persistence_migration_run_finalize_authority_ck CHECK (
    (NOT source_frozen AND NOT finalize_reauthorization_required
      AND release_commit IS NULL AND source_uri_digest IS NULL
      AND target_jdbc_url_digest IS NULL AND target_role IS NULL
      AND source_snapshot_digest IS NULL AND backup_digest IS NULL
      AND writer_lock_digest IS NULL AND finalize_evidence_digest IS NULL)
    OR
    (source_frozen AND finalize_reauthorization_required
      AND release_commit IS NULL AND source_uri_digest IS NULL
      AND target_jdbc_url_digest IS NULL AND target_role IS NULL
      AND source_snapshot_digest IS NULL AND backup_digest IS NULL
      AND writer_lock_digest IS NULL AND finalize_evidence_digest IS NULL)
    OR
    (source_frozen AND NOT finalize_reauthorization_required
      AND release_commit IS NOT NULL AND source_uri_digest IS NOT NULL
      AND target_jdbc_url_digest IS NOT NULL AND target_role IS NOT NULL
      AND source_snapshot_digest IS NOT NULL AND backup_digest IS NOT NULL
      AND writer_lock_digest IS NOT NULL AND finalize_evidence_digest IS NOT NULL)),
  ADD CONSTRAINT persistence_migration_run_authority_digest_ck CHECK (
    (source_uri_digest IS NULL OR source_uri_digest ~ '^[0-9a-f]{64}$')
    AND (target_jdbc_url_digest IS NULL OR target_jdbc_url_digest ~ '^[0-9a-f]{64}$')
    AND (source_snapshot_digest IS NULL OR source_snapshot_digest ~ '^[0-9a-f]{64}$')
    AND (backup_digest IS NULL OR backup_digest ~ '^[0-9a-f]{64}$')
    AND (writer_lock_digest IS NULL OR writer_lock_digest ~ '^[0-9a-f]{64}$')
    AND (finalize_evidence_digest IS NULL OR finalize_evidence_digest ~ '^[0-9a-f]{64}$'));
