package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.util.ClassUtils;

class MongoCollectionCatalogTest {
  private static final Path CATALOG =
      repositoryRoot().resolve("docs/operations/mongodb-collection-catalog.md");
  private static final String CATALOG_HEADER =
      "| Physical name | Logical name | Owner and mapping | Role | Cardinality and retention | Index contract | Sensitivity | Status |";
  private static final String CATALOG_SEPARATOR =
      "| --- | --- | --- | --- | --- | --- | --- | --- |";
  private static final String MANUAL_PROVENANCE_HEADER =
      "| Manual owner type | Physical names |";
  private static final String MANUAL_PROVENANCE_SEPARATOR = "| --- | --- |";
  private static final Pattern PHYSICAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern CODE_TOKEN = Pattern.compile("`([^`]+)`");
  private static final Set<String> VALID_ROLES = Set.of(
      "audit", "cache", "edge", "entity", "event-history", "job", "lease",
      "preference", "singleton-state");
  private static final Set<String> VALID_SENSITIVITY = Set.of(
      "audit", "confidential", "internal", "public-reference", "security", "user");
  private static final Set<String> VALID_STATUSES = Set.of(
      "active", "legacy-named", "rollback-retained", "orphan-candidate", "system-managed");
  private static final Set<String> SOURCE_BACKED_STATUSES = Set.of(
      "active", "legacy-named", "rollback-retained");
  private static final Map<String, Set<String>> APPROVED_SHARED_DOCUMENT_MAPPINGS = Map.of(
      "vehicle_import_state", Set.of(
          "dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState",
          "dev.christopherbell.vehicle.randomvin.model.RandomVinImportState"));
  private static final Map<String, Set<String>> MANUAL_COLLECTIONS_BY_OWNER = Map.ofEntries(
      manualOwner("dev.christopherbell.configuration.mongo.migration.MigrationStateStore",
          "application_migrations"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V001EnsureMigrationInfrastructure",
          "application_leases", "application_migrations"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V002EnsureRestaurantImportPreviewIndexes",
          "restaurant_import_previews"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V003EnsureVinPreviewCollectorIndexes",
          "post_link_preview_cache", "scheduled_collector_runs", "vehicle_vin_decode_cache"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V004EnsureVoidDiscoveryIndexes",
          "posts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V005EnsureVoidPeopleDiscoveryIndexes",
          "account_trust_relationships", "posts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V006EnsureFederationActorIndex",
          "accounts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V007EnsureFederationOutboundIndexes",
          "federation_delivery_jobs", "posts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V008RemoveAccountApprovalFields",
          "accounts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V009MoveSocialRelationshipsToEdges",
          "account_follows", "accounts", "post_likes", "posts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V010BackfillPostExpirationMetrics",
          "posts"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V011HardenWhatsForLunchData",
          "whatsforlunch", "whatsforlunch_sessions"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V012RetainSharedFolderWork",
          "shared_folder_media_jobs", "shared_folder_radio", "shared_folder_upload_sessions"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V013ConvertRestaurantRatingsToVotes",
          "whatsforlunch_ratings"),
      manualOwner("dev.christopherbell.configuration.mongo.migration.V014ConsolidateMusicRuntimeState",
          "music_queue_state", "music_radio_state", "music_runtime_state"),
      manualOwner("dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService",
          "shared_folder_audit"),
      manualOwner("dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore",
          "shared_folder_maintenance_leases"));
  private static final Set<String> MONGO_TEMPLATE_INFRASTRUCTURE_OWNERS = Set.of(
      "dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsService",
      "dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory",
      "dev.christopherbell.configuration.mongo.domain.MongoKindScopedOperations",
      "dev.christopherbell.configuration.mongo.migration.ApplicationMigration",
      "dev.christopherbell.configuration.mongo.migration.MongoMigrationRunner");

  @Test
  void catalogEntriesAreCompleteAndValid() throws IOException {
    assertCatalogEntriesAreValid(readCatalog());
  }

