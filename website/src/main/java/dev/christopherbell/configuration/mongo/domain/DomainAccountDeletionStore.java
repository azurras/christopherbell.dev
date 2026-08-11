package dev.christopherbell.configuration.mongo.domain;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Configuration-owned cross-domain persistence effects used only by account deletion. */
public final class DomainAccountDeletionStore {
  private static final Pattern SAFE_MAP_KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");

  private final KindScopedMongoOperations<?> posts;
  private final KindScopedMongoOperations<?> sessions;
  private final KindScopedMongoOperations<?> messages;
  private final KindScopedMongoOperations<?> notifications;
  private final KindScopedMongoOperations<?> notificationPreferences;
  private final KindScopedMongoOperations<?> deliveryGuards;
  private final KindScopedMongoOperations<?> rateLimits;
  private final KindScopedMongoOperations<?> trustRelationships;
  private final KindScopedMongoOperations<?> hiddenThreads;
  private final KindScopedMongoOperations<?> likes;
  private final KindScopedMongoOperations<?> follows;
  private final KindScopedMongoOperations<?> lunchPreferences;
  private final KindScopedMongoOperations<?> lunchFavorites;
  private final KindScopedMongoOperations<?> lunchVotes;
  private final KindScopedMongoOperations<?> lunchSessions;
  private final KindScopedMongoOperations<?> archives;
  private final KindScopedMongoOperations<?> reports;
  private final KindScopedMongoOperations<?> adminActivities;
  private final KindScopedMongoOperations<?> sharedAudit;
  private final KindScopedMongoOperations<?> recycleItems;

  public DomainAccountDeletionStore(DomainMongoOperationsFactory factory) {
    posts = factory.forExactKind("post");
    sessions = factory.forExactKind("browser_session");
    messages = factory.forExactKind("message");
    notifications = factory.forExactKind("notification");
    notificationPreferences = factory.forExactKind("notification_preference");
    deliveryGuards = factory.forExactKind("notification_delivery_guard");
    rateLimits = factory.forExactKind("notification_rate_limit");
    trustRelationships = factory.forExactKind("account_trust_relationship");
    hiddenThreads = factory.forExactKind("hidden_post_thread");
    likes = factory.forExactKind("post_like");
    follows = factory.forExactKind("account_follow");
    lunchPreferences = factory.forExactKind("preference");
    lunchFavorites = factory.forExactKind("favorite");
    lunchVotes = factory.forExactKind("vote");
    lunchSessions = factory.forExactKind("session");
    archives = factory.forExactKind("conversation_archive_state");
    reports = factory.forExactKind("post_report");
    adminActivities = factory.forExactKind("admin_activity");
    sharedAudit = factory.forExactKind("audit_event");
    recycleItems = factory.forExactKind("recycle_item");
  }

  public void anonymizePublicPosts(String accountId, String pseudonym, String tombstone) {
    posts.updateMulti(exact("accountId", accountId), new Update().set("accountId", tombstone));
    posts.updateMulti(exact("editAudit.editorAccountId", accountId), new Update()
        .set("editAudit.$[entry].editorAccountId", pseudonym)
        .filterArray(Criteria.where("entry.editorAccountId").is(accountId)));
  }

  public void removePrivateData(String accountId) {
    remove(sessions, accountId, "accountId");
    remove(messages, accountId, "participantIds", "senderAccountId", "recipientAccountId");
    remove(notifications, accountId, "accountId", "actorAccountId");
    remove(notificationPreferences, accountId, "accountId");
    remove(deliveryGuards, accountId, "accountId", "actorAccountId");
    remove(rateLimits, accountId, "accountId", "actorAccountId");
    remove(trustRelationships, accountId, "ownerAccountId", "targetAccountId");
    remove(hiddenThreads, accountId, "accountId");
    remove(likes, accountId, "accountId");
    remove(follows, accountId, "followerAccountId", "followedAccountId");
    remove(lunchPreferences, accountId, "accountId");
    remove(lunchFavorites, accountId, "accountId");
    remove(lunchVotes, accountId, "accountId");
    removeAccountFromLunchSessions(accountId);
    remove(archives, accountId, "ownerAccountId", "participantIds");
  }

  public void pseudonymizeRetainedRecords(
      String accountId, String pseudonym, String tombstone) {
    reports.updateMulti(exact("reporterAccountId", accountId), new Update()
        .set("reporterAccountId", pseudonym).set("reporterUsername", tombstone));
    reports.updateMulti(exact("reportedAccountId", accountId), new Update()
        .set("reportedAccountId", pseudonym).set("reportedUsername", tombstone));
    reports.updateMulti(exact("resolvedBy", accountId), new Update().set("resolvedBy", pseudonym));

    var safeMessage = "Account-related activity retained after account deletion.";
    adminActivities.updateMulti(exact("actorAccountId", accountId), new Update()
        .set("actorAccountId", pseudonym).set("actorUsername", tombstone)
        .set("message", safeMessage).unset("metadata"));
    adminActivities.updateMulti(exact("targetId", accountId), new Update()
        .set("targetId", pseudonym).set("targetLabel", tombstone)
        .set("message", safeMessage).unset("metadata"));
    sharedAudit.updateMulti(exact("accountId", accountId), new Update()
        .set("accountId", pseudonym).set("relativePath", tombstone).unset("clientIp"));
    recycleItems.updateMulti(exact("deletedByAccountId", accountId),
        new Update().set("deletedByAccountId", pseudonym));
  }

  private static void remove(
      KindScopedMongoOperations<?> mongo, String accountId, String... fields) {
    var matches = Arrays.stream(fields)
        .map(field -> Criteria.where(field).is(accountId))
        .toArray(Criteria[]::new);
    mongo.remove(new Query(new Criteria().orOperator(matches)));
  }

  private void removeAccountFromLunchSessions(String accountId) {
    var safeAccountId = safeMapKey(accountId);
    lunchSessions.remove(exact("createdByAccountId", safeAccountId));
    lunchSessions.updateMulti(exact("participantAccountIds", safeAccountId), new Update()
        .pull("participantAccountIds", safeAccountId)
        .unset("participantUsernamesByAccountId." + safeAccountId)
        .unset("votesByAccountId." + safeAccountId)
        .inc("revision", 1)
        .currentDate("lastUpdatedOn"));
  }

  private static String safeMapKey(String accountId) {
    if (accountId == null || !SAFE_MAP_KEY.matcher(accountId).matches()) {
      throw new IllegalArgumentException("Account id cannot be used as a Mongo map key.");
    }
    return accountId;
  }

  private static Query exact(String field, String value) {
    return Query.query(Criteria.where(field).is(value));
  }
}
