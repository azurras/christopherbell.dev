package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.MusicRuntimeStateDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Additively consolidates the two legacy Music runtime singleton documents. */
@Component
public final class V014ConsolidateMusicRuntimeState implements ApplicationMigration {
  static final String LEGACY_QUEUE = "music_queue_state";
  static final String LEGACY_RADIO = "music_radio_state";
  private static final String CHECKSUM =
      "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb";
  private static final String CLASS_FIELD = "_class";
  private static final String QUEUE_STATE_CLASS = MusicQueueState.class.getName();
  private static final String RADIO_STATE_CLASS = MusicRadioState.class.getName();
  private static final String TARGET_CLASS = MusicRuntimeStateDocument.class.getName();
  private static final Set<String> QUEUE_SOURCE_REQUIRED = Set.of("_id", "entries");
  private static final Set<String> QUEUE_SOURCE_OPTIONAL = Set.of("version", CLASS_FIELD);
  private static final Set<String> RADIO_REQUIRED = Set.of(
      "stationSequence", "trackId", "observedToken", "startedAt", "durationSeconds", "source");
  private static final Set<String> RADIO_OPTIONAL = Set.of("queueEntryId");
  private static final Set<String> RADIO_SOURCE_REQUIRED = Set.of(
      "_id", "stationSequence", "trackId", "observedToken", "startedAt", "durationSeconds",
      "source");
  private static final Set<String> RADIO_SOURCE_OPTIONAL = Set.of(
      "queueEntryId", "version", CLASS_FIELD);
  private static final Set<String> QUEUE_TARGET_REQUIRED = Set.of("_id", "kind", "queue");
  private static final Set<String> RADIO_TARGET_REQUIRED = Set.of("_id", "kind", "radio");
  private static final Set<String> TARGET_OPTIONAL = Set.of("version", CLASS_FIELD);
  private static final Set<String> QUEUE_PAYLOAD_REQUIRED = Set.of("entries");
  private static final Set<String> NO_FIELDS = Set.of();
  private static final Set<String> ENTRY_REQUIRED = Set.of(
      "id", "trackId", "observedToken", "enqueuedByAccountId", "enqueuedAt");

  @Override public String id() { return "014-consolidate-music-runtime-state"; }
  @Override public String checksum() { return CHECKSUM; }
  @Override public String description() { return "Consolidate Music queue and radio runtime state"; }

  @Override
  public void apply(MongoTemplate mongo) {
    var queue = queueSource(requireSingleton(mongo, LEGACY_QUEUE));
    var radio = radioSource(requireSingleton(mongo, LEGACY_RADIO));
    List<Document> expected = List.of(queueTarget(queue), radioTarget(radio));
    List<Document> existing = destination(mongo);
    if (!existing.isEmpty()) {
      requireEquivalent(existing, queue, radio);
      return;
    }

    mongo.getCollection(MusicRuntimeStateDocument.COLLECTION).insertMany(expected);
    requireEquivalent(destination(mongo), queue, radio);
  }

  private static Document requireSingleton(MongoTemplate mongo, String collection) {
    List<Document> documents = mongo.findAll(Document.class, collection);
    if (documents.size() != 1) {
      throw new IllegalStateException("Music runtime source has unexpected cardinality.");
    }
    return documents.getFirst();
  }

  private static List<Document> destination(MongoTemplate mongo) {
    return mongo.findAll(Document.class, MusicRuntimeStateDocument.COLLECTION);
  }

  private static MusicQueueState queueSource(Document document) {
    String context = "Music queue source";
    requireShape(document, QUEUE_SOURCE_REQUIRED, QUEUE_SOURCE_OPTIONAL, context);
    requireIdentity(document, MusicQueueState.ID, context);
    requireClass(document, QUEUE_STATE_CLASS, context);
    return queueState(document, optionalVersion(document, context), context);
  }

  private static MusicRadioState radioSource(Document document) {
    String context = "Music radio source";
    requireShape(document, RADIO_SOURCE_REQUIRED, RADIO_SOURCE_OPTIONAL, context);
    requireIdentity(document, MusicRadioState.ID, context);
    requireClass(document, RADIO_STATE_CLASS, context);
    return radioState(document, optionalVersion(document, context), context);
  }

