package dev.christopherbell.account.deletion;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Concrete idempotent Mongo effects for comprehensive account deletion. */
@Component
@RequiredArgsConstructor
public class MongoAccountDeletionOperations implements AccountDeletionOperations {
  private static final String TOMBSTONE = AccountDeletionService.TOMBSTONE_ID;
  private static final Pattern SAFE_MAP_KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private final MongoTemplate mongo;
  private final AccountDeletionResourceCleaner resources;

  @Override
  public boolean accountExists(String accountId) {
    return mongo.exists(exact("_id", accountId), Account.class);
  }

  @Override
  public void ensureTombstone() {
    var tombstone = new Update()
        .setOnInsert("type", "account")
        .setOnInsert("username", TOMBSTONE)
        .setOnInsert("email", "deleted-user@invalid.local")
        .setOnInsert("firstName", "Deleted")
        .setOnInsert("lastName", "User")
        .setOnInsert("role", Role.USER)
        .setOnInsert("status", AccountStatus.INACTIVE)
        .setOnInsert("permissions", Set.of());
    mongo.upsert(exact("_id", TOMBSTONE), tombstone, Account.class);
  }

  @Override
  public void anonymizePublicPosts(String accountId, String pseudonym) {
    mongo.updateMulti(
        exact("accountId", accountId),
        new Update().set("accountId", TOMBSTONE),
        "posts");
    mongo.updateMulti(
        exact("editAudit.editorAccountId", accountId),
        new Update()
            .set("editAudit.$[entry].editorAccountId", pseudonym)
            .filterArray(Criteria.where("entry.editorAccountId").is(accountId)),
        "posts");
  }

  @Override
  public void removePrivateData(String accountId) {
    remove("browser_sessions", accountId, "accountId");
    remove("messages", accountId,
        "participantIds", "senderAccountId", "recipientAccountId");
    remove("notifications", accountId, "accountId", "actorAccountId");
    remove("notification_preferences", accountId, "accountId");
    remove("notification_delivery_guards", accountId,
        "accountId", "actorAccountId", "recipientAccountId");
    remove("notification_rate_limits", accountId, "accountId", "actorAccountId");
    remove("account_trust_relationships", accountId,
        "ownerAccountId", "targetAccountId");
    remove("hidden_post_threads", accountId, "accountId");
    remove("post_likes", accountId, "accountId");
    remove("account_follows", accountId, "followerAccountId", "followedAccountId");
    remove("whatsforlunch_preferences", accountId, "accountId");
    remove("whatsforlunch_favorites", accountId, "accountId");
    remove("whatsforlunch_ratings", accountId, "accountId");
    removeAccountFromWhatsForLunchSessions(accountId);
    remove("conversation_archive_states", accountId,
        "ownerAccountId", "participantIds");
  }

  @Override
  public void cleanSharedFolderState(String accountId) {
    resources.deleteOwnedResources(accountId);
  }

  @Override
  public void pseudonymizeRetainedRecords(String accountId, String pseudonym) {
    mongo.updateMulti(
        exact("reporterAccountId", accountId),
        new Update()
            .set("reporterAccountId", pseudonym)
            .set("reporterUsername", TOMBSTONE),
        "post_reports");
    mongo.updateMulti(
        exact("reportedAccountId", accountId),
        new Update()
            .set("reportedAccountId", pseudonym)
            .set("reportedUsername", TOMBSTONE),
        "post_reports");
    mongo.updateMulti(
        exact("resolvedBy", accountId),
        new Update().set("resolvedBy", pseudonym),
        "post_reports");

    var safeActivityMessage = "Account-related activity retained after account deletion.";
    mongo.updateMulti(
        exact("actorAccountId", accountId),
        new Update()
            .set("actorAccountId", pseudonym)
            .set("actorUsername", TOMBSTONE)
            .set("message", safeActivityMessage)
            .unset("metadata"),
        "admin_activity");
    mongo.updateMulti(
        exact("targetId", accountId),
        new Update()
            .set("targetId", pseudonym)
            .set("targetLabel", TOMBSTONE)
            .set("message", safeActivityMessage)
            .unset("metadata"),
        "admin_activity");
    mongo.updateMulti(
        exact("accountId", accountId),
        new Update()
            .set("accountId", pseudonym)
            .set("relativePath", TOMBSTONE)
            .unset("clientIp"),
        "shared_folder_audit");
    mongo.updateMulti(
        exact("deletedByAccountId", accountId),
        new Update().set("deletedByAccountId", pseudonym),
        "shared_folder_recycle_items");
  }

  @Override
  public void removeReferencesAndAccount(String accountId) {
    mongo.remove(exact("_id", accountId), "accounts");
  }

  private void remove(String collection, String accountId, String... fields) {
    Criteria[] matches = java.util.Arrays.stream(fields)
        .map(field -> Criteria.where(field).is(accountId))
        .toArray(Criteria[]::new);
    mongo.remove(new Query(new Criteria().orOperator(matches)), collection);
  }

  private void removeAccountFromWhatsForLunchSessions(String accountId) {
    var safeAccountId = safeMapKey(accountId);
    mongo.remove(exact("createdByAccountId", safeAccountId), "whatsforlunch_sessions");
    mongo.updateMulti(
        exact("participantAccountIds", safeAccountId),
        new Update()
            .pull("participantAccountIds", safeAccountId)
            .unset("participantUsernamesByAccountId." + safeAccountId)
            .unset("votesByAccountId." + safeAccountId)
            .inc("revision", 1)
            .currentDate("lastUpdatedOn"),
        "whatsforlunch_sessions");
  }

  private String safeMapKey(String accountId) {
    if (accountId == null || !SAFE_MAP_KEY.matcher(accountId).matches()) {
      throw new IllegalArgumentException("Account id cannot be used as a Mongo map key.");
    }
    return accountId;
  }

  private Query exact(String field, String value) {
    return new Query(Criteria.where(field).is(value));
  }
}
