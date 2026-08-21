package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Startup-only validation of the durable domain-collection cutover ledger. */
@MongoPersistence
@Component
public class DomainCollectionCutoverLedger {
  public static final String LEGACY_ID = "domain-collection-cutover";
  private static final String COLLECTION = "application_migrations";
  private static final String KIND = "domain_collection_cutover";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final List<String> ENVELOPE_FIELDS =
      List.of("_id", "_kind", "schemaVersion", "payload");
  private static final List<String> ID_FIELDS = List.of("kind", "legacyId");
  private static final List<String> PAYLOAD_FIELDS = List.of(
      "state", "manifestDigest", "ownerToken", "release", "backupIdentity", "evidenceDigest",
      "revision", "stageIndex", "publishIndex", "dropIndex", "completed", "legacyDropped",
      "intent", "presentSources", "expectedKindMetrics");
  private static final List<String> METRIC_FIELDS = List.of("kind", "count", "checksum");
  private static final Pattern OWNER = Pattern.compile("[0-9a-f]{32}");
  private static final Pattern RELEASE = Pattern.compile("[0-9a-f]{40}");
  private static final int STAGE_COUNT = DomainCollectionManifest.ALL_KINDS.size()
      + DomainCollectionManifest.ALL_COLLECTIONS.size();
  private static final Set<String> SOURCE_NAMES = DomainCollectionManifest.ALL_KINDS.stream()
      .flatMap(kind -> kind.legacySource().stream())
      .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
    if (!List.copyOf(payload.keySet()).equals(PAYLOAD_FIELDS)
        || !"TARGET_ACTIVE".equals(payload.get("state"))
        || !expectedManifestDigest.equals(payload.get("manifestDigest"))
        || !(payload.get("ownerToken") instanceof String owner) || !OWNER.matcher(owner).matches()
        || !(payload.get("release") instanceof String release)
        || !RELEASE.matcher(release).matches()
        || !(payload.get("backupIdentity") instanceof String backup) || !SHA256.matcher(backup).matches()
        || !(payload.get("evidenceDigest") instanceof String evidence)
        || !SHA256.matcher(evidence).matches()
        || !(payload.get("revision") instanceof Integer revision) || revision < 1
        || !Integer.valueOf(STAGE_COUNT).equals(payload.get("stageIndex"))
        || !(payload.get("publishIndex") instanceof Integer publishIndex)
        || !(payload.get("dropIndex") instanceof Integer dropIndex)
        || !Boolean.TRUE.equals(payload.get("completed"))
        || !(payload.get("legacyDropped") instanceof Boolean legacyDropped)
        || payload.get("intent") != null
        || !(payload.get("presentSources") instanceof List<?> sources)
        || !(payload.get("expectedKindMetrics") instanceof List<?> metrics)) {
      return false;
    }
    var presentSources = exactSources(sources);
    if (presentSources == null || !exactMetrics(metrics)) {
      return false;
    }
    var publicationCount = DomainCollectionManifest.ALL_COLLECTIONS.size()
        + presentSources.stream().filter(DomainCollectionManifest.ALL_COLLECTIONS::contains).count();
    var dropCount = presentSources.stream()
        .filter(source -> !DomainCollectionManifest.ALL_COLLECTIONS.contains(source)).count()
        + 2L + presentSources.stream().filter(DomainCollectionManifest.ALL_COLLECTIONS::contains).count();
    return publishIndex.longValue() == publicationCount
        && dropIndex >= 0 && dropIndex.longValue() <= dropCount
        && legacyDropped == (dropIndex.longValue() == dropCount);
  }

  private static List<String> exactSources(List<?> values) {
    var sources = new ArrayList<String>();
    for (var value : values) {
      if (!(value instanceof String source) || !SOURCE_NAMES.contains(source)) {
        return null;
      }
      sources.add(source);
    }
    var sorted = sources.stream().distinct().sorted().toList();
    return sources.equals(sorted) ? sources : null;
  }

  private static boolean exactMetrics(List<?> values) {
    if (values.size() != DomainCollectionManifest.ALL_KINDS.size()) {
      return false;
    }
    for (int index = 0; index < values.size(); index++) {
      if (!(values.get(index) instanceof Document metric)
          || !List.copyOf(metric.keySet()).equals(METRIC_FIELDS)
          || !DomainCollectionManifest.ALL_KINDS.get(index).kind().equals(metric.get("kind"))
          || !(metric.get("count") instanceof Integer count) || count < 0
          || !(metric.get("checksum") instanceof String checksum)
          || !SHA256.matcher(checksum).matches()) {
        return false;
      }
    }
    return true;
  }
}