  @Test
  void catalogValidationAcceptsTheFullStatusVocabulary() {
    var entries = VALID_STATUSES.stream()
        .map(status -> new CatalogEntry(
            status.replace('-', '_'),
            "Status fixture",
            "test and `StatusDocument`",
            "entity",
            "One fixture",
            "`_id`",
            "internal",
            status))
        .toList();

    assertCatalogEntriesAreValid(entries);
  }

  private static void assertCatalogEntriesAreValid(List<CatalogEntry> entries) {
    assertThat(entries).extracting(CatalogEntry::physicalName).doesNotHaveDuplicates();
    assertThat(entries).allSatisfy(entry -> {
      assertThat(entry.physicalName()).matches(PHYSICAL_NAME);
      assertThat(entry.logicalName()).isNotBlank();
      assertThat(entry.ownerAndMapping()).isNotBlank();
      assertThat(entry.role()).isIn(VALID_ROLES);
      assertThat(entry.cardinalityAndRetention()).isNotBlank();
      assertThat(entry.indexContract()).isNotBlank();
      assertThat(entry.sensitivity()).isIn(VALID_SENSITIVITY);
      assertThat(entry.status()).isIn(VALID_STATUSES);
    });
  }

  @Test
  void everySourceBackedCollectionIsCatalogedSeparatelyFromClassifiedEntries() throws IOException {
    var expected = sourceBackedCollectionNames();

    assertThat(expected).hasSize(52);
    assertSourceCoverage(readCatalog(), expected);
  }

  @Test
  void musicRuntimeStateLifecycleHasExactStatusMembership() throws IOException {
    var statusesByPhysicalName = readCatalog().stream()
        .collect(Collectors.toMap(CatalogEntry::physicalName, CatalogEntry::status));

    assertThat(statusesByPhysicalName)
        .containsEntry("music_queue_state", "rollback-retained")
        .containsEntry("music_radio_state", "rollback-retained")
        .containsEntry("music_runtime_state", "active");
    assertThat(statusesByPhysicalName.entrySet().stream()
        .filter(entry -> entry.getValue().equals("rollback-retained"))
        .map(Map.Entry::getKey))
        .containsExactlyInAnyOrder("music_queue_state", "music_radio_state");
  }

  @Test
  void sourceCoverageAllowsSeparatelyClassifiedNonSourceEntries() {
    var entries = List.of(
        catalogEntry("mapped_source", "active"),
        catalogEntry("reviewed_extra", "orphan-candidate"),
        catalogEntry("system_namespace", "system-managed"));

    assertSourceCoverage(entries, Set.of("mapped_source"));
  }

  private static void assertSourceCoverage(
      List<CatalogEntry> entries, Set<String> expectedSourceNames) {
    assertThat(entries.stream()
        .filter(entry -> SOURCE_BACKED_STATUSES.contains(entry.status()))
        .map(CatalogEntry::physicalName))
        .containsExactlyInAnyOrderElementsOf(expectedSourceNames);
    assertThat(entries.stream()
        .filter(entry -> !SOURCE_BACKED_STATUSES.contains(entry.status()))
        .map(CatalogEntry::physicalName))
        .doesNotContainAnyElementsOf(expectedSourceNames);
  }

  @Test
  void catalogRecordsEveryMappedAndManualOwner() throws IOException {
    var entriesByName = readCatalog().stream()
        .collect(Collectors.toMap(CatalogEntry::physicalName, Function.identity()));
    mappedDocumentOwners().forEach((collection, ownerTypes) -> {
      var entry = entriesByName.get(collection);
      assertThat(entry).as("catalog entry for %s", collection).isNotNull();
      var documentedTypes = codeTokens(entry.ownerAndMapping());
      assertThat(documentedTypes)
          .as("mapped owner types for %s", collection)
          .containsAll(ownerTypes.stream().map(MongoCollectionCatalogTest::simpleName).toList());
    });

    assertThat(readManualProvenance()).isEqualTo(MANUAL_COLLECTIONS_BY_OWNER);
    var expectedMongoTemplateOwners = new TreeSet<>(MANUAL_COLLECTIONS_BY_OWNER.keySet());
    expectedMongoTemplateOwners.addAll(MONGO_TEMPLATE_INFRASTRUCTURE_OWNERS);
    assertThat(mongoTemplateOwnerTypes()).containsExactlyInAnyOrderElementsOf(
        expectedMongoTemplateOwners);
  }

