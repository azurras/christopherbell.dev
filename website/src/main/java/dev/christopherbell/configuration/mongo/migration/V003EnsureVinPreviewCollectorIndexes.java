package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorRun;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import java.time.Duration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates lifecycle indexes for VIN, link-preview, and collector state. */
@MongoPersistence
@Component
public final class V003EnsureVinPreviewCollectorIndexes implements ApplicationMigration {
  private static final String CHECKSUM =
      "799e5a12c1bfc022217a2c9f1e29f50ed4eef9b7f03daba01121a90c696dbd32";

  @Override
  public String id() {
    return "003-ensure-vin-preview-collector-indexes";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure VIN, link-preview, and scheduled-collector indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    mongo.indexOps("vehicle_vin_decode_cache").createIndex(new Index()
        .on("expiresOn", Direction.ASC)
        .expire(Duration.ZERO)
        .named("vehicle_vin_cache_expiry"));
    mongo.indexOps("scheduled_collector_runs").createIndex(new Index()
        .on("status", Direction.ASC)
        .on("completedOn", Direction.DESC)
        .named("scheduled_collector_status_completed"));
    mongo.indexOps(PostLinkPreviewCacheEntry.COLLECTION).createIndex(new Index()
        .on("expiresOn", Direction.ASC)
        .expire(Duration.ZERO)
        .named("post_link_preview_cache_expiry"));
  }
}
