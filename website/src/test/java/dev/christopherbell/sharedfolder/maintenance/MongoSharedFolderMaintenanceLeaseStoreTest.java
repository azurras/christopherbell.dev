package dev.christopherbell.sharedfolder.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MongoSharedFolderMaintenanceLeaseStoreTest {

  @Test
  void repositoryCanBeProxiedUsingTheApplicationClassProxyMode() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(MongoTemplate.class, () -> mock(MongoTemplate.class));
      context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
        var postProcessor = new PersistenceExceptionTranslationPostProcessor();
        postProcessor.setProxyTargetClass(true);
        return postProcessor;
      });
      context.register(MongoSharedFolderMaintenanceLeaseStore.class);

      context.refresh();

      assertThat(context.getBean(SharedFolderMaintenanceLeaseStore.class)).isNotNull();
    }
  }

  @Test
  void acquisitionUsesOneFixedKeyAndMapsAtomicUpsertContentionToFalse() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    MongoSharedFolderMaintenanceLeaseStore store =
        new MongoSharedFolderMaintenanceLeaseStore(mongo);
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    Instant expiresAt = now.plusSeconds(1800);
    SharedFolderMaintenanceLeaseDocument acquired = new SharedFolderMaintenanceLeaseDocument();
    acquired.setId(SharedFolderMaintenanceLeaseDocument.ID);
    acquired.setOwnerToken("owner-a");
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(SharedFolderMaintenanceLeaseDocument.class))).thenReturn(acquired);

    assertThat(store.tryAcquire("owner-a", now, expiresAt)).isTrue();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class),
        eq(SharedFolderMaintenanceLeaseDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("shared-folder-maintenance", "ownerToken", "expiresAt", "$lte");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("owner-a", "acquiredAt", "expiresAt");

    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(SharedFolderMaintenanceLeaseDocument.class)))
        .thenThrow(new DuplicateKeyException("fixed lease is held"));
    assertThat(store.tryAcquire("owner-b", now, expiresAt)).isFalse();
  }

  @Test
  void renewAndReleaseAreBothConditionedOnTheExactOwner() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    MongoSharedFolderMaintenanceLeaseStore store =
        new MongoSharedFolderMaintenanceLeaseStore(mongo);
    when(mongo.updateFirst(any(Query.class), any(Update.class),
        eq(SharedFolderMaintenanceLeaseDocument.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    Instant now = Instant.parse("2026-07-22T12:00:00Z");

    assertThat(store.renew("owner-a", now, now.plusSeconds(1800))).isTrue();
    assertThat(store.release("owner-a")).isTrue();

    ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(2)).updateFirst(
        queries.capture(), any(Update.class), eq(SharedFolderMaintenanceLeaseDocument.class));
    assertThat(queries.getAllValues()).allSatisfy(query ->
        assertThat(query.getQueryObject().toString())
            .contains("shared-folder-maintenance", "ownerToken", "owner-a"));
    assertThat(queries.getAllValues().get(0).getQueryObject().toString()).contains("$gt");
  }
}
