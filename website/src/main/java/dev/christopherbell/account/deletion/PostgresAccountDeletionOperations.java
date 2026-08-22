package dev.christopherbell.account.deletion;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Idempotent PostgreSQL effects for durable account deletion steps. */
@PostgresPersistence
public class PostgresAccountDeletionOperations implements AccountDeletionOperations {
  private static final String TOMBSTONE = AccountDeletionService.TOMBSTONE_ID;
  private static final String RETAINED_MESSAGE =
      "Account-related activity retained after account deletion.";

  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final AccountDeletionResourceCleaner resources;
  private final Tables tables;

  public PostgresAccountDeletionOperations(
      JdbcClient database,
      PostgresqlSchemaNames schemas,
      TransactionOperations transactions,
      AccountDeletionResourceCleaner resources) {
    this.database = database;
    this.transactions = transactions;
    this.resources = resources;
    tables = new Tables(schemas);
  }

  @Override
  public boolean accountExists(String accountId) {
    return database.sql("select exists(select 1 from %s where account_id = :id)"
            .formatted(tables.account))
        .param("id", accountId).query(Boolean.class).single();
  }

  @Override
  public void ensureTombstone() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    database.sql("""
            insert into %s (
              account_id, email, normalized_email, first_name, last_name,
              role, status, username, created_on, last_updated_on)
            values (
              :id, 'deleted-user@invalid.local', 'deleted-user@invalid.local',
              'Deleted', 'User', 'USER', 'INACTIVE', :id, :now, :now)
            on conflict (account_id) do nothing
            """.formatted(tables.account))
        .param("id", TOMBSTONE).param("now", now).update();
  }

  @Override
  public void anonymizePublicPosts(String accountId, String pseudonym) {
    transactions.executeWithoutResult(ignored -> {
      registerPseudonym(pseudonym);
      execute("update %s set account_id = :tombstone, version = version + 1 where account_id = :id"
          .formatted(tables.post), accountId, pseudonym);
      database.sql("update %s set editor_account_id = :pseudonym where editor_account_id = :id"
              .formatted(tables.postEditAudit))
          .param("pseudonym", pseudonym).param("id", accountId).update();
    });
  }

  @Override
  public void removePrivateData(String accountId) {
    transactions.executeWithoutResult(ignored -> {
      database.sql("""
              delete from %s where archive_state_id in (
                select state.archive_state_id from %s state
                left join %s participant
                  on participant.archive_state_id = state.archive_state_id
                where state.owner_account_id = :id or participant.account_id = :id)
              """.formatted(tables.conversationArchiveState, tables.conversationArchiveState,
              tables.conversationArchiveParticipant)).param("id", accountId).update();
      deleteWhere(tables.message, "sender_account_id = :id or recipient_account_id = :id", accountId);
      deleteWhere(tables.notification, "account_id = :id or actor_account_id = :id", accountId);
      deleteWhere(tables.notificationPreference, "account_id = :id", accountId);
      deleteWhere(tables.notificationDeliveryGuard,
          "account_id = :id or actor_account_id = :id", accountId);
      deleteWhere(tables.notificationRateLimit,
          "account_id = :id or actor_account_id = :id", accountId);
      deleteWhere(tables.browserSession, "account_id = :id", accountId);
      deleteWhere(tables.accountTrust,
          "owner_account_id = :id or target_account_id = :id", accountId);
      deleteWhere(tables.hiddenPostThread, "account_id = :id", accountId);
      deleteWhere(tables.postLike, "account_id = :id", accountId);
      deleteWhere(tables.accountFollow,
          "follower_account_id = :id or followed_account_id = :id", accountId);
      database.sql("""
              delete from %s where created_by_account_id = :id
                or lunch_session_id in (
                  select lunch_session_id from %s where account_id = :id)
              """.formatted(tables.lunchSession, tables.lunchSessionParticipant))
          .param("id", accountId).update();
      deleteWhere(tables.lunchPreference, "account_id = :id", accountId);
      deleteWhere(tables.restaurantFavorite, "account_id = :id", accountId);
      deleteWhere(tables.restaurantVote, "account_id = :id", accountId);
      deleteWhere(tables.restaurantImportPreview, "actor_account_id = :id", accountId);
      updateOwner(tables.playlist, "updated_by_account_id", accountId, TOMBSTONE);
      updateOwner(tables.metadataEdit, "edited_by_account_id", accountId, TOMBSTONE);
    });
  }

  @Override
  public void cleanSharedFolderState(String accountId) {
    resources.deleteOwnedResources(accountId);
  }

  @Override
  public void pseudonymizeRetainedRecords(String accountId, String pseudonym) {
    transactions.executeWithoutResult(ignored -> {
      registerPseudonym(pseudonym);
      database.sql("""
              update %s set reporter_account_id = :pseudonym, reporter_username = :tombstone
              where reporter_account_id = :id
              """.formatted(tables.postReport)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("id", accountId).update();
      database.sql("""
              update %s set reported_account_id = :pseudonym, reported_username = :tombstone
              where reported_account_id = :id
              """.formatted(tables.postReport)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("id", accountId).update();
      updateOwner(tables.postReport, "resolved_by", accountId, pseudonym);
      database.sql("""
              update %s set actor_account_id = :pseudonym, actor_username = :tombstone
              where actor_account_id = :id
              """.formatted(tables.postReportAudit)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("id", accountId).update();
      database.sql("""
              delete from %s where partition_name = 'metadata' and admin_activity_id in (
                select admin_activity_id from %s
                where actor_account_id = :id or target_id = :id)
              """.formatted(tables.adminActivityValue, tables.adminActivity))
          .param("id", accountId).update();
      database.sql("""
              update %s set actor_account_id = :pseudonym, actor_username = :tombstone,
                message = :message where actor_account_id = :id
              """.formatted(tables.adminActivity)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("message", RETAINED_MESSAGE)
          .param("id", accountId).update();
      database.sql("""
              update %s set target_id = :pseudonym, target_label = :tombstone,
                message = :message where target_id = :id
              """.formatted(tables.adminActivity)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("message", RETAINED_MESSAGE)
          .param("id", accountId).update();
      database.sql("""
              update %s set account_id = :pseudonym, relative_path = :tombstone,
                client_ip = null where account_id = :id
              """.formatted(tables.auditEvent)).param("pseudonym", pseudonym)
          .param("tombstone", TOMBSTONE).param("id", accountId).update();
      updateOwner(tables.recycleItem, "deleted_by_account_id", accountId, pseudonym);
    });
  }

  @Override
  public void removeReferencesAndAccount(String accountId) {
    transactions.executeWithoutResult(ignored -> {
      database.sql("""
              update %s set account_id = :tombstone, version = version + 1
              where account_id = :id
              """.formatted(tables.federationDeliveryJob)).param("tombstone", TOMBSTONE)
          .param("id", accountId).update();
      deleteWhere(tables.account, "account_id = :id", accountId);
    });
  }

  private void execute(String sql, String accountId, String pseudonym) {
    database.sql(sql).param("tombstone", TOMBSTONE).param("id", accountId)
        .param("pseudonym", pseudonym).update();
  }

  private void deleteWhere(String table, String predicate, String accountId) {
    database.sql("delete from %s where %s".formatted(table, predicate))
        .param("id", accountId).update();
  }

  private void updateOwner(String table, String column, String accountId, String replacement) {
    database.sql("update %s set %s = :replacement where %s = :id"
            .formatted(table, column, column))
        .param("replacement", replacement).param("id", accountId).update();
  }

  private void registerPseudonym(String pseudonym) {
    database.sql("""
            insert into %s (pseudonym_id) values (:id) on conflict (pseudonym_id) do nothing
            """.formatted(tables.deletedPseudonym)).param("id", pseudonym).update();
  }

  private static final class Tables {
    private final String account;
    private final String accountFollow;
    private final String accountTrust;
    private final String adminActivity;
    private final String adminActivityValue;
    private final String auditEvent;
    private final String browserSession;
    private final String conversationArchiveParticipant;
    private final String conversationArchiveState;
    private final String deletedPseudonym;
    private final String federationDeliveryJob;
    private final String hiddenPostThread;
    private final String lunchPreference;
    private final String lunchSession;
    private final String lunchSessionParticipant;
    private final String message;
    private final String metadataEdit;
    private final String notification;
    private final String notificationDeliveryGuard;
    private final String notificationPreference;
    private final String notificationRateLimit;
    private final String playlist;
    private final String post;
    private final String postEditAudit;
    private final String postLike;
    private final String postReport;
    private final String postReportAudit;
    private final String recycleItem;
    private final String restaurantFavorite;
    private final String restaurantImportPreview;
    private final String restaurantVote;

    private Tables(PostgresqlSchemaNames schemas) {
      account = schemas.qualifiedTable("identity", "account");
      accountFollow = schemas.qualifiedTable("identity", "account_follow");
      accountTrust = schemas.qualifiedTable("identity", "account_trust_relationship");
      adminActivity = schemas.qualifiedTable("platform", "admin_activity");
      adminActivityValue = schemas.qualifiedTable("platform", "admin_activity_value");
      auditEvent = schemas.qualifiedTable("shared_folder", "audit_event");
      browserSession = schemas.qualifiedTable("identity", "browser_session");
      conversationArchiveParticipant = schemas.qualifiedTable(
          "communication", "conversation_archive_participant");
      conversationArchiveState = schemas.qualifiedTable(
          "communication", "conversation_archive_state");
      deletedPseudonym = schemas.qualifiedTable("identity", "deleted_account_pseudonym");
      federationDeliveryJob = schemas.qualifiedTable("federation", "federation_delivery_job");
      hiddenPostThread = schemas.qualifiedTable("social", "hidden_post_thread");
      lunchPreference = schemas.qualifiedTable("lunch", "lunch_preference");
      lunchSession = schemas.qualifiedTable("lunch", "lunch_session");
      lunchSessionParticipant = schemas.qualifiedTable("lunch", "lunch_session_participant");
      message = schemas.qualifiedTable("communication", "message");
      metadataEdit = schemas.qualifiedTable("music", "metadata_edit");
      notification = schemas.qualifiedTable("communication", "notification");
      notificationDeliveryGuard = schemas.qualifiedTable(
          "communication", "notification_delivery_guard");
      notificationPreference = schemas.qualifiedTable("communication", "notification_preference");
      notificationRateLimit = schemas.qualifiedTable("communication", "notification_rate_limit");
      playlist = schemas.qualifiedTable("music", "playlist");
      post = schemas.qualifiedTable("social", "post");
      postEditAudit = schemas.qualifiedTable("social", "post_edit_audit");
      postLike = schemas.qualifiedTable("social", "post_like");
      postReport = schemas.qualifiedTable("social", "post_report");
      postReportAudit = schemas.qualifiedTable("social", "post_report_moderation_audit");
      recycleItem = schemas.qualifiedTable("shared_folder", "recycle_item");
      restaurantFavorite = schemas.qualifiedTable("lunch", "restaurant_favorite");
      restaurantImportPreview = schemas.qualifiedTable("lunch", "restaurant_import_preview");
      restaurantVote = schemas.qualifiedTable("lunch", "restaurant_vote");
    }
  }
}
