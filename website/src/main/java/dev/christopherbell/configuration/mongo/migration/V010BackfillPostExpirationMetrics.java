package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoBackendComponent;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Backfills queryable root reply counts and propagates root expiration in bounded batches. */
@MongoBackendComponent
public final class V010BackfillPostExpirationMetrics implements ApplicationMigration {
  private static final int BATCH_SIZE = 250;
  private static final Duration EXTENSION = Duration.ofHours(24);
  private static final String CHECKSUM =
      "ea5742c9d14f054b643a943c87e89e622807223780402928cd42603acc6d58c0";

  @Override
  public String id() {
    return "010-backfill-post-expiration-metrics";
  }

  @Override
  public String checksum() {
    return CHECKSUM;
  }

  @Override
  public String description() {
    return "Backfill post thread reply counts and expiration timestamps";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    String lastId = null;
    while (true) {
      var rootCriteria = Criteria.where("parentId").is(null);
      if (lastId != null) {
        rootCriteria = new Criteria().andOperator(
            rootCriteria,
            Criteria.where("_id").gt(lastId));
      }
      var roots = mongo.find(
          new Query(rootCriteria)
              .with(Sort.by(Sort.Direction.ASC, "_id"))
              .limit(BATCH_SIZE),
          Document.class,
          "posts");
      if (roots.isEmpty()) {
        return;
      }
      for (var root : roots) {
        backfillRoot(mongo, root);
      }
      lastId = roots.get(roots.size() - 1).getString("_id");
    }
  }

  private static void backfillRoot(MongoTemplate mongo, Document root) {
    var rootId = root.getString("_id");
    long replyCount = mongo.count(new Query(new Criteria().andOperator(
        Criteria.where("rootId").is(rootId),
        Criteria.where("parentId").ne(null))), "posts");
    int threadReplyLikes = integer(root, "threadReplyLikesCount");
    int extensions = integer(root, "likesCount")
        + threadReplyLikes
        + Math.toIntExact(replyCount);
    var createdOn = instant(root.get("createdOn"));
    var expiresOn = createdOn.plus(EXTENSION.multipliedBy(1L + Math.max(0, extensions)));
    mongo.updateFirst(
        new Query(Criteria.where("_id").is(rootId)),
        new Update()
            .set("threadReplyLikesCount", threadReplyLikes)
            .set("threadReplyCount", replyCount)
            .set("expiresOn", expiresOn),
        "posts");
    mongo.updateMulti(
        new Query(new Criteria().andOperator(
            Criteria.where("rootId").is(rootId),
            Criteria.where("_id").ne(rootId))),
        new Update().set("expiresOn", expiresOn),
        "posts");
  }

  private static int integer(Document document, String field) {
    var value = document.get(field);
    return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof Date date) {
      return date.toInstant();
    }
    return Instant.EPOCH;
  }
}
