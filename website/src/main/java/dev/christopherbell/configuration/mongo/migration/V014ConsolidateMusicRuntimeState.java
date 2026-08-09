package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateDocument;
import java.util.HashMap;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Additively consolidates the two legacy Music runtime singleton documents. */
@Component
public final class V014ConsolidateMusicRuntimeState implements ApplicationMigration {
  static final String LEGACY_QUEUE = "music_queue_state";
  static final String LEGACY_RADIO = "music_radio_state";
  private static final String CHECKSUM =
      "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb";

  @Override public String id() { return "014-consolidate-music-runtime-state"; }
  @Override public String checksum() { return CHECKSUM; }
  @Override public String description() { return "Consolidate Music queue and radio runtime state"; }

  @Override
  public void apply(MongoTemplate mongo) {
    var queue = requireLegacy(mongo, LEGACY_QUEUE, MusicQueueState.ID, MusicQueueState.class);
    var radio = requireLegacy(mongo, LEGACY_RADIO, MusicRadioState.ID, MusicRadioState.class);
    List<MusicRuntimeStateDocument> expected = List.of(
        MusicRuntimeStateDocument.forQueue(queue),
        MusicRuntimeStateDocument.forRadio(radio));
    List<MusicRuntimeStateDocument> existing = destination(mongo);
    if (!existing.isEmpty()) {
      requireEquivalent(existing, queue, radio);
      return;
    }

    mongo.insert(expected, MusicRuntimeStateDocument.COLLECTION);
    requireEquivalent(destination(mongo), queue, radio);
  }

  private static <T> T requireLegacy(
      MongoTemplate mongo, String collection, String id, Class<T> type) {
    if (mongo.count(new Query(), collection) != 1) {
      throw new IllegalStateException("Legacy Music runtime state has unexpected cardinality.");
    }
    T state = mongo.findById(id, type, collection);
    if (state == null) {
      throw new IllegalStateException("Legacy Music runtime state has an unexpected identity.");
    }
    return state;
  }

  private static List<MusicRuntimeStateDocument> destination(MongoTemplate mongo) {
    return mongo.findAll(MusicRuntimeStateDocument.class, MusicRuntimeStateDocument.COLLECTION);
  }

  private static void requireEquivalent(
      List<MusicRuntimeStateDocument> documents,
      MusicQueueState queue,
      MusicRadioState radio) {
    if (documents.size() != 2) {
      throw new IllegalStateException("Music runtime state destination is partial or unexpected.");
    }
    var byId = new HashMap<String, MusicRuntimeStateDocument>();
    for (var document : documents) {
      if (document == null) {
        throw new IllegalStateException("Music runtime state destination is malformed.");
      }
      if (byId.put(document.id(), document) != null) {
        throw new IllegalStateException("Music runtime state destination has duplicate identities.");
      }
    }
    var targetQueue = byId.get(MusicRuntimeStateDocument.QUEUE_ID);
    var targetRadio = byId.get(MusicRuntimeStateDocument.RADIO_ID);
    if (targetQueue == null || targetRadio == null
        || !queue.equals(targetQueue.toQueueState())
        || !radio.equals(targetRadio.toRadioState())) {
      throw new IllegalStateException("Music runtime state destination diverges from its sources.");
    }
  }
}
