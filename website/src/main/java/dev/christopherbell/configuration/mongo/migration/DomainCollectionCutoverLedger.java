package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Startup-only validation of the durable domain-collection cutover ledger. */
@Component
public final class DomainCollectionCutoverLedger {
  public static final String LEGACY_ID = "domain-collection-cutover";
  private static final String COLLECTION = "application_migrations";
  private static final String KIND = "domain_collection_cutover";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final List<String> ENVELOPE_FIELDS =
      List.of("_id", "_kind", "schemaVersion", "payload");
  private static final List<String> ID_FIELDS = List.of("kind", "legacyId");
  private static final String NOT_ACTIVE = "Domain collection schema is not active.";

  private final MongoTemplate mongo;

  public DomainCollectionCutoverLedger(MongoTemplate mongo) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
  }

  /** Fails closed unless the exact target manifest completed publication. */
  public void requireTargetActive() {
    requireTargetActive(DomainCollectionManifest.DIGEST);
  }

  void requireTargetActive(String expectedManifestDigest) {
    if (expectedManifestDigest == null || !SHA256.matcher(expectedManifestDigest).matches()) {
      throw new IllegalArgumentException("Domain collection manifest digest is invalid.");
    }
    var id = new Document("kind", KIND).append("legacyId", LEGACY_ID);
    var query = Query.query(Criteria.where("_id").is(id).and("_kind").is(KIND));
    var stored = mongo.findOne(query, Document.class, COLLECTION);
    if (!isActive(stored, expectedManifestDigest)) {
      throw new IllegalStateException(NOT_ACTIVE);
    }
  }

  private static boolean isActive(Document stored, String expectedManifestDigest) {
    if (stored == null || !List.copyOf(stored.keySet()).equals(ENVELOPE_FIELDS)
        || !KIND.equals(stored.getString("_kind"))
        || !Integer.valueOf(1).equals(stored.getInteger("schemaVersion"))) {
      return false;
    }
    var id = stored.get("_id", Document.class);
    var payload = stored.get("payload", Document.class);
    if (id == null || payload == null || !List.copyOf(id.keySet()).equals(ID_FIELDS)
        || !KIND.equals(id.getString("kind")) || !LEGACY_ID.equals(id.get("legacyId"))) {
      return false;
    }
    var revision = payload.get("revision");
    return "TARGET_ACTIVE".equals(payload.getString("state"))
        && Boolean.TRUE.equals(payload.getBoolean("completed"))
        && expectedManifestDigest.equals(payload.getString("manifestDigest"))
        && revision instanceof Number number
        && number.longValue() > 0
        && number.doubleValue() == number.longValue();
  }
}
