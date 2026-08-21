CREATE TABLE ${schema_prefix}identity.account (
  account_id varchar(128) PRIMARY KEY,
  created_by varchar(128),
  created_on timestamptz,
  email varchar(320) NOT NULL,
  normalized_email varchar(320) NOT NULL UNIQUE,
  federation_enabled boolean NOT NULL DEFAULT false,
  federation_enabled_on timestamptz,
  first_name varchar(160),
  invite_code uuid,
  invite_code_owner uuid,
  last_login_on timestamptz,
  last_name varchar(160),
  last_modified_by varchar(128),
  last_updated_on timestamptz,
  login_token varchar(512),
  password_salt varchar(512),
  password_hash varchar(512),
  password_reset_token_hash varchar(512),
  password_reset_token_expires_on timestamptz,
  role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'MOD', 'USER')),
  status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
  username varchar(128) NOT NULL UNIQUE,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE INDEX account__passwordResetTokenHash_asc
  ON ${schema_prefix}identity.account (password_reset_token_hash);
CREATE INDEX account__federation_actor_lookup
  ON ${schema_prefix}identity.account (status, federation_enabled, username);

CREATE TABLE ${schema_prefix}identity.account_permission (
  account_id varchar(128) NOT NULL,
  permission varchar(64) NOT NULL CHECK (permission IN (
    'SHARED_FOLDER_READ', 'SHARED_FOLDER_WRITE', 'MUSIC_READ', 'MUSIC_WRITE')),
  PRIMARY KEY (account_id, permission),
  CONSTRAINT account_permission_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}identity.account_federation_identity (
  account_id varchar(128) PRIMARY KEY,
  actor_id text NOT NULL,
  key_id text NOT NULL,
  public_key_pem text NOT NULL,
  private_key_nonce bytea NOT NULL CHECK (octet_length(private_key_nonce) = 12),
  private_key_ciphertext bytea NOT NULL CHECK (octet_length(private_key_ciphertext) >= 16),
  key_version integer NOT NULL CHECK (key_version > 0),
  created_on timestamptz NOT NULL,
  CONSTRAINT account_federation_identity_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  UNIQUE (actor_id),
  UNIQUE (key_id)
);

