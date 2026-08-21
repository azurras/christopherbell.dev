package dev.christopherbell.sharedfolder.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import dev.christopherbell.libs.lease.LeaseGrant;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MongoSharedFolderMaintenanceLeaseStoreTest {

  @Test
  void repositoryCanBeProxiedUsingTheApplicationClassProxyMode() {
    try (var context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("app.persistence.backend=mongodb").applyTo(context);
      var factory = mock(DomainMongoOperationsFactory.class);
      context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
        var postProcessor = new PersistenceExceptionTranslationPostProcessor();
        postProcessor.setProxyTargetClass(true);
        return postProcessor;
      });
      context.registerBean(
          MongoSharedFolderMaintenanceLeaseStore.class,
          () -> new MongoSharedFolderMaintenanceLeaseStore(factory));

      context.refresh();

      assertThat(context.getBean(SharedFolderMaintenanceLeaseStore.class)).isNotNull();
    }
  }

  @Test
  void acquisitionUsesOneFixedKeyAndMapsAtomicDatabaseTimeContentionToEmpty() {
    @SuppressWarnings("unchecked")
    KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo =
        mock(KindScopedMongoOperations.class);
    MongoSharedFolderMaintenanceLeaseStore store =
        new MongoSharedFolderMaintenanceLeaseStore(mongo);
    SharedFolderMaintenanceLeaseDocument acquired = new SharedFolderMaintenanceLeaseDocument();
    acquired.setId(SharedFolderMaintenanceLeaseDocument.ID);
    acquired.setOwnerToken("owner-a");
    acquired.setFenceToken(1L);
    acquired.setExpiresAt(Instant.parse("2026-07-22T12:30:00Z"));
    when(mongo.acquireDatabaseLease(
        any(Query.class), any(MongoDatabaseLeaseMutation.class),
        any(SharedFolderMaintenanceLeaseDocument.class)))
        .thenReturn(Optional.of(acquired))
        .thenReturn(Optional.empty());

    assertThat(store.tryAcquire("owner-a", Duration.ofMinutes(30))).contains(
        new LeaseGrant(SharedFolderMaintenanceLeaseDocument.ID, "owner-a", 1,
            acquired.getExpiresAt()));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).acquireDatabaseLease(
        query.capture(), any(MongoDatabaseLeaseMutation.class),
        any(SharedFolderMaintenanceLeaseDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("shared-folder-maintenance");

    assertThat(store.tryAcquire("owner-b", Duration.ofMinutes(30))).isEmpty();
  }

  @Test
  void renewAndReleaseAreBothConditionedOnTheExactGrant() {
    @SuppressWarnings("unchecked")
    KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo =
        mock(KindScopedMongoOperations.class);
    MongoSharedFolderMaintenanceLeaseStore store =
        new MongoSharedFolderMaintenanceLeaseStore(mongo);
    Instant expiresAt = Instant.parse("2026-07-22T12:30:00Z");
    var document = new SharedFolderMaintenanceLeaseDocument();
    document.setId(SharedFolderMaintenanceLeaseDocument.ID);
    document.setOwnerToken("owner-a");
    document.setFenceToken(7L);
    document.setExpiresAt(expiresAt);
    when(mongo.findAndUpdateDatabaseLease(
        any(Query.class), any(MongoDatabaseLeaseMutation.class)))
        .thenReturn(Optional.of(document))
        .thenReturn(Optional.of(document));
    var grant = new LeaseGrant(SharedFolderMaintenanceLeaseDocument.ID,
        "owner-a", 7, expiresAt);

    assertThat(store.renew(grant, Duration.ofMinutes(30))).contains(grant);
    assertThat(store.release(grant)).isTrue();

    ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(2)).findAndUpdateDatabaseLease(
        queries.capture(), any(MongoDatabaseLeaseMutation.class));
    assertThat(queries.getAllValues()).allSatisfy(query ->
        assertThat(query.getQueryObject().toString())
            .contains("shared-folder-maintenance", "ownerToken", "owner-a", "fenceToken", "7"));
  }
}
