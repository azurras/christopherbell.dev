package dev.christopherbell.account.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class MongoAccountDeletionOperationsTest {
  @Mock private MongoTemplate mongo;
  @Mock private AccountDeletionResourceCleaner resources;
  private MongoAccountDeletionOperations operations;

  @BeforeEach
  void setUp() {
    operations = new MongoAccountDeletionOperations(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo),
        resources);
  }

  @Test
  @DisplayName("Private-data cleanup deletes owned WFL sessions but preserves shared sessions")
  void removePrivateData_coversCompleteCollectionInventory() {
    operations.removePrivateData("account-1");

    var queries = ArgumentCaptor.forClass(Query.class);
    var collections = ArgumentCaptor.forClass(String.class);
    verify(mongo, times(15)).remove(
        queries.capture(), eq(Document.class), collections.capture());
    assertThat(collections.getAllValues())
        .containsOnly("accounts", "sessions", "communications", "content", "whatsforlunch");
    assertThat(queries.getAllValues().stream()
        .map(query -> query.getQueryObject().toString()).toList().toString())
        .contains("_kind=browser_session", "_kind=message", "_kind=notification",
            "_kind=notification_preference", "_kind=notification_delivery_guard",
            "_kind=notification_rate_limit", "_kind=account_trust_relationship",
            "_kind=hidden_post_thread", "_kind=post_like", "_kind=account_follow",
            "_kind=preference", "_kind=favorite", "_kind=vote", "_kind=session",
            "_kind=conversation_archive_state")
        .contains("payload.createdByAccountId=account-1");
    var ownedLunchSession = queries.getAllValues().stream()
        .map(query -> query.getQueryObject().toString())
        .filter(value -> value.contains("_kind=session")
            && value.contains("payload.createdByAccountId=account-1"))
        .findFirst().orElseThrow();
    assertThat(ownedLunchSession).doesNotContain("participantAccountIds");

    var sharedSessionUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateMulti(
        any(Query.class), sharedSessionUpdate.capture(), eq(Document.class), eq("whatsforlunch"));
    assertThat(sharedSessionUpdate.getValue().getUpdateObject().toString())
        .contains("payload.participantAccountIds", "payload.participantUsernamesByAccountId.account-1",
            "payload.votesByAccountId.account-1", "payload.revision");
  }

  @Test
  @DisplayName("Public and retained records replace identifiers with stable deletion values")
  void anonymization_updatesPostsReportsAndAuditCollections() {
    operations.anonymizePublicPosts("account-1", "deleted:abcdef012345");
    operations.pseudonymizeRetainedRecords("account-1", "deleted:abcdef012345");

    var contentUpdates = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo, times(5)).updateMulti(
        any(Query.class), contentUpdates.capture(), eq(Document.class), eq("content"));
    assertThat(contentUpdates.getAllValues().toString())
        .contains("accountId", "deleted-user")
        .doesNotContain("likedBy", "likesCount")
        .contains("editAudit", "editorAccountId", "deleted:abcdef012345");
    verify(mongo, times(2)).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq(Document.class), eq("admin_activity"));
    verify(mongo, times(2)).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq(Document.class), eq("shared_folder"));
  }

  @Test
  @DisplayName("Account removal deletes the credential after relationship cleanup")
  void removeReferencesAndAccount_deletesTarget() {
    operations.removeReferencesAndAccount("account-1");

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).remove(query.capture(), eq(Document.class), eq("accounts"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=account", "_id.legacyId=account-1");
  }

  @Test
  @DisplayName("Tombstone is credential-free and shared storage uses its owning cleanup boundary")
  void tombstoneAndSharedStorage_useSafeBoundaries() {
    when(mongo.insert(any(Document.class), eq("accounts")))
        .thenAnswer(invocation -> invocation.getArgument(0));
    operations.ensureTombstone();
    operations.cleanSharedFolderState("account-1");

    var inserted = ArgumentCaptor.forClass(Document.class);
    verify(mongo).insert(inserted.capture(), eq("accounts"));
    assertThat(inserted.getValue().toString())
        .contains("deleted-user", "INACTIVE")
        .doesNotContain("passwordHash", "passwordSalt", "loginToken", "passwordResetToken");
    verify(resources).deleteOwnedResources("account-1");
  }

  @Test
  @DisplayName("Account existence uses only the exact account id")
  void accountExists_usesExactId() {
    when(mongo.exists(any(Query.class), eq(Document.class), eq("accounts"))).thenReturn(true);

    assertThat(operations.accountExists("account-1")).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).exists(query.capture(), eq(Document.class), eq("accounts"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_kind=account", "_id.legacyId=account-1");
  }
}