CREATE TABLE ${schema_prefix}identity.account_moderation_audit (
  account_id varchar(128) PRIMARY KEY,
  event_id varchar(128) NOT NULL UNIQUE,
  actor_account_id varchar(128) NOT NULL,
  actor_username varchar(128) NOT NULL,
  action varchar(64) NOT NULL,
  target_type varchar(32) NOT NULL,
  target_id varchar(128) NOT NULL,
  target_label varchar(128) NOT NULL,
  reason varchar(500) NOT NULL,
  message varchar(256) NOT NULL,
  CONSTRAINT account_moderation_audit_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}identity.account_moderation_audit_value (
  account_id varchar(128) NOT NULL,
  partition_name varchar(16) NOT NULL CHECK (partition_name IN ('before', 'after', 'metadata')),
  value_key varchar(64) NOT NULL,
  value varchar(100) NOT NULL,
  PRIMARY KEY (account_id, partition_name, value_key),
  CONSTRAINT account_moderation_audit_value_audit_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account_moderation_audit(account_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}identity.account_follow (
  account_follow_id varchar(128) PRIMARY KEY,
  follower_account_id varchar(128) NOT NULL,
  followed_account_id varchar(128) NOT NULL,
  created_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT account_follow_follower_fk FOREIGN KEY (follower_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT account_follow_followed_fk FOREIGN KEY (followed_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT account_follow_follower_target_unique
    UNIQUE (follower_account_id, followed_account_id),
  CHECK (follower_account_id <> followed_account_id)
);
CREATE INDEX account_follow__account_follow_target
  ON ${schema_prefix}identity.account_follow (followed_account_id);

CREATE TABLE ${schema_prefix}identity.account_trust_relationship (
  relationship_id varchar(128) PRIMARY KEY,
  owner_account_id varchar(128) NOT NULL,
  target_account_id varchar(128) NOT NULL,
  trust_type varchar(16) NOT NULL CHECK (trust_type IN ('MUTE', 'BLOCK')),
  created_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT account_trust_owner_fk FOREIGN KEY (owner_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT account_trust_target_fk FOREIGN KEY (target_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT account_trust_owner_target_type_unique
    UNIQUE (owner_account_id, target_account_id, trust_type),
  CHECK (owner_account_id <> target_account_id)
);
CREATE INDEX account_trust_relationship__ownerAccountId_asc
  ON ${schema_prefix}identity.account_trust_relationship (owner_account_id);
CREATE INDEX account_trust_relationship__targetAccountId_asc
  ON ${schema_prefix}identity.account_trust_relationship (target_account_id);
CREATE INDEX account_trust_relationship__void_people_incoming_block
  ON ${schema_prefix}identity.account_trust_relationship
    (target_account_id, trust_type, owner_account_id);

CREATE TABLE ${schema_prefix}identity.account_deletion_job (
  account_deletion_job_id varchar(128) PRIMARY KEY,
  status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'FAILED', 'COMPLETE')),
  next_step varchar(64),
  failure_category varchar(128),
  created_on timestamptz NOT NULL,
  last_updated_on timestamptz NOT NULL,
  completed_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (completed_on IS NULL OR completed_on >= created_on)
);

CREATE TABLE ${schema_prefix}identity.browser_session (
  browser_session_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'MOD', 'USER')),
  token_hash varchar(512) NOT NULL,
  previous_token_hash varchar(512),
  previous_token_expires_on timestamptz,
  account_security_fingerprint varchar(512) NOT NULL,
  created_on timestamptz NOT NULL,
  last_seen_on timestamptz NOT NULL,
  rotated_on timestamptz,
  idle_expires_on timestamptz NOT NULL,
  absolute_expires_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT browser_session_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);
CREATE INDEX browser_session__accountId_asc
  ON ${schema_prefix}identity.browser_session (account_id);
CREATE INDEX browser_session__absoluteExpiresOn_asc
  ON ${schema_prefix}identity.browser_session (absolute_expires_on);

CREATE TABLE ${schema_prefix}social.post (
  post_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  post_text varchar(280) NOT NULL,
  root_post_id varchar(128) NOT NULL,
  parent_post_id varchar(128),
  thread_level integer NOT NULL DEFAULT 0 CHECK (thread_level >= 0),
  created_on timestamptz NOT NULL,
  last_updated_on timestamptz,
  edited_on timestamptz,
  expires_on timestamptz,
  federation_outbound_eligible boolean NOT NULL DEFAULT false,
  last_extended_on timestamptz,
  likes_count integer NOT NULL DEFAULT 0 CHECK (likes_count >= 0),
  thread_reply_likes_count integer NOT NULL DEFAULT 0 CHECK (thread_reply_likes_count >= 0),
  thread_reply_count integer NOT NULL DEFAULT 0 CHECK (thread_reply_count >= 0),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT post_author_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT,
  CONSTRAINT post_root_fk FOREIGN KEY (root_post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT post_parent_fk FOREIGN KEY (parent_post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
  CHECK ((parent_post_id IS NULL AND thread_level = 0) OR
         (parent_post_id IS NOT NULL AND thread_level > 0))
);
CREATE INDEX post__post_account_created_id_desc
  ON ${schema_prefix}social.post (account_id, created_on DESC, post_id DESC);
CREATE INDEX post__post_created_id_desc
  ON ${schema_prefix}social.post (created_on DESC, post_id DESC);
CREATE INDEX post__post_root_created_asc
  ON ${schema_prefix}social.post (root_post_id, created_on, post_id);
CREATE INDEX post__post_parent
  ON ${schema_prefix}social.post (parent_post_id);
CREATE INDEX post__post_expires
  ON ${schema_prefix}social.post (expires_on) WHERE expires_on IS NOT NULL;
CREATE INDEX post__post_account_parent
  ON ${schema_prefix}social.post (account_id, parent_post_id);
CREATE INDEX post__void_discovery_new
  ON ${schema_prefix}social.post (parent_post_id, created_on DESC, post_id DESC, expires_on);
CREATE INDEX post__void_discovery_fading
  ON ${schema_prefix}social.post (parent_post_id, expires_on, post_id);
CREATE INDEX post__void_discovery_revived
  ON ${schema_prefix}social.post (parent_post_id, last_extended_on DESC, post_id DESC, expires_on);
CREATE INDEX post__void_people_active_pool
  ON ${schema_prefix}social.post (expires_on, account_id);
CREATE INDEX post__void_people_authored_activity
  ON ${schema_prefix}social.post (account_id, expires_on, created_on DESC, post_id DESC);
CREATE INDEX post__federation_outbound_post_scan
  ON ${schema_prefix}social.post (federation_outbound_eligible, created_on, post_id);

CREATE TABLE ${schema_prefix}social.post_edit_audit (
  post_id varchar(128) NOT NULL,
  ordinal integer NOT NULL CHECK (ordinal >= 0),
  editor_account_id varchar(128) NOT NULL,
  before_text varchar(280) NOT NULL,
  after_text varchar(280) NOT NULL,
  edited_on timestamptz NOT NULL,
  PRIMARY KEY (post_id, ordinal),
  CONSTRAINT post_edit_audit_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE,
  CONSTRAINT post_edit_audit_editor_fk FOREIGN KEY (editor_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}social.post_topic (
  post_id varchar(128) NOT NULL,
  ordinal integer NOT NULL CHECK (ordinal >= 0),
  canonical varchar(160) NOT NULL,
  display varchar(160) NOT NULL,
  PRIMARY KEY (post_id, ordinal),
  CONSTRAINT post_topic_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE,
  UNIQUE (post_id, canonical)
);
CREATE INDEX post__void_discovery_topic
  ON ${schema_prefix}social.post_topic (canonical, post_id);

CREATE TABLE ${schema_prefix}social.post_link_preview (
  post_id varchar(128) NOT NULL,
  ordinal integer NOT NULL CHECK (ordinal >= 0),
  url text NOT NULL,
  domain_name text,
  title text,
  description text,
  image_url text,
  PRIMARY KEY (post_id, ordinal),
  CONSTRAINT post_link_preview_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE,
  UNIQUE (post_id, url)
);

CREATE TABLE ${schema_prefix}social.post_like (
  post_like_id varchar(128) PRIMARY KEY,
  post_id varchar(128) NOT NULL,
  account_id varchar(128) NOT NULL,
  created_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT post_like_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE,
  CONSTRAINT post_like_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT post_like_post_account_unique UNIQUE (post_id, account_id)
);

CREATE TABLE ${schema_prefix}social.post_report (
  post_report_id varchar(128) PRIMARY KEY,
  post_id varchar(128),
  post_text text,
  reported_account_id varchar(128),
  reported_username varchar(128),
  reporter_account_id varchar(128),
  reporter_username varchar(128),
  open_dedupe_key varchar(512),
  report_type varchar(32) NOT NULL,
  target_type varchar(32) NOT NULL,
  reason varchar(500) NOT NULL,
  details text,
  status varchar(16) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
  resolution varchar(64),
  resolved_by varchar(128),
  open_reports_for_account bigint CHECK (open_reports_for_account IS NULL OR open_reports_for_account >= 0),
  resolved_reports_for_account bigint CHECK (resolved_reports_for_account IS NULL OR resolved_reports_for_account >= 0),
  created_on timestamptz NOT NULL,
  last_updated_on timestamptz,
  resolved_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT post_report_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE SET NULL,
  CONSTRAINT post_report_reported_account_fk FOREIGN KEY (reported_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE SET NULL,
  CONSTRAINT post_report_reporter_account_fk FOREIGN KEY (reporter_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX post_report__openDedupeKey_asc
  ON ${schema_prefix}social.post_report (open_dedupe_key)
  WHERE open_dedupe_key IS NOT NULL;
CREATE INDEX post_report__report_created_id_desc
  ON ${schema_prefix}social.post_report (created_on DESC, post_report_id DESC);
CREATE INDEX post_report__report_status_created_id_desc
  ON ${schema_prefix}social.post_report (status, created_on DESC, post_report_id DESC);
CREATE INDEX post_report__reportType_asc
  ON ${schema_prefix}social.post_report (report_type);
CREATE INDEX post_report__targetType_asc
  ON ${schema_prefix}social.post_report (target_type);

CREATE TABLE ${schema_prefix}social.post_report_moderation_audit (
  post_report_id varchar(128) PRIMARY KEY,
  event_id varchar(128) NOT NULL UNIQUE,
  actor_account_id varchar(128) NOT NULL,
  actor_username varchar(128) NOT NULL,
  action varchar(64) NOT NULL,
  target_type varchar(32) NOT NULL,
  target_id varchar(128) NOT NULL,
  target_label varchar(128) NOT NULL,
  reason varchar(500) NOT NULL,
  message varchar(256) NOT NULL,
  CONSTRAINT post_report_moderation_audit_report_fk FOREIGN KEY (post_report_id)
    REFERENCES ${schema_prefix}social.post_report(post_report_id) ON DELETE CASCADE
);
CREATE TABLE ${schema_prefix}social.post_report_moderation_audit_value (
  post_report_id varchar(128) NOT NULL,
  partition_name varchar(16) NOT NULL CHECK (partition_name IN ('before', 'after', 'metadata')),
  value_key varchar(64) NOT NULL,
  value varchar(100) NOT NULL,
  PRIMARY KEY (post_report_id, partition_name, value_key),
  CONSTRAINT post_report_moderation_audit_value_fk FOREIGN KEY (post_report_id)
    REFERENCES ${schema_prefix}social.post_report_moderation_audit(post_report_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}social.hidden_post_thread (
  hidden_post_thread_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  root_post_id varchar(128) NOT NULL,
  created_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT hidden_post_thread_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT hidden_post_thread_root_fk FOREIGN KEY (root_post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE CASCADE,
  CONSTRAINT hidden_post_thread_account_root_unique UNIQUE (account_id, root_post_id)
);
CREATE INDEX hidden_post_thread__accountId_asc
  ON ${schema_prefix}social.hidden_post_thread (account_id);
CREATE INDEX hidden_post_thread__rootPostId_asc
  ON ${schema_prefix}social.hidden_post_thread (root_post_id);

CREATE TABLE ${schema_prefix}social.post_link_preview_cache (
  url text PRIMARY KEY,
  status varchar(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILURE')),
  preview_url text,
  preview_domain text,
  preview_title text,
  preview_description text,
  preview_image_url text,
  failure_category varchar(128),
  completed_on timestamptz NOT NULL,
  expires_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK ((status = 'SUCCESS' AND preview_url IS NOT NULL AND failure_category IS NULL) OR
         (status = 'FAILURE' AND failure_category IS NOT NULL))
);
CREATE INDEX post_link_preview_cache__post_link_preview_cache_expiry
  ON ${schema_prefix}social.post_link_preview_cache (expires_on);

CREATE TABLE ${schema_prefix}communication.message (
  message_id varchar(128) PRIMARY KEY,
  conversation_key varchar(512) NOT NULL,
  sender_account_id varchar(128) NOT NULL,
  recipient_account_id varchar(128) NOT NULL,
  message_text text NOT NULL,
  is_read boolean NOT NULL DEFAULT false,
  created_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT message_sender_fk FOREIGN KEY (sender_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT,
  CONSTRAINT message_recipient_fk FOREIGN KEY (recipient_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT,
  CHECK (sender_account_id <> recipient_account_id)
);
CREATE INDEX message__message_conversation_created_asc
  ON ${schema_prefix}communication.message (conversation_key, created_on);
CREATE INDEX message__message_conversation_created_id_desc
  ON ${schema_prefix}communication.message (conversation_key, created_on DESC, message_id DESC);
CREATE INDEX message__message_recipient_sender_read
  ON ${schema_prefix}communication.message (recipient_account_id, sender_account_id, is_read);

CREATE TABLE ${schema_prefix}communication.message_participant (
  message_id varchar(128) NOT NULL,
  account_id varchar(128) NOT NULL,
  PRIMARY KEY (message_id, account_id),
  CONSTRAINT message_participant_message_fk FOREIGN KEY (message_id)
    REFERENCES ${schema_prefix}communication.message(message_id) ON DELETE CASCADE,
  CONSTRAINT message_participant_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT
);
CREATE INDEX message__message_participant_created_desc
  ON ${schema_prefix}communication.message_participant (account_id, message_id);

CREATE TABLE ${schema_prefix}communication.conversation_archive_state (
  archive_state_id varchar(128) PRIMARY KEY,
  owner_account_id varchar(128) NOT NULL,
  conversation_key varchar(512) NOT NULL,
  archived_through_message_id varchar(128),
  archived_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT conversation_archive_owner_fk FOREIGN KEY (owner_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT conversation_archive_message_fk FOREIGN KEY (archived_through_message_id)
    REFERENCES ${schema_prefix}communication.message(message_id) ON DELETE SET NULL,
  CONSTRAINT conversation_archive_owner_key_unique UNIQUE (owner_account_id, conversation_key)
);
CREATE TABLE ${schema_prefix}communication.conversation_archive_participant (
  archive_state_id varchar(128) NOT NULL,
  account_id varchar(128) NOT NULL,
  PRIMARY KEY (archive_state_id, account_id),
  CONSTRAINT conversation_archive_participant_state_fk FOREIGN KEY (archive_state_id)
    REFERENCES ${schema_prefix}communication.conversation_archive_state(archive_state_id)
    ON DELETE CASCADE,
  CONSTRAINT conversation_archive_participant_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}communication.notification (
  notification_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  actor_account_id varchar(128),
  actor_username varchar(128),
  post_id varchar(128),
  post_text text,
  message_id varchar(128),
  message_text text,
  lunch_session_id varchar(128),
  lunch_session_text text,
  notification_type varchar(32) NOT NULL CHECK (
    notification_type IN ('MENTION', 'LIKE', 'COMMENT', 'MESSAGE', 'WFL_SESSION')),
  is_read boolean NOT NULL DEFAULT false,
  created_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT notification_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT notification_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE SET NULL,
  CONSTRAINT notification_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE SET NULL,
  CONSTRAINT notification_message_fk FOREIGN KEY (message_id)
    REFERENCES ${schema_prefix}communication.message(message_id) ON DELETE SET NULL
);
CREATE INDEX notification__notification_account_created_id_desc
  ON ${schema_prefix}communication.notification (account_id, created_on DESC, notification_id DESC);
CREATE INDEX notification__notification_account_read
  ON ${schema_prefix}communication.notification (account_id, is_read);

CREATE TABLE ${schema_prefix}communication.notification_preference (
  notification_preference_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL UNIQUE,
  mentions boolean NOT NULL DEFAULT false,
  likes boolean NOT NULL DEFAULT false,
  comments boolean NOT NULL DEFAULT false,
  messages boolean NOT NULL DEFAULT false,
  wfl_sessions boolean NOT NULL DEFAULT false,
  created_on timestamptz,
  last_updated_on timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT notification_preference_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}communication.notification_delivery_guard (
  guard_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  actor_account_id varchar(128) NOT NULL,
  notification_type varchar(64) NOT NULL,
  target_id varchar(128) NOT NULL,
  expires_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT notification_delivery_guard_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT notification_delivery_guard_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);
CREATE INDEX notification_delivery_guard__expiresAt_asc
  ON ${schema_prefix}communication.notification_delivery_guard (expires_at);

CREATE TABLE ${schema_prefix}communication.notification_rate_limit (
  rate_limit_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  actor_account_id varchar(128) NOT NULL,
  notification_type varchar(64) NOT NULL,
  delivery_count bigint NOT NULL DEFAULT 0 CHECK (delivery_count >= 0),
  expires_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT notification_rate_limit_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE,
  CONSTRAINT notification_rate_limit_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE CASCADE
);
CREATE INDEX notification_rate_limit__expiresAt_asc
  ON ${schema_prefix}communication.notification_rate_limit (expires_at);

CREATE TABLE ${schema_prefix}federation.federation_scan_state (
  scan_state_id varchar(128) PRIMARY KEY,
  created_on timestamptz,
  post_id varchar(128),
  updated_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT federation_scan_state_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE SET NULL
);

CREATE TABLE ${schema_prefix}federation.federation_delivery_job (
  delivery_job_id varchar(128) PRIMARY KEY,
  post_id varchar(128) NOT NULL,
  account_id varchar(128) NOT NULL,
  peer_name varchar(256) NOT NULL,
  peer_inbox text NOT NULL,
  state varchar(32) NOT NULL CHECK (
    state IN ('PENDING', 'CLAIMED', 'RETRY', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_on timestamptz,
  claim_owner varchar(256),
  claim_until timestamptz,
  last_status integer CHECK (last_status IS NULL OR last_status BETWEEN 100 AND 599),
  last_outcome varchar(128),
  created_on timestamptz NOT NULL,
  updated_on timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT federation_delivery_post_fk FOREIGN KEY (post_id)
    REFERENCES ${schema_prefix}social.post(post_id) ON DELETE RESTRICT,
  CONSTRAINT federation_delivery_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account(account_id) ON DELETE RESTRICT,
  CONSTRAINT federation_delivery_post_peer_unique UNIQUE (post_id, peer_name),
  CHECK ((claim_owner IS NULL) = (claim_until IS NULL))
);
CREATE INDEX federation_delivery_job__federation_delivery_due
  ON ${schema_prefix}federation.federation_delivery_job
    (state, next_attempt_on, created_on, delivery_job_id);
CREATE INDEX federation_delivery_job__federation_delivery_expired_claim
  ON ${schema_prefix}federation.federation_delivery_job (state, claim_until, delivery_job_id);
