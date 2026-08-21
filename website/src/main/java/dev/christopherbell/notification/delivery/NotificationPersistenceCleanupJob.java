package dev.christopherbell.notification.delivery;

import java.time.Clock;
import java.time.Instant;
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
  public NotificationPersistenceCleanupJob(NotificationFanoutPort fanout) {
    this(fanout, Clock.systemUTC(), DEFAULT_BATCH_LIMIT);
  }

  NotificationPersistenceCleanupJob(
      NotificationFanoutPort fanout, Clock clock, int batchLimit) {
    this.fanout = fanout;
    this.clock = clock;
    this.batchLimit = batchLimit;
  }

  @Scheduled(fixedDelayString = "${app.persistence.cleanup-delay:PT5M}")
  public NotificationCleanupResult cleanup() {
    var result = fanout.deleteExpired(Instant.now(clock), batchLimit);
    if (result.totalDeleted() > 0) {
      log.info("Deleted {} notification guards and {} notification rate rows",
          result.guardsDeleted(), result.ratesDeleted());
    }
    return result;
  }
}