  @Test
  void onlyTheExactVehicleDocumentPairMayShareACollection() {
    assertSharedDocumentMappings(mappedDocumentOwners(), APPROVED_SHARED_DOCUMENT_MAPPINGS);
  }

  @Test
  void sharedMappingValidationRejectsAnUndocumentedPair() {
    var owners = Map.of(
        "unexpected_shared", Set.of("example.FirstDocument", "example.SecondDocument"));

    assertThatThrownBy(() -> assertSharedDocumentMappings(owners, Map.of()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("shared document mappings");
  }

  @Test
  void sharedMappingValidationRejectsABroadenedApprovedPair() {
    var owners = Map.of("vehicle_import_state", Set.of(
        "dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState",
        "dev.christopherbell.vehicle.randomvin.model.RandomVinImportState",
        "example.UnreviewedImportState"));

    assertThatThrownBy(() -> assertSharedDocumentMappings(
        owners, APPROVED_SHARED_DOCUMENT_MAPPINGS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("shared document mappings");
  }

  @Test
  void catalogRejectsMalformedDataRow(@TempDir Path temporaryDirectory) throws IOException {
    var catalog = temporaryDirectory.resolve("catalog.md");
    Files.writeString(catalog, """
        | Physical name | Logical name | Owner and mapping | Role | Cardinality and retention | Index contract | Sensitivity | Status |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | `valid_collection` | Valid collection | test | entity | One document | `_id` | internal | active |
        | malformed_collection | Malformed collection | test | entity | One document | `_id` | internal | active |
        """);

    assertThatThrownBy(() -> readCatalog(catalog))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("catalog table row 4");
  }

  private static Map<String, Set<String>> mappedDocumentOwners() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
    var classLoader = MongoCollectionCatalogTest.class.getClassLoader();
    var owners = new HashMap<String, Set<String>>();
    for (var candidate : scanner.findCandidateComponents("dev.christopherbell")) {
      var className = candidate.getBeanClassName();
      assertThat(className).isNotBlank();
      var documentType = ClassUtils.resolveClassName(className, classLoader);
      var document = AnnotatedElementUtils.findMergedAnnotation(documentType, Document.class);
      assertThat(document).as("@Document on %s", className).isNotNull();
      assertThat(document.collection()).as("explicit collection for %s", className).isNotBlank();
      owners.computeIfAbsent(document.collection(), ignored -> new TreeSet<>()).add(className);
    }
    return owners.entrySet().stream().collect(Collectors.toUnmodifiableMap(
        Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
  }

  private static Set<String> sourceBackedCollectionNames() {
    var names = new TreeSet<>(mappedDocumentOwners().keySet());
    DomainCollectionManifest.ALL_KINDS.stream()
        .flatMap(kind -> kind.legacySource().stream())
        .forEach(names::add);
    MANUAL_COLLECTIONS_BY_OWNER.values().forEach(names::addAll);
    return names;
  }

  private static void assertSharedDocumentMappings(
      Map<String, Set<String>> ownersByCollection,
      Map<String, Set<String>> approvedSharedMappings
  ) {
    var actualSharedMappings = ownersByCollection.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    assertThat(actualSharedMappings)
        .as("shared document mappings")
        .isEqualTo(approvedSharedMappings);
  }

  private static List<CatalogEntry> readCatalog() throws IOException {
    return readCatalog(CATALOG);
  }

  private static List<CatalogEntry> readCatalog(Path catalog) throws IOException {
    var lines = Files.readAllLines(catalog);
    var headerIndex = lines.indexOf(CATALOG_HEADER);
    assertThat(headerIndex).as("catalog header").isGreaterThanOrEqualTo(0);
    assertThat(lines).as("catalog separator").hasSizeGreaterThan(headerIndex + 1);
    assertThat(lines.get(headerIndex + 1)).as("catalog separator").isEqualTo(CATALOG_SEPARATOR);

    var entries = new ArrayList<CatalogEntry>();
    for (var index = headerIndex + 2; index < lines.size(); index++) {
      var line = lines.get(index);
      if (line.isBlank()) {
        return entries;
      }
      assertThat(line).as("catalog table row %s", index + 1).startsWith("|").endsWith("|");
      entries.add(parseCatalogRow(line, index + 1));
    }
    return entries;
  }

  private static Map<String, Set<String>> readManualProvenance() throws IOException {
    var lines = Files.readAllLines(CATALOG);
    var headerIndex = lines.indexOf(MANUAL_PROVENANCE_HEADER);
    assertThat(headerIndex).as("manual provenance header").isGreaterThanOrEqualTo(0);
    assertThat(lines).as("manual provenance separator").hasSizeGreaterThan(headerIndex + 1);
    assertThat(lines.get(headerIndex + 1))
        .as("manual provenance separator")
        .isEqualTo(MANUAL_PROVENANCE_SEPARATOR);

    var provenance = new HashMap<String, Set<String>>();
    for (var index = headerIndex + 2; index < lines.size(); index++) {
      var line = lines.get(index);
      if (line.isBlank()) {
        break;
      }
      var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1))
          .map(String::trim)
          .toList();
      assertThat(cells).as("manual provenance row %s: %s", index + 1, line).hasSize(2);
      var owner = unquoteCode(cells.get(0), index + 1);
      var collections = new TreeSet<>(codeTokens(cells.get(1)));
      assertThat(collections).as("manual provenance collections for %s", owner).isNotEmpty();
      assertThat(provenance.put(owner, Set.copyOf(collections)))
          .as("duplicate manual provenance owner %s", owner)
          .isNull();
    }
    return Map.copyOf(provenance);
  }

  private static Set<String> mongoTemplateOwnerTypes() throws IOException {
    var sourceRoot = repositoryRoot().resolve("website/src/main/java");
    try (var files = Files.walk(sourceRoot)) {
      return files
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> {
            try {
              return Files.readString(path).contains(
                  "import org.springframework.data.mongodb.core.MongoTemplate;");
            } catch (IOException failure) {
              throw new IllegalStateException("Cannot read " + path, failure);
            }
          })
          .map(sourceRoot::relativize)
          .map(Path::toString)
          .map(path -> path.substring(0, path.length() - ".java".length()))
          .map(path -> path.replace('\\', '.').replace('/', '.'))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static Set<String> codeTokens(String value) {
    var matcher = CODE_TOKEN.matcher(value);
    var tokens = new TreeSet<String>();
    while (matcher.find()) {
      tokens.add(matcher.group(1));
    }
    return tokens;
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private static Map.Entry<String, Set<String>> manualOwner(
      String ownerType, String... collectionNames) {
    return Map.entry(ownerType, Set.of(collectionNames));
  }

  private static CatalogEntry catalogEntry(String physicalName, String status) {
    return new CatalogEntry(
        physicalName,
        "Fixture",
        "test and `FixtureDocument`",
        "entity",
        "One fixture",
        "`_id`",
        "internal",
        status);
  }

  private static CatalogEntry parseCatalogRow(String line, int lineNumber) {
    var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1))
        .map(String::trim)
        .toList();
    assertThat(cells).as("catalog table row %s: %s", lineNumber, line).hasSize(8);
    return new CatalogEntry(
        unquoteCode(cells.get(0), lineNumber),
        cells.get(1),
        cells.get(2),
        cells.get(3),
        cells.get(4),
        cells.get(5),
        cells.get(6),
        cells.get(7));
  }

  private static String unquoteCode(String value, int lineNumber) {
    assertThat(value).as("catalog table row %s physical name", lineNumber)
        .startsWith("`").endsWith("`");
    return value.substring(1, value.length() - 1);
  }

  private static Path repositoryRoot() {
    var current = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve(".github"))) {
      return current;
    }
    var parent = current.getParent();
    if (parent != null && Files.isDirectory(parent.resolve(".github"))) {
      return parent;
    }
    throw new IllegalStateException("Cannot locate repository root from " + current);
  }

  private record CatalogEntry(
      String physicalName,
      String logicalName,
      String ownerAndMapping,
      String role,
      String cardinalityAndRetention,
      String indexContract,
      String sensitivity,
      String status) {}
}
