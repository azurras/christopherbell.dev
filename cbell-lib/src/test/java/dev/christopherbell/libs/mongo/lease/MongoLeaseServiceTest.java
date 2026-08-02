package dev.christopherbell.libs.mongo.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class MongoLeaseServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-25T22:30:00Z");
  private static final Instant EXPIRES_AT = NOW.plusSeconds(120);

  @Mock private MongoTemplate mongo;
  private MongoLeaseService service;

  @BeforeEach
  void setUp() {
    service = new MongoLeaseService(mongo);
  }

  @Test
  void acquireUsesFixedNameOwnerAndExpiredLeaseBoundary() {
    var lease = new MongoLeaseDocument();
    lease.setId("application-migrations");
    lease.setOwnerToken("owner-1");
    when(mongo.findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(MongoLeaseDocument.class)))
        .thenReturn(lease);

    assertThat(service.tryAcquire("application-migrations", "owner-1", NOW, EXPIRES_AT))
        .isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findAndModify(
        query.capture(),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(MongoLeaseDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_id", "application-migrations", "ownerToken", "expiresAt", "$lte");
  }

  @Test
  void duplicateKeyContentionReturnsFalse() {
    when(mongo.findAndModify(
        any(Query.class),
        any(Update.class),
        any(FindAndModifyOptions.class),
        eq(MongoLeaseDocument.class)))
        .thenThrow(new DuplicateKeyException("host details"));

    assertThat(service.tryAcquire("application-migrations", "owner-2", NOW, EXPIRES_AT))
        .isFalse();
  }

  @Test
  void renewRequiresCurrentUnexpiredOwner() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MongoLeaseDocument.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertThat(service.renew("application-migrations", "owner-1", NOW, EXPIRES_AT)).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).updateFirst(query.capture(), any(Update.class), eq(MongoLeaseDocument.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_id", "ownerToken", "expiresAt", "$gt");
  }

  @Test
  void renewReturnsFalseWhenTheCurrentOwnerDoesNotMatch() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MongoLeaseDocument.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThat(service.renew("application-migrations", "stale-owner", NOW, EXPIRES_AT))
        .isFalse();
  }

  @Test
  void releaseRequiresCurrentOwner() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MongoLeaseDocument.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertThat(service.release("application-migrations", "owner-1")).isTrue();

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).updateFirst(query.capture(), any(Update.class), eq(MongoLeaseDocument.class));
    assertThat(query.getValue().getQueryObject().toJson())
        .contains("_id", "application-migrations", "ownerToken", "owner-1");
  }

  @Test
  void releaseReturnsFalseWhenTheCurrentOwnerDoesNotMatch() {
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MongoLeaseDocument.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThat(service.release("application-migrations", "stale-owner")).isFalse();
  }
}
