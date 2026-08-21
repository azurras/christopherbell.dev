package dev.christopherbell.configuration.persistence.migration;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.nor;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Sorts.ascending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Collation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Direct, bounded, read-only access to consolidated Mongo domain envelopes. */
public final class MongoMigrationSourceReader
    implements MigrationSourceReader, MigrationSourceCatalogGuard {
  private static final Set<String> ENVELOPE_KEYS =
      Set.of("_id", "_kind", "schemaVersion", "payload");
  private static final Set<String> ID_KEYS = Set.of("kind", "legacyId");
  private static final String SPRING_TYPE_METADATA = "_class";
  private static final Map<String, Set<String>> LEGACY_PERSISTED_CLASSES = Map.of(
      "scheduled_collector_run", Set.of(
          "dev.christopherbell.configuration.mongo.lease.ScheduledCollectorRun"),
      "vote", Set.of(
          "dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRating"));
  private static final int MAX_CURSOR_BYTES = 16 * 1024;
  private final MongoClient client;

  public MongoMigrationSourceReader(MongoClient client) {
    this.client = client;
  }

  @Override
  public SourceBatch readAfter(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      String cursor,
      int limit) {
    if (limit < 1 || limit > context.request().batchSize()) {
      throw invalid();
    }
    requireValidIdentifierTypes(context, kind);
    if ("string-or-object-id".equals(kind.identifierType())) {
      return readMixedIdentifiers(context, kind, cursor, limit);
    }
    var filter = and(
        eq("_kind", kind.sourceKind()));
    String previousIdentifier = null;
    if (cursor != null) {
      var decodedIdentifier = decodeCursor(kind, cursor);
      previousIdentifier = canonicalIdentifier(kind.identifierType(), decodedIdentifier);
      filter = and(filter, gt("_id.legacyId", decodedIdentifier));
    }
    var documents = new ArrayList<MigrationSourceDocument>();
    String lastCursor = null;
    for (var envelope : client.getDatabase(context.sourceIdentity().database())
        .getCollection(kind.sourceCollection())
        .find(filter)
        .collation(Collation.builder().locale("simple").build())
        .sort(ascending("_id.legacyId"))
        .limit(limit)) {
      var converted = convert(kind, envelope);
      if (previousIdentifier != null
          && MongoSimpleStringOrder.compare(
              converted.document().sourceId(), previousIdentifier) <= 0) {
        throw invalid();
      }
      documents.add(converted.document());
      lastCursor = converted.cursor();
      previousIdentifier = converted.document().sourceId();
    }
    return new SourceBatch(documents, lastCursor);
  }

  private SourceBatch readMixedIdentifiers(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      String cursor,
      int limit) {
    String previousIdentifier = null;
    if (cursor != null) {
      previousIdentifier = canonicalIdentifier(kind.identifierType(), decodeCursor(kind, cursor));
    } else {
      requireNoMixedIdentifierCollision(context, kind);
    }
    var pipeline = new ArrayList<Document>();
    pipeline.add(new Document("$match", new Document("_kind", kind.sourceKind())));
    pipeline.add(new Document("$set", new Document(
        "_migrationSourceId", new Document("$toString", "$_id.legacyId"))));
    if (previousIdentifier != null) {
      pipeline.add(new Document("$match", new Document(
          "_migrationSourceId", new Document("$gt", previousIdentifier))));
    }
    pipeline.add(new Document("$sort", new Document("_migrationSourceId", 1)));
    pipeline.add(new Document("$limit", limit));
    pipeline.add(new Document("$unset", "_migrationSourceId"));
    var documents = new ArrayList<MigrationSourceDocument>();
    String lastCursor = null;
    for (var envelope : client.getDatabase(context.sourceIdentity().database())
        .getCollection(kind.sourceCollection())
        .aggregate(pipeline)
        .collation(Collation.builder().locale("simple").build())) {
      var converted = convert(kind, envelope);
      if (previousIdentifier != null
          && MongoSimpleStringOrder.compare(
              converted.document().sourceId(), previousIdentifier) <= 0) {
        throw invalid();
      }
      documents.add(converted.document());
      lastCursor = converted.cursor();
      previousIdentifier = converted.document().sourceId();
    }
    return new SourceBatch(documents, lastCursor);
  }

  private void requireValidIdentifierTypes(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    var field = "_id.legacyId";
    var allowed = switch (kind.identifierType()) {
      case "string", "uuid-string" -> com.mongodb.client.model.Filters.type(
          field, BsonType.STRING);
      case "object-id" -> com.mongodb.client.model.Filters.type(field, BsonType.OBJECT_ID);
      case "string-or-object-id" -> or(
          com.mongodb.client.model.Filters.type(field, BsonType.STRING),
          com.mongodb.client.model.Filters.type(field, BsonType.OBJECT_ID));
      default -> throw invalid();
    };
    var invalid = client.getDatabase(context.sourceIdentity().database())
        .getCollection(kind.sourceCollection())
        .find(and(eq("_kind", kind.sourceKind()), nor(allowed)))
        .limit(1)
        .first();
    if (invalid != null) {
      throw invalid();
    }
  }

  private void requireNoMixedIdentifierCollision(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    var collision = client.getDatabase(context.sourceIdentity().database())
        .getCollection(kind.sourceCollection())
        .aggregate(java.util.List.of(
            new Document("$match", new Document("_kind", kind.sourceKind())),
            new Document("$group", new Document("_id", new Document(
                "$toString", "$_id.legacyId")).append("count", new Document("$sum", 1))),
            new Document("$match", new Document("count", new Document("$gt", 1))),
            new Document("$limit", 1)))
        .first();
    if (collision != null) {
      throw invalid();
    }
  }

  @Override
  public void requireOnlyCatalogKinds(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog catalog) {
    var byCollection = catalog.kinds().stream().collect(java.util.stream.Collectors.groupingBy(
        PostgresqlMigrationCatalog.Kind::sourceCollection,
        java.util.stream.Collectors.mapping(
            PostgresqlMigrationCatalog.Kind::sourceKind,
            java.util.stream.Collectors.toUnmodifiableSet())));
    for (var entry : byCollection.entrySet()) {
      var unknown = client.getDatabase(context.sourceIdentity().database())
          .getCollection(entry.getKey())
          .find(com.mongodb.client.model.Filters.nin("_kind", entry.getValue()))
          .limit(1)
          .first();
      if (unknown != null) {
        throw invalid();
      }
    }
  }

  private static Converted convert(
      PostgresqlMigrationCatalog.Kind kind, Document envelope) {
    if (!envelope.keySet().equals(ENVELOPE_KEYS)
        || !kind.sourceKind().equals(envelope.getString("_kind"))
        || !Integer.valueOf(kind.sourceSchemaVersion()).equals(envelope.getInteger("schemaVersion"))
        || !(envelope.get("_id") instanceof Document id)
        || !id.keySet().equals(ID_KEYS)
        || !kind.sourceKind().equals(id.getString("kind"))
        || id.get("legacyId") == null
        || !(envelope.get("payload") instanceof Document payload)) {
      throw invalid();
    }
    var legacyId = id.get("legacyId");
    if (!validIdentifierType(kind.identifierType(), legacyId)) {
      throw invalid();
    }
    var values = new LinkedHashMap<String, Object>();
    payload.forEach((key, value) -> {
      if (!SPRING_TYPE_METADATA.equals(key)) {
        values.put(key, value);
      } else if (!(value instanceof String typeName)
          || typeName.isBlank()
          || !allowedPersistedClasses(kind).contains(typeName)) {
        throw invalid();
      }
    });
    return new Converted(
        new MigrationSourceDocument(
            kind.sourceKind(), kind.sourceSchemaVersion(),
            canonicalIdentifier(kind.identifierType(), legacyId), values),
        encodeCursor(legacyId));
  }

  private static Set<String> allowedPersistedClasses(PostgresqlMigrationCatalog.Kind kind) {
    var allowed = new java.util.HashSet<String>();
    allowed.add(kind.sourceOwner());
    allowed.addAll(LEGACY_PERSISTED_CLASSES.getOrDefault(kind.sourceKind(), Set.of()));
    return Set.copyOf(allowed);
  }

  private static boolean validIdentifierType(String identifierType, Object legacyId) {
    return switch (identifierType) {
      case "string" -> legacyId instanceof String;
      case "uuid-string" -> {
        if (!(legacyId instanceof String text)) {
          yield false;
        }
        try {
          yield java.util.UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException failure) {
          yield false;
        }
      }
      case "object-id" -> legacyId instanceof ObjectId;
      case "string-or-object-id" -> legacyId instanceof String || legacyId instanceof ObjectId;
      default -> false;
    };
  }

  private static String canonicalIdentifier(String identifierType, Object legacyId) {
    return switch (identifierType) {
      case "string", "uuid-string" -> (String) legacyId;
      case "object-id" -> ((ObjectId) legacyId).toHexString();
      case "string-or-object-id" -> legacyId instanceof ObjectId objectId
          ? objectId.toHexString()
          : (String) legacyId;
      default -> throw invalid();
    };
  }

  private static String encodeCursor(Object legacyId) {
    var bytes = new Document("value", legacyId).toJson().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_CURSOR_BYTES) {
      throw invalid();
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static Object decodeCursor(
      PostgresqlMigrationCatalog.Kind kind, String cursor) {
    try {
      var bytes = Base64.getUrlDecoder().decode(cursor);
      if (bytes.length > MAX_CURSOR_BYTES) {
        throw invalid();
      }
      var parsed = Document.parse(new String(bytes, StandardCharsets.UTF_8));
      var value = parsed.get("value");
      if (!parsed.keySet().equals(Set.of("value"))
          || !validIdentifierType(kind.identifierType(), value)) {
        throw invalid();
      }
      return value;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("PostgreSQL migration Mongo source envelope is invalid.");
  }

  private record Converted(MigrationSourceDocument document, String cursor) {}
}
