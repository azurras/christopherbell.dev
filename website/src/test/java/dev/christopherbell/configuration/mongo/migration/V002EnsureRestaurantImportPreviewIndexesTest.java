package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V002EnsureRestaurantImportPreviewIndexesTest {
  @Test
  void createsExpiryAndActorIndexes() {
    var mongo = mock(MongoTemplate.class);
    var indexes = mock(IndexOperations.class);
    when(mongo.indexOps(RestaurantImportPreviewStore.COLLECTION)).thenReturn(indexes);

    new V002EnsureRestaurantImportPreviewIndexes().apply(mongo);

    verify(indexes).createIndex(argThat(this::isExpiryIndex));
    verify(indexes).createIndex(argThat(this::isActorIndex));
  }

  private boolean isExpiryIndex(IndexDefinition index) {
    return "restaurant_import_preview_expiry".equals(index.getIndexOptions().get("name"))
        && index.getIndexOptions().containsKey("expireAfterSeconds")
        && index.getIndexKeys().containsKey("expiresOn");
  }

  private boolean isActorIndex(IndexDefinition index) {
    return "restaurant_import_preview_actor_created".equals(index.getIndexOptions().get("name"))
        && index.getIndexKeys().containsKey("actorAccountId")
        && index.getIndexKeys().containsKey("createdOn");
  }
}
