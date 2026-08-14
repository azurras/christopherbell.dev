package dev.christopherbell.sharedfolder.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;

class MongoSharedFolderAuditRetentionTest {
  @Test
  void refreshedExpiryIsRecheckedAfterBoundedSelection() {
    Instant cutoff = Instant.parse("2026-08-14T00:00:00Z");
    var factory = mock(DomainMongoOperationsFactory.class);
    @SuppressWarnings("unchecked")
    KindScopedMongoOperations<SharedFolderAuditEvent> operations =
        mock(KindScopedMongoOperations.class);
    when(factory.forType(SharedFolderAuditEvent.class)).thenReturn(operations);
    var storedExpiry = new AtomicReference<>(cutoff.minusSeconds(1));
    when(operations.find(any(Query.class), any(Pageable.class)))
        .thenAnswer(invocation -> {
          var selected = new SharedFolderAuditEvent(
            "race", "owner", "READ", null, null, "SUCCESS", null, "127.0.0.1",
            cutoff.minusSeconds(2), storedExpiry.get());
          storedExpiry.set(cutoff.plusSeconds(1));
          return List.of(selected);
        });
    when(operations.remove(any(Query.class))).thenAnswer(invocation -> {
      Query deletion = invocation.getArgument(0);
      boolean rechecksExpiry = deletion.getQueryObject().containsKey("expiresAt");
      if (rechecksExpiry && storedExpiry.get().isAfter(cutoff)) {
        return DeleteResult.acknowledged(0);
      }
      storedExpiry.set(null);
      return DeleteResult.acknowledged(1);
    });

    assertThat(new MongoSharedFolderAuditRepository(factory).deleteExpired(cutoff, 1)).isZero();

    var deletion = ArgumentCaptor.forClass(Query.class);
    verify(operations).remove(deletion.capture());
    assertThat(deletion.getValue().getQueryObject())
        .containsEntry("id", "race")
        .containsKey("expiresAt");
    assertThat(storedExpiry).hasValue(cutoff.plusSeconds(1));
  }
}
