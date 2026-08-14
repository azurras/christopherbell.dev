package dev.christopherbell.configuration.persistence;

import dev.christopherbell.music.api.MusicAccessRetention;
import dev.christopherbell.sharedfolder.api.SharedFolderAuditRetention;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs one observable, bounded media-persistence retention batch per schedule. */
@Component
public final class MediaPersistenceRetentionCleanupJob {
  private static final Logger log =
      LoggerFactory.getLogger(MediaPersistenceRetentionCleanupJob.class);
  private static final int DEFAULT_BATCH_LIMIT = 250;

  private final MusicAccessRetention musicAccessAttempts;
  private final SharedFolderAuditRetention sharedAuditEvents;
  private final Clock clock;
  private final int batchLimit;

  @Autowired
  public MediaPersistenceRetentionCleanupJob(
      MusicAccessRetention musicAccessAttempts,
      SharedFolderAuditRetention sharedAuditEvents) {
    this(musicAccessAttempts, sharedAuditEvents, Clock.systemUTC(), DEFAULT_BATCH_LIMIT);
  }

  MediaPersistenceRetentionCleanupJob(
      MusicAccessRetention musicAccessAttempts,
      SharedFolderAuditRetention sharedAuditEvents,
      Clock clock,
      int batchLimit) {
    this.musicAccessAttempts = Objects.requireNonNull(musicAccessAttempts, "musicAccessAttempts");
    this.sharedAuditEvents = Objects.requireNonNull(sharedAuditEvents, "sharedAuditEvents");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (batchLimit <= 0) throw new IllegalArgumentException("Batch limit must be positive.");
    this.batchLimit = batchLimit;
  }

  @Scheduled(fixedDelayString = "${app.persistence.cleanup-delay:PT5M}")
  public MediaPersistenceCleanupResult cleanup() {
    Instant cutoff = Instant.now(clock);
    var result = new MediaPersistenceCleanupResult(
        musicAccessAttempts.deleteExpired(cutoff, batchLimit),
        sharedAuditEvents.deleteExpired(cutoff, batchLimit));
    if (result.totalDeleted() > 0) {
      log.info("Deleted {} music access attempts and {} shared-folder audit events",
          result.musicAccessAttemptsDeleted(), result.sharedAuditEventsDeleted());
    }
    return result;
  }
}
