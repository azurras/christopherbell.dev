package dev.christopherbell.account.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import java.util.List;
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
    operations = new MongoAccountDeletionOperations(mongo, resources);
  }

  @Test
  @DisplayName("Private-data cleanup removes every owner or participant collection")
  void removePrivateData_coversCompleteCollectionInventory() {
    operations.removePrivateData("account-1");

    for (var collection : List.of(
        "messages",
        "notifications",
        "notification_preferences",
        "notification_delivery_guards",
        "notification_rate_limits",
        "account_trust_relationships",
        "hidden_post_threads",
        "post_likes",
        "account_follows",
        "whatsforlunch_preferences",
        "whatsforlunch_favorites",
        "whatsforlunch_ratings",
        "whatsforlunch_sessions",
        "conversation_archive_states")) {
      verify(mongo).remove(any(Query.class), eq(collection));
    }
  }

  @Test
  @DisplayName("Public and retained records replace identifiers with stable deletion values")
  void anonymization_updatesPostsReportsAndAuditCollections() {
    operations.anonymizePublicPosts("account-1", "deleted:abcdef012345");
    operations.pseudonymizeRetainedRecords("account-1", "deleted:abcdef012345");

    var postUpdates = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo, org.mockito.Mockito.times(2)).updateMulti(
        any(Query.class), postUpdates.capture(), eq("posts"));
    assertThat(postUpdates.getAllValues().toString())
        .contains("accountId", "deleted-user")
        .doesNotContain("likedBy", "likesCount")
        .contains("editAudit", "editorAccountId", "deleted:abcdef012345");
    verify(mongo, org.mockito.Mockito.times(3)).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq("post_reports"));
    verify(mongo, org.mockito.Mockito.times(2)).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq("admin_activity"));
    verify(mongo).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq("shared_folder_audit"));
    verify(mongo).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq("shared_folder_recycle_items"));
  }

  @Test
  @DisplayName("Account removal deletes the credential after relationship cleanup")
  void removeReferencesAndAccount_deletesTarget() {
    operations.removeReferencesAndAccount("account-1");

    verify(mongo).remove(any(Query.class), eq("accounts"));
  }

  @Test
  @DisplayName("Tombstone is credential-free and shared storage uses its owning cleanup boundary")
  void tombstoneAndSharedStorage_useSafeBoundaries() {
    operations.ensureTombstone();
    operations.cleanSharedFolderState("account-1");

    var update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).upsert(any(Query.class), update.capture(), eq(Account.class));
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("deleted-user", "INACTIVE")
        .doesNotContain("passwordHash", "passwordSalt", "loginToken", "passwordResetToken");
    verify(resources).deleteOwnedResources("account-1");
  }

  @Test
  @DisplayName("Account existence uses only the exact account id")
  void accountExists_usesExactId() {
    when(mongo.exists(any(Query.class), eq(Account.class))).thenReturn(true);

    assertThat(operations.accountExists("account-1")).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).exists(query.capture(), eq(Account.class));
    assertThat(query.getValue().getQueryObject().toString()).contains("_id=account-1");
  }
}
