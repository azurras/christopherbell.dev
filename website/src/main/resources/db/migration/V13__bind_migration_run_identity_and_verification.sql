ALTER TABLE ${schema_prefix}platform.persistence_migration_run
  DROP CONSTRAINT persistence_migration_run_finalize_authority_ck,
  ADD COLUMN bridge_release integer,
  ADD CONSTRAINT persistence_migration_run_bridge_release_ck CHECK (
    bridge_release IS NULL OR bridge_release > 0),
  ADD CONSTRAINT persistence_migration_run_identity_authority_ck CHECK (
    (bridge_release IS NULL
      AND release_commit IS NULL
      AND source_uri_digest IS NULL
      AND target_jdbc_url_digest IS NULL
      AND target_role IS NULL
      AND source_snapshot_digest IS NULL
      AND backup_digest IS NULL
      AND writer_lock_digest IS NULL
      AND finalize_evidence_digest IS NULL)
    OR
    (bridge_release IS NULL
      AND source_frozen
      AND NOT finalize_reauthorization_required
      AND release_commit IS NOT NULL
      AND source_uri_digest IS NOT NULL
      AND target_jdbc_url_digest IS NOT NULL
      AND target_role IS NOT NULL
      AND source_snapshot_digest IS NOT NULL
      AND backup_digest IS NOT NULL
      AND writer_lock_digest IS NOT NULL
      AND finalize_evidence_digest IS NOT NULL)
    OR
    (bridge_release IS NOT NULL
      AND release_commit IS NOT NULL
      AND source_uri_digest IS NOT NULL
      AND target_jdbc_url_digest IS NOT NULL
      AND target_role IS NOT NULL
      AND NOT finalize_reauthorization_required
      AND (
        (NOT source_frozen
          AND source_snapshot_digest IS NULL
          AND backup_digest IS NULL
          AND writer_lock_digest IS NULL
          AND finalize_evidence_digest IS NULL)
        OR
        (source_frozen
          AND source_snapshot_digest IS NOT NULL
          AND backup_digest IS NOT NULL
          AND writer_lock_digest IS NOT NULL
          AND finalize_evidence_digest IS NOT NULL))));

ALTER TABLE ${schema_prefix}platform.persistence_migration_kind
  ADD COLUMN staged_rows_valid boolean,
  ADD COLUMN typed_rows_valid boolean;
