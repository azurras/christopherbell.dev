package dev.christopherbell.libs.lease;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Serializes collector work through owner-scoped leases and records safe status. */
@Service
public class ScheduledCollectorCoordinator {
  private final LeaseAccess leases;
  private final ScheduledCollectorRunStore runs;
  private final Clock clock;

  @Autowired
  public ScheduledCollectorCoordinator(
      LeaseService leases, ScheduledCollectorRunStore runs, Clock clock) {
    this(new LeaseAccess() {
      @Override
      public boolean tryAcquire(
          String name, String owner, Instant now, Instant expiresAt) {
        return leases.tryAcquire(name, owner, now, expiresAt);
      }
      @Override
      public boolean renew(
          String name, String owner, Instant now, Instant expiresAt) {
        return leases.renew(name, owner, now, expiresAt);
      }
      @Override
      public boolean release(String name, String owner) {
        return leases.release(name, owner);
      }
    }, runs, clock);
  }

  private ScheduledCollectorCoordinator(
      LeaseAccess leases, ScheduledCollectorRunStore runs, Clock clock) {
    this.leases = leases;
    this.runs = runs;
    this.clock = clock;
  }

  public <T> Outcome<T> run(String collectorName, Duration leaseDuration, Work<T> work) {
    var ownerToken = UUID.randomUUID().toString();
    var startedOn = clock.instant();
    var run = ScheduledCollectorRun.builder()
        .id(UUID.randomUUID().toString())
        .collectorName(collectorName)
        .ownerToken(ownerToken)
        .startedOn(startedOn)
        .build();
    if (!leases.tryAcquire(
        collectorName, ownerToken, startedOn, startedOn.plus(leaseDuration))) {
      run.setStatus(ScheduledCollectorRunStatus.SKIPPED_LOCKED);
      run.setCompletedOn(clock.instant());
      runs.save(run);
      return new Outcome<>(ScheduledCollectorRunStatus.SKIPPED_LOCKED, null);
    }

    var guard = new RenewingLease(
        leases::renew, clock, collectorName, ownerToken, leaseDuration, startedOn);
    InterruptedException interruption = null;
    try {
      run.setStatus(ScheduledCollectorRunStatus.RUNNING);
      runs.save(run);
      var value = work.execute(guard);
      guard.verifyHeld();
      run.setStatus(ScheduledCollectorRunStatus.SUCCEEDED);
      return new Outcome<>(ScheduledCollectorRunStatus.SUCCEEDED, value);
    } catch (RuntimeException failure) {
      run.setStatus(ScheduledCollectorRunStatus.FAILED);
      run.setErrorCategory(safeCategory(failure));
      throw failure;
    } catch (InterruptedException failure) {
      run.setStatus(ScheduledCollectorRunStatus.FAILED);
      run.setErrorCategory(safeCategory(failure));
      interruption = failure;
      throw new IllegalStateException("Scheduled collector failed.", failure);
    } catch (Exception failure) {
      run.setStatus(ScheduledCollectorRunStatus.FAILED);
      run.setErrorCategory(safeCategory(failure));
      throw new IllegalStateException("Scheduled collector failed.", failure);
    } finally {
      run.setCompletedOn(clock.instant());
      try {
        runs.save(run);
      } finally {
        try {
          leases.release(collectorName, ownerToken);
        } finally {
          if (interruption != null) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  private String safeCategory(Exception failure) {
    if (failure instanceof LeaseOwnershipLostException) {
      return "LEASE_LOST";
    }
    if (failure instanceof HttpTimeoutException) {
      return "REMOTE_TIMEOUT";
    }
    if (failure instanceof IOException) {
      return "REMOTE_IO";
    }
    return "COLLECTOR_FAILED";
  }

  @FunctionalInterface
  public interface Work<T> {
    T execute(CollectorLeaseGuard guard) throws Exception;
  }

  public record Outcome<T>(ScheduledCollectorRunStatus status, T value) {}

  private interface LeaseAccess {
    boolean tryAcquire(
        String name, String owner, Instant now, Instant expiresAt);
    boolean renew(
        String name, String owner, Instant now, Instant expiresAt);
    boolean release(String name, String owner);
  }
}
