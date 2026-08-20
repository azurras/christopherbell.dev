package dev.christopherbell.configuration.persistence.migration;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Sorts.ascending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Collation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bson.Document;

/** Direct, bounded, read-only access to consolidated Mongo domain envelopes. */
public final class MongoMigrationSourceReader
    implements MigrationSourceReader, MigrationSourceCatalogGuard {
  private static final Set<String> ENVELOPE_KEYS =
      Set.of("_id", "_kind", "schemaVersion", "payload");
  private static final Set<String> ID_KEYS = Set.of("kind", "legacyId");
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
    var filter = and(
        eq("_kind", kind.sourceKind()));
    String previousIdentifier = null;
    if (cursor != null) {
      previousIdentifier = decodeCursor(kind, cursor);
      filter = and(filter, gt("_id.legacyId", previousIdentifier));
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
    payload.forEach(values::put);
    return new Converted(
        new MigrationSourceDocument(
            kind.sourceKind(), kind.sourceSchemaVersion(), (String) legacyId, values),
        encodeCursor(legacyId));
  }

  private static boolean validIdentifierType(String identifierType, Object legacyId) {
    if (!(legacyId instanceof String text)) {
      return false;
    }
    return switch (identifierType) {
      case "string" -> true;
      case "uuid-string" -> {
        try {
          yield java.util.UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException failure) {
          yield false;
        }
      }
      default -> false;
    };
  }

  private static String encodeCursor(Object legacyId) {
    var bytes = new Document("value", legacyId).toJson().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_CURSOR_BYTES) {
      throw invalid();
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String decodeCursor(
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
      return (String) value;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("PostgreSQL migration Mongo source envelope is invalid.");
  }

  private record Converted(MigrationSourceDocument document, String cursor) {}
}
