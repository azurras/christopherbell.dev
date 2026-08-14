package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Persists and atomically consumes short-lived import preview tokens. */
@MongoPersistence
@Repository
public class RestaurantImportPreviewStore implements RestaurantImportPreviewPort {
  /** Legacy physical source retained for pre-cutover migration definitions only. */
  public static final String COLLECTION = "restaurant_import_previews";

  private final KindScopedMongoOperations<RestaurantImportPreviewDocument> previews;

  public RestaurantImportPreviewStore(DomainMongoOperationsFactory factory) {
    this.previews = factory.forType(RestaurantImportPreviewDocument.class);
  }

  @Override
  public RestaurantImportPreviewDocument save(RestaurantImportPreviewDocument preview) {
    return previews.save(preview);
  }

  @Override
  public Optional<RestaurantImportPreviewDocument> claim(
      String token,
      String actorAccountId,
      Instant now
  ) {
    var query = Query.query(Criteria.where("id").is(token)
        .and("actorAccountId").is(actorAccountId)
        .and("consumedOn").is(null)
        .and("expiresOn").gt(now));
    return previews.findAndUpdate(
        query,
        new Update().set("consumedOn", now));
  }
}
