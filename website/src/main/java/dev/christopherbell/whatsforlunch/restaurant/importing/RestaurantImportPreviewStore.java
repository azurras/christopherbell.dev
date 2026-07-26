package dev.christopherbell.whatsforlunch.restaurant.importing;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Persists and atomically consumes short-lived import preview tokens. */
@Repository
@RequiredArgsConstructor
public class RestaurantImportPreviewStore {
  public static final String COLLECTION = "restaurant_import_previews";

  private final MongoTemplate mongo;

  public RestaurantImportPreviewDocument save(RestaurantImportPreviewDocument preview) {
    return mongo.save(preview);
  }

  public Optional<RestaurantImportPreviewDocument> claim(
      String token,
      String actorAccountId,
      Instant now
  ) {
    var query = Query.query(Criteria.where("_id").is(token)
        .and("actorAccountId").is(actorAccountId)
        .and("consumedOn").is(null)
        .and("expiresOn").gt(now));
    var claimed = mongo.findAndModify(
        query,
        new Update().set("consumedOn", now),
        FindAndModifyOptions.options().returnNew(true),
        RestaurantImportPreviewDocument.class);
    return Optional.ofNullable(claimed);
  }
}
