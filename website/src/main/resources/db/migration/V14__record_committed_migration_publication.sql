CREATE TABLE ${schema_prefix}platform.persistence_migration_publication_commit (
  run_id uuid PRIMARY KEY,
  committed_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  CONSTRAINT persistence_migration_publication_commit_run_fk
    FOREIGN KEY (run_id)
    REFERENCES ${schema_prefix}platform.persistence_migration_run(run_id)
    ON DELETE RESTRICT
);

CREATE FUNCTION ${schema_prefix}platform.reject_migration_publication_commit_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'Committed migration publication authority is immutable.'
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER persistence_migration_publication_commit_immutable
BEFORE UPDATE OR DELETE
ON ${schema_prefix}platform.persistence_migration_publication_commit
FOR EACH ROW
EXECUTE FUNCTION ${schema_prefix}platform.reject_migration_publication_commit_change();
