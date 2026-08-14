package dev.christopherbell.post.preview;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs one observable bounded link-preview expiration batch per schedule. */
@Component
public final class PostLinkPreviewCleanupJob {
  private static final Logger log = LoggerFactory.getLogger(PostLinkPreviewCleanupJob.class);
  private static final int DEFAULT_BATCH_LIMIT = 250;

  private final PostLinkPreviewCacheRepository cache;
  private final Clock clock;
  private final int batchLimit;

  @Autowired
  public PostLinkPreviewCleanupJob(PostLinkPreviewCacheRepository cache) {
    this(cache, Clock.systemUTC(), DEFAULT_BATCH_LIMIT);
  }

  PostLinkPreviewCleanupJob(
      PostLinkPreviewCacheRepository cache, Clock clock, int batchLimit) {
    this.cache = cache;
    this.clock = clock;
    this.batchLimit = batchLimit;
  }

  @Scheduled(fixedDelayString = "${app.persistence.cleanup-delay:PT5M}")
  public int cleanup() {
    int deleted = cache.deleteExpired(Instant.now(clock), batchLimit);
    if (deleted > 0) log.info("Deleted {} expired link-preview cache rows", deleted);
    return deleted;
  }
}
