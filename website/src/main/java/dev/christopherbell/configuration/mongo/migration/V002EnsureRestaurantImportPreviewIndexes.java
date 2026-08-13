package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore;
import java.time.Duration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates lifecycle and lookup indexes for short-lived WFL import previews. */
@MongoPersistence
@Component
public final class V002EnsureRestaurantImportPreviewIndexes implements ApplicationMigration {
  private static final String CHECKSUM =
      "63e1372e0a08e3cdba44aed0d855d124bf2d0e9a1318781851e42f148874cb18";

  @Override
  public String id() {
    return "002-ensure-restaurant-import-preview-indexes";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Ensure WFL import preview expiry and operator lookup indexes";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    var indexes = mongo.indexOps(RestaurantImportPreviewStore.COLLECTION);
    indexes.createIndex(new Index()
        .on("expiresOn", Direction.ASC)
        .expire(Duration.ZERO)
        .named("restaurant_import_preview_expiry"));
    indexes.createIndex(new Index()
        .on("actorAccountId", Direction.ASC)
        .on("createdOn", Direction.DESC)
        .named("restaurant_import_preview_actor_created"));
  }
}
