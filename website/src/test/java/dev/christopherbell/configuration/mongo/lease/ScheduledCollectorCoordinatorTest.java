package dev.christopherbell.configuration.mongo.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;

class ScheduledCollectorCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

  @Test
  void contentionRecordsSkippedWithoutRunningWork() {
    var leases = Mockito.mock(MongoLeaseService.class);
    var mongo = Mockito.mock(MongoTemplate.class);
    var ran = new AtomicBoolean();
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(false);
    var coordinator = new ScheduledCollectorCoordinator(
        leases, mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    var outcome = coordinator.run("collector:test", Duration.ofMinutes(2), lease -> {
      ran.set(true);
      return "value";
    });

    assertThat(outcome.status()).isEqualTo(ScheduledCollectorRunStatus.SKIPPED_LOCKED);
    assertThat(outcome.value()).isNull();
    assertThat(ran).isFalse();
    verify(leases, never()).release(any(), any());
    verify(mongo).save(any(ScheduledCollectorRun.class));
  }

  @Test
  void successRecordsLifecycleAndReleasesExactOwner() {
    var leases = Mockito.mock(MongoLeaseService.class);
    var mongo = Mockito.mock(MongoTemplate.class);
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(true);
    var coordinator = new ScheduledCollectorCoordinator(
        leases, mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    var outcome = coordinator.run("collector:test", Duration.ofMinutes(2), lease -> "value");

    assertThat(outcome.status()).isEqualTo(ScheduledCollectorRunStatus.SUCCEEDED);
    assertThat(outcome.value()).isEqualTo("value");
    var owner = ArgumentCaptor.forClass(String.class);
    verify(leases).release(org.mockito.ArgumentMatchers.eq("collector:test"), owner.capture());
    assertThat(owner.getValue()).isNotBlank();
    verify(mongo, org.mockito.Mockito.times(2)).save(any(ScheduledCollectorRun.class));
  }

  @Test
  void terminalStatusFailureStillReleasesTheExactLeaseOwner() {
    var leases = Mockito.mock(MongoLeaseService.class);
    var mongo = Mockito.mock(MongoTemplate.class);
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(true);
    var saves = new AtomicInteger();
    when(mongo.save(any(ScheduledCollectorRun.class))).thenAnswer(invocation -> {
      if (saves.incrementAndGet() == 2) {
        throw new org.springframework.dao.DataAccessResourceFailureException("mongo unavailable");
      }
      return invocation.getArgument(0);
    });
    var coordinator = new ScheduledCollectorCoordinator(
        leases, mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> coordinator.run(
        "collector:test", Duration.ofMinutes(2), lease -> "value"))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

    verify(leases).release(org.mockito.ArgumentMatchers.eq("collector:test"), any());
  }

  @Test
  void runningStatusFailureStillReleasesTheExactLeaseOwner() {
    var leases = Mockito.mock(MongoLeaseService.class);
    var mongo = Mockito.mock(MongoTemplate.class);
    when(leases.tryAcquire(any(), any(), any(), any())).thenReturn(true);
    when(mongo.save(any(ScheduledCollectorRun.class)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("mongo unavailable"));
    var coordinator = new ScheduledCollectorCoordinator(
        leases, mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> coordinator.run(
        "collector:test", Duration.ofMinutes(2), lease -> "value"))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

    verify(leases).release(org.mockito.ArgumentMatchers.eq("collector:test"), any());
  }
}
