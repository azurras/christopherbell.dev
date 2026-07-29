package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.media.MediaJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Adds cleanup-before-TTL retention without arming active shared-folder work. */
@Component
public final class V012RetainSharedFolderWork implements ApplicationMigration {
  private static final int BATCH_SIZE = 250;
  private static final String UPLOADS = "shared_folder_upload_sessions";
  private static final String MEDIA = "shared_folder_media_jobs";
  private static final String RADIO = "shared_folder_radio";
  private static final List<String> TERMINAL_MEDIA = List.of(
      "FAILED", "CANCELED", "INSUFFICIENT_SPACE", "TIMED_OUT");
  private static final String CHECKSUM =
      "20b57d9152f4ea8b78f3de3b808325d195a9a864a5ace222c66954066228396a";

  private final SharedFolderProperties folders;
  private final SharedFolderMediaProperties media;
  private final Clock clock;

  public V012RetainSharedFolderWork(
      SharedFolderProperties folders, SharedFolderMediaProperties media, Clock clock) {
    this.folders = folders;
    this.media = media;
    this.clock = clock;
  }

  @Override public String id() { return "012-retain-shared-folder-work"; }
  @Override public String checksum() { return CHECKSUM; }
  @Override public String description() {
    return "Backfill cleanup-before-TTL retention for shared-folder uploads and media";
  }

  @Override
  public void apply(MongoTemplate mongo) {
    ensureIndexes(mongo);
    backfillUploads(mongo);
    backfillMedia(mongo);
    resetLegacyRadioDuration(mongo);
  }

  private void ensureIndexes(MongoTemplate mongo) {
    mongo.indexOps(UPLOADS).createIndex(new Index()
        .on("deleteAt", Sort.Direction.ASC).expire(Duration.ZERO)
        .named("shared_upload_delete_ttl"));
    mongo.indexOps(MEDIA).createIndex(new Index()
        .on("artifactsCleaned", Sort.Direction.ASC)
        .on("cleanupAfter", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("_id", Sort.Direction.ASC)
        .named("media_cleanup_due"));
    mongo.indexOps(MEDIA).createIndex(new Index()
        .on("deleteAt", Sort.Direction.ASC).expire(Duration.ZERO)
        .named("shared_media_delete_ttl"));
  }

  private void backfillUploads(MongoTemplate mongo) {
    forEachBatch(mongo, UPLOADS, Criteria.where("state").is("COMPLETED")
        .and("deleteAt").exists(false), document -> {
          Instant completed = timestamp(document, "updatedAt", "createdAt");
          mongo.updateFirst(Query.query(Criteria.where("_id").is(document.getString("_id"))
                  .and("state").is("COMPLETED").and("deleteAt").exists(false)),
              new Update().set("deleteAt", completed.plus(folders.completedUploadRetention())),
              UPLOADS);
        });
  }

  private void backfillMedia(MongoTemplate mongo) {
    forEachBatch(mongo, MEDIA, Criteria.where("status").in(TERMINAL_MEDIA)
        .and("artifactsCleaned").ne(true).and("cleanupAfter").exists(false), document -> {
          String status = document.getString("status");
          Instant terminalAt = timestamp(document, "updatedAt", "createdAt");
          Duration delay = media.cleanupDelay(MediaJobStatus.valueOf(status));
          mongo.updateFirst(Query.query(Criteria.where("_id").is(document.getString("_id"))
                  .and("status").is(status).and("artifactsCleaned").ne(true)
                  .and("cleanupAfter").exists(false)),
              new Update().set("cleanupAfter", terminalAt.plus(delay))
                  .set("artifactsCleaned", false).unset("deleteAt"),
              MEDIA);
        });
  }

  private void resetLegacyRadioDuration(MongoTemplate mongo) {
    mongo.updateFirst(Query.query(Criteria.where("_id").is("shared-folder-radio")),
        new Update().unset("knownDurations").unset("durationSeconds"), RADIO);
    mongo.updateFirst(Query.query(Criteria.where("_id").is("shared-folder-radio")
            .and("version").exists(false)),
        new Update().set("version", 0L), RADIO);
  }

  private void forEachBatch(
      MongoTemplate mongo,
      String collection,
      Criteria criteria,
      java.util.function.Consumer<Document> consumer) {
    String lastId = null;
    while (true) {
      Criteria page = lastId == null
          ? criteria
          : new Criteria().andOperator(criteria, Criteria.where("_id").gt(lastId));
      Query query = Query.query(page).with(Sort.by(Sort.Direction.ASC, "_id")).limit(BATCH_SIZE);
      List<Document> batch = mongo.find(query, Document.class, collection);
      if (batch.isEmpty()) return;
      batch.forEach(consumer);
      lastId = batch.getLast().getString("_id");
    }
  }

  private Instant timestamp(Document document, String primary, String fallback) {
    Instant value = instant(document.get(primary));
    if (value == null) value = instant(document.get(fallback));
    return value == null ? clock.instant() : value;
  }

  private Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof Date date) return date.toInstant();
    return null;
  }
}
