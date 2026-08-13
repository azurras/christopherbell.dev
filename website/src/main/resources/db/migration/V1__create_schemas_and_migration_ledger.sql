CREATE SCHEMA ${schema_prefix}identity;
CREATE SCHEMA ${schema_prefix}social;
CREATE SCHEMA ${schema_prefix}communication;
CREATE SCHEMA ${schema_prefix}federation;
CREATE SCHEMA ${schema_prefix}music;
CREATE SCHEMA ${schema_prefix}shared_folder;
CREATE SCHEMA ${schema_prefix}mobility;
CREATE SCHEMA ${schema_prefix}lunch;
CREATE SCHEMA ${schema_prefix}canes;
CREATE SCHEMA ${schema_prefix}platform;

CREATE TABLE ${schema_prefix}platform.persistence_migration_run (
  run_id uuid PRIMARY KEY,
  catalog_version varchar(64) NOT NULL,
  source_database varchar(128) NOT NULL,
  target_database varchar(128) NOT NULL,
  source_frozen boolean NOT NULL,
  status varchar(32) NOT NULL CHECK (
    status IN ('STAGING', 'RECONCILING', 'READY', 'PUBLISHED', 'FAILED')),
  started_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  completed_at timestamptz,
  CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE ${schema_prefix}platform.persistence_migration_source (
  run_id uuid NOT NULL,
  source_kind varchar(96) NOT NULL,
  source_id varchar(512) NOT NULL,
  transformer_version integer NOT NULL CHECK (transformer_version > 0),
  source_hash char(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
  target_hash char(64) CHECK (target_hash IS NULL OR target_hash ~ '^[0-9a-f]{64}$'),
  status varchar(24) NOT NULL CHECK (status IN ('STAGED', 'VERIFIED', 'PUBLISHED', 'FAILED')),
  PRIMARY KEY (run_id, source_kind, source_id),
  CONSTRAINT persistence_migration_source_run_fk
    FOREIGN KEY (run_id)
    REFERENCES ${schema_prefix}platform.persistence_migration_run(run_id)
    ON DELETE RESTRICT
);

CREATE INDEX persistence_migration_source_status_idx
  ON ${schema_prefix}platform.persistence_migration_source (run_id, source_kind, status);
