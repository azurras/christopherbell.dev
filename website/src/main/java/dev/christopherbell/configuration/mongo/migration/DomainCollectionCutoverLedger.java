package dev.christopherbell.configuration.mongo.migration;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.persistence.IsolatedTestDatabase;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.core.env.Environment;
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
  private static final Pattern MIGRATION_ID = Pattern.compile("([0-9]{3})-[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final int STAGE_COUNT = DomainCollectionManifest.ALL_KINDS.size()
      + DomainCollectionManifest.ALL_COLLECTIONS.size();
  private static final Set<String> SOURCE_NAMES = DomainCollectionManifest.ALL_KINDS.stream()
      .flatMap(kind -> kind.legacySource().stream())
      .collect(Collectors.toUnmodifiableSet());
  private static final String NOT_ACTIVE = "Domain collection schema is not active.";
  private static final Set<String> MIGRATION_RUNNER_COLLECTIONS =
      Set.of(COLLECTION, "application_runtime");
  private final MongoTemplate mongo;
  private final Environment environment;

  public DomainCollectionCutoverLedger(MongoTemplate mongo, Environment environment) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  /** Requires a completed target cutover or a pristine isolated test database. */
  public void requireTargetSchemaReady() {
    if (IsolatedTestDatabase.isExplicitTestProfile(environment)) {
      IsolatedTestDatabase.requireConnection(environment, mongo);
      var hasActiveCutoverLedger =
          isActive(findStoredLedger(), DomainCollectionManifest.DIGEST);
      requireEmptyTestDatabase(hasActiveCutoverLedger);
      return;
    }

    var stored = findStoredLedger();
    if (isActive(stored, DomainCollectionManifest.DIGEST)) {
      return;
    }
    throw new IllegalStateException(NOT_ACTIVE);
  }

  void requireTargetActive(String expectedManifestDigest) {
    if (expectedManifestDigest == null || !SHA256.matcher(expectedManifestDigest).matches()) {
      throw new IllegalArgumentException("Domain collection manifest digest is invalid.");
    }
    var stored = findStoredLedger();
    if (!isActive(stored, expectedManifestDigest)) {
      throw new IllegalStateException(NOT_ACTIVE);
    }
  }

  private Document findStoredLedger() {
    var id = new Document("kind", KIND).append("legacyId", LEGACY_ID);
    var query = Query.query(Criteria.where("_id").is(id).and("_kind").is(KIND));
    return mongo.findOne(query, Document.class, COLLECTION);
  }

  private void requireEmptyTestDatabase(boolean hasActiveCutoverLedger) {
    var allowedCollections = new HashSet<>(DomainCollectionManifest.ALL_COLLECTIONS);
    allowedCollections.addAll(SOURCE_NAMES);
    var collectionNames = mongo.getCollectionNames();
    if (!allowedCollections.containsAll(collectionNames)) {
      throw new IllegalStateException("Test Mongo database contains an unapproved collection.");
    }

    for (var collectionName : collectionNames) {
      if (!MIGRATION_RUNNER_COLLECTIONS.contains(collectionName)
          && mongo.getCollection(collectionName).countDocuments() > 0) {
        throw new IllegalStateException("Test Mongo database contains application data.");
      }
    }
    var v015IsRunning = requireOnlyMigrationRecords(hasActiveCutoverLedger);
    requireOnlyMigrationLease(v015IsRunning);
  }

  private boolean requireOnlyMigrationRecords(boolean hasActiveCutoverLedger) {
    var migrationVersions = new HashSet<Integer>();
    boolean foundActiveCutoverLedger = false;
    boolean v015IsRunning = false;
    for (var record : mongo.findAll(Document.class, COLLECTION)) {
      if (hasActiveCutoverLedger
          && isActive(record, DomainCollectionManifest.DIGEST)) {
        if (foundActiveCutoverLedger) {
          throw new IllegalStateException("Test Mongo migration state is not a pristine bootstrap.");
        }
        foundActiveCutoverLedger = true;
        continue;
      }
      var recordId = record.get("_id", Document.class);
      var payload = record.get("payload", Document.class);
      var migrationVersion = migrationVersionFrom(recordId);
      if (migrationVersion < 1
          || !isAcceptedBootstrapMigrationRecord(record, recordId, payload, migrationVersion)
          || !migrationVersions.add(migrationVersion)) {
        throw new IllegalStateException("Test Mongo migration state is not a pristine bootstrap.");
      }
      if (migrationVersion == 15 && "RUNNING".equals(payload.getString("status"))) {
        v015IsRunning = true;
      }
    }
    if (hasActiveCutoverLedger != foundActiveCutoverLedger
        || !migrationVersionsAreContiguous(migrationVersions)) {
      throw new IllegalStateException("Test Mongo migration state is not a pristine bootstrap.");
    }
    return v015IsRunning;
  }

  private static boolean isAcceptedBootstrapMigrationRecord(
      Document record, Document recordId, Document payload, int migrationVersion) {
    if (recordId == null || payload == null
        || !"migration_record".equals(record.getString("_kind"))
        || !Integer.valueOf(1).equals(record.getInteger("schemaVersion"))
        || !"migration_record".equals(recordId.getString("kind"))
        || !(recordId.get("legacyId") instanceof String)
        || !(payload.get("checksum") instanceof String checksum)
        || !SHA256.matcher(checksum).matches()
        || !(payload.get("status") instanceof String status)) {
      return false;
    }
    if (migrationVersion == 15) {
      return DomainCollectionManifest.DIGEST.equals(checksum)
          && Set.of("RUNNING", "APPLIED").contains(status);
    }
    return "APPLIED".equals(status);
  }

  private static int migrationVersionFrom(Document recordId) {
    if (recordId == null || !(recordId.get("legacyId") instanceof String migrationId)) {
      return -1;
    }
    var migrationMatcher = MIGRATION_ID.matcher(migrationId);
    return migrationMatcher.matches() ? Integer.parseInt(migrationMatcher.group(1)) : -1;
  }

  private static boolean migrationVersionsAreContiguous(Set<Integer> migrationVersions) {
    var highestVersion = migrationVersions.stream().mapToInt(Integer::intValue).max().orElse(0);
    for (int version = 1; version <= highestVersion; version++) {
      if (!migrationVersions.contains(version)) {
        return false;
      }
    }
    return true;
  }

  private void requireOnlyMigrationLease(boolean v015IsRunning) {
    var leaseDocuments = mongo.findAll(Document.class, "application_runtime");
    if (leaseDocuments.size() > 1) {
      throw new IllegalStateException("Test Mongo lease state is not a pristine bootstrap.");
    }
    if (leaseDocuments.isEmpty()) {
      if (v015IsRunning) {
        throw new IllegalStateException("Test Mongo V015 migration requires its migration lease.");
      }
      return;
    }
    var lease = leaseDocuments.getFirst();
    var leaseId = lease.get("_id", Document.class);
    var leasePayload = lease.get("payload", Document.class);
    var ownerToken = leasePayload == null ? null : leasePayload.get("ownerToken");
    var fenceToken = leasePayload == null ? null : leasePayload.get("fenceToken");
    var expiresAt = leasePayload == null ? null : leasePayload.get("expiresAt");
    var hasOwnerToken = ownerToken instanceof String owner && !owner.isBlank();
    if (!"application_lease".equals(lease.getString("_kind"))
        || !Integer.valueOf(1).equals(lease.getInteger("schemaVersion"))
        || leaseId == null
        || !"application_lease".equals(leaseId.getString("kind"))
        || !"application-migrations".equals(leaseId.getString("legacyId"))
        || leasePayload == null
        || !(fenceToken instanceof Number fenceNumber)
        || fenceNumber.longValue() < 1
        || !(expiresAt instanceof Date)
        || (ownerToken != null && !hasOwnerToken)
        || (v015IsRunning && !hasOwnerToken)) {
      throw new IllegalStateException("Test Mongo lease state is not a pristine bootstrap.");
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
    return exactMetrics(metrics)
        && exactSources(sources)
            .filter(presentSources -> countsMatch(presentSources, publishIndex, dropIndex, legacyDropped))
            .isPresent();
  }

  /** Whether the recorded publish and drop progress fits the sources the cutover found. */
  private static boolean countsMatch(
      List<String> presentSources,
      int publishIndex,
      int dropIndex,
      boolean legacyDropped
  ) {
    var publicationCount = DomainCollectionManifest.ALL_COLLECTIONS.size()
        + presentSources.stream().filter(DomainCollectionManifest.ALL_COLLECTIONS::contains).count();
    var dropCount = presentSources.stream()
        .filter(source -> !DomainCollectionManifest.ALL_COLLECTIONS.contains(source)).count()
        + 2L + presentSources.stream().filter(DomainCollectionManifest.ALL_COLLECTIONS::contains).count();
    return publishIndex == publicationCount
        && dropIndex >= 0 && dropIndex <= dropCount
        && legacyDropped == (dropIndex == dropCount);
  }

  /** The recorded sources when every one is a known name, listed once in sorted order. */
  private static Optional<List<String>> exactSources(List<?> values) {
    var sources = new ArrayList<String>();
    for (var value : values) {
      if (!(value instanceof String source) || !SOURCE_NAMES.contains(source)) {
        return Optional.empty();
      }
      sources.add(source);
    }
    var sorted = sources.stream().distinct().sorted().toList();
    return sources.equals(sorted) ? Optional.of(sources) : Optional.empty();
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
