CREATE TABLE ${schema_prefix}identity.deleted_account_pseudonym (
  pseudonym_id varchar(128) PRIMARY KEY,
  created_on timestamptz NOT NULL DEFAULT transaction_timestamp(),
  CONSTRAINT deleted_account_pseudonym_format_ck
    CHECK (pseudonym_id ~ '^deleted:[0-9a-f]{12}$')
);

INSERT INTO ${schema_prefix}identity.deleted_account_pseudonym (pseudonym_id)
SELECT DISTINCT retained.account_id
FROM (
  SELECT editor_account_id AS account_id
  FROM ${schema_prefix}social.post_edit_audit
  UNION ALL
  SELECT reported_account_id
  FROM ${schema_prefix}social.post_report
  UNION ALL
  SELECT reporter_account_id
  FROM ${schema_prefix}social.post_report
  UNION ALL
  SELECT actor_account_id
  FROM ${schema_prefix}platform.admin_activity
  UNION ALL
  SELECT account_id
  FROM ${schema_prefix}shared_folder.audit_event
  UNION ALL
  SELECT deleted_by_account_id
  FROM ${schema_prefix}shared_folder.recycle_item
) retained
WHERE retained.account_id ~ '^deleted:[0-9a-f]{12}$'
ON CONFLICT (pseudonym_id) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (
      SELECT 1
      FROM (
        SELECT editor_account_id AS account_id
        FROM ${schema_prefix}social.post_edit_audit
        UNION ALL
        SELECT reported_account_id
        FROM ${schema_prefix}social.post_report
        UNION ALL
        SELECT reporter_account_id
        FROM ${schema_prefix}social.post_report
        UNION ALL
        SELECT actor_account_id
        FROM ${schema_prefix}platform.admin_activity
        UNION ALL
        SELECT account_id
        FROM ${schema_prefix}shared_folder.audit_event
        UNION ALL
        SELECT deleted_by_account_id
        FROM ${schema_prefix}shared_folder.recycle_item
      ) retained
      WHERE retained.account_id IS NOT NULL
        AND NOT EXISTS (
          SELECT 1
          FROM ${schema_prefix}identity.account account
          WHERE account.account_id = retained.account_id)
        AND NOT EXISTS (
          SELECT 1
          FROM ${schema_prefix}identity.deleted_account_pseudonym pseudonym
          WHERE pseudonym.pseudonym_id = retained.account_id)) THEN
    RAISE EXCEPTION 'retained account identifier is neither live nor a deletion pseudonym'
      USING ERRCODE = '23503';
  END IF;
END
$$;

CREATE FUNCTION ${schema_prefix}identity.require_live_account_or_deleted_pseudonym()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  retained_account_id text;
BEGIN
  retained_account_id := to_jsonb(NEW) ->> TG_ARGV[0];
  IF retained_account_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF EXISTS (
      SELECT 1
      FROM ${schema_prefix}identity.account account
      WHERE account.account_id = retained_account_id)
    OR EXISTS (
      SELECT 1
      FROM ${schema_prefix}identity.deleted_account_pseudonym pseudonym
      WHERE pseudonym.pseudonym_id = retained_account_id) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'retained account identifier % is not registered', retained_account_id
    USING ERRCODE = '23503';
END
$$;

CREATE CONSTRAINT TRIGGER post_edit_audit_editor_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}social.post_edit_audit
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('editor_account_id');

CREATE CONSTRAINT TRIGGER post_report_reported_account_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}social.post_report
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('reported_account_id');

CREATE CONSTRAINT TRIGGER post_report_reporter_account_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}social.post_report
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('reporter_account_id');

CREATE CONSTRAINT TRIGGER admin_activity_actor_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}platform.admin_activity
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('actor_account_id');

CREATE CONSTRAINT TRIGGER audit_event_account_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}shared_folder.audit_event
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('account_id');

CREATE CONSTRAINT TRIGGER recycle_item_deleted_by_integrity
AFTER INSERT OR UPDATE ON ${schema_prefix}shared_folder.recycle_item
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.require_live_account_or_deleted_pseudonym('deleted_by_account_id');
