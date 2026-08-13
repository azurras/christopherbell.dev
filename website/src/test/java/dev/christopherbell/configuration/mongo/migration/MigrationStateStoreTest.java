package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@ExtendWith(MockitoExtension.class)
class MigrationStateStoreTest {
  private static final Instant NOW = Instant.parse("2026-07-25T22:30:00Z");

  @Mock private KindScopedMongoOperations<MigrationRecord> mongo;
  private MigrationStateStore store;

  @Test
  void springSelectsTheProductionFactoryConstructor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          DomainMongoOperationsFactory.class,
          () -> org.mockito.Mockito.mock(DomainMongoOperationsFactory.class));
      context.register(MigrationStateStore.class);

      context.refresh();

      assertThat(context.getBean(MigrationStateStore.class)).isNotNull();
    }
  }

  @BeforeEach
  void setUp() {
    store = new MigrationStateStore(mongo);
  }

  @Test
  void completionRequiresRunningRecordOwnedByCaller() {
    when(mongo.updateFirst(any(Query.class), any(Update.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    store.complete("001", "owner-1", NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).updateFirst(query.capture(), any(Update.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("001", "owner-1", "RUNNING");
  }

  @Test
  void unmatchedTransitionFailsStartup() {
    when(mongo.updateFirst(any(Query.class), any(Update.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThatThrownBy(() -> store.fail("001", "owner-1", NOW, "MIGRATION_FAILED"))
        .hasMessageContaining("ownership was lost", "001");
  }
}
