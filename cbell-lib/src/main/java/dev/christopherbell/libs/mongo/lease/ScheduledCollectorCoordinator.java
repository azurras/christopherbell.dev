package dev.christopherbell.libs.mongo.lease;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Serializes collector work through owner-scoped Mongo leases and records safe status. */
@Service
public class ScheduledCollectorCoordinator {
  private final MongoLeaseService leases;
  private final ScheduledCollectorRunStore runs;
  private final Clock clock;

  public ScheduledCollectorCoordinator(
      MongoLeaseService leases, ScheduledCollectorRunStore runs, Clock clock) {
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

    var guard = new RenewingMongoLease(
        leases, clock, collectorName, ownerToken, leaseDuration, startedOn);
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
    } catch (Exception failure) {
      run.setStatus(ScheduledCollectorRunStatus.FAILED);
      run.setErrorCategory(safeCategory(failure));
      throw new IllegalStateException("Scheduled collector failed.", failure);
    } finally {
      run.setCompletedOn(clock.instant());
      try {
        runs.save(run);
      } finally {
        leases.release(collectorName, ownerToken);
      }
    }
  }

  private String safeCategory(Exception failure) {
    if (failure instanceof LeaseOwnershipLostException) {
      return "LEASE_LOST";
    }
    if (failure instanceof java.net.http.HttpTimeoutException) {
      return "REMOTE_TIMEOUT";
    }
    if (failure instanceof java.io.IOException) {
      return "REMOTE_IO";
    }
    return "COLLECTOR_FAILED";
  }

  @FunctionalInterface
  public interface Work<T> {
    T execute(CollectorLeaseGuard guard) throws Exception;
  }

  public record Outcome<T>(ScheduledCollectorRunStatus status, T value) {}
}
