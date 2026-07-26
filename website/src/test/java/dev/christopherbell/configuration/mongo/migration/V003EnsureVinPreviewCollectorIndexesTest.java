package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.configuration.mongo.lease.ScheduledCollectorRun;
import dev.christopherbell.post.preview.PostLinkPreviewCacheEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class V003EnsureVinPreviewCollectorIndexesTest {
  private static final String EXPECTED_CHECKSUM =
      "799e5a12c1bfc022217a2c9f1e29f50ed4eef9b7f03daba01121a90c696dbd32";

  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations vinIndexes;
  @Mock private IndexOperations collectorIndexes;
  @Mock private IndexOperations previewIndexes;

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    var migration = new V003EnsureVinPreviewCollectorIndexes();

    assertThat(migration.id()).isEqualTo("003-ensure-vin-preview-collector-indexes");
    assertThat(migration.checksum()).isEqualTo(EXPECTED_CHECKSUM);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyEnsuresNamedVinExpiryIndex() {
    when(mongo.indexOps(VehicleVinDecodeCache.COLLECTION)).thenReturn(vinIndexes);
    when(mongo.indexOps(ScheduledCollectorRun.COLLECTION)).thenReturn(collectorIndexes);
    when(mongo.indexOps(PostLinkPreviewCacheEntry.COLLECTION)).thenReturn(previewIndexes);

    new V003EnsureVinPreviewCollectorIndexes().apply(mongo);

    verify(vinIndexes).createIndex(argThat(index ->
        "vehicle_vin_cache_expiry".equals(index.getIndexOptions().get("name"))
            && Long.valueOf(0L).equals(index.getIndexOptions().get("expireAfterSeconds"))
            && index.getIndexKeys().containsKey("expiresOn")));
    verify(collectorIndexes).createIndex(argThat(index ->
        "scheduled_collector_status_completed".equals(index.getIndexOptions().get("name"))
            && index.getIndexKeys().containsKey("status")
            && index.getIndexKeys().containsKey("completedOn")));
    verify(previewIndexes).createIndex(argThat(index ->
        "post_link_preview_cache_expiry".equals(index.getIndexOptions().get("name"))
            && Long.valueOf(0L).equals(index.getIndexOptions().get("expireAfterSeconds"))
            && index.getIndexKeys().containsKey("expiresOn")));
  }
}
