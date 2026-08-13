package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.whatsforlunch.restaurant.RestaurantWebsiteUrlPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Backfills bounded WFL session lifecycle and indexed safe restaurant query fields. */
@MongoPersistence
@Component
public final class V011HardenWhatsForLunchData implements ApplicationMigration {
  private static final int BATCH_SIZE = 250;
  private static final Duration ACTIVE_LIFETIME = Duration.ofHours(24);
  private static final Duration ARCHIVE_LIFETIME = Duration.ofDays(30);
  private static final String CHECKSUM =
      "73e242e0b87a60dea69ee9eaf7e3290c014891b5a306ac4d6c4df39c53fe2f2a";

  @Override
  public String id() {
    return "011-harden-whats-for-lunch-data";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Backfill WFL lifecycle, query keys, indexes, and safe restaurant websites";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    ensureIndexes(mongo);
    forEachBatch(mongo, "whatsforlunch_sessions", document -> backfillSession(mongo, document));
    forEachBatch(mongo, "whatsforlunch", document -> backfillRestaurant(mongo, document));
  }

  private static void ensureIndexes(MongoTemplate mongo) {
    mongo.indexOps("whatsforlunch_sessions").createIndex(new Index()
        .on("participantAccountIds", Sort.Direction.ASC)
        .on("createdOn", Sort.Direction.DESC)
        .on("_id", Sort.Direction.ASC)
        .named("wfl_session_participant_created"));
    mongo.indexOps("whatsforlunch_sessions").createIndex(new Index()
        .on("deleteOn", Sort.Direction.ASC)
        .expire(Duration.ZERO)
        .named("wfl_session_delete_ttl"));
    mongo.indexOps("whatsforlunch").createIndex(new Index()
        .on("searchState", Sort.Direction.ASC)
        .on("searchCity", Sort.Direction.ASC)
        .on("dedupeKey", Sort.Direction.ASC)
        .on("_id", Sort.Direction.ASC)
        .named("restaurant_inventory_location_name"));
    mongo.indexOps("whatsforlunch").createIndex(new Index()
        .on("searchCity", Sort.Direction.ASC)
        .on("dedupeKey", Sort.Direction.ASC)
        .on("_id", Sort.Direction.ASC)
        .named("restaurant_inventory_city_name"));
    mongo.indexOps("whatsforlunch").createIndex(new Index()
        .on("searchState", Sort.Direction.ASC)
        .on("dedupeKey", Sort.Direction.ASC)
        .on("_id", Sort.Direction.ASC)
        .named("restaurant_inventory_state_name"));
    mongo.indexOps("whatsforlunch").createIndex(new Index()
        .on("dedupeKey", Sort.Direction.ASC)
        .on("_id", Sort.Direction.ASC)
        .named("restaurant_dedupe_key_member"));
  }

  private static void backfillSession(MongoTemplate mongo, Document session) {
    var createdOn = instant(session.get("createdOn"));
    if (createdOn == null) {
      createdOn = instant(session.get("lastUpdatedOn"));
    }
    if (createdOn == null) {
      createdOn = Instant.now();
    }
    var activeUntil = instant(session.get("activeUntil"));
    if (activeUntil == null) {
      activeUntil = createdOn.plus(ACTIVE_LIFETIME);
    }
    var deleteOn = instant(session.get("deleteOn"));
    if (deleteOn == null) {
      deleteOn = activeUntil.plus(ARCHIVE_LIFETIME);
    }
    var update = new Update()
        .set("revision", number(session.get("revision")))
        .set("activeUntil", activeUntil)
        .set("deleteOn", deleteOn)
        .set("restaurantResetCount", number(session.get("restaurantResetCount")))
        .set("restaurantResetAudit",
            session.get("restaurantResetAudit") instanceof List<?> values ? values : List.of());
    mongo.updateFirst(
        new Query(Criteria.where("_id").is(session.getString("_id"))),
        update,
        "whatsforlunch_sessions");
  }

  private static void backfillRestaurant(MongoTemplate mongo, Document restaurant) {
    var name = normalize(restaurant.getString("name"));
    var address = restaurant.get("address") instanceof Document value ? value : new Document();
    var update = new Update()
        .set("dedupeKey", name)
        .set("searchCity", normalize(address.getString("city")))
        .set("searchState", normalize(address.getString("state")));
    var website = RestaurantWebsiteUrlPolicy.safeOrNull(restaurant.getString("website"));
    if (website == null) {
      update.unset("website");
    } else {
      update.set("website", website);
    }
    mongo.updateFirst(
        new Query(Criteria.where("_id").is(restaurant.getString("_id"))),
        update,
        "whatsforlunch");
  }

  private static void forEachBatch(
      MongoTemplate mongo,
      String collection,
      java.util.function.Consumer<Document> consumer
  ) {
    String lastId = null;
    while (true) {
      var query = lastId == null
          ? new Query()
          : new Query(Criteria.where("_id").gt(lastId));
      query.with(Sort.by(Sort.Direction.ASC, "_id")).limit(BATCH_SIZE);
      var batch = mongo.find(query, Document.class, collection);
      if (batch.isEmpty()) {
        return;
      }
      batch.forEach(consumer);
      lastId = batch.getLast().getString("_id");
    }
  }

  private static long number(Object value) {
    return value instanceof Number number ? Math.max(0, number.longValue()) : 0L;
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof Date date) {
      return date.toInstant();
    }
    return null;
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
