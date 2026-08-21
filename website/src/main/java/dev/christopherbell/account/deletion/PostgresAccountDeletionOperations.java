package dev.christopherbell.account.deletion;

import static dev.christopherbell.persistence.jooq.communication.Tables.CONVERSATION_ARCHIVE_PARTICIPANT;
import static dev.christopherbell.persistence.jooq.communication.Tables.CONVERSATION_ARCHIVE_STATE;
import static dev.christopherbell.persistence.jooq.communication.Tables.MESSAGE;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_DELIVERY_GUARD;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_PREFERENCE;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_RATE_LIMIT;
import static dev.christopherbell.persistence.jooq.federation.Tables.FEDERATION_DELIVERY_JOB;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_FOLLOW;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_TRUST_RELATIONSHIP;
import static dev.christopherbell.persistence.jooq.identity.Tables.BROWSER_SESSION;
import static dev.christopherbell.persistence.jooq.identity.Tables.DELETED_ACCOUNT_PSEUDONYM;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_PREFERENCE;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION;
import static dev.christopherbell.persistence.jooq.lunch.Tables.LUNCH_SESSION_PARTICIPANT;
import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_FAVORITE;
import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_IMPORT_PREVIEW;
import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_VOTE;
import static dev.christopherbell.persistence.jooq.music.Tables.METADATA_EDIT;
import static dev.christopherbell.persistence.jooq.music.Tables.PLAYLIST;
import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY;
import static dev.christopherbell.persistence.jooq.platform.Tables.ADMIN_ACTIVITY_VALUE;
import static dev.christopherbell.persistence.jooq.shared_folder.Tables.AUDIT_EVENT;
import static dev.christopherbell.persistence.jooq.shared_folder.Tables.RECYCLE_ITEM;
import static dev.christopherbell.persistence.jooq.social.Tables.HIDDEN_POST_THREAD;
import static dev.christopherbell.persistence.jooq.social.Tables.POST;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_EDIT_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_LIKE;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT_MODERATION_AUDIT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Idempotent PostgreSQL effects for durable account deletion steps. */
@PostgresPersistence
public class PostgresAccountDeletionOperations implements AccountDeletionOperations {
  private static final String TOMBSTONE = AccountDeletionService.TOMBSTONE_ID;
  private static final String RETAINED_MESSAGE =
      "Account-related activity retained after account deletion.";

  private final DSLContext database;
  private final AccountDeletionResourceCleaner resources;

  public PostgresAccountDeletionOperations(
      DSLContext database, AccountDeletionResourceCleaner resources) {
    this.database = database;
    this.resources = resources;
  }

  @Override
  public boolean accountExists(String accountId) {
    return database.fetchExists(ACCOUNT, ACCOUNT.ACCOUNT_ID.eq(accountId));
  }

