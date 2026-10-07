package dev.christopherbell.notification.delivery;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs one observable bounded notification-guard cleanup batch per schedule. */
@Component
public final class NotificationPersistenceCleanupJob {
  private static final Logger log =
      LoggerFactory.getLogger(NotificationPersistenceCleanupJob.class);
  private static final int DEFAULT_BATCH_LIMIT = 250;

  private final NotificationFanoutPort fanout;
  private final Clock clock;
  private final int batchLimit;

  @Autowired
  public NotificationPersistenceCleanupJob(NotificationFanoutPort fanout, Clock clock) {
    this(fanout, clock, DEFAULT_BATCH_LIMIT);
  }

  NotificationPersistenceCleanupJob(NotificationFanoutPort fanout, Clock clock, int batchLimit) {
    this.fanout = fanout;
    this.clock = clock;
    this.batchLimit = batchLimit;
  }

  /** Deletes up to one batch of expired dedupe claims and rate counters, logging when any go. */
  @Scheduled(fixedDelayString = "${app.persistence.cleanup-delay:PT5M}")
  public NotificationCleanupResult cleanup() {
    NotificationCleanupResult cleanupResult = fanout.deleteExpired(clock.instant(), batchLimit);
    if (cleanupResult.totalDeleted() > 0) {
      log.info("Deleted {} notification guards and {} notification rate rows",
          cleanupResult.guardsDeleted(), cleanupResult.ratesDeleted());
    }
    return cleanupResult;
  }
}
