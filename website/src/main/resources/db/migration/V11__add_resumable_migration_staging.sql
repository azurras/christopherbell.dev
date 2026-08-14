CREATE TABLE ${schema_prefix}platform.persistence_migration_kind (
  run_id uuid NOT NULL,
  source_kind varchar(96) NOT NULL,
  transformer_version integer NOT NULL CHECK (transformer_version > 0),
  checkpoint_cursor text,
  source_count bigint NOT NULL DEFAULT 0 CHECK (source_count >= 0),
  source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  staging_complete boolean NOT NULL DEFAULT false,
  staged_count bigint CHECK (staged_count IS NULL OR staged_count >= 0),
  reconstructed_source_digest char(64) CHECK (
    reconstructed_source_digest IS NULL
      OR reconstructed_source_digest ~ '^[0-9a-f]{64}$'),
  relationships_valid boolean,
  port_queries_valid boolean,
  published boolean NOT NULL DEFAULT false,
  published_count bigint NOT NULL DEFAULT 0 CHECK (published_count >= 0),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  published_at timestamptz,
  PRIMARY KEY (run_id, source_kind),
  CONSTRAINT persistence_migration_kind_run_fk
    FOREIGN KEY (run_id)
    REFERENCES ${schema_prefix}platform.persistence_migration_run(run_id)
    ON DELETE RESTRICT,
  CHECK (NOT published OR staging_complete),
  CHECK (published_at IS NULL OR published)
);

ALTER TABLE ${schema_prefix}platform.persistence_migration_source
  ADD COLUMN staged_sequence bigint CHECK (staged_sequence IS NULL OR staged_sequence >= 0);

CREATE UNIQUE INDEX persistence_migration_source_sequence_idx
  ON ${schema_prefix}platform.persistence_migration_source (
    run_id, source_kind, staged_sequence);

CREATE TABLE ${schema_prefix}platform.persistence_migration_staged_row (
  run_id uuid NOT NULL,
  source_kind varchar(96) NOT NULL,
  source_id varchar(512) NOT NULL,
  row_ordinal integer NOT NULL CHECK (row_ordinal >= 0),
  target_ordinal integer NOT NULL CHECK (target_ordinal >= 0),
  target_schema varchar(96) NOT NULL CHECK (target_schema ~ '^[a-z][a-z0-9_]*$'),
  target_table varchar(96) NOT NULL CHECK (target_table ~ '^[a-z][a-z0-9_]*$'),
  source_hash char(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
  row_hash char(64) NOT NULL CHECK (row_hash ~ '^[0-9a-f]{64}$'),
  row_payload bytea NOT NULL,
  PRIMARY KEY (run_id, source_kind, source_id, row_ordinal),
  CONSTRAINT persistence_migration_staged_row_source_fk
    FOREIGN KEY (run_id, source_kind, source_id)
    REFERENCES ${schema_prefix}platform.persistence_migration_source(
      run_id, source_kind, source_id)
    ON DELETE RESTRICT
);

CREATE INDEX persistence_migration_staged_row_target_idx
  ON ${schema_prefix}platform.persistence_migration_staged_row (
    run_id, source_kind, target_schema, target_table, source_id, row_ordinal);
