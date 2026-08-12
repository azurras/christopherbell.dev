package dev.christopherbell.libs.mongo.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MongoLeaseServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-25T22:30:00Z");
  private static final Instant EXPIRES_AT = NOW.plusSeconds(120);

  @Mock private MongoLeaseStore store;
  private MongoLeaseService service;

  @BeforeEach
  void setUp() {
    service = new MongoLeaseService(store);
  }

  @Test
  void acquireDelegatesTheExactOwnerAndTimeBoundary() {
    when(store.tryAcquire("application-migrations", "owner-1", NOW, EXPIRES_AT))
        .thenReturn(true);

    assertThat(service.tryAcquire("application-migrations", "owner-1", NOW, EXPIRES_AT))
        .isTrue();
    verify(store).tryAcquire("application-migrations", "owner-1", NOW, EXPIRES_AT);
  }

  @Test
  void acquireReturnsStoreContention() {
    when(store.tryAcquire("application-migrations", "owner-2", NOW, EXPIRES_AT))
        .thenReturn(false);
    assertThat(service.tryAcquire("application-migrations", "owner-2", NOW, EXPIRES_AT))
        .isFalse();
  }

  @Test
  void renewDelegatesTheExactCurrentOwnerAndTimeBoundary() {
    when(store.renew("application-migrations", "owner-1", NOW, EXPIRES_AT))
        .thenReturn(true);
    assertThat(service.renew("application-migrations", "owner-1", NOW, EXPIRES_AT)).isTrue();
    verify(store).renew("application-migrations", "owner-1", NOW, EXPIRES_AT);
  }

  @Test
  void renewReturnsStoreOwnershipLoss() {
    assertThat(service.renew("application-migrations", "stale-owner", NOW, EXPIRES_AT))
        .isFalse();
  }

  @Test
  void releaseDelegatesTheExactCurrentOwner() {
    when(store.release("application-migrations", "owner-1")).thenReturn(true);
    assertThat(service.release("application-migrations", "owner-1")).isTrue();
    verify(store).release("application-migrations", "owner-1");
  }

  @Test
  void releaseReturnsStoreOwnershipLoss() {
    assertThat(service.release("application-migrations", "stale-owner")).isFalse();
  }
}
