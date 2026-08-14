CREATE OR REPLACE FUNCTION ${schema_prefix}identity.require_live_account_or_deleted_pseudonym()
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

  PERFORM 1
  FROM ${schema_prefix}identity.account account
  WHERE account.account_id = retained_account_id
  FOR KEY SHARE;
  IF FOUND THEN
    RETURN NEW;
  END IF;

  PERFORM 1
  FROM ${schema_prefix}identity.deleted_account_pseudonym pseudonym
  WHERE pseudonym.pseudonym_id = retained_account_id
  FOR KEY SHARE;
  IF FOUND THEN
    RETURN NEW;
  END IF;

  RAISE EXCEPTION 'retained account identifier % is not registered', retained_account_id
    USING ERRCODE = '23503';
END
$$;

CREATE FUNCTION ${schema_prefix}identity.prevent_retained_account_identifier_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  retained_account_id text;
BEGIN
  retained_account_id := to_jsonb(OLD) ->> TG_ARGV[0];
  IF EXISTS (
      SELECT 1
      FROM ${schema_prefix}social.post_edit_audit
      WHERE editor_account_id = retained_account_id
      UNION ALL
      SELECT 1
      FROM ${schema_prefix}social.post_report
      WHERE reported_account_id = retained_account_id
      UNION ALL
      SELECT 1
      FROM ${schema_prefix}social.post_report
      WHERE reporter_account_id = retained_account_id
      UNION ALL
      SELECT 1
      FROM ${schema_prefix}platform.admin_activity
      WHERE actor_account_id = retained_account_id
      UNION ALL
      SELECT 1
      FROM ${schema_prefix}shared_folder.audit_event
      WHERE account_id = retained_account_id
      UNION ALL
      SELECT 1
      FROM ${schema_prefix}shared_folder.recycle_item
      WHERE deleted_by_account_id = retained_account_id) THEN
    RAISE EXCEPTION 'retained account identifier % is still referenced', retained_account_id
      USING ERRCODE = '23001';
  END IF;
  RETURN OLD;
END
$$;

CREATE TRIGGER account_retained_identifier_delete_guard
BEFORE DELETE ON ${schema_prefix}identity.account
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.prevent_retained_account_identifier_delete('account_id');

CREATE TRIGGER deleted_pseudonym_retained_identifier_delete_guard
BEFORE DELETE ON ${schema_prefix}identity.deleted_account_pseudonym
FOR EACH ROW EXECUTE FUNCTION
  ${schema_prefix}identity.prevent_retained_account_identifier_delete('pseudonym_id');