  @Override
  public void ensureTombstone() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    database.insertInto(ACCOUNT)
        .set(ACCOUNT.ACCOUNT_ID, TOMBSTONE)
        .set(ACCOUNT.EMAIL, "deleted-user@invalid.local")
        .set(ACCOUNT.NORMALIZED_EMAIL, "deleted-user@invalid.local")
        .set(ACCOUNT.FIRST_NAME, "Deleted")
        .set(ACCOUNT.LAST_NAME, "User")
        .set(ACCOUNT.ROLE, "USER")
        .set(ACCOUNT.STATUS, "INACTIVE")
        .set(ACCOUNT.USERNAME, TOMBSTONE)
        .set(ACCOUNT.CREATED_ON, now)
        .set(ACCOUNT.LAST_UPDATED_ON, now)
        .onConflict(ACCOUNT.ACCOUNT_ID).doNothing()
        .execute();
  }

  @Override
  public void anonymizePublicPosts(String accountId, String pseudonym) {
    database.transaction(configuration -> {
      var transaction = DSL.using(configuration);
      registerPseudonym(transaction, pseudonym);
      transaction.update(POST).set(POST.ACCOUNT_ID, TOMBSTONE)
          .set(POST.VERSION, POST.VERSION.plus(1L))
          .where(POST.ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(POST_EDIT_AUDIT).set(POST_EDIT_AUDIT.EDITOR_ACCOUNT_ID, pseudonym)
          .where(POST_EDIT_AUDIT.EDITOR_ACCOUNT_ID.eq(accountId)).execute();
    });
  }

  @Override
  public void removePrivateData(String accountId) {
    database.transaction(configuration -> {
      var transaction = DSL.using(configuration);
      var ownedArchives = DSL.select(CONVERSATION_ARCHIVE_STATE.ARCHIVE_STATE_ID)
          .from(CONVERSATION_ARCHIVE_STATE)
          .leftJoin(CONVERSATION_ARCHIVE_PARTICIPANT)
          .on(CONVERSATION_ARCHIVE_PARTICIPANT.ARCHIVE_STATE_ID
              .eq(CONVERSATION_ARCHIVE_STATE.ARCHIVE_STATE_ID))
          .where(CONVERSATION_ARCHIVE_STATE.OWNER_ACCOUNT_ID.eq(accountId)
              .or(CONVERSATION_ARCHIVE_PARTICIPANT.ACCOUNT_ID.eq(accountId)));
      transaction.deleteFrom(CONVERSATION_ARCHIVE_STATE)
          .where(CONVERSATION_ARCHIVE_STATE.ARCHIVE_STATE_ID.in(ownedArchives)).execute();
      transaction.deleteFrom(MESSAGE).where(MESSAGE.SENDER_ACCOUNT_ID.eq(accountId)
          .or(MESSAGE.RECIPIENT_ACCOUNT_ID.eq(accountId))).execute();
      transaction.deleteFrom(NOTIFICATION).where(NOTIFICATION.ACCOUNT_ID.eq(accountId)
          .or(NOTIFICATION.ACTOR_ACCOUNT_ID.eq(accountId))).execute();
      transaction.deleteFrom(NOTIFICATION_PREFERENCE)
          .where(NOTIFICATION_PREFERENCE.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(NOTIFICATION_DELIVERY_GUARD)
          .where(NOTIFICATION_DELIVERY_GUARD.ACCOUNT_ID.eq(accountId)
              .or(NOTIFICATION_DELIVERY_GUARD.ACTOR_ACCOUNT_ID.eq(accountId))).execute();
      transaction.deleteFrom(NOTIFICATION_RATE_LIMIT)
          .where(NOTIFICATION_RATE_LIMIT.ACCOUNT_ID.eq(accountId)
              .or(NOTIFICATION_RATE_LIMIT.ACTOR_ACCOUNT_ID.eq(accountId))).execute();
      transaction.deleteFrom(BROWSER_SESSION).where(BROWSER_SESSION.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(ACCOUNT_TRUST_RELATIONSHIP)
          .where(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID.eq(accountId)
              .or(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID.eq(accountId))).execute();
      transaction.deleteFrom(HIDDEN_POST_THREAD)
          .where(HIDDEN_POST_THREAD.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(POST_LIKE).where(POST_LIKE.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(ACCOUNT_FOLLOW)
          .where(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(accountId)
              .or(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(accountId))).execute();
      var ownedSessions = DSL.select(LUNCH_SESSION_PARTICIPANT.LUNCH_SESSION_ID)
          .from(LUNCH_SESSION_PARTICIPANT)
          .where(LUNCH_SESSION_PARTICIPANT.ACCOUNT_ID.eq(accountId));
      transaction.deleteFrom(LUNCH_SESSION).where(LUNCH_SESSION.CREATED_BY_ACCOUNT_ID.eq(accountId)
          .or(LUNCH_SESSION.LUNCH_SESSION_ID.in(ownedSessions))).execute();
      transaction.deleteFrom(LUNCH_PREFERENCE).where(LUNCH_PREFERENCE.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(RESTAURANT_FAVORITE).where(RESTAURANT_FAVORITE.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(RESTAURANT_VOTE).where(RESTAURANT_VOTE.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(RESTAURANT_IMPORT_PREVIEW)
          .where(RESTAURANT_IMPORT_PREVIEW.ACTOR_ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(PLAYLIST).set(PLAYLIST.UPDATED_BY_ACCOUNT_ID, TOMBSTONE)
          .where(PLAYLIST.UPDATED_BY_ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(METADATA_EDIT).set(METADATA_EDIT.EDITED_BY_ACCOUNT_ID, TOMBSTONE)
          .where(METADATA_EDIT.EDITED_BY_ACCOUNT_ID.eq(accountId)).execute();
    });
  }

  @Override
  public void cleanSharedFolderState(String accountId) {
    resources.deleteOwnedResources(accountId);
  }

  @Override
  public void pseudonymizeRetainedRecords(String accountId, String pseudonym) {
    database.transaction(configuration -> {
      var transaction = DSL.using(configuration);
      registerPseudonym(transaction, pseudonym);
      transaction.update(POST_REPORT)
          .set(POST_REPORT.REPORTER_ACCOUNT_ID, pseudonym)
          .set(POST_REPORT.REPORTER_USERNAME, TOMBSTONE)
          .where(POST_REPORT.REPORTER_ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(POST_REPORT)
          .set(POST_REPORT.REPORTED_ACCOUNT_ID, pseudonym)
          .set(POST_REPORT.REPORTED_USERNAME, TOMBSTONE)
          .where(POST_REPORT.REPORTED_ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(POST_REPORT).set(POST_REPORT.RESOLVED_BY, pseudonym)
          .where(POST_REPORT.RESOLVED_BY.eq(accountId)).execute();
      transaction.update(POST_REPORT_MODERATION_AUDIT)
          .set(POST_REPORT_MODERATION_AUDIT.ACTOR_ACCOUNT_ID, pseudonym)
          .set(POST_REPORT_MODERATION_AUDIT.ACTOR_USERNAME, TOMBSTONE)
          .where(POST_REPORT_MODERATION_AUDIT.ACTOR_ACCOUNT_ID.eq(accountId)).execute();

      var affectedActivities = DSL.select(ADMIN_ACTIVITY.ADMIN_ACTIVITY_ID)
          .from(ADMIN_ACTIVITY)
          .where(ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID.eq(accountId)
              .or(ADMIN_ACTIVITY.TARGET_ID.eq(accountId)));
      transaction.deleteFrom(ADMIN_ACTIVITY_VALUE)
          .where(ADMIN_ACTIVITY_VALUE.ADMIN_ACTIVITY_ID.in(affectedActivities)
              .and(ADMIN_ACTIVITY_VALUE.PARTITION_NAME.eq("metadata"))).execute();
      transaction.update(ADMIN_ACTIVITY)
          .set(ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID, pseudonym)
          .set(ADMIN_ACTIVITY.ACTOR_USERNAME, TOMBSTONE)
          .set(ADMIN_ACTIVITY.MESSAGE, RETAINED_MESSAGE)
          .where(ADMIN_ACTIVITY.ACTOR_ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(ADMIN_ACTIVITY)
          .set(ADMIN_ACTIVITY.TARGET_ID, pseudonym)
          .set(ADMIN_ACTIVITY.TARGET_LABEL, TOMBSTONE)
          .set(ADMIN_ACTIVITY.MESSAGE, RETAINED_MESSAGE)
          .where(ADMIN_ACTIVITY.TARGET_ID.eq(accountId)).execute();
      transaction.update(AUDIT_EVENT)
          .set(AUDIT_EVENT.ACCOUNT_ID, pseudonym)
          .set(AUDIT_EVENT.RELATIVE_PATH, TOMBSTONE)
          .setNull(AUDIT_EVENT.CLIENT_IP)
          .where(AUDIT_EVENT.ACCOUNT_ID.eq(accountId)).execute();
      transaction.update(RECYCLE_ITEM).set(RECYCLE_ITEM.DELETED_BY_ACCOUNT_ID, pseudonym)
          .where(RECYCLE_ITEM.DELETED_BY_ACCOUNT_ID.eq(accountId)).execute();
    });
  }

  @Override
  public void removeReferencesAndAccount(String accountId) {
    database.transaction(configuration -> {
      var transaction = DSL.using(configuration);
      transaction.update(FEDERATION_DELIVERY_JOB).set(FEDERATION_DELIVERY_JOB.ACCOUNT_ID, TOMBSTONE)
          .set(FEDERATION_DELIVERY_JOB.VERSION, FEDERATION_DELIVERY_JOB.VERSION.plus(1L))
          .where(FEDERATION_DELIVERY_JOB.ACCOUNT_ID.eq(accountId)).execute();
      transaction.deleteFrom(ACCOUNT).where(ACCOUNT.ACCOUNT_ID.eq(accountId)).execute();
    });
  }

  private static void registerPseudonym(DSLContext transaction, String pseudonym) {
    transaction.insertInto(DELETED_ACCOUNT_PSEUDONYM)
        .set(DELETED_ACCOUNT_PSEUDONYM.PSEUDONYM_ID, pseudonym)
        .onConflict(DELETED_ACCOUNT_PSEUDONYM.PSEUDONYM_ID)
        .doNothing()
        .execute();
  }
}