  private static MusicQueueState queueTargetState(Document document) {
    String context = "Music runtime destination queue";
    requireShape(document, QUEUE_TARGET_REQUIRED, TARGET_OPTIONAL, context);
    requireIdentity(document, MusicRuntimeStateDocument.QUEUE_ID, context);
    requireExactString(document, "kind", MusicRuntimeStateDocument.Kind.QUEUE.name(), context);
    requireClass(document, TARGET_CLASS, context);
    Document payload = requireDocument(document, "queue", context);
    requireShape(payload, QUEUE_PAYLOAD_REQUIRED, NO_FIELDS, context);
    return queueState(payload, optionalVersion(document, context), context);
  }

  private static MusicRadioState radioTargetState(Document document) {
    String context = "Music runtime destination radio";
    requireShape(document, RADIO_TARGET_REQUIRED, TARGET_OPTIONAL, context);
    requireIdentity(document, MusicRuntimeStateDocument.RADIO_ID, context);
    requireExactString(document, "kind", MusicRuntimeStateDocument.Kind.RADIO.name(), context);
    requireClass(document, TARGET_CLASS, context);
    Document payload = requireDocument(document, "radio", context);
    requireShape(payload, RADIO_REQUIRED, RADIO_OPTIONAL, context);
    return radioState(payload, optionalVersion(document, context), context);
  }

  private static MusicQueueState queueState(Document document, Long version, String context) {
    Object rawEntries = document.get("entries");
    if (!(rawEntries instanceof List<?> entries)) {
      throw malformed(context);
    }
    var parsedEntries = new ArrayList<MusicQueueState.Entry>(entries.size());
    for (Object rawEntry : entries) {
      if (!(rawEntry instanceof Document entry)) {
        throw malformed(context);
      }
      requireShape(entry, ENTRY_REQUIRED, NO_FIELDS, context);
      try {
        parsedEntries.add(new MusicQueueState.Entry(
            requireString(entry, "id", context),
            requireString(entry, "trackId", context),
            requireString(entry, "observedToken", context),
            requireString(entry, "enqueuedByAccountId", context),
            requireInstant(entry, "enqueuedAt", context)));
      } catch (IllegalArgumentException invalid) {
        throw malformed(context, invalid);
      }
    }
    try {
      return new MusicQueueState(MusicQueueState.ID, parsedEntries, version);
    } catch (IllegalArgumentException invalid) {
      throw malformed(context, invalid);
    }
  }

  private static MusicRadioState radioState(Document document, Long version, String context) {
    String rawSource = requireString(document, "source", context);
    MusicRadioState.Source source;
    try {
      source = MusicRadioState.Source.valueOf(rawSource);
    } catch (IllegalArgumentException invalid) {
      throw malformed(context, invalid);
    }
    String queueEntryId = document.containsKey("queueEntryId")
        ? requireString(document, "queueEntryId", context)
        : null;
    try {
      return new MusicRadioState(
          MusicRadioState.ID,
          requireIntegral(document, "stationSequence", context),
          requireString(document, "trackId", context),
          requireString(document, "observedToken", context),
          requireInstant(document, "startedAt", context),
          requireDouble(document, "durationSeconds", context),
          source,
          queueEntryId,
          version);
    } catch (IllegalArgumentException invalid) {
      throw malformed(context, invalid);
    }
  }

  private static Document queueTarget(MusicQueueState state) {
    var entries = state.entries().stream().map(entry -> new Document("id", entry.id())
        .append("trackId", entry.trackId())
        .append("observedToken", entry.observedToken())
        .append("enqueuedByAccountId", entry.enqueuedByAccountId())
        .append("enqueuedAt", Date.from(entry.enqueuedAt()))).toList();
    var document = new Document("_id", MusicRuntimeStateDocument.QUEUE_ID)
        .append("kind", MusicRuntimeStateDocument.Kind.QUEUE.name())
        .append("queue", new Document("entries", entries));
    return withVersion(document, state.version());
  }

