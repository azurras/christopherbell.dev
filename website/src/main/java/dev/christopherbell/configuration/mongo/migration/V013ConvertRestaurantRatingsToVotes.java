package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import java.util.List;
import java.util.function.Consumer;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Converts legacy WFL ratings to binary votes after validating the complete collection. */
@Component
public final class V013ConvertRestaurantRatingsToVotes implements ApplicationMigration {
  private static final int BATCH_SIZE = 250;
  private static final String COLLECTION = "whatsforlunch_ratings";
  private static final String CHECKSUM =
      "c10c2769b37044d866224770f7fb8b0877e02c2457c53d33ee25eeb879ab86f7";

  @Override public String id() { return "013-convert-restaurant-ratings-to-votes"; }
  @Override public String checksum() { return CHECKSUM; }
  @Override public String description() { return "Convert WFL 1-5 ratings to binary votes"; }

  @Override
  public void apply(MongoTemplate mongo) {
    forEachBatch(mongo, V013ConvertRestaurantRatingsToVotes::validateDocument);
    forEachBatch(mongo, document -> convert(mongo, document));
  }

  static RestaurantVoteValue targetVote(Document document) {
    Object raw = document.get("rating");
    if (!(raw instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < 1
        || number.intValue() > 5) {
      throw new IllegalStateException(
          "Legacy restaurant rating must be an integer from 1 to 5: " + document.get("_id"));
    }
    return number.intValue() >= 3 ? RestaurantVoteValue.UP : RestaurantVoteValue.DOWN;
  }

  static void validateDocument(Document document) {
    Object rating = document.get("rating");
    Object vote = document.get("vote");
    if (rating == null) {
      if (!RestaurantVoteValue.UP.name().equals(vote)
          && !RestaurantVoteValue.DOWN.name().equals(vote)) {
        throw new IllegalStateException(
            "Restaurant vote is missing or invalid: " + document.get("_id"));
      }
      return;
    }
    RestaurantVoteValue expected = targetVote(document);
    if (vote != null && !expected.name().equals(vote)) {
      throw new IllegalStateException("Restaurant rating and vote conflict: " + document.get("_id"));
    }
  }

  private static void convert(MongoTemplate mongo, Document document) {
    if (document.get("rating") == null) {
      return;
    }
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(document.get("_id"))),
        new Update().set("vote", targetVote(document).name()).unset("rating")
            .set("type", "restaurant_vote"),
        COLLECTION);
  }

  private static void forEachBatch(MongoTemplate mongo, Consumer<Document> consumer) {
    String lastId = null;
    while (true) {
      Query query = lastId == null
          ? new Query()
          : Query.query(Criteria.where("_id").gt(lastId));
      query.with(Sort.by(Sort.Direction.ASC, "_id")).limit(BATCH_SIZE);
      List<Document> batch = mongo.find(query, Document.class, COLLECTION);
      if (batch.isEmpty()) {
        return;
      }
      batch.forEach(consumer);
      lastId = batch.getLast().getString("_id");
    }
  }
}
