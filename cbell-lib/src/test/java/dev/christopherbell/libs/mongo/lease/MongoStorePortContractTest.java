package dev.christopherbell.libs.mongo.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.time.Instant;
import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import org.junit.jupiter.api.Test;

class MongoStorePortContractTest {
  @Test
  void leaseStoreIsANarrowPhysicalNameFreePort() throws Exception {
    assertThat(MongoLeaseStore.class.isInterface()).isTrue();
    assertThat(MongoLeaseStore.class.getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("tryAcquire", "renew", "release");
    assertThat(MongoLeaseStore.class.getDeclaredFields())
        .allMatch(field -> !Modifier.isStatic(field.getModifiers()));
    assertThat(MongoLeaseStore.class.getMethod(
        "tryAcquire", String.class, String.class, Instant.class, Instant.class))
        .isNotNull();
  }

  @Test
  void collectorRunStoreOwnsOnlyDurableRunWrites() throws Exception {
    assertThat(ScheduledCollectorRunStore.class.isInterface()).isTrue();
    assertThat(ScheduledCollectorRunStore.class.getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactly("save");
    assertThat(ScheduledCollectorRunStore.class.getMethod("save", ScheduledCollectorRun.class))
        .isNotNull();
  }
}