  private static Document radioTarget(MusicRadioState state) {
    var payload = new Document("stationSequence", state.stationSequence())
        .append("trackId", state.trackId())
        .append("observedToken", state.observedToken())
        .append("startedAt", Date.from(state.startedAt()))
        .append("durationSeconds", state.durationSeconds())
        .append("source", state.source().name());
    if (state.queueEntryId() != null) {
      payload.append("queueEntryId", state.queueEntryId());
    }
    var document = new Document("_id", MusicRuntimeStateDocument.RADIO_ID)
        .append("kind", MusicRuntimeStateDocument.Kind.RADIO.name())
        .append("radio", payload);
    return withVersion(document, state.version());
  }

  private static Document withVersion(Document document, Long version) {
    if (version != null) {
      document.append("version", version);
    }
    return document;
  }

  private static void requireEquivalent(
      List<Document> documents, MusicQueueState queue, MusicRadioState radio) {
    if (documents.size() != 2) {
      throw new IllegalStateException("Music runtime destination is partial or unexpected.");
    }
    var byId = new HashMap<String, Document>();
    for (Document document : documents) {
      if (document == null) {
        throw malformed("Music runtime destination");
      }
      String targetId = requireString(document, "_id", "Music runtime destination");
      if (byId.put(targetId, document) != null) {
        throw new IllegalStateException("Music runtime destination has duplicate identities.");
      }
    }
    Document targetQueue = byId.get(MusicRuntimeStateDocument.QUEUE_ID);
    Document targetRadio = byId.get(MusicRuntimeStateDocument.RADIO_ID);
    if (targetQueue == null || targetRadio == null
        || !queue.equals(queueTargetState(targetQueue))
        || !radio.equals(radioTargetState(targetRadio))) {
      throw new IllegalStateException("Music runtime destination diverges from its sources.");
    }
  }

  private static void requireShape(
      Document document, Set<String> required, Set<String> optional, String context) {
    if (document == null || !document.keySet().containsAll(required)) {
      throw malformed(context);
    }
    for (String field : document.keySet()) {
      if (!required.contains(field) && !optional.contains(field)) {
        throw malformed(context);
      }
    }
  }

  private static void requireIdentity(Document document, String expected, String context) {
    if (!expected.equals(document.get("_id"))) {
      throw new IllegalStateException(context + " has an unexpected identity.");
    }
  }

  private static void requireClass(Document document, String expected, String context) {
    if (document.containsKey(CLASS_FIELD)) {
      requireExactString(document, CLASS_FIELD, expected, context);
    }
  }

  private static void requireExactString(
      Document document, String field, String expected, String context) {
    if (!expected.equals(document.get(field))) {
      throw malformed(context);
    }
  }

  private static String requireString(Document document, String field, String context) {
    Object value = document.get(field);
    if (!(value instanceof String string)) {
      throw malformed(context);
    }
    return string;
  }

  private static Document requireDocument(Document document, String field, String context) {
    Object value = document.get(field);
    if (!(value instanceof Document nested)) {
      throw malformed(context);
    }
    return nested;
  }

  private static Instant requireInstant(Document document, String field, String context) {
    Object value = document.get(field);
    if (!(value instanceof Date date)) {
      throw malformed(context);
    }
    return date.toInstant();
  }

  private static long requireIntegral(Document document, String field, String context) {
    Object value = document.get(field);
    if (value instanceof Long longValue) {
      return longValue;
    }
    if (value instanceof Integer integerValue) {
      return integerValue.longValue();
    }
    throw malformed(context);
  }

  private static double requireDouble(Document document, String field, String context) {
    Object value = document.get(field);
    if (!(value instanceof Double doubleValue)) {
      throw malformed(context);
    }
    return doubleValue;
  }

  private static Long optionalVersion(Document document, String context) {
    if (!document.containsKey("version")) {
      return null;
    }
    long version = requireIntegral(document, "version", context);
    if (version < 0) {
      throw malformed(context);
    }
    return version;
  }

  private static IllegalStateException malformed(String context) {
    return new IllegalStateException(context + " is malformed.");
  }

  private static IllegalStateException malformed(String context, IllegalArgumentException cause) {
    return new IllegalStateException(context + " is malformed.", cause);
  }
}
