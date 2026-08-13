package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.music.api.MusicRuntimeStateMigrationPort;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Additively consolidates the two legacy Music runtime singleton documents. */
@MongoPersistence
@Component
public final class V014ConsolidateMusicRuntimeState implements ApplicationMigration {
  static final String LEGACY_QUEUE = "music_queue_state";
  static final String LEGACY_RADIO = "music_radio_state";
  private static final String TARGET = "music_runtime_state";
  private static final String CHECKSUM =
      "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb";

  private final MusicRuntimeStateMigrationPort music;

  public V014ConsolidateMusicRuntimeState(MusicRuntimeStateMigrationPort music) {
    this.music = music;
  }

  @Override public String id() { return "014-consolidate-music-runtime-state"; }
  @Override public String checksum() { return CHECKSUM; }
  @Override public String description() { return "Consolidate Music queue and radio runtime state"; }

  @Override
  public void apply(MongoTemplate mongo) {
    List<Document> queueSources = mongo.findAll(Document.class, LEGACY_QUEUE);
    List<Document> radioSources = mongo.findAll(Document.class, LEGACY_RADIO);
    var prepared = music.prepare(queueSources, radioSources);
    List<Document> insert = prepared.documentsToInsert(destination(mongo));
    if (insert.isEmpty()) {
      return;
    }

    mongo.getCollection(TARGET).insertMany(insert);
    prepared.requireEquivalent(destination(mongo));
  }

  private static List<Document> destination(MongoTemplate mongo) {
    return mongo.findAll(Document.class, TARGET);
  }
}
