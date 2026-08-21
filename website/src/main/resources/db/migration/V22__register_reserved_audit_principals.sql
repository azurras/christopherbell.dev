CREATE OR REPLACE FUNCTION ${schema_prefix}identity.require_live_account_or_deleted_pseudonym()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  retained_account_id text;
BEGIN
  retained_account_id := to_jsonb(NEW) ->> TG_ARGV[0];
  IF retained_account_id IS NULL
      OR (retained_account_id IN ('system', 'unknown')
        AND TG_TABLE_SCHEMA = '${schema_prefix}shared_folder'
        AND TG_TABLE_NAME = 'audit_event'
        AND TG_ARGV[0] = 'account_id') THEN
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
